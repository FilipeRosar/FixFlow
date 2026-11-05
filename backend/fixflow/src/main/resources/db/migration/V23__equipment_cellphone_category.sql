ALTER TABLE public.equipment DROP CONSTRAINT equipment_category_check;
ALTER TABLE public.equipment ADD CONSTRAINT equipment_category_check
    CHECK (category IN ('CELULAR', 'NOTEBOOK', 'DESKTOP', 'IMPRESSORA', 'PERIFERICO', 'OUTRO'));
