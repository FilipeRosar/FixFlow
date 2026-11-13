-- Give every company-owned table a direct purge path, including the inventory ledger.
DO $$ DECLARE t record; BEGIN
    FOR t IN SELECT c.oid,c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        JOIN pg_attribute a ON a.attrelid=c.oid AND a.attname='tenant_id' AND NOT a.attisdropped
        WHERE n.nspname='public' AND c.relkind='r' AND c.relname<>'company_file_deletions'
        AND NOT EXISTS(SELECT 1 FROM pg_constraint k WHERE k.conrelid=c.oid AND k.contype='f' AND k.confrelid='public.tenants'::regclass)
    LOOP
        EXECUTE format('ALTER TABLE public.%I ADD CONSTRAINT company_purge_tenant FOREIGN KEY(tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE',t.relname);
    END LOOP;
END $$;
CREATE OR REPLACE FUNCTION public.delete_company(p_actor uuid,p_tenant uuid) RETURNS void
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
        VALUES(p_tenant,'logo','');
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
