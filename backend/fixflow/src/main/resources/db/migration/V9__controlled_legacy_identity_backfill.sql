-- Preserve legacy records. New identities remain disabled until ownership and
-- credentials are reviewed; no legacy role is inferred as OWNER or ADMIN.
CREATE TABLE public.legacy_user_identity_migrations (
    legacy_user_id UUID PRIMARY KEY REFERENCES public.app_users (id),
    user_id UUID NOT NULL REFERENCES public.users (id),
    tenant_id UUID NOT NULL REFERENCES public.tenants (id),
    created_identity BOOLEAN NOT NULL,
    created_membership BOOLEAN NOT NULL,
    requires_password_reset BOOLEAN NOT NULL,
    migrated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

REVOKE ALL ON TABLE public.legacy_user_identity_migrations FROM PUBLIC, fixflow_runtime;

CREATE FUNCTION public.backfill_legacy_app_users()
RETURNS TABLE (
    migrated_records BIGINT,
    created_identities BIGINT,
    created_memberships BIGINT,
    reset_required BIGINT
)
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    bcrypt_pattern CONSTANT TEXT := '^[$]2[aby][$](0[4-9]|[12][0-9]|3[01])[$][./A-Za-z0-9]{53}$';
BEGIN
    -- Serialize provisioning and hold a stable snapshot of identities/memberships.
    LOCK TABLE public.app_users, public.users, public.tenant_memberships,
        public.legacy_user_identity_migrations IN SHARE ROW EXCLUSIVE MODE;

    -- FORCE RLS also applies to the owner. These policies exist only inside the
    -- migration transaction, target the migrator exclusively and are removed
    -- before returning. A failed statement rolls back their creation too.
    CREATE POLICY f02_legacy_read ON public.app_users
        FOR SELECT TO fixflow_migrator USING (true);
    CREATE POLICY f02_legacy_memberships ON public.tenant_memberships
        FOR ALL TO fixflow_migrator USING (true) WITH CHECK (true);

    CREATE TEMP TABLE f02_legacy_candidates ON COMMIT DROP AS
    WITH pending AS (
        SELECT a.*,
            COALESCE(u.id, FIRST_VALUE(a.id) OVER (
                PARTITION BY LOWER(a.email) ORDER BY a.created_at, a.id
            )) AS target_user_id,
            u.id IS NOT NULL AS identity_existed
        FROM public.app_users a
        LEFT JOIN public.users u ON LOWER(u.email) = LOWER(a.email)
        WHERE NOT EXISTS (
            SELECT 1 FROM public.legacy_user_identity_migrations m WHERE m.legacy_user_id = a.id
        )
    )
    SELECT p.*, EXISTS (
        SELECT 1 FROM public.tenant_memberships m
        WHERE m.user_id = p.target_user_id AND m.tenant_id = p.tenant_id
    ) AS membership_existed
    FROM pending p;

    IF EXISTS (SELECT 1 FROM pg_temp.f02_legacy_candidates WHERE email = '' OR email <> BTRIM(email)) THEN
        RAISE EXCEPTION 'Legacy backfill refused: blank or non-canonical email. Review source records.';
    END IF;
    IF EXISTS (
        SELECT LOWER(email) FROM pg_temp.f02_legacy_candidates
        GROUP BY LOWER(email) HAVING COUNT(DISTINCT password_hash) > 1
    ) THEN
        RAISE EXCEPTION 'Legacy backfill refused: conflicting credentials for the same email. Verify account ownership.';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_temp.f02_legacy_candidates a JOIN public.users u ON LOWER(u.email) = LOWER(a.email)
        WHERE u.password_hash <> a.password_hash
    ) THEN
        RAISE EXCEPTION 'Legacy backfill refused: credentials differ from an existing global identity.';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_temp.f02_legacy_candidates a JOIN public.users u ON u.id = a.id
        WHERE LOWER(u.email) <> LOWER(a.email)
    ) THEN
        RAISE EXCEPTION 'Legacy backfill refused: legacy UUID belongs to another global identity.';
    END IF;

    INSERT INTO public.users (id, full_name, email, password_hash, status, created_at)
    SELECT DISTINCT ON (target_user_id)
        target_user_id, full_name, LOWER(email),
        CASE WHEN password_hash ~ bcrypt_pattern THEN password_hash ELSE '!RESET_REQUIRED!' END,
        'SUSPENDED', created_at
    FROM pg_temp.f02_legacy_candidates
    WHERE NOT identity_existed
    ORDER BY target_user_id, created_at, id;

    INSERT INTO public.tenant_memberships (user_id, tenant_id, role, status)
    SELECT DISTINCT target_user_id, tenant_id, 'VIEWER', 'INVITED'
    FROM pg_temp.f02_legacy_candidates
    WHERE NOT membership_existed;

    INSERT INTO public.legacy_user_identity_migrations (
        legacy_user_id, user_id, tenant_id, created_identity, created_membership, requires_password_reset
    )
    SELECT id, target_user_id, tenant_id, NOT identity_existed, NOT membership_existed,
        NOT (password_hash ~ bcrypt_pattern)
    FROM pg_temp.f02_legacy_candidates;

    SELECT COUNT(*),
        COUNT(DISTINCT target_user_id) FILTER (WHERE NOT identity_existed),
        COUNT(DISTINCT (target_user_id, tenant_id)) FILTER (WHERE NOT membership_existed),
        COUNT(*) FILTER (WHERE NOT (password_hash ~ bcrypt_pattern))
    INTO migrated_records, created_identities, created_memberships, reset_required
    FROM pg_temp.f02_legacy_candidates;

    DROP TABLE pg_temp.f02_legacy_candidates;
    DROP POLICY f02_legacy_read ON public.app_users;
    DROP POLICY f02_legacy_memberships ON public.tenant_memberships;
    RETURN NEXT;
END;
$$;

REVOKE ALL ON FUNCTION public.backfill_legacy_app_users() FROM PUBLIC, fixflow_runtime;

-- Flyway runs the migration atomically. The invoker must be the table owner
-- fixflow_migrator. The runtime cannot execute this maintenance function.
SELECT * FROM public.backfill_legacy_app_users();
