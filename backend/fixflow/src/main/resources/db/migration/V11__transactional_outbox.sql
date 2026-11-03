CREATE TABLE public.outbox_event (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES public.tenants(id),
    aggregate_type VARCHAR(100) NOT NULL CHECK (btrim(aggregate_type) <> ''),
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL CHECK (btrim(event_type) <> ''),
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    processed_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED')),
    last_error VARCHAR(500)
);
CREATE INDEX idx_outbox_pending ON public.outbox_event (tenant_id, next_attempt_at, id)
    WHERE status = 'PENDING';
ALTER TABLE public.outbox_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.outbox_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_outbox ON public.outbox_event
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
-- The owner may discover pending tenants; runtime still reads payloads only through RLS.
CREATE POLICY outbox_owner_discovery ON public.outbox_event FOR SELECT TO fixflow_migrator USING (true);
REVOKE ALL ON public.outbox_event FROM PUBLIC, fixflow_runtime;
GRANT SELECT, INSERT ON public.outbox_event TO fixflow_runtime;
GRANT UPDATE (processed_at, attempts, next_attempt_at, status, last_error) ON public.outbox_event TO fixflow_runtime;
CREATE FUNCTION public.pending_outbox_tenants() RETURNS SETOF UUID
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
    SELECT DISTINCT tenant_id FROM public.outbox_event
    WHERE status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP;
$$;
REVOKE ALL ON FUNCTION public.pending_outbox_tenants() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.pending_outbox_tenants() TO fixflow_runtime;
