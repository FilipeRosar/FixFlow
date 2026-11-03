

CREATE OR REPLACE FUNCTION public.find_auth_identity(
    p_email TEXT
)
RETURNS TABLE (
    user_id UUID,
    user_email VARCHAR(254),
    password_hash VARCHAR(255),
    user_status VARCHAR(20)
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
SELECT
    u.id,
    u.email,
    u.password_hash,
    u.status
FROM public.users AS u
WHERE LOWER(u.email) = LOWER(p_email)
    LIMIT 1;
$$;

REVOKE ALL ON FUNCTION public.find_auth_identity(TEXT)
    FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.find_auth_identity(TEXT)
TO fixflow_runtime;
