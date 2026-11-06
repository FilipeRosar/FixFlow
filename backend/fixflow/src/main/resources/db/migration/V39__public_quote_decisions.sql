ALTER TABLE public.work_order_status_history ALTER COLUMN changed_by DROP NOT NULL;
ALTER TABLE public.work_order_status_history ADD CONSTRAINT history_public_actor
    CHECK (changed_by IS NOT NULL OR source = 'CUSTOMER');

CREATE TABLE public.quote_decisions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    quote_id uuid NOT NULL UNIQUE,
    quote_version integer NOT NULL,
    decision varchar(10) NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    reason varchar(2000),
    ip_address inet NOT NULL,
    user_agent varchar(1024) NOT NULL,
    channel varchar(20) NOT NULL CHECK (channel = 'PUBLIC_LINK'),
    decided_at timestamptz NOT NULL,
    quote_content jsonb NOT NULL,
    quote_content_hash varchar(64) NOT NULL CHECK (quote_content_hash ~ '^[0-9a-f]{64}$'),
    FOREIGN KEY (tenant_id, quote_id) REFERENCES public.quotes(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, work_order_id) REFERENCES public.work_orders(tenant_id, id) ON DELETE CASCADE
);
CREATE TABLE public.public_quote_decision_requests (
    tenant_id uuid NOT NULL,
    quote_id uuid NOT NULL,
    key_hash varchar(64) NOT NULL,
    request_hash varchar(64) NOT NULL,
    response jsonb NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, quote_id, key_hash),
    FOREIGN KEY (tenant_id, quote_id) REFERENCES public.quotes(tenant_id, id) ON DELETE CASCADE
);
ALTER TABLE public.quote_decisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.quote_decisions FORCE ROW LEVEL SECURITY;
CREATE POLICY quote_decisions_isolation ON public.quote_decisions
 USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
ALTER TABLE public.public_quote_decision_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.public_quote_decision_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY public_quote_decision_requests_isolation ON public.public_quote_decision_requests
 USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON public.quote_decisions, public.public_quote_decision_requests FROM PUBLIC, fixflow_runtime;
GRANT SELECT ON public.quote_decisions TO fixflow_runtime;
CREATE FUNCTION public.protect_quote_decision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Quote decision evidence is immutable'; END;
$$;
CREATE TRIGGER protect_quote_decision BEFORE UPDATE ON public.quote_decisions
 FOR EACH ROW EXECUTE FUNCTION public.protect_quote_decision();
REVOKE ALL ON FUNCTION public.protect_quote_decision() FROM PUBLIC;

