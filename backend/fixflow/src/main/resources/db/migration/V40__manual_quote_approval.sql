ALTER TABLE public.quote_decisions DROP CONSTRAINT quote_decisions_channel_check;
ALTER TABLE public.quote_decisions
    ADD COLUMN decided_by uuid REFERENCES public.users(id),
    ADD COLUMN decided_by_name varchar(150),
    ADD CONSTRAINT quote_decisions_channel_check CHECK (channel IN ('PUBLIC_LINK', 'PHONE', 'COUNTER')),
    ADD CONSTRAINT quote_decisions_manual_actor CHECK (
        (channel = 'PUBLIC_LINK' AND decided_by IS NULL AND decided_by_name IS NULL) OR
        (channel IN ('PHONE', 'COUNTER') AND decision = 'APPROVED' AND decided_by IS NOT NULL
            AND decided_by_name IS NOT NULL AND reason IS NOT NULL AND btrim(reason) <> '')
    );

-- Called inside the authorized OS transaction, before its state update. All source fields
-- come from the authenticated server context; no tenant/actor data is accepted from HTTP.
CREATE FUNCTION public.record_manual_quote_approval(p_tenant uuid, p_order uuid, p_quote uuid, p_actor uuid,
    p_channel text, p_reason text, p_ip text, p_ua text, p_at timestamptz) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    actor_name text;
    current_order record;
    proposal record;
    current_facts record;
    content jsonb;
    content_hash text;
    decision_id uuid;
BEGIN
    IF p_tenant IS DISTINCT FROM NULLIF(current_setting('app.tenant_id', true), '')::uuid THEN
        RETURN jsonb_build_object('status', 403);
    END IF;
    SELECT u.full_name INTO actor_name FROM public.users u
        JOIN public.tenant_memberships m ON m.user_id = u.id AND m.tenant_id = p_tenant
        JOIN public.tenants t ON t.id = m.tenant_id
        WHERE u.id = p_actor AND u.status = 'ACTIVE' AND m.status = 'ACTIVE' AND t.status = 'ACTIVE' AND m.role IN ('OWNER', 'ATTENDANT');
    IF NOT FOUND THEN RETURN jsonb_build_object('status', 403); END IF;
    IF p_channel IS NULL OR p_channel NOT IN ('PHONE', 'COUNTER') OR p_reason IS NULL OR btrim(p_reason) = ''
        OR length(p_reason) > 2000 OR p_ip IS NULL OR p_ua IS NULL OR length(p_ua) > 1024 OR p_at IS NULL THEN
        RETURN jsonb_build_object('status', 400);
    END IF;
    SELECT * INTO current_order FROM public.work_orders WHERE tenant_id = p_tenant AND id = p_order FOR UPDATE;
    IF NOT FOUND THEN RETURN jsonb_build_object('status', 404); END IF;
    SELECT * INTO current_facts FROM public.work_order_transition_facts WHERE tenant_id = p_tenant AND work_order_id = p_order FOR UPDATE;
    SELECT * INTO proposal FROM public.quotes WHERE tenant_id = p_tenant AND work_order_id = p_order AND id = p_quote FOR UPDATE;
    IF NOT FOUND THEN RETURN jsonb_build_object('status', 404); END IF;
    IF current_order.status <> 'AGUARDANDO_APROVACAO' OR current_facts.quote_id IS DISTINCT FROM p_quote
        OR current_facts.quote_status <> 'SENT' OR proposal.status <> 'SENT'
        OR proposal.valid_until <= clock_timestamp() OR current_facts.quote_valid_until <= clock_timestamp()
        OR NOT EXISTS(SELECT 1 FROM public.quote_items WHERE tenant_id = p_tenant AND quote_id = p_quote)
        OR EXISTS(SELECT 1 FROM public.quote_decisions WHERE quote_id = p_quote) THEN
        RETURN jsonb_build_object('status', 409);
    END IF;
    content := public.public_quote_content(p_tenant, p_quote);
    content_hash := encode(sha256(convert_to(content::text, 'UTF8')), 'hex');
    decision_id := gen_random_uuid();
    INSERT INTO public.quote_decisions(id, tenant_id, work_order_id, quote_id, quote_version, decision, reason,
        ip_address, user_agent, channel, decided_at, quote_content, quote_content_hash, decided_by, decided_by_name)
        VALUES (decision_id, p_tenant, p_order, p_quote, proposal.version, 'APPROVED', p_reason,
            p_ip::inet, p_ua, p_channel, p_at, content, content_hash, p_actor, actor_name);
    RETURN jsonb_build_object('status', 200, 'approval', jsonb_build_object('id', decision_id, 'quoteId', p_quote,
        'quoteVersion', proposal.version, 'contentHash', content_hash, 'actorName', actor_name));
