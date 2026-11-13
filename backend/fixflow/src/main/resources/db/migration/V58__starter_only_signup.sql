-- Pending confirmations, including previously issued links, start on Starter.
-- Existing tenant subscriptions are unaffected.
UPDATE public.pending_signups SET selected_plan='STARTER' WHERE selected_plan<>'STARTER';
ALTER TABLE public.pending_signups ADD CONSTRAINT pending_signups_starter_only
    CHECK (selected_plan='STARTER');
