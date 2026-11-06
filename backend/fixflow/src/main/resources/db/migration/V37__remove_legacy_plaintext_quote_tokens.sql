-- Previous state-machine events had no email consumer. Remove their cleartext
-- delivery token rather than preserve an unprotected bearer credential.
CREATE POLICY quote_send_migration_tenants ON public.tenants FOR SELECT TO fixflow_migrator USING (true);
DO $$
DECLARE
    tenant uuid;
    previous_tenant text := current_setting('app.tenant_id', true);
BEGIN
    FOR tenant IN SELECT id FROM public.tenants LOOP
        PERFORM set_config('app.tenant_id', tenant::text, true);
        UPDATE public.outbox_event SET payload = payload - 'publicToken'
            WHERE tenant_id = tenant AND event_type = 'QuoteSent' AND payload ? 'publicToken';
    END LOOP;
    PERFORM set_config('app.tenant_id', coalesce(previous_tenant, ''), true);
END;
$$;
DROP POLICY quote_send_migration_tenants ON public.tenants;