END;
$$;
REVOKE ALL ON FUNCTION public.record_manual_quote_approval(uuid, uuid, uuid, uuid, text, text, text, text, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.record_manual_quote_approval(uuid, uuid, uuid, uuid, text, text, text, text, timestamptz) TO fixflow_runtime;

-- Internal justifications are never returned on the public link.
CREATE OR REPLACE FUNCTION public.read_public_quote(p_hash text) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    access_record record;
    proposal record;
    result jsonb;
    content jsonb;
    allowed boolean := false;
    previous_tenant text := current_setting('app.tenant_id', true);
BEGIN
    IF p_hash IS NULL OR p_hash !~ '^[0-9a-f]{64}$' THEN RETURN NULL; END IF;
    SELECT * INTO access_record FROM public.quote_access_tokens WHERE token_hash = p_hash;
    IF NOT FOUND THEN RETURN NULL; END IF;
    PERFORM set_config('app.tenant_id', access_record.tenant_id::text, true);
    SELECT q.*, w.number AS order_number, w.status AS order_status, t.name AS company_name, t.status AS company_status,
        concat_ws(' ', nullif(e.brand, ''), e.model) AS equipment_label,
        f.quote_id AS current_quote_id, f.quote_token_hash AS current_token_hash
        INTO proposal
        FROM public.quotes q
        JOIN public.work_orders w ON w.tenant_id = q.tenant_id AND w.id = q.work_order_id
        JOIN public.tenants t ON t.id = q.tenant_id
        LEFT JOIN public.equipment e ON e.tenant_id = w.tenant_id AND e.id = w.equipment_id
        LEFT JOIN public.work_order_transition_facts f ON f.tenant_id = w.tenant_id AND f.work_order_id = w.id
        WHERE q.tenant_id = access_record.tenant_id AND q.id = access_record.quote_id;
    IF FOUND THEN
        allowed := access_record.revoked_at IS NULL AND proposal.company_status = 'ACTIVE'
            AND proposal.current_quote_id = proposal.id AND (
                (proposal.status = 'SENT' AND proposal.order_status = 'AGUARDANDO_APROVACAO'
                    AND proposal.current_token_hash = p_hash AND access_record.expires_at > clock_timestamp())
                OR (proposal.status IN ('APPROVED', 'REJECTED') AND access_record.read_until > clock_timestamp())
            );
        INSERT INTO public.audit_log(id, tenant_id, actor_id, action, resource_type, resource_id, details)
            VALUES (gen_random_uuid(), access_record.tenant_id, NULL,
                CASE WHEN coalesce(allowed, false) THEN 'PUBLIC_QUOTE_VIEWED' ELSE 'PUBLIC_QUOTE_ACCESS_DENIED' END,
                'WORK_ORDER', proposal.work_order_id, jsonb_build_object('channel', 'PUBLIC_LINK', 'quoteVersion', proposal.version));
        IF coalesce(allowed, false) THEN
            content := coalesce((SELECT quote_content FROM public.quote_decisions WHERE tenant_id = access_record.tenant_id AND quote_id = access_record.quote_id),
                public.public_quote_content(access_record.tenant_id, access_record.quote_id));
            result := content || jsonb_build_object('workOrderStatus', proposal.order_status, 'readOnly', proposal.status <> 'SENT',
                'contentHash', encode(sha256(convert_to(content::text, 'UTF8')), 'hex'),
                'decision', (SELECT jsonb_build_object('decision', d.decision, 'decidedAt', d.decided_at, 'reason', CASE WHEN d.channel = 'PUBLIC_LINK' THEN d.reason ELSE NULL END)
                    FROM public.quote_decisions d WHERE d.tenant_id = access_record.tenant_id AND d.quote_id = access_record.quote_id));
            result := jsonb_set(result, '{quote,status}', to_jsonb(proposal.status));
        END IF;
    END IF;
    PERFORM set_config('app.tenant_id', coalesce(previous_tenant, ''), true);
    RETURN result;
END;
$$;
REVOKE ALL ON FUNCTION public.read_public_quote(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.read_public_quote(text) TO fixflow_runtime;

CREATE OR REPLACE FUNCTION public.append_work_order_timeline(
    p_tenant uuid, p_order uuid, p_source_type text, p_source_id uuid, p_event text,
    p_actor uuid, p_origin text, p_from text, p_to text, p_reason text,
    p_time timestamptz, p_details jsonb
) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    display_name text;
    safe_details jsonb := '{}'::jsonb;
BEGIN
    IF p_tenant IS DISTINCT FROM NULLIF(current_setting('app.tenant_id', true), '')::uuid THEN
        RAISE EXCEPTION 'Timeline tenant context mismatch';
    END IF;
    -- Audit may reference a resource already purged by administrative maintenance.
    IF NOT EXISTS (SELECT 1 FROM public.work_orders WHERE tenant_id = p_tenant AND id = p_order) THEN RETURN; END IF;
    SELECT u.full_name INTO display_name FROM public.users u
        JOIN public.tenant_memberships m ON m.user_id = u.id AND m.tenant_id = p_tenant WHERE u.id = p_actor;
    display_name := coalesce(display_name, CASE WHEN p_origin = 'CUSTOMER' THEN 'Cliente'
        WHEN p_actor IS NULL THEN 'Sistema' ELSE 'Usuário indisponível' END);
    -- Explicit allowlist: never copy arbitrary audit JSON, tokens, storage keys or signed URLs.
    IF p_event = 'WORK_ORDER_STATUS_CHANGED' AND p_to = 'ENTREGUE' THEN
        SELECT jsonb_build_object('paymentOverride', w.delivery_payment_override, 'warrantyDays', w.warranty_days,
            'warrantyUntil', w.warranty_until) INTO safe_details FROM public.work_orders w WHERE w.tenant_id = p_tenant AND w.id = p_order;
    ELSIF p_event = 'WORK_ORDER_STATUS_CHANGED' AND p_to = 'APROVADA' THEN
        SELECT jsonb_build_object('approvalChannel', d.channel, 'approvedByName', d.decided_by_name) INTO safe_details
            FROM public.quote_decisions d WHERE d.tenant_id = p_tenant AND d.work_order_id = p_order
                AND d.decision = 'APPROVED' AND d.channel IN ('PHONE', 'COUNTER') ORDER BY d.decided_at DESC LIMIT 1;
    ELSIF p_event = 'WORK_ORDER_TECHNICIAN_ASSIGNED' THEN
        SELECT jsonb_build_object('technicianName', u.full_name) INTO safe_details
            FROM public.users u JOIN public.tenant_memberships m ON m.user_id = u.id AND m.tenant_id = p_tenant
            WHERE u.id::text = p_details->>'technicianId';
    ELSIF p_event = 'WORK_ORDER_DIAGNOSIS_RECORDED' THEN
        safe_details := jsonb_build_object('revision', p_details->'revision');
    ELSIF p_event IN ('WORK_ORDER_ITEM_ADDED', 'WORK_ORDER_ITEM_REMOVED') THEN
        SELECT jsonb_build_object('description', i.description, 'itemType', i.type, 'quantity', i.quantity)
            INTO safe_details FROM public.work_order_items i
            WHERE i.tenant_id = p_tenant AND i.work_order_id = p_order AND i.id::text = p_details->>'itemId';
    ELSIF p_event = 'WORK_ORDER_ATTACHMENT_UPLOADED' THEN
        SELECT jsonb_build_object('filename', a.original_name) INTO safe_details
            FROM public.work_order_attachments a
            WHERE a.tenant_id = p_tenant AND a.work_order_id = p_order AND a.id::text = p_details->>'attachmentId';
    END IF;
    INSERT INTO public.work_order_timeline(tenant_id, work_order_id, source_type, source_id, event_type,
        actor_id, actor_name, occurred_at, origin, from_status, to_status, reason, details)
    VALUES (p_tenant, p_order, p_source_type, p_source_id, p_event, p_actor, display_name, p_time,
        p_origin, p_from, p_to, nullif(btrim(p_reason), ''), coalesce(safe_details, '{}'::jsonb))
    ON CONFLICT (tenant_id, source_type, source_id) DO NOTHING;
END;
$$;
REVOKE ALL ON FUNCTION public.append_work_order_timeline(uuid, uuid, text, uuid, text, uuid, text, text, text, text, timestamptz, jsonb) FROM PUBLIC, fixflow_runtime;

