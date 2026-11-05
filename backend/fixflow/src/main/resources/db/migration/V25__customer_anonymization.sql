ALTER TABLE public.customers ADD COLUMN anonymized_at timestamptz;

CREATE FUNCTION public.guard_customer_anonymization() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF OLD.anonymized_at IS NOT NULL AND
       (NEW.name IS DISTINCT FROM OLD.name OR NEW.document IS DISTINCT FROM OLD.document
        OR NEW.anonymized_at IS DISTINCT FROM OLD.anonymized_at OR NEW.deleted_at IS NULL) THEN
        RAISE EXCEPTION 'CUSTOMER_ALREADY_ANONYMIZED' USING ERRCODE = '23514';
    END IF;
    IF OLD.anonymized_at IS NULL AND NEW.anonymized_at IS NOT NULL THEN
        IF EXISTS (SELECT 1 FROM public.work_orders WHERE tenant_id = NEW.tenant_id AND customer_id = NEW.id
                   AND status NOT IN ('ENTREGUE', 'CANCELADA')) THEN
            RAISE EXCEPTION 'CUSTOMER_HAS_OPEN_ORDERS' USING ERRCODE = '23514';
        END IF;
        IF NEW.name <> 'Cliente anonimizado' OR NEW.document IS NOT NULL OR NEW.deleted_at IS NULL
           OR EXISTS (SELECT 1 FROM public.customer_contacts WHERE tenant_id = NEW.tenant_id AND customer_id = NEW.id)
           OR EXISTS (SELECT 1 FROM public.equipment WHERE tenant_id = NEW.tenant_id AND customer_id = NEW.id
                      AND (brand <> '' OR model <> 'Equipamento anonimizado' OR serial_number <> '' OR description <> '' OR deleted_at IS NULL)) THEN
            RAISE EXCEPTION 'CUSTOMER_ANONYMIZATION_INCOMPLETE' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER customer_anonymization_guard BEFORE UPDATE ON public.customers
    FOR EACH ROW EXECUTE FUNCTION public.guard_customer_anonymization();

CREATE FUNCTION public.prevent_anonymized_customer_details() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM 1 FROM public.customers WHERE tenant_id = NEW.tenant_id AND id = NEW.customer_id
        AND anonymized_at IS NULL FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'CUSTOMER_UNAVAILABLE' USING ERRCODE = '23514'; END IF;
    IF TG_OP = 'UPDATE' AND OLD.customer_id IS DISTINCT FROM NEW.customer_id THEN
        PERFORM 1 FROM public.customers WHERE tenant_id = OLD.tenant_id AND id = OLD.customer_id
            AND anonymized_at IS NULL FOR UPDATE;
        IF NOT FOUND THEN RAISE EXCEPTION 'CUSTOMER_ALREADY_ANONYMIZED' USING ERRCODE = '23514'; END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER contacts_anonymization_guard BEFORE INSERT OR UPDATE ON public.customer_contacts
    FOR EACH ROW EXECUTE FUNCTION public.prevent_anonymized_customer_details();
CREATE TRIGGER equipment_anonymization_guard BEFORE INSERT OR UPDATE ON public.equipment
    FOR EACH ROW EXECUTE FUNCTION public.prevent_anonymized_customer_details();
REVOKE ALL ON FUNCTION public.guard_customer_anonymization(), public.prevent_anonymized_customer_details() FROM PUBLIC;
