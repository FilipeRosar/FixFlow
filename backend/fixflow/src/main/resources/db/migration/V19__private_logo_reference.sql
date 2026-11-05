-- Keep only a private file reference; never retain inline file contents in PostgreSQL.
ALTER TABLE public.tenant_settings DROP CONSTRAINT chk_tenant_logo;
ALTER TABLE public.tenant_settings RENAME COLUMN logo_data_url TO logo_key;
ALTER TABLE public.tenant_settings ADD CONSTRAINT chk_tenant_logo_key CHECK
    (logo_key IS NULL OR logo_key ~ ('^tenants/' || tenant_id::text || '/logos/[0-9a-f-]{36}[.]png$'));
