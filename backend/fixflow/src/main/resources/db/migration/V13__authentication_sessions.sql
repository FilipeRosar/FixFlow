ALTER TABLE public.users ADD COLUMN failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0);
ALTER TABLE public.users ADD COLUMN lock_level INTEGER NOT NULL DEFAULT 0 CHECK (lock_level BETWEEN 0 AND 3);
ALTER TABLE public.users ADD COLUMN locked_until TIMESTAMPTZ;

CREATE TABLE public.auth_session (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    absolute_expires_at TIMESTAMPTZ NOT NULL DEFAULT (clock_timestamp() + interval '30 days'),
    revoked_at TIMESTAMPTZ
);
CREATE INDEX idx_auth_session_user ON public.auth_session(user_id);
CREATE TABLE public.refresh_token (
    id UUID PRIMARY KEY,
    family_id UUID NOT NULL REFERENCES public.auth_session(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    replaced_by UUID REFERENCES public.refresh_token(id)
);
CREATE INDEX idx_refresh_token_family ON public.refresh_token(family_id);
REVOKE ALL ON public.auth_session, public.refresh_token FROM PUBLIC, fixflow_runtime;
ALTER TABLE public.auth_session ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.auth_session FORCE ROW LEVEL SECURITY;
ALTER TABLE public.refresh_token ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.refresh_token FORCE ROW LEVEL SECURITY;
CREATE POLICY auth_session_owner ON public.auth_session TO fixflow_migrator USING (true) WITH CHECK (true);
CREATE POLICY refresh_token_owner ON public.refresh_token TO fixflow_migrator USING (true) WITH CHECK (true);
-- Authentication enumerates active memberships through a restricted function only.
CREATE POLICY auth_memberships_owner_read ON public.tenant_memberships FOR SELECT TO fixflow_migrator USING (true);
CREATE POLICY auth_tenants_owner_read ON public.tenants FOR SELECT TO fixflow_migrator USING (true);

CREATE FUNCTION public.auth_lock_account(p_email TEXT)
RETURNS TABLE (user_id UUID, email TEXT, password_hash TEXT, status TEXT, locked_until TIMESTAMPTZ)
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
    SELECT u.id, u.email::text, u.password_hash::text, u.status::text, u.locked_until
    FROM public.users u WHERE lower(u.email) = lower(btrim(p_email)) FOR UPDATE;
$$;
CREATE FUNCTION public.auth_record_failure(p_user UUID) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE failures INTEGER; level INTEGER;
BEGIN
    SELECT CASE WHEN u.failed_attempts >= 5 THEN 1 ELSE u.failed_attempts + 1 END, u.lock_level
    INTO failures, level FROM public.users u WHERE id = p_user FOR UPDATE;
    UPDATE public.users SET failed_attempts = failures,
        lock_level = CASE WHEN failures >= 5 THEN least(3, level + 1) ELSE level END,
        locked_until = CASE WHEN failures >= 5 THEN clock_timestamp() +
            CASE level WHEN 0 THEN interval '1 minute' WHEN 1 THEN interval '5 minutes' ELSE interval '15 minutes' END
            ELSE NULL END
    WHERE id = p_user;
END;
$$;
CREATE FUNCTION public.auth_reset_failures(p_user UUID) RETURNS void
LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$ UPDATE public.users SET failed_attempts = 0, lock_level = 0, locked_until = NULL WHERE id = p_user; $$;
CREATE FUNCTION public.auth_active_membership(p_user UUID, p_tenant UUID)
RETURNS TABLE (user_id UUID, email TEXT, tenant_id UUID, role TEXT)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
    SELECT u.id, u.email::text, m.tenant_id, m.role::text
    FROM public.users u JOIN public.tenant_memberships m ON m.user_id = u.id
    JOIN public.tenants t ON t.id = m.tenant_id
    WHERE u.id = p_user AND u.status = 'ACTIVE' AND m.status = 'ACTIVE' AND t.status = 'ACTIVE'
        AND (p_tenant IS NULL OR m.tenant_id = p_tenant)
    ORDER BY m.created_at, m.id LIMIT 1;
$$;
CREATE FUNCTION public.auth_create_session(p_family UUID, p_user UUID, p_tenant UUID, p_token UUID, p_hash TEXT)
RETURNS TIMESTAMPTZ
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE token_expiry TIMESTAMPTZ := clock_timestamp() + interval '7 days';
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.auth_active_membership(p_user, p_tenant)) THEN
        RAISE EXCEPTION 'Inactive membership' USING ERRCODE = '28000';
    END IF;
    INSERT INTO public.auth_session(id, user_id, tenant_id) VALUES (p_family, p_user, p_tenant);
    INSERT INTO public.refresh_token(id, family_id, token_hash, expires_at) VALUES (p_token, p_family, p_hash, token_expiry);
    RETURN token_expiry;
