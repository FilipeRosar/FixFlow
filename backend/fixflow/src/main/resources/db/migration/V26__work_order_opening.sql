ALTER TABLE public.work_orders
    ADD COLUMN number bigint,
    ADD COLUMN equipment_id uuid,
    ADD COLUMN reported_issue varchar(4000),
    ADD COLUMN received_accessories jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN physical_condition varchar(2000),
    ADD COLUMN version integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT work_orders_tenant_id UNIQUE (tenant_id, id),
    ADD CONSTRAINT work_orders_number UNIQUE (tenant_id, number),
    ADD CONSTRAINT work_orders_equipment FOREIGN KEY (tenant_id, equipment_id) REFERENCES public.equipment(tenant_id, id),
    ADD CONSTRAINT work_orders_positive_number CHECK (number > 0);

-- Preserve foundation records created before the opening API existed.
CREATE POLICY opening_backfill ON public.work_orders TO fixflow_migrator USING (true) WITH CHECK (true);
WITH numbered AS (
    SELECT id, row_number() OVER (PARTITION BY tenant_id ORDER BY created_at, id) AS number
    FROM public.work_orders
)
UPDATE public.work_orders w SET number = n.number FROM numbered n WHERE w.id = n.id;

CREATE TABLE public.work_order_counters (
    tenant_id uuid PRIMARY KEY REFERENCES public.tenants(id) ON DELETE CASCADE,
    last_number bigint NOT NULL CHECK (last_number > 0)
);
INSERT INTO public.work_order_counters SELECT tenant_id, max(number) FROM public.work_orders GROUP BY tenant_id;
DROP POLICY opening_backfill ON public.work_orders;
CREATE TABLE public.work_order_status_history (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    from_status varchar(30),
    to_status varchar(30) NOT NULL,
    changed_by uuid NOT NULL REFERENCES public.users(id),
    source varchar(30) NOT NULL,
    reason varchar(2000),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (tenant_id, work_order_id) REFERENCES public.work_orders(tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX work_order_history_order ON public.work_order_status_history(tenant_id, work_order_id, created_at);
ALTER TABLE public.work_order_counters ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.work_order_counters FORCE ROW LEVEL SECURITY;
ALTER TABLE public.work_order_status_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.work_order_status_history FORCE ROW LEVEL SECURITY;
CREATE POLICY counter_isolation ON public.work_order_counters
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY history_isolation ON public.work_order_status_history
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON public.work_order_counters, public.work_order_status_history FROM PUBLIC, fixflow_runtime;
GRANT SELECT, INSERT, UPDATE ON public.work_order_counters TO fixflow_runtime;
GRANT SELECT, INSERT ON public.work_order_status_history TO fixflow_runtime;
