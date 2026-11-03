-- Bootstrap is the only public provisioning operation; runtime gets no table writes.
CREATE POLICY registration_membership_insert ON public.tenant_memberships
    FOR INSERT TO fixflow_migrator
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE FUNCTION public.register_owner(p_company_name TEXT, p_full_name TEXT, p_email TEXT, p_password_hash TEXT)
RETURNS TABLE (user_id UUID, tenant_id UUID)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    new_user UUID := gen_random_uuid();
    new_tenant UUID := gen_random_uuid();
    previous_tenant TEXT := current_setting('app.tenant_id', true);
BEGIN
    IF p_company_name IS NULL OR btrim(p_company_name) = '' OR length(p_company_name) > 150
       OR p_full_name IS NULL OR btrim(p_full_name) = '' OR length(p_full_name) > 150
       OR p_email IS NULL OR btrim(p_email) = '' OR length(p_email) > 254
       OR p_password_hash IS NULL OR p_password_hash !~ '^[$]2[aby][$]12[$][./A-Za-z0-9]{53}$' THEN
        RAISE EXCEPTION 'Invalid registration' USING ERRCODE = '22023';
    END IF;
    PERFORM set_config('app.tenant_id', new_tenant::text, true);
    INSERT INTO public.tenants (id, name) VALUES (new_tenant, btrim(p_company_name));
    INSERT INTO public.users (id, full_name, email, password_hash)
        VALUES (new_user, btrim(p_full_name), lower(btrim(p_email)), p_password_hash);
    INSERT INTO public.tenant_memberships (user_id, tenant_id, role)
        VALUES (new_user, new_tenant, 'OWNER');
    PERFORM set_config('app.tenant_id', coalesce(previous_tenant, ''), true);
    RETURN QUERY SELECT new_user, new_tenant;
END;
$$;
REVOKE ALL ON FUNCTION public.register_owner(TEXT, TEXT, TEXT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.register_owner(TEXT, TEXT, TEXT, TEXT) TO fixflow_runtime;
