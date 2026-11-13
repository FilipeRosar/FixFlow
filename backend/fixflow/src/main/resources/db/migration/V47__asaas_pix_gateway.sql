CREATE TABLE public.pix_charges (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    amount numeric(14,2) NOT NULL CHECK (amount > 0),
    due_date date NOT NULL,
    asaas_customer varchar(100) NOT NULL,
    idempotency_key varchar(200) NOT NULL,
    created_by uuid NOT NULL REFERENCES public.users(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    status varchar(20) NOT NULL DEFAULT 'CREATING' CHECK (status IN ('CREATING', 'PENDING', 'RECEIVED')),
    gateway_ref varchar(100) UNIQUE,
    pix_payload text,
    pix_image text,
    pix_expiration varchar(100),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, work_order_id) REFERENCES public.work_orders(tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX pix_charges_order ON public.pix_charges(tenant_id, work_order_id, created_at);
ALTER TABLE public.pix_charges ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.pix_charges FORCE ROW LEVEL SECURITY;
CREATE POLICY pix_charges_isolation ON public.pix_charges
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
-- Trusted webhook function can resolve the stored tenant from the opaque local reference.
CREATE POLICY asaas_charge_lookup ON public.pix_charges FOR SELECT TO fixflow_migrator USING (true);
REVOKE ALL ON public.pix_charges FROM PUBLIC, fixflow_runtime;
GRANT SELECT, INSERT ON public.pix_charges TO fixflow_runtime;
GRANT UPDATE (gateway_ref, pix_payload, pix_image, pix_expiration, status) ON public.pix_charges TO fixflow_runtime;
ALTER TABLE public.payments ADD COLUMN gateway_charge_id uuid,
    ADD CONSTRAINT payments_gateway_charge UNIQUE (gateway_charge_id),
    ADD CONSTRAINT payments_gateway_charge_tenant FOREIGN KEY (tenant_id, gateway_charge_id) REFERENCES public.pix_charges(tenant_id, id);

CREATE TABLE public.asaas_webhook_events (
    id varchar(200) PRIMARY KEY,
    tenant_id uuid NOT NULL,
    charge_id uuid NOT NULL,
    gateway_ref varchar(100) NOT NULL,
    received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (tenant_id, charge_id) REFERENCES public.pix_charges(tenant_id, id) ON DELETE CASCADE
);
ALTER TABLE public.asaas_webhook_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.asaas_webhook_events FORCE ROW LEVEL SECURITY;
CREATE POLICY asaas_events_isolation ON public.asaas_webhook_events
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON public.asaas_webhook_events FROM PUBLIC, fixflow_runtime;
GRANT SELECT ON public.asaas_webhook_events TO fixflow_runtime;

CREATE FUNCTION public.reconcile_asaas_pix(p_reference uuid, p_event text, p_remote text, p_customer text, p_value numeric, p_paid date)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    charge record;
    payment_id uuid;
    existing_event record;
    previous_tenant text := current_setting('app.tenant_id', true);
BEGIN
    IF p_event IS NULL OR length(p_event) > 200 OR p_event !~ '^evt_[a-zA-Z0-9_-]+$'
        OR p_remote IS NULL OR p_remote !~ '^pay_[a-zA-Z0-9]+$' THEN RETURN false; END IF;
    SELECT * INTO charge FROM public.pix_charges WHERE id = p_reference;
    IF NOT FOUND THEN RETURN true; END IF; -- unrelated charge in the Asaas account
    PERFORM set_config('app.tenant_id', charge.tenant_id::text, true);
    PERFORM 1 FROM public.work_orders WHERE tenant_id = charge.tenant_id AND id = charge.work_order_id FOR UPDATE;
    SELECT * INTO charge FROM public.pix_charges WHERE id = p_reference FOR UPDATE;
    IF charge.amount IS DISTINCT FROM p_value OR charge.asaas_customer IS DISTINCT FROM p_customer
        OR (charge.gateway_ref IS NOT NULL AND charge.gateway_ref <> p_remote) THEN
        PERFORM set_config('app.tenant_id', coalesce(previous_tenant, ''), true);
        RETURN false;
    END IF;
    INSERT INTO public.asaas_webhook_events(id, tenant_id, charge_id, gateway_ref)
        VALUES (p_event, charge.tenant_id, charge.id, p_remote) ON CONFLICT (id) DO NOTHING;
    IF NOT FOUND THEN
        SELECT * INTO existing_event FROM public.asaas_webhook_events WHERE id = p_event;
        PERFORM set_config('app.tenant_id', coalesce(previous_tenant, ''), true);
        RETURN existing_event.charge_id IS NOT DISTINCT FROM charge.id AND existing_event.gateway_ref IS NOT DISTINCT FROM p_remote;
    END IF;
    UPDATE public.pix_charges SET gateway_ref = p_remote, status = 'RECEIVED' WHERE id = charge.id;
    payment_id := gen_random_uuid();
    INSERT INTO public.payments(id, tenant_id, work_order_id, amount, method, paid_at, notes, idempotency_key, created_by, gateway_charge_id)
        VALUES (payment_id, charge.tenant_id, charge.work_order_id, charge.amount, 'PIX',
            coalesce(p_paid::timestamp AT TIME ZONE 'America/Sao_Paulo', clock_timestamp()), 'Recebimento PIX via Asaas',
            'asaas:' || charge.id::text, charge.created_by, charge.id)
        ON CONFLICT (gateway_charge_id) DO NOTHING;
    IF FOUND THEN
        INSERT INTO public.audit_log(id, tenant_id, actor_id, action, resource_type, resource_id, details)
            VALUES (gen_random_uuid(), charge.tenant_id, NULL, 'PAYMENT_REGISTERED', 'WORK_ORDER', charge.work_order_id,
                jsonb_build_object('paymentId', payment_id, 'amount', charge.amount, 'gateway', 'ASAAS', 'gatewayRef', p_remote, 'eventId', p_event));
    END IF;
    PERFORM set_config('app.tenant_id', coalesce(previous_tenant, ''), true);
    RETURN true;
END;
$$;
REVOKE ALL ON FUNCTION public.reconcile_asaas_pix(uuid, text, text, text, numeric, date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.reconcile_asaas_pix(uuid, text, text, text, numeric, date) TO fixflow_runtime;
