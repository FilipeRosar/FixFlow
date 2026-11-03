CREATE TABLE public.audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES public.tenants(id),
    actor_id UUID,
    action VARCHAR(100) NOT NULL CHECK (btrim(action) <> ''),
    resource_type VARCHAR(100) NOT NULL CHECK (btrim(resource_type) <> ''),
    resource_id UUID NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX idx_audit_log_tenant_time ON public.audit_log (tenant_id, occurred_at, id);
ALTER TABLE public.audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.audit_log FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_audit_log ON public.audit_log
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

REVOKE ALL ON public.audit_log FROM PUBLIC, fixflow_runtime;
GRANT SELECT, INSERT ON public.audit_log TO fixflow_runtime;
