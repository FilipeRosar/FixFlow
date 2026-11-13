ALTER TABLE public.users ADD COLUMN email_verified_at timestamptz;
ALTER TABLE public.tenants
    ADD COLUMN onboarding_profile_completed_at timestamptz DEFAULT clock_timestamp(),
    ADD COLUMN onboarding_completed_at timestamptz DEFAULT clock_timestamp();
GRANT UPDATE(name,document,address,phone,street,number,complement,neighborhood,city,state,postal_code,
    onboarding_profile_completed_at,onboarding_completed_at) ON public.tenants TO fixflow_runtime;

-- A pending signup owns no tenant or user. Only these narrow functions can access it.
CREATE TABLE public.pending_signups (
    email varchar(254) PRIMARY KEY,
    full_name varchar(301) NOT NULL,
    company_name varchar(150) NOT NULL,
    selected_plan varchar(20) NOT NULL CHECK(selected_plan IN ('STARTER','PROFESSIONAL','BUSINESS')),
    password_hash varchar(255),
    token_hash varchar(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    last_sent_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    send_window_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    sends integer NOT NULL DEFAULT 1,
    consumed_at timestamptz,
    created_user uuid REFERENCES public.users(id) ON DELETE SET NULL,
    created_tenant uuid REFERENCES public.tenants(id) ON DELETE SET NULL
);
CREATE TABLE public.signup_request_limits (
    origin_hash varchar(64) PRIMARY KEY,
    window_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    requests integer NOT NULL DEFAULT 1
);
REVOKE ALL ON public.pending_signups,public.signup_request_limits FROM PUBLIC,fixflow_runtime;

CREATE FUNCTION public.begin_verified_signup(p_email text,p_name text,p_company text,p_plan text,p_password_hash text,p_token_hash text,p_origin_hash text)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE v_previous record; v_limit record;
BEGIN
    IF p_email IS NULL OR p_email<>lower(btrim(p_email)) OR length(p_email)>254 OR p_email !~ '^[^[:space:]@]+@[^[:space:]@]+[.][^[:space:]@]+$'
        OR p_name IS NULL OR btrim(p_name)='' OR length(p_name)>301 OR p_company IS NULL OR btrim(p_company)='' OR length(p_company)>150
        OR p_plan IS NULL OR p_plan NOT IN ('STARTER','PROFESSIONAL','BUSINESS')
        OR p_password_hash IS NULL OR p_password_hash !~ '^[$]2[aby][$]12[$][./A-Za-z0-9]{53}$'
        OR p_token_hash IS NULL OR p_token_hash !~ '^[0-9a-f]{64}$' OR p_origin_hash IS NULL OR p_origin_hash !~ '^[0-9a-f]{64}$'
        THEN RAISE EXCEPTION 'Invalid signup' USING ERRCODE='22023'; END IF;
    INSERT INTO public.signup_request_limits(origin_hash) VALUES(p_origin_hash) ON CONFLICT DO NOTHING;
    SELECT * INTO v_limit FROM public.signup_request_limits WHERE origin_hash=p_origin_hash FOR UPDATE;
    IF v_limit.window_at<clock_timestamp()-interval '1 hour' THEN
        UPDATE public.signup_request_limits SET window_at=clock_timestamp(),requests=1 WHERE origin_hash=p_origin_hash;
    ELSIF v_limit.requests>=20 THEN RETURN false;
    ELSE UPDATE public.signup_request_limits SET requests=requests+1 WHERE origin_hash=p_origin_hash; END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('signup:'||p_email,0));
    IF EXISTS(SELECT 1 FROM public.users u WHERE lower(u.email)=p_email) THEN RETURN false; END IF;
    SELECT * INTO v_previous FROM public.pending_signups WHERE email=p_email FOR UPDATE;
    IF FOUND AND (v_previous.last_sent_at>clock_timestamp()-interval '60 seconds'
        OR (v_previous.send_window_at>clock_timestamp()-interval '1 hour' AND v_previous.sends>=5)) THEN RETURN false; END IF;
    INSERT INTO public.pending_signups(email,full_name,company_name,selected_plan,password_hash,token_hash,expires_at)
        VALUES(p_email,btrim(p_name),btrim(p_company),p_plan,p_password_hash,p_token_hash,clock_timestamp()+interval '24 hours')
        ON CONFLICT(email) DO UPDATE SET full_name=EXCLUDED.full_name,company_name=EXCLUDED.company_name,selected_plan=EXCLUDED.selected_plan,
            password_hash=EXCLUDED.password_hash,token_hash=EXCLUDED.token_hash,expires_at=EXCLUDED.expires_at,last_sent_at=clock_timestamp(),
            sends=CASE WHEN public.pending_signups.send_window_at<clock_timestamp()-interval '1 hour' THEN 1 ELSE public.pending_signups.sends+1 END,
            send_window_at=CASE WHEN public.pending_signups.send_window_at<clock_timestamp()-interval '1 hour' THEN clock_timestamp() ELSE public.pending_signups.send_window_at END;
    RETURN true;
