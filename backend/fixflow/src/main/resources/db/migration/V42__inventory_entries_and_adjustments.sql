ALTER TABLE public.inventory_movements ADD COLUMN reason varchar(500);
DO $$
DECLARE constraint_name text;
BEGIN
    FOR constraint_name IN SELECT conname FROM pg_constraint
        WHERE conrelid = 'public.inventory_movements'::regclass AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%type%'
    LOOP
        EXECUTE format('ALTER TABLE public.inventory_movements DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END $$;
ALTER TABLE public.inventory_movements ADD CONSTRAINT inventory_movement_kind CHECK (
    (type = 'EXIT' AND quantity < 0 AND item_id IS NOT NULL AND work_order_id IS NOT NULL)
    OR (type = 'RETURN' AND quantity > 0 AND item_id IS NOT NULL AND work_order_id IS NOT NULL)
    OR (type = 'ENTRY' AND quantity > 0 AND item_id IS NULL AND work_order_id IS NULL)
    OR (type = 'ADJUSTMENT' AND quantity <> 0 AND item_id IS NULL AND work_order_id IS NULL
        AND reason IS NOT NULL AND btrim(reason) <> '')
);
-- Ledger remains append-only for the application role.
REVOKE UPDATE, DELETE, TRUNCATE ON public.inventory_movements FROM fixflow_runtime;
