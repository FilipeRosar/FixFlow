CREATE TABLE public.customers (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    name varchar(150) NOT NULL CHECK (btrim(name) <> ''),
    document varchar(18),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at timestamptz,
    UNIQUE (tenant_id, id)
);
CREATE TABLE public.customer_contacts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    customer_id uuid NOT NULL,
    type varchar(20) NOT NULL CHECK (type IN ('PHONE', 'WHATSAPP', 'EMAIL')),
    value varchar(254) NOT NULL CHECK (btrim(value) <> ''),
    label varchar(80) NOT NULL DEFAULT '',
    position integer NOT NULL,
    FOREIGN KEY (tenant_id, customer_id) REFERENCES public.customers(tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX customers_active_name ON public.customers(tenant_id, lower(name), id) WHERE deleted_at IS NULL;
CREATE INDEX customer_contacts_customer ON public.customer_contacts(tenant_id, customer_id);

-- Minimal persistence foundation for the future OS module; no OS creation API yet.
CREATE TABLE public.work_orders (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    customer_id uuid NOT NULL,
    status varchar(30) NOT NULL DEFAULT 'RECEBIDA' CHECK (status IN (
        'RECEBIDA', 'EM_DIAGNOSTICO', 'AGUARDANDO_ORCAMENTO', 'AGUARDANDO_APROVACAO',
        'APROVADA', 'ORCAMENTO_REJEITADO', 'EM_MANUTENCAO', 'AGUARDANDO_PECA',
        'PRONTA', 'SEM_REPARO', 'ENTREGUE', 'CANCELADA')),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (tenant_id, customer_id) REFERENCES public.customers(tenant_id, id)
);
CREATE INDEX work_orders_open_customer ON public.work_orders(tenant_id, customer_id)
    WHERE status NOT IN ('ENTREGUE', 'CANCELADA');

ALTER TABLE public.customers ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.customers FORCE ROW LEVEL SECURITY;
ALTER TABLE public.customer_contacts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.customer_contacts FORCE ROW LEVEL SECURITY;
ALTER TABLE public.work_orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.work_orders FORCE ROW LEVEL SECURITY;
CREATE POLICY customers_isolation ON public.customers
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY contacts_isolation ON public.customer_contacts
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY work_orders_isolation ON public.work_orders
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON public.customers, public.customer_contacts, public.work_orders FROM PUBLIC, fixflow_runtime;
GRANT SELECT, INSERT, UPDATE ON public.customers, public.work_orders TO fixflow_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.customer_contacts TO fixflow_runtime;

-- Both operations lock the customer first to prevent a concurrent OS from
-- being attached while deletion checks for open work orders.
CREATE FUNCTION public.require_active_order_customer() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM 1 FROM public.customers WHERE tenant_id = NEW.tenant_id AND id = NEW.customer_id
        AND deleted_at IS NULL FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'CUSTOMER_UNAVAILABLE' USING ERRCODE = '23514'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER active_order_customer BEFORE INSERT OR UPDATE ON public.work_orders
    FOR EACH ROW EXECUTE FUNCTION public.require_active_order_customer();
CREATE FUNCTION public.prevent_customer_delete_with_open_orders() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF OLD.deleted_at IS NULL AND NEW.deleted_at IS NOT NULL AND EXISTS (
        SELECT 1 FROM public.work_orders WHERE tenant_id = NEW.tenant_id AND customer_id = NEW.id
            AND status NOT IN ('ENTREGUE', 'CANCELADA')) THEN
        RAISE EXCEPTION 'CUSTOMER_HAS_OPEN_ORDERS' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER customer_delete_guard BEFORE UPDATE ON public.customers
    FOR EACH ROW EXECUTE FUNCTION public.prevent_customer_delete_with_open_orders();
REVOKE ALL ON FUNCTION public.require_active_order_customer(), public.prevent_customer_delete_with_open_orders() FROM PUBLIC;
