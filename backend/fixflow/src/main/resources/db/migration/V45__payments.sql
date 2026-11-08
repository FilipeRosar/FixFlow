ALTER TABLE public.work_orders ADD COLUMN financial_status varchar(10) NOT NULL DEFAULT 'UNPAID'
    CHECK (financial_status IN ('UNPAID', 'PARTIAL', 'PAID'));
CREATE TABLE public.payments (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    amount numeric(14,2) NOT NULL CHECK (amount > 0),
    method varchar(20) NOT NULL CHECK (method IN ('CASH', 'PIX', 'CREDIT_CARD', 'DEBIT_CARD', 'BANK_TRANSFER', 'OTHER')),
    status varchar(10) NOT NULL DEFAULT 'CONFIRMED' CHECK (status IN ('CONFIRMED', 'VOIDED')),
    paid_at timestamptz NOT NULL,
    notes varchar(2000),
    idempotency_key varchar(200) NOT NULL CHECK (btrim(idempotency_key) <> ''),
    created_by uuid NOT NULL REFERENCES public.users(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, work_order_id) REFERENCES public.work_orders(tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX payments_order ON public.payments(tenant_id, work_order_id, paid_at, id);
ALTER TABLE public.payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.payments FORCE ROW LEVEL SECURITY;
CREATE POLICY payments_isolation ON public.payments
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON public.payments FROM PUBLIC, fixflow_runtime;
GRANT SELECT, INSERT ON public.payments TO fixflow_runtime;

-- Both payment registration and delivery lock the order before changing financial evidence.
CREATE FUNCTION public.refresh_payment_status() RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp AS $$
DECLARE paid numeric; approved numeric; financial text;
BEGIN
    PERFORM 1 FROM public.work_orders WHERE tenant_id = NEW.tenant_id AND id = NEW.work_order_id FOR UPDATE;
    SELECT coalesce(sum(amount), 0) INTO paid FROM public.payments
        WHERE tenant_id = NEW.tenant_id AND work_order_id = NEW.work_order_id AND status = 'CONFIRMED';
    SELECT total INTO approved FROM public.quotes WHERE tenant_id = NEW.tenant_id
        AND work_order_id = NEW.work_order_id AND status = 'APPROVED' ORDER BY version DESC LIMIT 1;
    financial := CASE WHEN paid = 0 THEN 'UNPAID' WHEN approved IS NOT NULL AND paid >= approved THEN 'PAID' ELSE 'PARTIAL' END;
    UPDATE public.work_orders SET financial_status = financial WHERE tenant_id = NEW.tenant_id AND id = NEW.work_order_id;
    INSERT INTO public.work_order_transition_facts(tenant_id, work_order_id, payment_settled)
        VALUES (NEW.tenant_id, NEW.work_order_id, financial = 'PAID')
        ON CONFLICT (tenant_id, work_order_id) DO UPDATE SET payment_settled = EXCLUDED.payment_settled;
    RETURN NEW;
END;
$$;
CREATE TRIGGER payment_financial_status AFTER INSERT ON public.payments
    FOR EACH ROW EXECUTE FUNCTION public.refresh_payment_status();
CREATE TRIGGER quote_payment_status AFTER UPDATE OF status ON public.quotes
    FOR EACH ROW WHEN (NEW.status = 'APPROVED') EXECUTE FUNCTION public.refresh_payment_status();
REVOKE ALL ON FUNCTION public.refresh_payment_status() FROM PUBLIC, fixflow_runtime;
