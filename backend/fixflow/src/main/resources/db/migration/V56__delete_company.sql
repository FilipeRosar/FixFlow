-- Tenant deletion is available only through the owner-checked function below.
DO $$ DECLARE c record; BEGIN
    FOR c IN SELECT conrelid::regclass AS tab,conname,pg_get_constraintdef(oid) AS definition
        FROM pg_constraint WHERE contype='f' AND confrelid='public.tenants'::regclass AND confdeltype='a'
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I',c.tab,c.conname);
        EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I %s ON DELETE CASCADE',c.tab,c.conname,c.definition);
    END LOOP;
    FOR c IN SELECT DISTINCT t.oid::regclass AS tab FROM pg_class t JOIN pg_namespace n ON n.oid=t.relnamespace
        JOIN pg_attribute a ON a.attrelid=t.oid WHERE n.nspname='public' AND t.relkind='r'
        AND a.attname='tenant_id' AND NOT a.attisdropped
    LOOP
        EXECUTE format('CREATE POLICY company_deletion ON %s FOR DELETE TO fixflow_migrator USING (tenant_id = nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',c.tab);
    END LOOP;
END $$;
CREATE POLICY company_deletion ON public.tenants FOR DELETE TO fixflow_migrator
    USING(id = nullif(current_setting('app.tenant_id',true),'')::uuid);
CREATE TABLE public.company_file_deletions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL,
    kind text NOT NULL CHECK(kind IN ('logo','attachment')), storage_key text NOT NULL
);
REVOKE ALL ON public.company_file_deletions FROM PUBLIC;
GRANT SELECT,DELETE ON public.company_file_deletions TO fixflow_runtime;
CREATE FUNCTION public.delete_company(p_actor uuid,p_tenant uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE members uuid[];
BEGIN
    IF p_tenant IS DISTINCT FROM nullif(current_setting('app.tenant_id',true),'')::uuid THEN
        RAISE EXCEPTION 'Tenant denied' USING ERRCODE='42501'; END IF;
    PERFORM 1 FROM public.tenants WHERE id=p_tenant FOR UPDATE;
    IF NOT FOUND OR NOT EXISTS(SELECT 1 FROM public.tenant_memberships m JOIN public.users u ON u.id=m.user_id
        WHERE m.tenant_id=p_tenant AND m.user_id=p_actor AND m.role='OWNER' AND m.status='ACTIVE' AND u.status='ACTIVE') THEN
        RAISE EXCEPTION 'Owner required' USING ERRCODE='42501'; END IF;
    IF EXISTS(SELECT 1 FROM public.saas_contracts WHERE tenant_id=p_tenant AND state<>'CANCELED') THEN
        RAISE EXCEPTION 'Cancel subscription before deleting' USING ERRCODE='23514'; END IF;
    SELECT array_agg(user_id) INTO members FROM public.tenant_memberships WHERE tenant_id=p_tenant;
    INSERT INTO public.company_file_deletions(tenant_id,kind,storage_key)
        SELECT p_tenant,'attachment',storage_key FROM public.work_order_attachments WHERE tenant_id=p_tenant;
    INSERT INTO public.company_file_deletions(tenant_id,kind,storage_key)
        SELECT p_tenant,'logo',logo_key FROM public.tenant_settings WHERE tenant_id=p_tenant AND logo_key IS NOT NULL;
    DELETE FROM public.pending_signups WHERE created_tenant=p_tenant;
    DELETE FROM public.tenants WHERE id=p_tenant;
    -- Shared identities remain usable in their other companies. Orphan identities lose their personal data and access.
    DELETE FROM public.password_reset_token WHERE user_id=ANY(members)
        AND NOT EXISTS(SELECT 1 FROM public.tenant_memberships m WHERE m.user_id=password_reset_token.user_id);
    UPDATE public.users u SET full_name='Conta excluída',email=u.id::text||'@deleted.invalid',password_hash='!',status='SUSPENDED',updated_at=clock_timestamp()
        WHERE u.id=ANY(members) AND NOT EXISTS(SELECT 1 FROM public.tenant_memberships m WHERE m.user_id=u.id);
END;
$$;
REVOKE ALL ON FUNCTION public.delete_company(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.delete_company(uuid,uuid) TO fixflow_runtime;
