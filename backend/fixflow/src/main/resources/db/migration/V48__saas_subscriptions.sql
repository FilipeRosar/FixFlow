-- Existing companies receive a transition trial; future tenants receive it at creation.
ALTER TABLE public.tenants
    ADD COLUMN trial_started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    ADD COLUMN trial_ends_at timestamptz NOT NULL DEFAULT (clock_timestamp() + interval '14 days'),
    ADD COLUMN subscription_paid_from timestamptz,
    ADD COLUMN subscription_paid_until timestamptz,
    ADD COLUMN subscription_cancelled_at timestamptz;
GRANT UPDATE(subscription_cancelled_at) ON public.tenants TO fixflow_runtime;
GRANT SELECT ON public.tenant_order_usage TO fixflow_runtime;

CREATE TABLE public.saas_contracts (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    plan varchar(20) NOT NULL CHECK(plan IN ('STARTER','PROFESSIONAL','BUSINESS')),
    amount numeric(14,2) NOT NULL CHECK(amount > 0),
    currency varchar(3) NOT NULL DEFAULT 'BRL' CHECK(currency = 'BRL'),
    cycle varchar(10) NOT NULL DEFAULT 'MONTHLY' CHECK(cycle = 'MONTHLY'),
    idempotency_key varchar(100) NOT NULL,
    state varchar(20) NOT NULL DEFAULT 'READY' CHECK(state IN ('READY','CUSTOMER_CREATING','SUBSCRIPTION_READY','CREATING','ACTIVE','CANCELING','CANCELED')),
    gateway_customer varchar(100),
    gateway_ref varchar(100) UNIQUE,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    cancel_requested_at timestamptz,
    created_by uuid NOT NULL REFERENCES public.users(id),
    UNIQUE(tenant_id, id), UNIQUE(tenant_id, idempotency_key)
);
CREATE UNIQUE INDEX saas_one_current_contract ON public.saas_contracts(tenant_id) WHERE state <> 'CANCELED';
CREATE TABLE public.saas_invoices (
    id varchar(100) PRIMARY KEY,
    tenant_id uuid NOT NULL,
    contract_id uuid NOT NULL,
    amount numeric(14,2) NOT NULL CHECK(amount > 0),
    due_date date NOT NULL,
    status varchar(20) NOT NULL,
    invoice_url text,
    paid_at timestamptz,
    applied_at timestamptz,
    FOREIGN KEY(tenant_id,contract_id) REFERENCES public.saas_contracts(tenant_id,id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX saas_one_grant_per_cycle ON public.saas_invoices(contract_id,due_date) WHERE applied_at IS NOT NULL;
CREATE INDEX saas_invoices_history ON public.saas_invoices(tenant_id,due_date DESC,id);
CREATE TABLE public.saas_events (
    id varchar(200) PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    payment_ref varchar(100) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
ALTER TABLE public.saas_contracts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.saas_contracts FORCE ROW LEVEL SECURITY;
ALTER TABLE public.saas_invoices ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.saas_invoices FORCE ROW LEVEL SECURITY;
ALTER TABLE public.saas_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.saas_events FORCE ROW LEVEL SECURITY;
CREATE POLICY saas_contract_isolation ON public.saas_contracts USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY saas_invoice_isolation ON public.saas_invoices USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY saas_event_isolation ON public.saas_events USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY saas_webhook_lookup ON public.saas_contracts FOR SELECT TO fixflow_migrator USING(true);
REVOKE ALL ON public.saas_contracts, public.saas_invoices, public.saas_events FROM PUBLIC,fixflow_runtime;
GRANT SELECT,INSERT ON public.saas_contracts TO fixflow_runtime;
GRANT UPDATE(state,gateway_customer,gateway_ref,cancel_requested_at) ON public.saas_contracts TO fixflow_runtime;
GRANT SELECT ON public.saas_invoices TO fixflow_runtime;

-- Trusted gateway projection: tenant comes only from the stored contract, never from webhook input.
CREATE FUNCTION public.reconcile_saas_invoice(p_contract uuid,p_event text,p_subscription text,p_customer text,
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
        AND NOT EXISTS(SELECT 1 FROM public.audit_log WHERE tenant_id=c.tenant_id AND action='SUBSCRIPTION_LATE_PAYMENT' AND resource_id=c.id AND details->>'payment'=p_payment) THEN
        INSERT INTO public.audit_log(id,tenant_id,actor_id,action,resource_type,resource_id,details)
            VALUES(gen_random_uuid(),c.tenant_id,NULL,'SUBSCRIPTION_LATE_PAYMENT','SUBSCRIPTION',c.id,
                jsonb_build_object('payment',p_payment,'amount',p_amount,'requiresReconciliation',true));
    END IF;
    PERFORM set_config('app.tenant_id',coalesce(previous_tenant,''),true);
    RETURN true;
END;
$$;
REVOKE ALL ON FUNCTION public.reconcile_saas_invoice(uuid,text,text,text,text,numeric,date,text,text,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.reconcile_saas_invoice(uuid,text,text,text,text,numeric,date,text,text,timestamptz) TO fixflow_runtime;

ALTER TABLE public.tenants ADD COLUMN subscription_observed_state varchar(20) NOT NULL DEFAULT 'TRIALING';
CREATE TABLE public.saas_notices (
    tenant_id uuid NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    notice_key text NOT NULL,
    PRIMARY KEY(tenant_id,notice_key)
);
ALTER TABLE public.saas_notices ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.saas_notices FORCE ROW LEVEL SECURITY;
CREATE POLICY saas_notice_isolation ON public.saas_notices USING(tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK(tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid);
REVOKE ALL ON public.saas_notices FROM PUBLIC,fixflow_runtime;
-- Only this trusted function uses the migrator discovery policy; runtime remains tenant-scoped.
CREATE POLICY saas_account_discovery ON public.tenants FOR SELECT TO fixflow_migrator USING(true);
CREATE FUNCTION public.observe_saas_subscriptions() RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog,pg_temp AS $$
DECLARE t record; state text; until_at timestamptz; kind text; notice text;
    previous_tenant text := current_setting('app.tenant_id',true);
BEGIN
    FOR t IN SELECT * FROM public.tenants WHERE status='ACTIVE' FOR UPDATE LOOP
        PERFORM set_config('app.tenant_id',t.id::text,true);
        until_at := coalesce(t.subscription_paid_until,t.trial_ends_at);
        state := CASE WHEN t.subscription_paid_until>clock_timestamp() THEN 'ACTIVE'
            WHEN t.subscription_paid_until IS NULL AND t.trial_ends_at>clock_timestamp() THEN 'TRIALING'
            WHEN t.subscription_cancelled_at IS NOT NULL THEN 'CANCELED'
            WHEN t.subscription_paid_until IS NULL THEN 'EXPIRED' ELSE 'PAST_DUE' END;
        IF state<>t.subscription_observed_state THEN
            UPDATE public.tenants SET subscription_observed_state=state WHERE id=t.id;
            INSERT INTO public.audit_log(id,tenant_id,actor_id,action,resource_type,resource_id,details)
                VALUES(gen_random_uuid(),t.id,NULL,'SUBSCRIPTION_ACCESS_CHANGED','TENANT',t.id,
                    jsonb_build_object('previousState',t.subscription_observed_state,'state',state,'accessUntil',until_at));
            INSERT INTO public.outbox_event(id,tenant_id,aggregate_type,aggregate_id,event_type,payload)
                VALUES(gen_random_uuid(),t.id,'SUBSCRIPTION',t.id,'SubscriptionAccessChanged',jsonb_build_object('state',state,'accessUntil',until_at));
        END IF;
        IF state IN ('ACTIVE','TRIALING') AND t.subscription_cancelled_at IS NULL AND until_at<=clock_timestamp()+interval '3 days' THEN
            kind := CASE WHEN state='TRIALING' THEN 'TrialEnding' ELSE 'SubscriptionDueSoon' END;
            notice := kind||':'||until_at::text;
            INSERT INTO public.saas_notices(tenant_id,notice_key) VALUES(t.id,notice) ON CONFLICT DO NOTHING;
            IF FOUND THEN
                INSERT INTO public.outbox_event(id,tenant_id,aggregate_type,aggregate_id,event_type,payload)
                    VALUES(gen_random_uuid(),t.id,'SUBSCRIPTION',t.id,kind,jsonb_build_object('state',state,'accessUntil',until_at));
            END IF;
        END IF;
    END LOOP;
    PERFORM set_config('app.tenant_id',coalesce(previous_tenant,''),true);
END;
$$;
REVOKE ALL ON FUNCTION public.observe_saas_subscriptions() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.observe_saas_subscriptions() TO fixflow_runtime;

CREATE FUNCTION public.record_saas_trial() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE previous_tenant text := current_setting('app.tenant_id',true);
BEGIN
    PERFORM set_config('app.tenant_id',NEW.id::text,true);
    INSERT INTO public.audit_log(id,tenant_id,actor_id,action,resource_type,resource_id,details)
        VALUES(gen_random_uuid(),NEW.id,NULL,'SUBSCRIPTION_TRIAL_STARTED','TENANT',NEW.id,
            jsonb_build_object('state','TRIALING','startsAt',NEW.trial_started_at,'endsAt',NEW.trial_ends_at));
    INSERT INTO public.outbox_event(id,tenant_id,aggregate_type,aggregate_id,event_type,payload)
        VALUES(gen_random_uuid(),NEW.id,'SUBSCRIPTION',NEW.id,'TrialStarted',jsonb_build_object('state','TRIALING','accessUntil',NEW.trial_ends_at));
    PERFORM set_config('app.tenant_id',coalesce(previous_tenant,''),true);
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.record_saas_trial() FROM PUBLIC;
CREATE TRIGGER saas_trial_created AFTER INSERT ON public.tenants FOR EACH ROW EXECUTE FUNCTION public.record_saas_trial();

-- Public decision links must respect write restrictions too; public reading stays available.
ALTER FUNCTION public.decide_public_quote(text,text,text,text,text,text,text) RENAME TO decide_public_quote_before_subscription;
REVOKE ALL ON FUNCTION public.decide_public_quote_before_subscription(text,text,text,text,text,text,text) FROM PUBLIC,fixflow_runtime;
CREATE FUNCTION public.decide_public_quote(p_hash text,p_decision text,p_content_hash text,p_reason text,p_key_hash text,p_ip text,p_ua text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE tenant uuid; writable boolean; result jsonb; previous_tenant text:=current_setting('app.tenant_id',true);
BEGIN
    SELECT tenant_id INTO tenant FROM public.quote_access_tokens WHERE token_hash=p_hash AND revoked_at IS NULL;
    IF NOT FOUND THEN RETURN jsonb_build_object('status',404); END IF;
    PERFORM set_config('app.tenant_id',tenant::text,true);
    SELECT coalesce(subscription_paid_until,trial_ends_at)>clock_timestamp() INTO writable FROM public.tenants WHERE id=tenant;
    IF NOT coalesce(writable,false) THEN
        PERFORM set_config('app.tenant_id',coalesce(previous_tenant,''),true);
        RETURN jsonb_build_object('status',409);
    END IF;
    result:=public.decide_public_quote_before_subscription(p_hash,p_decision,p_content_hash,p_reason,p_key_hash,p_ip,p_ua);
    PERFORM set_config('app.tenant_id',coalesce(previous_tenant,''),true);
    RETURN result;
END;
$$;
REVOKE ALL ON FUNCTION public.decide_public_quote(text,text,text,text,text,text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.decide_public_quote(text,text,text,text,text,text,text) TO fixflow_runtime;

CREATE FUNCTION public.saas_owner_emails() RETURNS SETOF text LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
    SELECT u.email::text FROM public.users u JOIN public.tenant_memberships m ON m.user_id=u.id
    WHERE m.tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid
        AND m.role='OWNER' AND m.status='ACTIVE' AND u.status='ACTIVE';
$$;
REVOKE ALL ON FUNCTION public.saas_owner_emails() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.saas_owner_emails() TO fixflow_runtime;

CREATE FUNCTION public.complete_saas_cancellation(p_contract uuid,p_remote text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE c record; previous_tenant text:=current_setting('app.tenant_id',true);
BEGIN
    SELECT * INTO c FROM public.saas_contracts WHERE id=p_contract;
    IF NOT FOUND OR c.gateway_ref IS DISTINCT FROM p_remote THEN RETURN; END IF;
    PERFORM set_config('app.tenant_id',c.tenant_id::text,true);
    PERFORM 1 FROM public.tenants WHERE id=c.tenant_id FOR UPDATE;
    UPDATE public.saas_contracts SET state='CANCELED' WHERE id=c.id AND state='CANCELING';
    IF FOUND THEN
        INSERT INTO public.audit_log(id,tenant_id,actor_id,action,resource_type,resource_id,details)
            VALUES(gen_random_uuid(),c.tenant_id,NULL,'SubscriptionCanceled','SUBSCRIPTION',c.id,jsonb_build_object('previousState','CANCELING','state','CANCELED'));
        INSERT INTO public.outbox_event(id,tenant_id,aggregate_type,aggregate_id,event_type,payload)
            VALUES(gen_random_uuid(),c.tenant_id,'SUBSCRIPTION',c.id,'SubscriptionCanceled',jsonb_build_object('state','CANCELED'));
    END IF;
    PERFORM set_config('app.tenant_id',coalesce(previous_tenant,''),true);
END;
$$;
REVOKE ALL ON FUNCTION public.complete_saas_cancellation(uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.complete_saas_cancellation(uuid,text) TO fixflow_runtime;
