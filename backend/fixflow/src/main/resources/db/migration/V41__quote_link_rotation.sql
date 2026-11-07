-- Preserve revoked token hashes for audit/404 responses while allowing one current link.
ALTER TABLE public.quote_access_tokens DROP CONSTRAINT quote_access_tokens_tenant_id_quote_id_key;
CREATE UNIQUE INDEX quote_access_tokens_one_current ON public.quote_access_tokens(tenant_id, quote_id) WHERE revoked_at IS NULL;
CREATE INDEX quote_access_tokens_quote ON public.quote_access_tokens(tenant_id, quote_id);
