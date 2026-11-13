ALTER TABLE public.pending_signups ADD COLUMN profile jsonb;
CREATE FUNCTION public.begin_verified_signup(p_email text,p_name text,p_company text,p_plan text,p_password_hash text,p_token_hash text,p_origin_hash text,p_profile jsonb)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
BEGIN
    IF NOT public.begin_verified_signup(p_email,p_name,p_company,p_plan,p_password_hash,p_token_hash,p_origin_hash) THEN RETURN false; END IF;
    UPDATE public.pending_signups SET profile=p_profile WHERE email=p_email;
    RETURN true;
END;
$$;
REVOKE ALL ON FUNCTION public.begin_verified_signup(text,text,text,text,text,text,text,jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.begin_verified_signup(text,text,text,text,text,text,text,jsonb) TO fixflow_runtime;
CREATE OR REPLACE FUNCTION public.confirm_verified_signup(p_hash text) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
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
    IF v_request.profile IS NOT NULL THEN
        UPDATE public.tenants SET document=v_request.profile->>'document',phone=v_request.profile->>'phone',
            street=v_request.profile#>>'{address,street}',number=v_request.profile#>>'{address,number}',
            complement=v_request.profile#>>'{address,complement}',neighborhood=v_request.profile#>>'{address,neighborhood}',
            city=v_request.profile#>>'{address,city}',state=v_request.profile#>>'{address,state}',postal_code=v_request.profile#>>'{address,postalCode}',
            address=concat_ws(', ',v_request.profile#>>'{address,street}',v_request.profile#>>'{address,number}',v_request.profile#>>'{address,complement}',v_request.profile#>>'{address,neighborhood}',v_request.profile#>>'{address,city}',v_request.profile#>>'{address,state}',v_request.profile#>>'{address,postalCode}'),
            onboarding_profile_completed_at=clock_timestamp() WHERE id=v_tenant;
    END IF;
    INSERT INTO public.users(id,full_name,email,password_hash,email_verified_at)
        VALUES(v_user,v_request.full_name,v_request.email,v_request.password_hash,clock_timestamp());
    INSERT INTO public.tenant_memberships(user_id,tenant_id,role) VALUES(v_user,v_tenant,'OWNER');
    UPDATE public.pending_signups SET consumed_at=clock_timestamp(),password_hash=NULL,profile=NULL,created_user=v_user,created_tenant=v_tenant WHERE email=v_request.email;
    INSERT INTO public.audit_log(id,tenant_id,actor_id,action,resource_type,resource_id,details)
        VALUES(gen_random_uuid(),v_tenant,v_user,'EMAIL_CONFIRMED','TENANT',v_tenant,jsonb_build_object('plan',v_request.selected_plan));
    PERFORM set_config('app.tenant_id',coalesce(v_previous_tenant,''),true);
    RETURN jsonb_build_object('userId',v_user,'tenantId',v_tenant,'role','OWNER','email',v_request.email);
END;
$$;
REVOKE ALL ON FUNCTION public.confirm_verified_signup(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.confirm_verified_signup(text) TO fixflow_runtime;
