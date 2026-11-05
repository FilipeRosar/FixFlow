CREATE TABLE public.equipment (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    customer_id uuid NOT NULL,
    category varchar(20) NOT NULL CHECK (category IN ('NOTEBOOK', 'DESKTOP', 'IMPRESSORA', 'PERIFERICO', 'OUTRO')),
    brand varchar(100) NOT NULL DEFAULT '',
    model varchar(150) NOT NULL CHECK (btrim(model) <> ''),
    serial_number varchar(150) NOT NULL DEFAULT '',
    description varchar(2000) NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamptz,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES public.customers(tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX equipment_customer ON public.equipment(tenant_id, customer_id) WHERE deleted_at IS NULL;
CREATE INDEX equipment_serial ON public.equipment(tenant_id, lower(serial_number), id) WHERE deleted_at IS NULL;
ALTER TABLE public.equipment ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.equipment FORCE ROW LEVEL SECURITY;
CREATE POLICY equipment_isolation ON public.equipment
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON public.equipment FROM PUBLIC, fixflow_runtime;
GRANT SELECT, INSERT, UPDATE ON public.equipment TO fixflow_runtime;

-- Lock the customer so concurrent soft deletion cannot race with attachment.
CREATE FUNCTION public.require_active_equipment_customer() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM 1 FROM public.customers WHERE tenant_id = NEW.tenant_id AND id = NEW.customer_id
        AND deleted_at IS NULL FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'CUSTOMER_UNAVAILABLE' USING ERRCODE = '23514'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER equipment_active_customer BEFORE INSERT OR UPDATE OF tenant_id, customer_id ON public.equipment
    FOR EACH ROW EXECUTE FUNCTION public.require_active_equipment_customer();
REVOKE ALL ON FUNCTION public.require_active_equipment_customer() FROM PUBLIC;
