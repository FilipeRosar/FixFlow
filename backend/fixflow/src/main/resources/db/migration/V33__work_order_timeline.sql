-- Business history is an append-only projection, written only by trusted source triggers.
CREATE TABLE public.work_order_timeline (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    sequence bigint GENERATED ALWAYS AS IDENTITY,
    tenant_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    source_type varchar(20) NOT NULL CHECK (source_type IN ('STATUS_HISTORY', 'AUDIT')),
    source_id uuid NOT NULL,
    event_type varchar(100) NOT NULL,
    actor_id uuid,
    actor_name varchar(150) NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    origin varchar(30) NOT NULL,
    from_status varchar(30),
    to_status varchar(30),
    reason varchar(2000),
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (tenant_id, source_type, source_id),
    FOREIGN KEY (tenant_id, work_order_id) REFERENCES public.work_orders(tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX timeline_order_time ON public.work_order_timeline(tenant_id, work_order_id, occurred_at DESC, sequence DESC);
ALTER TABLE public.work_order_timeline ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.work_order_timeline FORCE ROW LEVEL SECURITY;
CREATE POLICY timeline_isolation ON public.work_order_timeline
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON public.work_order_timeline FROM PUBLIC, fixflow_runtime;
REVOKE ALL ON SEQUENCE public.work_order_timeline_sequence_seq FROM PUBLIC, fixflow_runtime;
GRANT SELECT ON public.work_order_timeline TO fixflow_runtime;

CREATE FUNCTION public.append_work_order_timeline(
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
    IF p_event = 'WORK_ORDER_TECHNICIAN_ASSIGNED' THEN
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

CREATE FUNCTION public.timeline_from_status_history() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM public.append_work_order_timeline(NEW.tenant_id, NEW.work_order_id, 'STATUS_HISTORY', NEW.id,
        CASE WHEN NEW.from_status IS NULL THEN 'WORK_ORDER_CREATED' ELSE 'WORK_ORDER_STATUS_CHANGED' END,
        NEW.changed_by, NEW.source, NEW.from_status, NEW.to_status, NEW.reason, clock_timestamp(), '{}'::jsonb);
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.timeline_from_status_history() FROM PUBLIC, fixflow_runtime;
CREATE TRIGGER record_work_order_transition AFTER INSERT ON public.work_order_status_history
    FOR EACH ROW EXECUTE FUNCTION public.timeline_from_status_history();

CREATE FUNCTION public.timeline_from_audit() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM public.append_work_order_timeline(NEW.tenant_id, NEW.resource_id, 'AUDIT', NEW.id, NEW.action,
        NEW.actor_id, CASE WHEN NEW.actor_id IS NULL THEN 'SYSTEM' ELSE 'USER' END,
        NULL, NULL, NULL, NEW.occurred_at, NEW.details);
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.timeline_from_audit() FROM PUBLIC, fixflow_runtime;
-- Creation/status events already come from status history: no duplicated entries.
CREATE TRIGGER record_work_order_business_event AFTER INSERT ON public.audit_log FOR EACH ROW
    WHEN (NEW.resource_type = 'WORK_ORDER' AND NEW.action IN ('WORK_ORDER_TECHNICIAN_ASSIGNED',
        'WORK_ORDER_DIAGNOSIS_RECORDED', 'WORK_ORDER_ITEM_ADDED', 'WORK_ORDER_ITEM_REMOVED', 'WORK_ORDER_ATTACHMENT_UPLOADED'))
    EXECUTE FUNCTION public.timeline_from_audit();

-- Backfill each tenant under its own RLS context. Only migrator can enumerate tenants here.
CREATE POLICY timeline_migration_tenants ON public.tenants FOR SELECT TO fixflow_migrator USING (true);
DO $$
DECLARE
    tenant uuid;
    entry record;
    previous_tenant text := current_setting('app.tenant_id', true);
BEGIN
    FOR tenant IN SELECT id FROM public.tenants LOOP
        PERFORM set_config('app.tenant_id', tenant::text, true);
        FOR entry IN
            SELECT h.id, h.work_order_id, 'STATUS_HISTORY'::text AS source_type,
                CASE WHEN h.from_status IS NULL THEN 'WORK_ORDER_CREATED' ELSE 'WORK_ORDER_STATUS_CHANGED' END AS event_type,
                h.changed_by AS actor_id, h.source AS origin, h.from_status, h.to_status, h.reason,
                h.created_at AS occurred_at, '{}'::jsonb AS details
            FROM public.work_order_status_history h WHERE h.tenant_id = tenant
            UNION ALL
            SELECT a.id, a.resource_id, 'AUDIT', a.action, a.actor_id,
                CASE WHEN a.actor_id IS NULL THEN 'SYSTEM' ELSE 'USER' END, NULL, NULL, NULL, a.occurred_at, a.details
            FROM public.audit_log a WHERE a.tenant_id = tenant AND a.resource_type = 'WORK_ORDER'
                AND a.action IN ('WORK_ORDER_TECHNICIAN_ASSIGNED', 'WORK_ORDER_DIAGNOSIS_RECORDED',
                    'WORK_ORDER_ITEM_ADDED', 'WORK_ORDER_ITEM_REMOVED', 'WORK_ORDER_ATTACHMENT_UPLOADED')
            ORDER BY occurred_at, id
        LOOP
            PERFORM public.append_work_order_timeline(tenant, entry.work_order_id, entry.source_type, entry.id,
                entry.event_type, entry.actor_id, entry.origin, entry.from_status, entry.to_status,
                entry.reason, entry.occurred_at, entry.details);
        END LOOP;
    END LOOP;
    PERFORM set_config('app.tenant_id', coalesce(previous_tenant, ''), true);
END;
$$;
DROP POLICY timeline_migration_tenants ON public.tenants;
