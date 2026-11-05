CREATE TABLE public.work_order_attachments (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    upload_id uuid NOT NULL,
    slot smallint NOT NULL CHECK (slot BETWEEN 1 AND 20),
    storage_key varchar(250) NOT NULL UNIQUE,
    original_name varchar(180) NOT NULL,
    content_type varchar(40) NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'application/pdf')),
    size_bytes bigint NOT NULL CHECK (size_bytes BETWEEN 1 AND 10485760),
    checksum varchar(64) NOT NULL,
    source_checksum varchar(64) NOT NULL,
    uploaded_by uuid NOT NULL REFERENCES public.users(id),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, work_order_id, slot),
    UNIQUE (tenant_id, work_order_id, upload_id),
    FOREIGN KEY (tenant_id, work_order_id) REFERENCES public.work_orders(tenant_id, id) ON DELETE CASCADE
);
ALTER TABLE public.work_order_attachments ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.work_order_attachments FORCE ROW LEVEL SECURITY;
CREATE POLICY attachment_isolation ON public.work_order_attachments
 USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON public.work_order_attachments FROM PUBLIC, fixflow_runtime;
GRANT SELECT, INSERT ON public.work_order_attachments TO fixflow_runtime;
