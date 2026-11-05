CREATE TABLE public.work_order_diagnoses (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    revision integer NOT NULL CHECK (revision > 0),
    findings varchar(4000) NOT NULL CHECK (btrim(findings) <> ''),
    probable_cause varchar(4000) NOT NULL CHECK (btrim(probable_cause) <> ''),
    proposed_solution varchar(4000) NOT NULL CHECK (btrim(proposed_solution) <> ''),
    recorded_by uuid NOT NULL REFERENCES public.users(id),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, work_order_id, revision),
    FOREIGN KEY (tenant_id, work_order_id) REFERENCES public.work_orders(tenant_id, id) ON DELETE CASCADE
);
ALTER TABLE public.work_order_diagnoses ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.work_order_diagnoses FORCE ROW LEVEL SECURITY;
CREATE POLICY diagnosis_isolation ON public.work_order_diagnoses
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
REVOKE ALL ON public.work_order_diagnoses FROM PUBLIC, fixflow_runtime;
GRANT SELECT, INSERT ON public.work_order_diagnoses TO fixflow_runtime;
