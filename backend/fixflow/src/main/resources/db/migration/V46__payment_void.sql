ALTER TABLE public.payments
    ADD COLUMN void_reason varchar(2000),
    ADD COLUMN voided_at timestamptz,
    ADD COLUMN voided_by uuid REFERENCES public.users(id),
    ADD CONSTRAINT payment_void_evidence CHECK (
        (status = 'CONFIRMED' AND void_reason IS NULL AND voided_at IS NULL AND voided_by IS NULL)
        OR (status = 'VOIDED' AND void_reason IS NOT NULL AND btrim(void_reason) <> ''
            AND voided_at IS NOT NULL AND voided_by IS NOT NULL)
    );

-- Grant only the fields needed for a single irreversible ledger transition.
GRANT UPDATE (status, void_reason, voided_at, voided_by) ON public.payments TO fixflow_runtime;
CREATE FUNCTION public.protect_payment_evidence() RETURNS trigger LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF OLD.status <> 'CONFIRMED' OR NEW.status <> 'VOIDED'
        OR (to_jsonb(NEW) - ARRAY['status', 'void_reason', 'voided_at', 'voided_by'])
            IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['status', 'void_reason', 'voided_at', 'voided_by']) THEN
        RAISE EXCEPTION 'Payment evidence is immutable; only CONFIRMED to VOIDED is allowed' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER protect_payment_evidence BEFORE UPDATE ON public.payments
    FOR EACH ROW EXECUTE FUNCTION public.protect_payment_evidence();
CREATE TRIGGER void_payment_financial_status AFTER UPDATE OF status ON public.payments
    FOR EACH ROW EXECUTE FUNCTION public.refresh_payment_status();
REVOKE ALL ON FUNCTION public.protect_payment_evidence() FROM PUBLIC, fixflow_runtime;
-- DELETE and TRUNCATE remain forbidden to the runtime; a void keeps the original row.
