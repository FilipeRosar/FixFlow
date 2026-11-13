ALTER TABLE public.saas_invoices ALTER COLUMN status TYPE varchar(50);

CREATE OR REPLACE FUNCTION public.reconcile_saas_invoice(p_contract uuid,p_event text,p_subscription text,p_customer text,
    p_payment text,p_amount numeric,p_due date,p_status text,p_url text,p_paid timestamptz)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog,pg_temp AS $$
DECLARE c record; t record; inv record; previous_tenant text := current_setting('app.tenant_id',true);
    old_state text; new_until timestamptz; new_from timestamptz; did_apply boolean := false;
BEGIN
    IF p_payment !~ '^pay_[a-zA-Z0-9]+$' OR p_subscription !~ '^sub_[a-zA-Z0-9]+$'
        OR p_event IS NULL OR length(p_event)>200 OR p_due IS NULL THEN RETURN false; END IF;
    SELECT * INTO c FROM public.saas_contracts WHERE id=p_contract;
    IF NOT FOUND THEN RETURN false; END IF;
    PERFORM set_config('app.tenant_id',c.tenant_id::text,true);
    -- Stable lock ordering across creation, cancellation and payment confirmation.
    SELECT * INTO t FROM public.tenants WHERE id=c.tenant_id FOR UPDATE;
    SELECT * INTO c FROM public.saas_contracts WHERE id=p_contract FOR UPDATE;
    IF c.amount IS DISTINCT FROM p_amount OR c.gateway_customer IS DISTINCT FROM p_customer
        OR c.gateway_ref IS DISTINCT FROM p_subscription THEN
        PERFORM set_config('app.tenant_id',coalesce(previous_tenant,''),true); RETURN false;
    END IF;
    IF EXISTS(SELECT 1 FROM public.saas_events WHERE id=p_event AND payment_ref<>p_payment) THEN
        PERFORM set_config('app.tenant_id',coalesce(previous_tenant,''),true); RETURN false;
    END IF;
    INSERT INTO public.saas_events(id,tenant_id,payment_ref) VALUES(p_event,c.tenant_id,p_payment) ON CONFLICT DO NOTHING;
    IF NOT FOUND AND NOT EXISTS(SELECT 1 FROM public.saas_events WHERE id=p_event AND tenant_id=c.tenant_id AND payment_ref=p_payment) THEN
        PERFORM set_config('app.tenant_id',coalesce(previous_tenant,''),true); RETURN false;
    END IF;
    SELECT * INTO inv FROM public.saas_invoices WHERE id=p_payment FOR UPDATE;
    IF FOUND AND (inv.contract_id<>c.id OR inv.due_date<>p_due OR inv.amount<>p_amount) THEN
        PERFORM set_config('app.tenant_id',coalesce(previous_tenant,''),true); RETURN false;
    END IF;
    INSERT INTO public.saas_invoices(id,tenant_id,contract_id,amount,due_date,status,invoice_url,paid_at)
        VALUES(p_payment,c.tenant_id,c.id,p_amount,p_due,p_status,p_url,p_paid)
        ON CONFLICT(id) DO UPDATE SET
          status=CASE WHEN public.saas_invoices.applied_at IS NOT NULL OR public.saas_invoices.status IN ('RECEIVED','CONFIRMED')
                      THEN public.saas_invoices.status ELSE EXCLUDED.status END,
          invoice_url=EXCLUDED.invoice_url,paid_at=coalesce(public.saas_invoices.paid_at,EXCLUDED.paid_at);
    IF p_status IN ('RECEIVED','CONFIRMED') AND c.state='ACTIVE' AND c.cancel_requested_at IS NULL
        AND NOT EXISTS(SELECT 1 FROM public.saas_invoices WHERE contract_id=c.id AND due_date=p_due AND applied_at IS NOT NULL) THEN
        new_from := greatest(coalesce(t.subscription_paid_until,clock_timestamp()),clock_timestamp());
        new_until := (new_from AT TIME ZONE 'America/Sao_Paulo' + interval '1 month') AT TIME ZONE 'America/Sao_Paulo';
        UPDATE public.saas_invoices SET applied_at=clock_timestamp() WHERE id=p_payment AND applied_at IS NULL;
        IF FOUND THEN
            old_state := CASE WHEN t.subscription_paid_until>clock_timestamp() THEN 'ACTIVE'
                WHEN t.subscription_paid_until IS NOT NULL THEN 'PAST_DUE'
                WHEN t.trial_ends_at>clock_timestamp() THEN 'TRIALING' ELSE 'EXPIRED' END;
            UPDATE public.tenants SET plan=c.plan,subscription_paid_from=new_from,subscription_paid_until=new_until,
                subscription_cancelled_at=NULL WHERE id=c.tenant_id;
            did_apply := true;
            INSERT INTO public.audit_log(id,tenant_id,actor_id,action,resource_type,resource_id,details)
                VALUES(gen_random_uuid(),c.tenant_id,NULL,'SUBSCRIPTION_PAYMENT_CONFIRMED','SUBSCRIPTION',c.id,
                    jsonb_build_object('previousState',old_state,'state','ACTIVE','payment',p_payment,'amount',p_amount,'paidUntil',new_until));
            INSERT INTO public.outbox_event(id,tenant_id,aggregate_type,aggregate_id,event_type,payload)
                VALUES(gen_random_uuid(),c.tenant_id,'SUBSCRIPTION',c.id,'SubscriptionActivated',
                    jsonb_build_object('state','ACTIVE','paidUntil',new_until,'payment',p_payment));
        END IF;
    END IF;
    IF NOT did_apply AND p_status IN ('RECEIVED','CONFIRMED') AND c.cancel_requested_at IS NOT NULL
        AND NOT EXISTS(SELECT 1 FROM public.saas_invoices WHERE id=p_payment AND applied_at IS NOT NULL)
        AND NOT EXISTS(SELECT 1 FROM public.audit_log WHERE tenant_id=c.tenant_id AND action='SUBSCRIPTION_LATE_PAYMENT' AND resource_id=c.id AND details->>'payment'=p_payment) THEN
        INSERT INTO public.audit_log(id,tenant_id,actor_id,action,resource_type,resource_id,details)
            VALUES(gen_random_uuid(),c.tenant_id,NULL,'SUBSCRIPTION_LATE_PAYMENT','SUBSCRIPTION',c.id,
                jsonb_build_object('payment',p_payment,'amount',p_amount,'requiresReconciliation',true));
    END IF;
    PERFORM set_config('app.tenant_id',coalesce(previous_tenant,''),true);
    RETURN true;
END;
$$;
