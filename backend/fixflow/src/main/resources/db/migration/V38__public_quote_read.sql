-- Only the token-resolving SECURITY DEFINER function may discover a public tenant.
-- The runtime role retains tenant RLS on all tables and receives only a minimal JSON view.
CREATE POLICY public_quote_token_lookup ON public.quote_access_tokens FOR SELECT TO fixflow_migrator USING (true);
CREATE FUNCTION public.read_public_quote(p_hash text) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    access_record record;
    proposal record;
    result jsonb;
    allowed boolean := false;
    previous_tenant text := current_setting('app.tenant_id', true);
BEGIN
    IF p_hash IS NULL OR p_hash !~ '^[0-9a-f]{64}$' THEN RETURN NULL; END IF;
    SELECT * INTO access_record FROM public.quote_access_tokens WHERE token_hash = p_hash;
    IF NOT FOUND THEN RETURN NULL; END IF;
    PERFORM set_config('app.tenant_id', access_record.tenant_id::text, true);
    SELECT q.*, w.number AS order_number, w.status AS order_status, t.name AS company_name, t.status AS company_status,
        concat_ws(' ', nullif(e.brand, ''), e.model) AS equipment_label,
        f.quote_id AS current_quote_id, f.quote_token_hash AS current_token_hash
        INTO proposal
        FROM public.quotes q
        JOIN public.work_orders w ON w.tenant_id = q.tenant_id AND w.id = q.work_order_id
        JOIN public.tenants t ON t.id = q.tenant_id
        LEFT JOIN public.equipment e ON e.tenant_id = w.tenant_id AND e.id = w.equipment_id
        LEFT JOIN public.work_order_transition_facts f ON f.tenant_id = w.tenant_id AND f.work_order_id = w.id
        WHERE q.tenant_id = access_record.tenant_id AND q.id = access_record.quote_id;
    IF FOUND THEN
        allowed := access_record.revoked_at IS NULL AND proposal.company_status = 'ACTIVE'
            AND proposal.current_quote_id = proposal.id AND (
                (proposal.status = 'SENT' AND proposal.order_status = 'AGUARDANDO_APROVACAO'
                    AND proposal.current_token_hash = p_hash AND access_record.expires_at > clock_timestamp())
                OR (proposal.status IN ('APPROVED', 'REJECTED') AND access_record.read_until > clock_timestamp())
            );
        INSERT INTO public.audit_log(id, tenant_id, actor_id, action, resource_type, resource_id, details)
            VALUES (gen_random_uuid(), access_record.tenant_id, NULL,
                CASE WHEN coalesce(allowed, false) THEN 'PUBLIC_QUOTE_VIEWED' ELSE 'PUBLIC_QUOTE_ACCESS_DENIED' END,
                'WORK_ORDER', proposal.work_order_id, jsonb_build_object('channel', 'PUBLIC_LINK', 'quoteVersion', proposal.version));
        IF coalesce(allowed, false) THEN
            SELECT jsonb_build_object(
                'companyName', proposal.company_name, 'workOrderNumber', proposal.order_number,
                'equipmentLabel', proposal.equipment_label, 'workOrderStatus', proposal.order_status,
                'readOnly', proposal.status <> 'SENT',
                'quote', jsonb_build_object('version', proposal.version, 'status', proposal.status,
                    'laborSubtotal', proposal.labor_subtotal, 'partsSubtotal', proposal.parts_subtotal,
                    'discountAmount', proposal.discount_amount, 'total', proposal.total,
                    'validUntil', proposal.valid_until, 'warrantyTerms', proposal.warranty_terms,
                    'items', coalesce((SELECT jsonb_agg(jsonb_build_object('type', i.type, 'description', i.description,
                        'quantity', i.quantity, 'unitPrice', i.unit_price) ORDER BY i.position)
                        FROM public.quote_items i WHERE i.tenant_id = access_record.tenant_id AND i.quote_id = access_record.quote_id), '[]'::jsonb))) INTO result;
        END IF;
    END IF;
    PERFORM set_config('app.tenant_id', coalesce(previous_tenant, ''), true);
    RETURN result;
END;
$$;
REVOKE ALL ON FUNCTION public.read_public_quote(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.read_public_quote(text) TO fixflow_runtime;
