-- Expose only tenant-scoped display data; runtime cannot read global users.
CREATE FUNCTION public.work_order_team(p_tenant uuid)
RETURNS TABLE(user_id uuid, full_name text, role text, active boolean)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
    SELECT u.id, u.full_name::text, m.role::text, m.status = 'ACTIVE' AND u.status = 'ACTIVE'
    FROM public.tenant_memberships m JOIN public.users u ON u.id = m.user_id
    WHERE m.tenant_id = p_tenant
      AND p_tenant = NULLIF(current_setting('app.tenant_id', true), '')::uuid;
$$;
REVOKE ALL ON FUNCTION public.work_order_team(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.work_order_team(uuid) TO fixflow_runtime;
