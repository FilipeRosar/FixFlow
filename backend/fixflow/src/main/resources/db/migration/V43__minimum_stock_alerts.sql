CREATE TABLE public.inventory_alerts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    part_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at timestamptz,
    FOREIGN KEY (tenant_id, part_id) REFERENCES public.parts(tenant_id, id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX inventory_alerts_active ON public.inventory_alerts(tenant_id, part_id) WHERE resolved_at IS NULL;
ALTER TABLE public.inventory_alerts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.inventory_alerts FORCE ROW LEVEL SECURITY;
CREATE POLICY inventory_alerts_isolation ON public.inventory_alerts
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON public.inventory_alerts FROM PUBLIC, fixflow_runtime;
GRANT SELECT, INSERT, UPDATE ON public.inventory_alerts TO fixflow_runtime;

-- Runs in the same transaction and tenant context as every balance/minimum change.
CREATE FUNCTION public.sync_minimum_stock_alert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.active AND NEW.quantity_on_hand <= NEW.min_quantity THEN
        INSERT INTO public.inventory_alerts(tenant_id, part_id) VALUES (NEW.tenant_id, NEW.id)
            ON CONFLICT (tenant_id, part_id) WHERE resolved_at IS NULL DO NOTHING;
    ELSE
        UPDATE public.inventory_alerts SET resolved_at = CURRENT_TIMESTAMP
            WHERE tenant_id = NEW.tenant_id AND part_id = NEW.id AND resolved_at IS NULL;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER parts_minimum_stock_alert AFTER INSERT OR UPDATE OF quantity_on_hand, min_quantity, active ON public.parts
    FOR EACH ROW EXECUTE FUNCTION public.sync_minimum_stock_alert();

-- Backfill existing tenants without bypassing forced row isolation.
CREATE POLICY minimum_stock_migration_tenants ON public.tenants FOR SELECT TO fixflow_migrator USING (true);
DO $$
DECLARE company uuid;
BEGIN
    FOR company IN SELECT id FROM public.tenants LOOP
        PERFORM set_config('app.tenant_id', company::text, true);
        INSERT INTO public.inventory_alerts(tenant_id, part_id)
            SELECT tenant_id, id FROM public.parts
            WHERE tenant_id = company AND active AND quantity_on_hand <= min_quantity;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END $$;

DROP POLICY minimum_stock_migration_tenants ON public.tenants;
