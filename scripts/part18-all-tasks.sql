-- Standalone extract of V2 PART 18 (docs/ALL_TASKS_MODULE_DESIGN.md §D3).
-- Apply by hand BEFORE the Flyway re-stamp:
--   psql -v ON_ERROR_STOP=1 -U postgres -d travel_crm -f scripts/part18-all-tasks.sql
-- then:  DELETE FROM flyway_schema_history WHERE version = '2';   and boot.
-- Every statement is idempotent, so re-running is safe.

-- ║  PART 18 — All Tasks: booking/guest link, creator snapshot, alert mark    ║
-- ╚══════════════════════════════════════════════════════════════════════════╝
--
-- Backs the Sembark-style "All Tasks" grid (docs/ALL_TASKS_MODULE_DESIGN.md). The `tasks` table
-- itself ships in V1__baseline_schema.sql; this part only ADDS columns, so it is safe to re-run.
--
-- Why these columns exist at all: `tasks` had exactly one cross-aggregate link — to a lead. The
-- grid needs three more facts (which trip, which guest, where the trip came from) and a creator
-- display name, none of which were reachable. `BaseEntity.created_by` is a login-username string,
-- not a joinable user reference, so "Created By" needs its own snapshot beside owner_user_id.
--
-- All four reference columns are DENORMALISED SNAPSHOTS, not joins. Two reasons, and the second is
-- the load-bearing one: (1) a 50-row grid would otherwise cost 50 booking lookups, and (2) a task
-- must stay readable after the booking or lead it points at is soft-deleted — "Trip#79799 · Mr.
-- Piyush Patel" is the historical fact the row is about, and a join would blank it.
--
-- ddl-auto is `validate` on every profile, so every field added to Task.java must appear here or
-- the next boot fails with `Schema-validation: missing column`. That is the intended failure.
ALTER TABLE tasks
    ADD COLUMN IF NOT EXISTS booking_id_ref          bigint,
    ADD COLUMN IF NOT EXISTS booking_public_id       uuid,
    ADD COLUMN IF NOT EXISTS booking_code            varchar(20),
    ADD COLUMN IF NOT EXISTS customer_name_snapshot  varchar(255),
    ADD COLUMN IF NOT EXISTS trip_source             varchar(50),
    ADD COLUMN IF NOT EXISTS owner_name              varchar(150),
    ADD COLUMN IF NOT EXISTS overdue_notified_at     timestamp(6) with time zone;

-- ── 18.1  Indexes ──────────────────────────────────────────────────────────
-- Partial (deleted_at IS NULL) to match every query the module issues; the existing
-- idx_task_* indexes in V1 are unpartitioned and stay as they are.
CREATE INDEX IF NOT EXISTS idx_task_booking
    ON tasks (booking_id_ref) WHERE deleted_at IS NULL;

-- The All Tasks list always narrows on (tenant, assignee, due window) together. idx_task_assignee
-- alone leaves the Today/Yesterday/Overdue tabs scanning every task the assignee has ever had.
CREATE INDEX IF NOT EXISTS idx_task_tenant_assignee_due
    ON tasks (tenant_id, assign_to_user_id, due_date) WHERE deleted_at IS NULL;

-- The overdue sweeper's poll runs across ALL tenants every minute and must touch only the few rows
-- it can act on: open, past due, not yet alerted. Without this it is a full scan of `tasks` per tick.
CREATE INDEX IF NOT EXISTS idx_task_overdue_sweep
    ON tasks (due_date)
    WHERE deleted_at IS NULL AND overdue_notified_at IS NULL;

-- ── 18.2  notifications.reference_type gains TASK ──────────────────────────
-- V1 pinned this CHECK to five values (LEAD, BOOKING, REMINDER, CUSTOMER, VENDOR). Task notifications
-- have therefore ALWAYS been persisting reference_type = NULL — NotificationReferenceType.fromString
-- ("TASK") returns null rather than throwing — which is why an existing TASK_ASSIGNED bell item
-- cannot be deep-linked. Adding the value here is what makes the overdue alert clickable.
ALTER TABLE notifications DROP CONSTRAINT IF EXISTS notifications_reference_type_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_reference_type_check
    CHECK (reference_type IN ('LEAD','BOOKING','REMINDER','CUSTOMER','VENDOR','TASK'));

-- ── 18.3  Per-tenant opt-in for the noisy overdue channels ─────────────────
-- IN_APP is always on. WhatsApp and email are OFF by default and must be switched on per tenant.
-- This is deliberate: there is no per-USER notification opt-out anywhere in this product yet
-- (comm_notification_prefs is designed but unread by any code), so a tenant-level switch is the
-- only off-ramp an agency has. Shipping WhatsApp alerts on-by-default would give every agent a
-- message they cannot mute.
ALTER TABLE tenant_settings
    ADD COLUMN IF NOT EXISTS task_overdue_alert_whatsapp boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS task_overdue_alert_email    boolean NOT NULL DEFAULT false;


-- ── Verification (run by hand; all counts must be 0) ────────────────────────
--   SELECT count(*) AS missing_cols FROM (
--     SELECT unnest(ARRAY['booking_id_ref','booking_public_id','booking_code',
--                         'customer_name_snapshot','trip_source','owner_name',
--                         'overdue_notified_at']) AS c) w
--   WHERE NOT EXISTS (SELECT 1 FROM information_schema.columns
--                     WHERE table_name = 'tasks' AND column_name = w.c);
--
--   -- The reference_type CHECK must now admit TASK.
--   SELECT count(*) AS task_ref_rejected FROM pg_constraint
--   WHERE conname = 'notifications_reference_type_check'
--     AND pg_get_constraintdef(oid) NOT LIKE '%TASK%';
