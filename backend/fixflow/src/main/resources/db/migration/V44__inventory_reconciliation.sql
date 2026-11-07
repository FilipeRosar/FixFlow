-- Background jobs discover only tenant identifiers; business data remains under RLS.
CREATE POLICY inventory_reconciliation_discovery ON public.tenants FOR SELECT TO fixflow_migrator USING (true);
CREATE FUNCTION public.inventory_reconciliation_tenants() RETURNS SETOF uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
    SELECT id FROM public.tenants;
$$;
REVOKE ALL ON FUNCTION public.inventory_reconciliation_tenants() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.inventory_reconciliation_tenants() TO fixflow_runtime;

CREATE TABLE public.inventory_reconciliation_alerts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    part_id uuid NOT NULL,
    movement_quantity numeric NOT NULL,
    quantity_on_hand numeric(14,3) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    last_checked_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    resolved_at timestamptz,
    FOREIGN KEY (tenant_id, part_id) REFERENCES public.parts(tenant_id, id) ON DELETE CASCADE,
    CHECK (movement_quantity <> quantity_on_hand)
);
CREATE UNIQUE INDEX inventory_reconciliation_active ON public.inventory_reconciliation_alerts(tenant_id, part_id)
    WHERE resolved_at IS NULL;
ALTER TABLE public.inventory_reconciliation_alerts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.inventory_reconciliation_alerts FORCE ROW LEVEL SECURITY;
CREATE POLICY inventory_reconciliation_isolation ON public.inventory_reconciliation_alerts
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON public.inventory_reconciliation_alerts FROM PUBLIC, fixflow_runtime;
GRANT SELECT, INSERT, UPDATE ON public.inventory_reconciliation_alerts TO fixflow_runtime;
