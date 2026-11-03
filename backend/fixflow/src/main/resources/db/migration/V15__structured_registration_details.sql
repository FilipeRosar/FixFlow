ALTER TABLE public.users ALTER COLUMN full_name TYPE VARCHAR(301), ADD COLUMN first_name VARCHAR(150), ADD COLUMN last_name VARCHAR(150);
ALTER TABLE public.tenants
    ALTER COLUMN address TYPE TEXT,
    ADD COLUMN street VARCHAR(150), ADD COLUMN number VARCHAR(20), ADD COLUMN complement VARCHAR(100),
    ADD COLUMN neighborhood VARCHAR(100), ADD COLUMN city VARCHAR(100), ADD COLUMN state VARCHAR(2),
    ADD COLUMN postal_code VARCHAR(8);
DROP FUNCTION public.register_owner(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT);

CREATE FUNCTION public.register_owner(p_company_name TEXT, p_first_name TEXT, p_last_name TEXT, p_email TEXT, p_password_hash TEXT, p_document TEXT, p_street TEXT, p_number TEXT, p_complement TEXT, p_neighborhood TEXT, p_city TEXT, p_state TEXT, p_postal_code TEXT, p_phone TEXT)
RETURNS TABLE (user_id UUID, tenant_id UUID)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    new_user UUID := gen_random_uuid();
    new_tenant UUID := gen_random_uuid();
    previous_tenant TEXT := current_setting('app.tenant_id', true);
BEGIN
    IF p_company_name IS NULL OR btrim(p_company_name) = '' OR length(p_company_name) > 150
       OR p_first_name IS NULL OR btrim(p_first_name) = '' OR length(p_first_name) > 150
       OR p_last_name IS NULL OR btrim(p_last_name) = '' OR length(p_last_name) > 150
       OR p_email IS NULL OR btrim(p_email) = '' OR length(p_email) > 254
       OR p_document IS NULL OR p_document !~ '^([0-9]{11}|[0-9]{14})$'
       OR p_street IS NULL OR btrim(p_street) = '' OR length(p_street) > 150
       OR p_number IS NULL OR btrim(p_number) = '' OR length(p_number) > 20
       OR p_neighborhood IS NULL OR btrim(p_neighborhood) = '' OR length(p_neighborhood) > 100
       OR p_city IS NULL OR btrim(p_city) = '' OR length(p_city) > 100
       OR length(p_complement) > 100 OR p_state IS NULL OR p_state !~ '^[A-Z]{2}$'
       OR p_postal_code IS NULL OR p_postal_code !~ '^[0-9]{8}$'
       OR p_phone IS NULL OR p_phone !~ '^[0-9]{10,11}$'
       OR p_password_hash IS NULL OR p_password_hash !~ '^[$]2[aby][$]12[$][./A-Za-z0-9]{53}$' THEN
        RAISE EXCEPTION 'Invalid registration' USING ERRCODE = '22023';
    END IF;
    PERFORM set_config('app.tenant_id', new_tenant::text, true);
    INSERT INTO public.tenants (id, name, document, address, phone, street, number, complement, neighborhood, city, state, postal_code)
        VALUES (new_tenant, btrim(p_company_name), p_document, concat_ws(', ', p_street, p_number, nullif(p_complement, ''), p_neighborhood, p_city, p_state, p_postal_code), p_phone,
                p_street, p_number, p_complement, p_neighborhood, p_city, p_state, p_postal_code);
    INSERT INTO public.users (id, full_name, email, password_hash, first_name, last_name)
        VALUES (new_user, btrim(p_first_name) || ' ' || btrim(p_last_name), lower(btrim(p_email)), p_password_hash, btrim(p_first_name), btrim(p_last_name));
    INSERT INTO public.tenant_memberships (user_id, tenant_id, role)
        VALUES (new_user, new_tenant, 'OWNER');
    PERFORM set_config('app.tenant_id', coalesce(previous_tenant, ''), true);
    RETURN QUERY SELECT new_user, new_tenant;
END;
$$;
REVOKE ALL ON FUNCTION public.register_owner(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.register_owner(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT) TO fixflow_runtime;
