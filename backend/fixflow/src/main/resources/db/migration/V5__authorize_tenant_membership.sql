
CREATE OR REPLACE FUNCTION public.authorize_tenant_membership(
    p_user_id UUID,
    p_tenant_id UUID
)
RETURNS TABLE (
    authorized_user_id UUID,
    authorized_tenant_id UUID,
    authorized_role VARCHAR(20)
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
SELECT
    m.user_id,
    m.tenant_id,
    m.role
FROM public.tenant_memberships AS m
         INNER JOIN public.tenants AS t
                    ON t.id = m.tenant_id
         INNER JOIN public.users AS u
                    ON u.id = m.user_id
WHERE m.user_id = p_user_id
  AND m.tenant_id = p_tenant_id
  AND m.status = 'ACTIVE'
  AND t.status = 'ACTIVE'
  AND u.status = 'ACTIVE'
    LIMIT 1;
$$;

REVOKE ALL ON FUNCTION
    public.authorize_tenant_membership(UUID, UUID)
    FROM PUBLIC;

GRANT EXECUTE ON FUNCTION
public.authorize_tenant_membership(UUID, UUID)
TO fixflow_runtime;
