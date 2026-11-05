ALTER TABLE public.work_orders
    ADD COLUMN ready_at timestamptz,
    ADD COLUMN delivered_at timestamptz,
    ADD COLUMN warranty_days integer CHECK (warranty_days >= 0),
    ADD COLUMN warranty_until date;

-- Server-owned integration projection for business modules. Never accepted in status requests.
-- Those modules must update their evidence while locking the work_order row in the same transaction.
CREATE TABLE public.work_order_transition_facts (
    tenant_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    technician_id uuid,
    diagnosis text,
    quote_id uuid,
    quote_version integer NOT NULL DEFAULT 1 CHECK (quote_version > 0),
    quote_status varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK (quote_status IN ('DRAFT', 'SENT', 'APPROVED', 'REJECTED')),
    quote_item_count integer NOT NULL DEFAULT 0 CHECK (quote_item_count >= 0),
    quote_total numeric(14,2) NOT NULL DEFAULT 0 CHECK (quote_total >= 0),
    quote_valid_until timestamptz,
    quote_token_hash varchar(64),
    service_count integer NOT NULL DEFAULT 0 CHECK (service_count >= 0),
    parts_consumed boolean NOT NULL DEFAULT false,
    parts_written_off boolean NOT NULL DEFAULT false,
    payment_settled boolean NOT NULL DEFAULT false,
    diagnostic_fee_registered boolean NOT NULL DEFAULT false,
    missing_part varchar(2000),
    maintenance_started boolean NOT NULL DEFAULT false,
    warranty_days integer NOT NULL DEFAULT 0 CHECK (warranty_days BETWEEN 0 AND 3650),
    PRIMARY KEY (tenant_id, work_order_id),
    FOREIGN KEY (tenant_id, work_order_id) REFERENCES public.work_orders(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (technician_id, tenant_id) REFERENCES public.tenant_memberships(user_id, tenant_id)
);
ALTER TABLE public.work_order_transition_facts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.work_order_transition_facts FORCE ROW LEVEL SECURITY;
CREATE POLICY transition_facts_isolation ON public.work_order_transition_facts
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON public.work_order_transition_facts FROM PUBLIC, fixflow_runtime;
GRANT SELECT, INSERT, UPDATE ON public.work_order_transition_facts TO fixflow_runtime;
