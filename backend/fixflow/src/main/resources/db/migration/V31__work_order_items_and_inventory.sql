CREATE TABLE public.parts (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES public.tenants(id),
    sku varchar(80) NOT NULL CHECK (btrim(sku) <> ''),
    name varchar(200) NOT NULL CHECK (btrim(name) <> ''),
    unit varchar(20) NOT NULL DEFAULT 'UN',
    cost numeric(14,2) NOT NULL CHECK (cost >= 0),
    sale_price numeric(14,2) NOT NULL CHECK (sale_price >= 0),
    quantity_on_hand numeric(14,3) NOT NULL DEFAULT 0,
    min_quantity numeric(14,3) NOT NULL DEFAULT 0 CHECK (min_quantity >= 0),
    active boolean NOT NULL DEFAULT true,
    version integer NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, sku)
);
CREATE TABLE public.work_order_items (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    type varchar(10) NOT NULL CHECK (type IN ('SERVICE', 'PART')),
    part_id uuid,
    description varchar(500) NOT NULL CHECK (btrim(description) <> ''),
    quantity numeric(14,3) NOT NULL CHECK (quantity > 0),
    unit_price numeric(14,2) NOT NULL CHECK (unit_price >= 0),
    unit_cost numeric(14,2) NOT NULL CHECK (unit_cost >= 0),
    request_hash varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    removed_at timestamptz,
    UNIQUE (tenant_id, id),
    CHECK ((type = 'PART' AND part_id IS NOT NULL) OR (type = 'SERVICE' AND part_id IS NULL)),
    FOREIGN KEY (tenant_id, part_id) REFERENCES public.parts(tenant_id, id),
    FOREIGN KEY (tenant_id, work_order_id) REFERENCES public.work_orders(tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX work_order_items_order ON public.work_order_items(tenant_id, work_order_id, created_at);
CREATE TABLE public.inventory_movements (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    part_id uuid NOT NULL,
    work_order_id uuid,
    item_id uuid,
    type varchar(10) NOT NULL CHECK (type IN ('ENTRY', 'EXIT', 'RETURN')),
    quantity numeric(14,3) NOT NULL CHECK (quantity <> 0),
    unit_cost numeric(14,2) NOT NULL CHECK (unit_cost >= 0),
    created_by uuid NOT NULL REFERENCES public.users(id),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((type = 'EXIT' AND quantity < 0 AND item_id IS NOT NULL AND work_order_id IS NOT NULL)
        OR (type = 'RETURN' AND quantity > 0 AND item_id IS NOT NULL AND work_order_id IS NOT NULL)
        OR (type = 'ENTRY' AND quantity > 0 AND item_id IS NULL AND work_order_id IS NULL)),
    UNIQUE (tenant_id, item_id, type),
    FOREIGN KEY (tenant_id, part_id) REFERENCES public.parts(tenant_id, id)
);
CREATE INDEX inventory_movements_part ON public.inventory_movements(tenant_id, part_id, created_at);
-- Ledger references retain the item/order identifiers even if an administrative purge occurs.
ALTER TABLE public.parts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.parts FORCE ROW LEVEL SECURITY;
CREATE POLICY parts_isolation ON public.parts
 USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
ALTER TABLE public.work_order_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.work_order_items FORCE ROW LEVEL SECURITY;
CREATE POLICY items_isolation ON public.work_order_items
 USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
ALTER TABLE public.inventory_movements ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.inventory_movements FORCE ROW LEVEL SECURITY;
CREATE POLICY movements_isolation ON public.inventory_movements
 USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON public.parts, public.work_order_items, public.inventory_movements FROM PUBLIC, fixflow_runtime;
GRANT SELECT, INSERT, UPDATE ON public.parts, public.work_order_items TO fixflow_runtime;
GRANT SELECT, INSERT ON public.inventory_movements TO fixflow_runtime;
