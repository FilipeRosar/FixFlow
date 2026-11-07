CREATE TABLE public.quotes (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    status varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'SENT', 'APPROVED', 'REJECTED')),
    discount_type varchar(10) NOT NULL CHECK (discount_type IN ('FIXED', 'PERCENT')),
    discount_value numeric(14,2) NOT NULL CHECK (discount_value >= 0 AND (discount_type <> 'PERCENT' OR discount_value <= 100)),
    labor_subtotal numeric(14,2) NOT NULL CHECK (labor_subtotal >= 0),
    parts_subtotal numeric(14,2) NOT NULL CHECK (parts_subtotal >= 0),
    discount_amount numeric(14,2) NOT NULL CHECK (discount_amount >= 0 AND discount_amount <= labor_subtotal + parts_subtotal),
    total numeric(14,2) NOT NULL CHECK (total = labor_subtotal + parts_subtotal - discount_amount),
    valid_until timestamptz NOT NULL,
    warranty_terms text NOT NULL DEFAULT '',
    logo_data_url text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    sent_at timestamptz,
    CHECK ((status = 'DRAFT' AND sent_at IS NULL) OR (status <> 'DRAFT' AND sent_at IS NOT NULL)),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, work_order_id, version),
    FOREIGN KEY (tenant_id, work_order_id) REFERENCES public.work_orders(tenant_id, id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX quotes_one_draft ON public.quotes(tenant_id, work_order_id) WHERE status = 'DRAFT';
CREATE TABLE public.quote_items (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    quote_id uuid NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    type varchar(10) NOT NULL CHECK (type IN ('SERVICE', 'PART')),
    part_id uuid,
    description varchar(500) NOT NULL CHECK (btrim(description) <> ''),
    quantity numeric(14,3) NOT NULL CHECK (quantity > 0),
    unit_price numeric(14,2) NOT NULL CHECK (unit_price >= 0),
    CHECK ((type = 'PART' AND part_id IS NOT NULL) OR (type = 'SERVICE' AND part_id IS NULL)),
    UNIQUE (tenant_id, quote_id, position),
    FOREIGN KEY (tenant_id, quote_id) REFERENCES public.quotes(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, part_id) REFERENCES public.parts(tenant_id, id)
);
ALTER TABLE public.quotes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.quotes FORCE ROW LEVEL SECURITY;
CREATE POLICY quotes_isolation ON public.quotes
 USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
ALTER TABLE public.quote_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.quote_items FORCE ROW LEVEL SECURITY;
CREATE POLICY quote_items_isolation ON public.quote_items
 USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON public.quotes, public.quote_items FROM PUBLIC, fixflow_runtime;
GRANT SELECT, INSERT, UPDATE ON public.quotes TO fixflow_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.quote_items TO fixflow_runtime;

CREATE FUNCTION public.protect_sent_quote() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id, NEW.tenant_id, NEW.work_order_id, NEW.version, NEW.created_at)
        IS DISTINCT FROM (OLD.id, OLD.tenant_id, OLD.work_order_id, OLD.version, OLD.created_at) THEN
        RAISE EXCEPTION 'Quote identity is immutable';
    END IF;
    IF OLD.status <> 'DRAFT' AND (
        (to_jsonb(NEW) - 'status') IS DISTINCT FROM (to_jsonb(OLD) - 'status') OR
        (NEW.status IS DISTINCT FROM OLD.status AND NOT (OLD.status = 'SENT' AND NEW.status IN ('APPROVED', 'REJECTED')))
    ) THEN RAISE EXCEPTION 'Sent quote is immutable'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER protect_sent_quote BEFORE UPDATE ON public.quotes FOR EACH ROW EXECUTE FUNCTION public.protect_sent_quote();
CREATE FUNCTION public.protect_sent_quote_item() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_status text;
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.tenant_id, NEW.quote_id) IS DISTINCT FROM (OLD.tenant_id, OLD.quote_id) THEN
        RAISE EXCEPTION 'Quote item parent is immutable';
    END IF;
    IF TG_OP = 'DELETE' THEN
        SELECT status INTO parent_status FROM public.quotes WHERE tenant_id = OLD.tenant_id AND id = OLD.quote_id FOR UPDATE;
    ELSE
        SELECT status INTO parent_status FROM public.quotes WHERE tenant_id = NEW.tenant_id AND id = NEW.quote_id FOR UPDATE;
    END IF;
    IF parent_status IS NOT NULL AND parent_status <> 'DRAFT' THEN RAISE EXCEPTION 'Sent quote items are immutable'; END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER protect_sent_quote_item BEFORE INSERT OR UPDATE OR DELETE ON public.quote_items
 FOR EACH ROW EXECUTE FUNCTION public.protect_sent_quote_item();
REVOKE ALL ON FUNCTION public.protect_sent_quote(), public.protect_sent_quote_item() FROM PUBLIC;
