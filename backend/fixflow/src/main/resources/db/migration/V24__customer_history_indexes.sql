CREATE INDEX work_orders_customer_history ON public.work_orders(tenant_id, customer_id, created_at DESC, id DESC);
CREATE INDEX equipment_customer_history ON public.equipment(tenant_id, customer_id, created_at DESC, id DESC);
