ALTER TABLE public.saas_contracts ADD COLUMN checkout_url text;
GRANT UPDATE(checkout_url) ON public.saas_contracts TO fixflow_runtime;

-- Resolve tenant ownership from the stored contract; no tenant is accepted from the webhook.
CREATE FUNCTION public.cancel_mercadopago_subscription(p_contract uuid,p_remote text) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE c record; previous_tenant text:=current_setting('app.tenant_id',true);
BEGIN
    IF p_remote !~ '^sub_mp[a-zA-Z0-9]+$' THEN RETURN false; END IF;
    SELECT * INTO c FROM public.saas_contracts WHERE id=p_contract;
    IF NOT FOUND OR c.gateway_ref IS DISTINCT FROM p_remote THEN RETURN false; END IF;
    PERFORM set_config('app.tenant_id',c.tenant_id::text,true);
    PERFORM 1 FROM public.tenants WHERE id=c.tenant_id FOR UPDATE;
    UPDATE public.saas_contracts SET state='CANCELED',checkout_url=NULL,
        cancel_requested_at=coalesce(cancel_requested_at,clock_timestamp()) WHERE id=c.id AND state<>'CANCELED';
    IF FOUND THEN
        UPDATE public.tenants SET subscription_cancelled_at=coalesce(subscription_cancelled_at,clock_timestamp()) WHERE id=c.tenant_id;
        INSERT INTO public.audit_log(id,tenant_id,actor_id,action,resource_type,resource_id,details)
            VALUES(gen_random_uuid(),c.tenant_id,NULL,'SubscriptionCanceled','SUBSCRIPTION',c.id,
                jsonb_build_object('previousState',c.state,'state','CANCELED','provider','MERCADO_PAGO'));
        INSERT INTO public.outbox_event(id,tenant_id,aggregate_type,aggregate_id,event_type,payload)
            VALUES(gen_random_uuid(),c.tenant_id,'SUBSCRIPTION',c.id,'SubscriptionCanceled',jsonb_build_object('state','CANCELED'));
    END IF;
    PERFORM set_config('app.tenant_id',coalesce(previous_tenant,''),true);
    RETURN true;
END;
$$;
REVOKE ALL ON FUNCTION public.cancel_mercadopago_subscription(uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.cancel_mercadopago_subscription(uuid,text) TO fixflow_runtime;
