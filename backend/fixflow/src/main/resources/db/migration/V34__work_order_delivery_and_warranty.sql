ALTER TABLE public.work_orders
    ADD COLUMN delivered_by uuid,
    ADD COLUMN delivered_by_name varchar(150),
    ADD COLUMN delivery_payment_override boolean NOT NULL DEFAULT false,
    ADD COLUMN delivery_reason varchar(2000),
    ADD COLUMN warranty_terms varchar(10000) NOT NULL DEFAULT '';

-- Preserve delivery identities/exceptions already recorded before this feature.
CREATE POLICY delivery_migration_tenants ON public.tenants FOR SELECT TO fixflow_migrator USING (true);
DO $$
DECLARE tenant uuid; previous_tenant text := current_setting('app.tenant_id', true);
BEGIN
    FOR tenant IN SELECT id FROM public.tenants LOOP
        PERFORM set_config('app.tenant_id', tenant::text, true);
        UPDATE public.work_orders w SET
            delivered_by = (SELECT actor_id FROM public.work_order_timeline t WHERE t.tenant_id = tenant AND t.work_order_id = w.id AND t.to_status = 'ENTREGUE' ORDER BY t.occurred_at DESC, t.sequence DESC LIMIT 1),
            delivered_by_name = (SELECT actor_name FROM public.work_order_timeline t WHERE t.tenant_id = tenant AND t.work_order_id = w.id AND t.to_status = 'ENTREGUE' ORDER BY t.occurred_at DESC, t.sequence DESC LIMIT 1),
            delivery_reason = (SELECT reason FROM public.work_order_timeline t WHERE t.tenant_id = tenant AND t.work_order_id = w.id AND t.to_status = 'ENTREGUE' ORDER BY t.occurred_at DESC, t.sequence DESC LIMIT 1),
            delivery_payment_override = coalesce((SELECT a.details->>'paymentOverride' = 'true' FROM public.audit_log a WHERE a.tenant_id = tenant AND a.resource_type = 'WORK_ORDER' AND a.resource_id = w.id AND a.action = 'WORK_ORDER_STATUS_CHANGED' AND a.details->>'toStatus' = 'ENTREGUE' ORDER BY a.occurred_at DESC, a.id DESC LIMIT 1), false)
            WHERE w.tenant_id = tenant AND w.status = 'ENTREGUE';
    END LOOP;
    PERFORM set_config('app.tenant_id', coalesce(previous_tenant, ''), true);
END;
$$;
DROP POLICY delivery_migration_tenants ON public.tenants;

-- The runtime cannot reopen an ENTREGUE order or rewrite its delivery/warranty snapshot.
CREATE FUNCTION public.protect_delivered_work_order() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF OLD.status = 'ENTREGUE' AND ROW(NEW.status, NEW.delivered_at, NEW.warranty_days, NEW.warranty_until,
        NEW.delivered_by, NEW.delivered_by_name, NEW.delivery_reason, NEW.delivery_payment_override, NEW.warranty_terms)
        IS DISTINCT FROM ROW(OLD.status, OLD.delivered_at, OLD.warranty_days, OLD.warranty_until,
        OLD.delivered_by, OLD.delivered_by_name, OLD.delivery_reason, OLD.delivery_payment_override, OLD.warranty_terms) THEN
        RAISE EXCEPTION 'ENTREGUE is terminal; delivery and warranty are immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.protect_delivered_work_order() FROM PUBLIC, fixflow_runtime;
CREATE TRIGGER protect_delivery BEFORE UPDATE ON public.work_orders FOR EACH ROW EXECUTE FUNCTION public.protect_delivered_work_order();

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

