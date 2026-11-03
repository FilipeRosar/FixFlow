CREATE TABLE public.password_reset_token (
    token_hash VARCHAR(64) PRIMARY KEY CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    user_id UUID NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (clock_timestamp() + interval '30 minutes'),
    used_at TIMESTAMPTZ
);
CREATE INDEX idx_password_reset_user ON public.password_reset_token(user_id);
REVOKE ALL ON public.password_reset_token FROM PUBLIC, fixflow_runtime;
ALTER TABLE public.password_reset_token ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.password_reset_token FORCE ROW LEVEL SECURITY;
CREATE POLICY password_reset_owner ON public.password_reset_token TO fixflow_migrator USING (true) WITH CHECK (true);

CREATE FUNCTION public.auth_issue_password_reset(p_email TEXT, p_hash TEXT)
RETURNS SETOF TEXT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE account_id UUID; recipient TEXT;
BEGIN
    IF p_hash IS NULL OR p_hash !~ '^[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'Invalid reset hash' USING ERRCODE = '22023';
    END IF;
    SELECT u.id, u.email INTO account_id, recipient FROM public.users u
        WHERE lower(u.email) = lower(btrim(p_email)) AND u.status = 'ACTIVE' FOR UPDATE;
    IF account_id IS NULL THEN RETURN; END IF;
    IF EXISTS (SELECT 1 FROM public.password_reset_token r WHERE r.user_id = account_id
        AND r.created_at > clock_timestamp() - interval '1 minute') THEN RETURN; END IF;
    UPDATE public.password_reset_token SET used_at = coalesce(used_at, clock_timestamp()) WHERE user_id = account_id;
    INSERT INTO public.password_reset_token(token_hash, user_id) VALUES (p_hash, account_id);
    RETURN NEXT recipient;
END;
$$;

CREATE FUNCTION public.auth_consume_password_reset(p_hash TEXT, p_password_hash TEXT)
RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
DECLARE account_id UUID;
BEGIN
    IF p_password_hash IS NULL OR p_password_hash !~ '^[$]2[aby][$]12[$][./A-Za-z0-9]{53}$' THEN
        RAISE EXCEPTION 'Invalid password hash' USING ERRCODE = '22023';
    END IF;
    SELECT r.user_id INTO account_id FROM public.password_reset_token r WHERE r.token_hash = p_hash;
    IF account_id IS NULL THEN RETURN false; END IF;
    -- Lock the identity before consuming: concurrent requests cannot reuse a token.
    PERFORM 1 FROM public.users u WHERE u.id = account_id AND u.status = 'ACTIVE' FOR UPDATE;
    IF NOT FOUND THEN RETURN false; END IF;
    UPDATE public.password_reset_token SET used_at = clock_timestamp()
        WHERE token_hash = p_hash AND used_at IS NULL AND expires_at > clock_timestamp();
    IF NOT FOUND THEN RETURN false; END IF;
    UPDATE public.users SET password_hash = p_password_hash, failed_attempts = 0, lock_level = 0, locked_until = NULL
        WHERE id = account_id;
    UPDATE public.password_reset_token SET used_at = coalesce(used_at, clock_timestamp()) WHERE user_id = account_id;
    UPDATE public.auth_session SET revoked_at = coalesce(revoked_at, clock_timestamp()) WHERE user_id = account_id;
    RETURN true;
END;
$$;
REVOKE ALL ON FUNCTION public.auth_issue_password_reset(TEXT, TEXT), public.auth_consume_password_reset(TEXT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.auth_issue_password_reset(TEXT, TEXT), public.auth_consume_password_reset(TEXT, TEXT) TO fixflow_runtime;
