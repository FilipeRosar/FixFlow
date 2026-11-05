CREATE INDEX work_order_assigned_queue ON public.work_order_transition_facts(tenant_id, technician_id, work_order_id) WHERE technician_id IS NOT NULL;
CREATE INDEX work_orders_queue_created ON public.work_orders(tenant_id, created_at DESC, id DESC);
