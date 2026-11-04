ALTER TABLE public.tenant_memberships DROP CONSTRAINT chk_membership_role;
ALTER TABLE public.tenant_memberships ADD CONSTRAINT chk_membership_role
    CHECK (role IN ('OWNER', 'ATTENDANT', 'TECHNICIAN', 'ADMIN', 'VIEWER'));
CREATE POLICY management_membership_update ON public.tenant_memberships
    FOR UPDATE TO fixflow_migrator
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE FUNCTION public.manage_tenant_users(p_actor uuid, p_tenant uuid, p_user uuid,
    p_name text, p_email text, p_hash text, p_role text, p_deactivate boolean)
RETURNS TABLE(user_id uuid, full_name text, email text, role text, status text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE target uuid := p_user;
BEGIN
    IF p_tenant IS DISTINCT FROM NULLIF(current_setting('app.tenant_id', true), '')::uuid THEN
        RAISE EXCEPTION 'Tenant denied' USING ERRCODE = '42501';
    END IF;
    PERFORM 1 FROM public.tenants t WHERE t.id = p_tenant AND t.status = 'ACTIVE' FOR UPDATE;
    IF NOT FOUND OR NOT EXISTS (
        SELECT 1 FROM public.tenant_memberships m JOIN public.users u ON u.id = m.user_id
        WHERE m.user_id = p_actor AND m.tenant_id = p_tenant AND m.role = 'OWNER'
          AND m.status = 'ACTIVE' AND u.status = 'ACTIVE') THEN
        RAISE EXCEPTION 'Owner required' USING ERRCODE = '42501';
    END IF;
    IF p_role IS NOT NULL AND p_role NOT IN ('OWNER', 'ATTENDANT', 'TECHNICIAN') THEN
        RAISE EXCEPTION 'Invalid role' USING ERRCODE = '22023';
    END IF;
    IF p_name IS NOT NULL THEN
        IF p_role IS NULL OR btrim(p_name) = '' OR length(p_name) > 150
           OR p_email IS NULL OR btrim(p_email) = '' OR length(p_email) > 254
           OR p_hash IS NULL OR p_hash !~ '^[$]2[aby][$]12[$][./A-Za-z0-9]{53}$' THEN
            RAISE EXCEPTION 'Invalid user' USING ERRCODE = '22023';
        END IF;
        target := gen_random_uuid();
        INSERT INTO public.users(id, full_name, email, password_hash)
            VALUES(target, btrim(p_name), lower(btrim(p_email)), p_hash);
        INSERT INTO public.tenant_memberships(user_id, tenant_id, role) VALUES(target, p_tenant, p_role);
    ELSIF target IS NOT NULL THEN
        IF EXISTS (SELECT 1 FROM public.tenant_memberships m WHERE m.user_id = target AND m.tenant_id = p_tenant
                   AND m.role = 'OWNER' AND m.status = 'ACTIVE')
           AND (p_deactivate OR p_role IS DISTINCT FROM 'OWNER')
           AND NOT EXISTS (SELECT 1 FROM public.tenant_memberships m JOIN public.users u ON u.id = m.user_id
                           WHERE m.tenant_id = p_tenant AND m.user_id <> target AND m.role = 'OWNER'
                             AND m.status = 'ACTIVE' AND u.status = 'ACTIVE') THEN
            RAISE EXCEPTION 'Last owner' USING ERRCODE = '23514';
        END IF;
        UPDATE public.tenant_memberships m SET role = coalesce(p_role, m.role),
            status = CASE WHEN p_deactivate THEN 'SUSPENDED' ELSE m.status END, updated_at = CURRENT_TIMESTAMP
        WHERE m.tenant_id = p_tenant AND m.user_id = target;
    END IF;
    RETURN QUERY SELECT u.id, u.full_name::text, u.email::text, m.role::text, m.status::text
        FROM public.tenant_memberships m JOIN public.users u ON u.id = m.user_id
        WHERE m.tenant_id = p_tenant AND (target IS NULL OR m.user_id = target)
        ORDER BY u.full_name, u.id;
END;
$$;
REVOKE ALL ON FUNCTION public.manage_tenant_users(uuid, uuid, uuid, text, text, text, text, boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.manage_tenant_users(uuid, uuid, uuid, text, text, text, text, boolean) TO fixflow_runtime;
