-- One-time production migration for LeadType change:
--   FRESH_LEAD / REPEAT_CUSTOMER / CORPORATE / VIP
--   -> FRESH / WARM / HOT / COLD
--
-- Run after taking a database backup and before relying on new lead writes.

BEGIN;

SELECT lead_type, COUNT(*)
FROM leads
GROUP BY lead_type
ORDER BY lead_type;

UPDATE leads SET lead_type = 'FRESH' WHERE lead_type = 'FRESH_LEAD';
UPDATE leads SET lead_type = 'WARM'  WHERE lead_type = 'REPEAT_CUSTOMER';
UPDATE leads SET lead_type = 'HOT'   WHERE lead_type IN ('CORPORATE', 'VIP');

DO $$
DECLARE
    constraint_name text;
BEGIN
    FOR constraint_name IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
        WHERE nsp.nspname = current_schema()
          AND rel.relname = 'leads'
          AND con.contype = 'c'
          AND pg_get_constraintdef(con.oid) ILIKE '%lead_type%'
    LOOP
        EXECUTE format('ALTER TABLE leads DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END $$;

ALTER TABLE leads
    ADD CONSTRAINT leads_lead_type_check
    CHECK (lead_type IN ('FRESH', 'HOT', 'WARM', 'COLD'));

SELECT lead_type, COUNT(*)
FROM leads
GROUP BY lead_type
ORDER BY lead_type;

COMMIT;
