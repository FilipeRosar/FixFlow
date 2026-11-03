-- Existing accounts keep their current data; new registrations require contact details.
ALTER TABLE public.tenants
    ADD COLUMN document VARCHAR(14),
    ADD COLUMN address VARCHAR(500),
    ADD COLUMN phone VARCHAR(11);

DROP FUNCTION public.register_owner(TEXT, TEXT, TEXT, TEXT);

CREATE FUNCTION public.register_owner(p_company_name TEXT, p_full_name TEXT, p_email TEXT, p_password_hash TEXT, p_document TEXT, p_address TEXT, p_phone TEXT)
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
       OR p_document IS NULL OR p_document !~ '^([0-9]{11}|[0-9]{14})$'
       OR p_address IS NULL OR btrim(p_address) = '' OR length(p_address) > 500
       OR p_phone IS NULL OR p_phone !~ '^[0-9]{10,11}$'
       OR p_password_hash IS NULL OR p_password_hash !~ '^[$]2[aby][$]12[$][./A-Za-z0-9]{53}$' THEN
        RAISE EXCEPTION 'Invalid registration' USING ERRCODE = '22023';
    END IF;
    PERFORM set_config('app.tenant_id', new_tenant::text, true);
    INSERT INTO public.tenants (id, name, document, address, phone)
        VALUES (new_tenant, btrim(p_company_name), p_document, btrim(p_address), p_phone);
    INSERT INTO public.users (id, full_name, email, password_hash)
        VALUES (new_user, btrim(p_full_name), lower(btrim(p_email)), p_password_hash);
    INSERT INTO public.tenant_memberships (user_id, tenant_id, role)
        VALUES (new_user, new_tenant, 'OWNER');
    PERFORM set_config('app.tenant_id', coalesce(previous_tenant, ''), true);
    RETURN QUERY SELECT new_user, new_tenant;
END;
$$;
REVOKE ALL ON FUNCTION public.register_owner(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.register_owner(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT) TO fixflow_runtime;
