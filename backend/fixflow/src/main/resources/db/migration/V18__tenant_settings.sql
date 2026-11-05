CREATE TABLE public.tenant_settings (
    tenant_id UUID PRIMARY KEY REFERENCES public.tenants(id) ON DELETE CASCADE,
    logo_data_url TEXT,
    warranty_terms TEXT NOT NULL DEFAULT '' CHECK (length(warranty_terms) <= 10000),
    quote_validity_days INTEGER NOT NULL DEFAULT 7 CHECK (quote_validity_days BETWEEN 1 AND 365),
    allow_negative_stock BOOLEAN NOT NULL DEFAULT false,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_tenant_logo CHECK (logo_data_url IS NULL OR
        (length(logo_data_url) <= 700000 AND logo_data_url ~ '^data:image/(png|jpeg);base64,[A-Za-z0-9+/=]+$'))
);
ALTER TABLE public.tenant_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.tenant_settings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_settings_isolation ON public.tenant_settings
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON public.tenant_settings FROM PUBLIC, fixflow_runtime;
GRANT SELECT, INSERT, UPDATE ON public.tenant_settings TO fixflow_runtime;
