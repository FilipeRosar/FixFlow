ALTER TABLE public.tenants ADD COLUMN plan VARCHAR(20) NOT NULL DEFAULT 'STARTER'
    CHECK (plan IN ('STARTER', 'PROFESSIONAL', 'BUSINESS'));

-- Tenant row locking serializes membership creation and plan changes.
CREATE FUNCTION public.enforce_user_plan_limit() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE current_plan text; maximum integer;
BEGIN
    IF NEW.status <> 'ACTIVE' THEN RETURN NEW; END IF;
    IF TG_OP = 'UPDATE' THEN
        IF OLD.status = 'ACTIVE' AND OLD.tenant_id = NEW.tenant_id THEN RETURN NEW; END IF;
    END IF;
    SELECT plan INTO current_plan FROM public.tenants WHERE id = NEW.tenant_id FOR UPDATE;
    maximum := CASE current_plan WHEN 'STARTER' THEN 2 WHEN 'PROFESSIONAL' THEN 6 ELSE NULL END;
    IF maximum IS NOT NULL AND (SELECT count(*) FROM public.tenant_memberships
        WHERE tenant_id = NEW.tenant_id AND status = 'ACTIVE') >= maximum THEN
        RAISE EXCEPTION 'USER_PLAN_LIMIT' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.enforce_user_plan_limit() FROM PUBLIC;
CREATE TRIGGER membership_plan_limit BEFORE INSERT OR UPDATE ON public.tenant_memberships
    FOR EACH ROW EXECUTE FUNCTION public.enforce_user_plan_limit();

CREATE TABLE public.tenant_order_usage (
    tenant_id uuid NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    month date NOT NULL,
    used integer NOT NULL CHECK (used >= 0),
    PRIMARY KEY (tenant_id, month)
);
ALTER TABLE public.tenant_order_usage ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.tenant_order_usage FORCE ROW LEVEL SECURITY;
CREATE POLICY order_usage_isolation ON public.tenant_order_usage
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON public.tenant_order_usage FROM PUBLIC, fixflow_runtime;

-- Called in the same transaction as OS insertion: rollback restores the quota.
CREATE FUNCTION public.consume_order_quota(p_tenant uuid) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE current_plan text; maximum integer; current_usage integer;
    current_month date := date_trunc('month', CURRENT_TIMESTAMP AT TIME ZONE 'America/Sao_Paulo')::date;
BEGIN
    IF p_tenant IS DISTINCT FROM NULLIF(current_setting('app.tenant_id', true), '')::uuid THEN
        RAISE EXCEPTION 'Tenant denied' USING ERRCODE = '42501';
    END IF;
    SELECT plan INTO current_plan FROM public.tenants WHERE id = p_tenant FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Tenant denied' USING ERRCODE = '42501'; END IF;
    maximum := CASE current_plan WHEN 'STARTER' THEN 100 WHEN 'PROFESSIONAL' THEN 500 ELSE NULL END;
    SELECT used INTO current_usage FROM public.tenant_order_usage
        WHERE tenant_id = p_tenant AND month = current_month;
    IF maximum IS NOT NULL AND coalesce(current_usage, 0) >= maximum THEN RETURN false; END IF;
    INSERT INTO public.tenant_order_usage(tenant_id, month, used) VALUES(p_tenant, current_month, 1)
        ON CONFLICT (tenant_id, month) DO UPDATE SET used = public.tenant_order_usage.used + 1;
    RETURN true;
END;
$$;
REVOKE ALL ON FUNCTION public.consume_order_quota(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.consume_order_quota(uuid) TO fixflow_runtime;