END;
$$;
REVOKE ALL ON FUNCTION public.begin_verified_signup(text,text,text,text,text,text,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.begin_verified_signup(text,text,text,text,text,text,text) TO fixflow_runtime;

CREATE FUNCTION public.confirm_verified_signup(p_hash text) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE v_request record; v_user uuid:=gen_random_uuid(); v_tenant uuid:=gen_random_uuid();
    v_previous_tenant text:=current_setting('app.tenant_id',true);
BEGIN
    IF p_hash IS NULL OR p_hash !~ '^[0-9a-f]{64}$' THEN RETURN NULL; END IF;
    SELECT * INTO v_request FROM public.pending_signups WHERE token_hash=p_hash FOR UPDATE;
    IF NOT FOUND OR v_request.expires_at<=clock_timestamp() THEN RETURN NULL; END IF;
    IF v_request.consumed_at IS NOT NULL THEN
        IF v_request.created_user IS NULL OR v_request.created_tenant IS NULL THEN RETURN NULL; END IF;
        RETURN jsonb_build_object('userId',v_request.created_user,'tenantId',v_request.created_tenant,'role','OWNER','email',v_request.email);
    END IF;
    IF EXISTS(SELECT 1 FROM public.users u WHERE lower(u.email)=v_request.email) THEN RETURN NULL; END IF;
    PERFORM set_config('app.tenant_id',v_tenant::text,true);
    INSERT INTO public.tenants(id,name,plan,onboarding_profile_completed_at,onboarding_completed_at)
        VALUES(v_tenant,v_request.company_name,v_request.selected_plan,NULL,NULL);
    INSERT INTO public.users(id,full_name,email,password_hash,email_verified_at)
        VALUES(v_user,v_request.full_name,v_request.email,v_request.password_hash,clock_timestamp());
    INSERT INTO public.tenant_memberships(user_id,tenant_id,role) VALUES(v_user,v_tenant,'OWNER');
    UPDATE public.pending_signups SET consumed_at=clock_timestamp(),password_hash=NULL,created_user=v_user,created_tenant=v_tenant WHERE email=v_request.email;
    INSERT INTO public.audit_log(id,tenant_id,actor_id,action,resource_type,resource_id,details)
        VALUES(gen_random_uuid(),v_tenant,v_user,'EMAIL_CONFIRMED','TENANT',v_tenant,jsonb_build_object('plan',v_request.selected_plan));
    PERFORM set_config('app.tenant_id',coalesce(v_previous_tenant,''),true);
    RETURN jsonb_build_object('userId',v_user,'tenantId',v_tenant,'role','OWNER','email',v_request.email);
END;
$$;
REVOKE ALL ON FUNCTION public.confirm_verified_signup(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.confirm_verified_signup(text) TO fixflow_runtime;
CREATE FUNCTION public.purge_expired_signups() RETURNS void LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
    DELETE FROM public.pending_signups WHERE expires_at<clock_timestamp()-interval '1 day';
    DELETE FROM public.signup_request_limits WHERE window_at<clock_timestamp()-interval '1 day';
$$;
REVOKE ALL ON FUNCTION public.purge_expired_signups() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.purge_expired_signups() TO fixflow_runtime;