END;
$$;
CREATE FUNCTION public.auth_lock_refresh(p_hash TEXT)
RETURNS TABLE (token_id UUID, family_id UUID, user_id UUID, tenant_id UUID,
    expires_at TIMESTAMPTZ, absolute_expires_at TIMESTAMPTZ, used_at TIMESTAMPTZ, revoked_at TIMESTAMPTZ)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE family UUID;
BEGIN
    SELECT r.family_id INTO family FROM public.refresh_token r WHERE r.token_hash = p_hash;
    IF family IS NULL THEN RETURN; END IF;
    -- Lock the family first, so requests using different generations cannot race revocation.
    PERFORM 1 FROM public.auth_session s WHERE s.id = family FOR UPDATE;
    RETURN QUERY SELECT r.id, r.family_id, s.user_id, s.tenant_id, r.expires_at,
        s.absolute_expires_at, r.used_at, s.revoked_at
        FROM public.refresh_token r JOIN public.auth_session s ON s.id = r.family_id
        WHERE r.token_hash = p_hash;
END;
$$;
CREATE FUNCTION public.auth_revoke_family(p_family UUID) RETURNS void
LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$ UPDATE public.auth_session SET revoked_at = coalesce(revoked_at, clock_timestamp()) WHERE id = p_family; $$;
CREATE FUNCTION public.auth_rotate_refresh(p_old UUID, p_new UUID, p_hash TEXT)
RETURNS TIMESTAMPTZ
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE family UUID; expiry TIMESTAMPTZ;
BEGIN
    SELECT r.family_id INTO family FROM public.refresh_token r WHERE r.id = p_old;
    PERFORM 1 FROM public.auth_session s WHERE s.id = family FOR UPDATE;
    IF NOT EXISTS (SELECT 1 FROM public.refresh_token r JOIN public.auth_session s ON s.id = r.family_id
        WHERE r.id = p_old AND r.used_at IS NULL AND r.expires_at > clock_timestamp()
            AND s.revoked_at IS NULL AND s.absolute_expires_at > clock_timestamp()) THEN
        RAISE EXCEPTION 'Invalid refresh' USING ERRCODE = '28000';
    END IF;
    SELECT least(clock_timestamp() + interval '7 days', s.absolute_expires_at) INTO expiry
        FROM public.auth_session s WHERE s.id = family;
    INSERT INTO public.refresh_token(id, family_id, token_hash, expires_at) VALUES (p_new, family, p_hash, expiry);
    UPDATE public.refresh_token SET used_at = clock_timestamp(), replaced_by = p_new WHERE id = p_old;
    RETURN expiry;
END;
$$;

REVOKE ALL ON FUNCTION public.auth_lock_account(TEXT), public.auth_record_failure(UUID), public.auth_reset_failures(UUID),
    public.auth_active_membership(UUID, UUID), public.auth_create_session(UUID, UUID, UUID, UUID, TEXT),
    public.auth_lock_refresh(TEXT), public.auth_revoke_family(UUID), public.auth_rotate_refresh(UUID, UUID, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.auth_lock_account(TEXT), public.auth_record_failure(UUID), public.auth_reset_failures(UUID),
    public.auth_active_membership(UUID, UUID), public.auth_create_session(UUID, UUID, UUID, UUID, TEXT),
    public.auth_lock_refresh(TEXT), public.auth_revoke_family(UUID), public.auth_rotate_refresh(UUID, UUID, TEXT) TO fixflow_runtime;