-- Canonical commercial document: status and navigation metadata are deliberately excluded.
-- The UTC representation is independent of the connection's TimeZone.
CREATE FUNCTION public.public_quote_content(p_tenant uuid, p_quote uuid) RETURNS jsonb
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT jsonb_build_object('companyName', t.name, 'workOrderNumber', w.number,
        'equipmentLabel', concat_ws(' ', nullif(e.brand, ''), e.model),
        'quote', jsonb_build_object('version', q.version, 'laborSubtotal', q.labor_subtotal,
            'partsSubtotal', q.parts_subtotal, 'discountAmount', q.discount_amount, 'total', q.total,
            'validUntil', to_char(q.valid_until AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
            'warrantyTerms', q.warranty_terms,
            'items', coalesce((SELECT jsonb_agg(jsonb_build_object('type', i.type, 'description', i.description,
                'quantity', i.quantity, 'unitPrice', i.unit_price) ORDER BY i.position)
                FROM public.quote_items i WHERE i.tenant_id = p_tenant AND i.quote_id = p_quote), '[]'::jsonb)))
    FROM public.quotes q JOIN public.work_orders w ON w.tenant_id = q.tenant_id AND w.id = q.work_order_id
    JOIN public.tenants t ON t.id = q.tenant_id
    LEFT JOIN public.equipment e ON e.tenant_id = w.tenant_id AND e.id = w.equipment_id
    WHERE q.tenant_id = p_tenant AND q.id = p_quote;
$$;
REVOKE ALL ON FUNCTION public.public_quote_content(uuid, uuid) FROM PUBLIC, fixflow_runtime;

CREATE FUNCTION public.decide_public_quote(p_hash text, p_decision text, p_content_hash text, p_reason text,
    p_key_hash text, p_ip text, p_ua text) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    access_record record;
    proposal record;
    work_order record;
    facts record;
    cached record;
    content jsonb;
    content_hash text;
    request_hash text;
    result jsonb;
    receipt jsonb;
    audit_action text := 'PUBLIC_QUOTE_DECISION_DENIED';
    decision_id uuid;
    target_status text;
    event_type text;
    at_time timestamptz;
    previous_tenant text := current_setting('app.tenant_id', true);
BEGIN
    IF p_hash IS NULL OR p_hash !~ '^[0-9a-f]{64}$' THEN RETURN jsonb_build_object('status', 404); END IF;
    SELECT * INTO access_record FROM public.quote_access_tokens WHERE token_hash = p_hash;
    IF NOT FOUND THEN RETURN jsonb_build_object('status', 404); END IF;
    PERFORM set_config('app.tenant_id', access_record.tenant_id::text, true);
    SELECT * INTO proposal FROM public.quotes WHERE tenant_id = access_record.tenant_id AND id = access_record.quote_id;
    <<attempt>>
    BEGIN
        IF NOT FOUND THEN result := jsonb_build_object('status', 404); EXIT attempt; END IF;
        -- Same ordering as the authenticated OS workflow; inspect validity only after obtaining the lock.
        SELECT * INTO work_order FROM public.work_orders WHERE tenant_id = access_record.tenant_id AND id = proposal.work_order_id FOR UPDATE;
        SELECT * INTO facts FROM public.work_order_transition_facts WHERE tenant_id = access_record.tenant_id AND work_order_id = proposal.work_order_id FOR UPDATE;
        SELECT * INTO proposal FROM public.quotes WHERE tenant_id = access_record.tenant_id AND id = access_record.quote_id FOR UPDATE;
        SELECT * INTO access_record FROM public.quote_access_tokens WHERE token_hash = p_hash FOR UPDATE;
        at_time := clock_timestamp();
        IF access_record.revoked_at IS NOT NULL OR NOT EXISTS(SELECT 1 FROM public.tenants WHERE id = access_record.tenant_id AND status = 'ACTIVE')
            OR facts.quote_id IS DISTINCT FROM proposal.id
            OR (proposal.status = 'SENT' AND (access_record.expires_at <= at_time OR facts.quote_token_hash IS DISTINCT FROM p_hash))
            OR (proposal.status IN ('APPROVED', 'REJECTED') AND access_record.read_until <= at_time)
            OR proposal.status NOT IN ('SENT', 'APPROVED', 'REJECTED') THEN
            result := jsonb_build_object('status', 404); EXIT attempt;
        END IF;
        IF p_decision IS NULL OR p_decision NOT IN ('APPROVED', 'REJECTED') OR p_content_hash IS NULL OR p_content_hash !~ '^[0-9a-f]{64}$'
            OR p_key_hash IS NULL OR p_key_hash !~ '^[0-9a-f]{64}$' OR length(coalesce(p_reason, '')) > 2000
            OR p_ip IS NULL OR p_ua IS NULL OR length(p_ua) > 1024 THEN
            result := jsonb_build_object('status', 400); EXIT attempt;
        END IF;
        request_hash := encode(sha256(convert_to(jsonb_build_object('decision', p_decision, 'contentHash', p_content_hash, 'reason', p_reason)::text, 'UTF8')), 'hex');
        SELECT * INTO cached FROM public.public_quote_decision_requests
            WHERE tenant_id = access_record.tenant_id AND quote_id = proposal.id AND key_hash = p_key_hash;
        IF FOUND AND cached.expires_at > at_time THEN
            IF cached.request_hash = request_hash THEN result := cached.response; audit_action := 'PUBLIC_QUOTE_DECISION_REPLAYED';
            ELSE result := jsonb_build_object('status', 409, 'code', 'IDEMPOTENCY_KEY_REUSED'); END IF;
            EXIT attempt;
        END IF;
        IF proposal.status <> 'SENT' OR EXISTS(SELECT 1 FROM public.quote_decisions WHERE quote_id = proposal.id) THEN
            result := jsonb_build_object('status', 409, 'code', 'QUOTE_ALREADY_DECIDED'); EXIT attempt;
        END IF;
        IF work_order.status <> 'AGUARDANDO_APROVACAO' OR facts.quote_status <> 'SENT' THEN
            result := jsonb_build_object('status', 409, 'code', 'QUOTE_NOT_PENDING'); EXIT attempt;
        END IF;
        content := public.public_quote_content(access_record.tenant_id, proposal.id);
        content_hash := encode(sha256(convert_to(content::text, 'UTF8')), 'hex');
        IF content_hash IS DISTINCT FROM p_content_hash THEN
            result := jsonb_build_object('status', 409, 'code', 'QUOTE_CONTENT_CHANGED'); EXIT attempt;
        END IF;
        decision_id := gen_random_uuid();
        target_status := CASE WHEN p_decision = 'APPROVED' THEN 'APROVADA' ELSE 'ORCAMENTO_REJEITADO' END;
        event_type := CASE WHEN p_decision = 'APPROVED' THEN 'QuoteApproved' ELSE 'QuoteRejected' END;
        INSERT INTO public.quote_decisions(id, tenant_id, work_order_id, quote_id, quote_version, decision, reason,
            ip_address, user_agent, channel, decided_at, quote_content, quote_content_hash)
            VALUES (decision_id, access_record.tenant_id, work_order.id, proposal.id, proposal.version, p_decision, p_reason,
                p_ip::inet, p_ua, 'PUBLIC_LINK', at_time, content, content_hash);
        UPDATE public.work_orders SET status = target_status, version = version + 1 WHERE tenant_id = access_record.tenant_id AND id = work_order.id;
        UPDATE public.quotes SET status = p_decision WHERE tenant_id = access_record.tenant_id AND id = proposal.id;
        UPDATE public.work_order_transition_facts SET quote_status = p_decision, quote_token_hash = NULL
            WHERE tenant_id = access_record.tenant_id AND work_order_id = work_order.id;
        UPDATE public.quote_access_tokens SET read_until = at_time + interval '90 days' WHERE token_hash = p_hash;
        INSERT INTO public.work_order_status_history(id, tenant_id, work_order_id, from_status, to_status, changed_by, source, reason, created_at)
            VALUES (gen_random_uuid(), access_record.tenant_id, work_order.id, 'AGUARDANDO_APROVACAO', target_status, NULL, 'CUSTOMER', p_reason, at_time);
        INSERT INTO public.audit_log(id, tenant_id, actor_id, action, resource_type, resource_id, details, occurred_at)
            VALUES (gen_random_uuid(), access_record.tenant_id, NULL, 'WORK_ORDER_STATUS_CHANGED', 'WORK_ORDER', work_order.id,
                jsonb_build_object('fromStatus', 'AGUARDANDO_APROVACAO', 'toStatus', target_status, 'version', work_order.version + 1, 'channel', 'PUBLIC_LINK', 'decisionId', decision_id), at_time);
        INSERT INTO public.outbox_event(id, tenant_id, aggregate_type, aggregate_id, event_type, payload)
            VALUES (gen_random_uuid(), access_record.tenant_id, 'WorkOrder', work_order.id, event_type,
                jsonb_build_object('workOrderId', work_order.id, 'fromStatus', 'AGUARDANDO_APROVACAO', 'toStatus', target_status,
                    'version', work_order.version + 1, 'quoteId', proposal.id, 'quoteVersion', proposal.version,
                    'decisionId', decision_id, 'quoteContentHash', content_hash, 'channel', 'PUBLIC_LINK'));
        receipt := jsonb_build_object('decision', p_decision, 'workOrderStatus', target_status, 'quoteVersion', proposal.version,
            'decidedAt', at_time, 'contentHash', content_hash);
        result := jsonb_build_object('status', 200, 'receipt', receipt);
        INSERT INTO public.public_quote_decision_requests(tenant_id, quote_id, key_hash, request_hash, response, expires_at)
            VALUES (access_record.tenant_id, proposal.id, p_key_hash, request_hash, result, at_time + interval '24 hours');
        audit_action := CASE WHEN p_decision = 'APPROVED' THEN 'QUOTE_PUBLIC_APPROVED' ELSE 'QUOTE_PUBLIC_REJECTED' END;
    END;
    IF proposal.work_order_id IS NOT NULL THEN
        INSERT INTO public.audit_log(id, tenant_id, actor_id, action, resource_type, resource_id, details)
            VALUES (gen_random_uuid(), access_record.tenant_id, NULL, audit_action, 'WORK_ORDER', proposal.work_order_id,
                jsonb_build_object('channel', 'PUBLIC_LINK', 'quoteVersion', proposal.version, 'decision', p_decision,
                    'status', result->'status', 'code', result->'code'));
    END IF;
    PERFORM set_config('app.tenant_id', coalesce(previous_tenant, ''), true);
    RETURN result;
END;
$$;
REVOKE ALL ON FUNCTION public.decide_public_quote(text, text, text, text, text, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.decide_public_quote(text, text, text, text, text, text, text) TO fixflow_runtime;

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
                'decision', (SELECT jsonb_build_object('decision', d.decision, 'decidedAt', d.decided_at, 'reason', d.reason)
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
