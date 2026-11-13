-- Paid plans continue to be applied exclusively by payment reconciliation.
CREATE FUNCTION public.change_saas_trial_plan(p_plan text) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE tenant uuid:=nullif(current_setting('app.tenant_id',true),'')::uuid; t record;
BEGIN
    IF tenant IS NULL OR p_plan NOT IN ('STARTER','PROFESSIONAL','BUSINESS') THEN RETURN false; END IF;
    SELECT * INTO t FROM public.tenants WHERE id=tenant FOR UPDATE;
    IF NOT FOUND OR t.subscription_paid_until IS NOT NULL OR t.trial_ends_at<=clock_timestamp()
        OR t.subscription_cancelled_at IS NOT NULL
        OR EXISTS(SELECT 1 FROM public.saas_contracts WHERE tenant_id=tenant AND state<>'CANCELED') THEN RETURN false; END IF;
    UPDATE public.tenants SET plan=p_plan WHERE id=tenant;
    RETURN true;
END;
$$;
REVOKE ALL ON FUNCTION public.change_saas_trial_plan(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.change_saas_trial_plan(text) TO fixflow_runtime;
