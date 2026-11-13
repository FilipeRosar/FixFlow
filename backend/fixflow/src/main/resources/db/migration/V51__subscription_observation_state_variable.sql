-- Avoid ambiguity with the company address state column in PL/pgSQL.
CREATE OR REPLACE FUNCTION public.observe_saas_subscriptions() RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog,pg_temp AS $$
DECLARE t record; subscription_state text; until_at timestamptz; kind text; notice text;
    previous_tenant text := current_setting('app.tenant_id',true);
BEGIN
    FOR t IN SELECT id FROM public.tenants WHERE status='ACTIVE' LOOP
        PERFORM set_config('app.tenant_id',t.id::text,true);
        SELECT * INTO t FROM public.tenants WHERE id=t.id FOR UPDATE;
        until_at := coalesce(t.subscription_paid_until,t.trial_ends_at);
        subscription_state := CASE WHEN t.subscription_paid_until>clock_timestamp() THEN 'ACTIVE'
            WHEN t.subscription_paid_until IS NULL AND t.trial_ends_at>clock_timestamp() THEN 'TRIALING'
            WHEN t.subscription_cancelled_at IS NOT NULL THEN 'CANCELED'
            WHEN t.subscription_paid_until IS NULL THEN 'EXPIRED' ELSE 'PAST_DUE' END;
        IF subscription_state<>t.subscription_observed_state THEN
            UPDATE public.tenants SET subscription_observed_state=subscription_state WHERE id=t.id;
            INSERT INTO public.audit_log(id,tenant_id,actor_id,action,resource_type,resource_id,details)
                VALUES(gen_random_uuid(),t.id,NULL,'SUBSCRIPTION_ACCESS_CHANGED','TENANT',t.id,
                    jsonb_build_object('previousState',t.subscription_observed_state,'state',subscription_state,'accessUntil',until_at));
            INSERT INTO public.outbox_event(id,tenant_id,aggregate_type,aggregate_id,event_type,payload)
                VALUES(gen_random_uuid(),t.id,'SUBSCRIPTION',t.id,'SubscriptionAccessChanged',jsonb_build_object('state',subscription_state,'accessUntil',until_at));
        END IF;
        IF subscription_state IN ('ACTIVE','TRIALING') AND t.subscription_cancelled_at IS NULL AND until_at<=clock_timestamp()+interval '3 days' THEN
            kind := CASE WHEN subscription_state='TRIALING' THEN 'TrialEnding' ELSE 'SubscriptionDueSoon' END;
            notice := kind||':'||until_at::text;
            INSERT INTO public.saas_notices(tenant_id,notice_key) VALUES(t.id,notice) ON CONFLICT DO NOTHING;
            IF FOUND THEN
                INSERT INTO public.outbox_event(id,tenant_id,aggregate_type,aggregate_id,event_type,payload)
                    VALUES(gen_random_uuid(),t.id,'SUBSCRIPTION',t.id,kind,jsonb_build_object('state',subscription_state,'accessUntil',until_at));
            END IF;
        END IF;
    END LOOP;
    PERFORM set_config('app.tenant_id',coalesce(previous_tenant,''),true);
END;
$$;
