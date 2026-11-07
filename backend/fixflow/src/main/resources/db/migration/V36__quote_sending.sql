CREATE TABLE public.quote_send_requests (
    tenant_id uuid NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    key_hash varchar(64) NOT NULL,
    request_hash varchar(64) NOT NULL,
    response jsonb,
    expires_at timestamptz NOT NULL DEFAULT (clock_timestamp() + interval '24 hours'),
    PRIMARY KEY (tenant_id, key_hash)
);
CREATE TABLE public.quote_access_tokens (
    tenant_id uuid NOT NULL,
    quote_id uuid NOT NULL,
    token_hash varchar(64) PRIMARY KEY,
    expires_at timestamptz NOT NULL,
    read_until timestamptz NOT NULL,
    revoked_at timestamptz,
    UNIQUE (tenant_id, quote_id),
    FOREIGN KEY (tenant_id, quote_id) REFERENCES public.quotes(tenant_id, id) ON DELETE CASCADE
);
CREATE TABLE public.quote_email_deliveries (
    tenant_id uuid NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    event_id uuid NOT NULL,
    status varchar(10) NOT NULL CHECK (status IN ('SENDING', 'SENT', 'SKIPPED')),
    sent_at timestamptz,
    PRIMARY KEY (tenant_id, event_id)
);
ALTER TABLE public.quote_send_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.quote_send_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY quote_send_requests_isolation ON public.quote_send_requests
 USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
ALTER TABLE public.quote_access_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.quote_access_tokens FORCE ROW LEVEL SECURITY;
CREATE POLICY quote_access_tokens_isolation ON public.quote_access_tokens
 USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
ALTER TABLE public.quote_email_deliveries ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.quote_email_deliveries FORCE ROW LEVEL SECURITY;
CREATE POLICY quote_email_deliveries_isolation ON public.quote_email_deliveries
 USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON public.quote_send_requests, public.quote_access_tokens, public.quote_email_deliveries FROM PUBLIC, fixflow_runtime;
GRANT SELECT, INSERT, UPDATE ON public.quote_send_requests, public.quote_access_tokens, public.quote_email_deliveries TO fixflow_runtime;
