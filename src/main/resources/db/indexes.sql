-- ============================================================================
-- Tripotomize — supplementary DDL/DML run AFTER Hibernate schema generation.
--
-- Wired via:
--   spring.sql.init.mode=always
--   spring.sql.init.schema-locations=classpath:db/indexes.sql
--   spring.jpa.defer-datasource-initialization=true   (run after Hibernate)
--
-- Every statement is idempotent and safe to run on every startup. This file
-- only contains what JPA annotations CANNOT express:
--   1. Partial-unique indexes (uniqueness among non-soft-deleted rows only).
--   2. A handful of logical-FK indexes not already declared as @Index.
--   3. One-time legacy data normalization (Vendor status → enum names).
-- Plain tenant/status indexes already exist as @Index on the entities.
-- ============================================================================

-- ── Refund ledger: idempotency + legacy entry_type backfill ─────────────────
-- The refund flow writes booking_payments rows with entry_type = 'REFUND' and an optional
-- idempotency_key; a resubmit with the same key must collapse onto the original payout rather than
-- double-pay. A PARTIAL unique index (only where the key is set) enforces that without touching the
-- many receipt rows that carry no key. Scoped per tenant.
CREATE UNIQUE INDEX IF NOT EXISTS uq_bkpay_idem
        ON booking_payments (tenant_id, idempotency_key)
        WHERE idempotency_key IS NOT NULL;

-- entry_type was added to an existing table, so pre-existing rows are NULL. They are all ordinary
-- receipts — backfill them so aggregates/filters that key off the column behave. Idempotent.
UPDATE booking_payments SET entry_type = 'RECEIPT' WHERE entry_type IS NULL;

-- bookings.refunded_amount (the refund counter) was likewise added to an existing table; any legacy
-- NULL must read as 0 so SUM()/comparisons don't NPE or skew. Idempotent.
UPDATE bookings SET refunded_amount = 0 WHERE refunded_amount IS NULL;

-- ── Logical-FK / hot-path indexes not already declared via @Index ───────────
CREATE INDEX IF NOT EXISTS idx_bookings_destination ON bookings(destination_id);
CREATE INDEX IF NOT EXISTS idx_bookings_lead        ON bookings(lead_id);
CREATE INDEX IF NOT EXISTS idx_reminders_owner      ON reminders(owner_user_id);
CREATE INDEX IF NOT EXISTS idx_reminders_lead_ref   ON reminders(lead_id_ref);
CREATE INDEX IF NOT EXISTS idx_reminders_assign_to  ON reminders(assign_to_user_id);
CREATE INDEX IF NOT EXISTS idx_users_manager        ON users(manager_id);
CREATE INDEX IF NOT EXISTS idx_leads_tenant_stage   ON leads(tenant_id, lead_stage);

-- Lead list (GET /api/leads) default sort: WHERE tenant_id=? AND deleted_at IS NULL
-- ORDER BY created_at DESC. Partial index over live rows serves the filter + sort
-- without a filesort.
CREATE INDEX IF NOT EXISTS idx_leads_tenant_created ON leads(tenant_id, created_at DESC) WHERE deleted_at IS NULL;

-- ── Partial unique indexes (soft-delete compatible) ─────────────────────────
-- Uniqueness enforced only across LIVE rows, so a code/email can be reused after
-- the original row is soft-deleted (deleted_at IS NOT NULL).

-- vendors.vendor_code previously had only a plain index — make it unique per tenant.
CREATE UNIQUE INDEX IF NOT EXISTS uq_vendors_code_tenant
        ON vendors (vendor_code, tenant_id) WHERE deleted_at IS NULL;

-- customers.customer_code: the absolute uk_customer_tenant_code constraint (its
-- @UniqueConstraint annotation was removed from the entity) is replaced here by a
-- soft-delete-aware partial unique index.
ALTER TABLE customers DROP CONSTRAINT IF EXISTS uk_customer_tenant_code;
CREATE UNIQUE INDEX IF NOT EXISTS uq_customers_code_tenant
        ON customers (customer_code, tenant_id) WHERE deleted_at IS NULL;

-- customers.phone: the absolute uk_customer_tenant_phone constraint (annotation removed
-- from the entity) is replaced by a soft-delete-aware partial unique index. The absolute
-- form blocked reusing a phone after the owning customer was moved to Trash, which made the
-- lead→booking re-conversion flow (convert → cancel → convert again) fail with a raw
-- constraint violation. Uniqueness now applies only across LIVE customers.
ALTER TABLE customers DROP CONSTRAINT IF EXISTS uk_customer_tenant_phone;
CREATE UNIQUE INDEX IF NOT EXISTS uq_customers_phone_tenant
        ON customers (phone, tenant_id) WHERE deleted_at IS NULL;

-- leads.email / leads.phone: the absolute uk_lead_tenant_email / uk_lead_tenant_phone
-- constraints (annotations removed from the entity) are replaced by soft-delete-aware,
-- OPEN-lead-only partial unique indexes. The absolute form permanently blocked repeat
-- business — a CONVERTED lead is kept for history (never deleted), so its email/phone stayed
-- reserved forever and the same customer could never be entered as a fresh lead. Uniqueness
-- now applies only to OPEN leads (stage NOT IN CONVERTED/LOST), so at most one open lead per
-- contact while new inquiries are allowed once the prior one closes. lead_stage is persisted
-- as the enum NAME (@Enumerated(STRING)), hence 'CONVERTED' / 'LOST'.
ALTER TABLE leads DROP CONSTRAINT IF EXISTS uk_lead_tenant_email;
ALTER TABLE leads DROP CONSTRAINT IF EXISTS uk_lead_tenant_phone;
CREATE UNIQUE INDEX IF NOT EXISTS uq_leads_email_tenant_open
        ON leads (email, tenant_id)
        WHERE deleted_at IS NULL AND lead_stage NOT IN ('CONVERTED', 'LOST');
CREATE UNIQUE INDEX IF NOT EXISTS uq_leads_phone_tenant_open
        ON leads (phone, tenant_id)
        WHERE deleted_at IS NULL AND lead_stage NOT IN ('CONVERTED', 'LOST');

-- NOTE: users(email, tenant_id) and tenants(organization_code) are intentionally
-- left on their existing absolute UNIQUE constraints. Converting them to partial
-- indexes would require dropping Hibernate-managed constraints on the tables that
-- back the protected Create-Organization + Tenant-Admin flow, so that change is
-- deferred to a deliberate, manually-reviewed migration.

-- ── Legacy data normalization: Vendor status / pay_status → enum names ───────
-- Vendor.status & Vendor.payStatus are now @Enumerated(STRING). Existing free-text
-- rows ("Active", "Unpaid", "Partially Paid") must match the enum names or reads
-- would throw. These updates are idempotent (no-op once already uppercased).
UPDATE vendors SET status = UPPER(status)
        WHERE status IS NOT NULL AND status <> UPPER(status);
UPDATE vendors SET pay_status = UPPER(REPLACE(pay_status, ' ', '_'))
        WHERE pay_status IS NOT NULL AND pay_status <> UPPER(REPLACE(pay_status, ' ', '_'));

-- ── Optimistic-lock backfill ────────────────────────────────────────────────
-- Vendor gained an @Version column (row_version). Pre-existing rows have NULL, which
-- Hibernate can choke on at first update — initialize them to 0. Idempotent.
UPDATE vendors SET row_version = 0 WHERE row_version IS NULL;

-- ── Role enum CHECK constraint refresh ──────────────────────────────────────
-- Hibernate generated users_role_check from the Role enum when the table was first
-- created (original 4 roles). ddl-auto=update never alters an existing constraint,
-- so roles added later (STAFF, ACCOUNTANT) get rejected at the DB level — breaking
-- both user creation and the dev seeder. Drop + recreate with the full current set.
-- (DROP-then-ADD on every startup keeps it idempotent.)
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check
        CHECK (role IN ('SUPERADMIN','TENANT_ADMIN','MANAGER','TRAVEL_AGENT','STAFF','ACCOUNTANT','SUB_AGENT'));

-- ── PlatformAuditAction enum CHECK constraint refresh ────────────────────────
-- Same story as users_role_check: Hibernate generated platform_audit_logs_action_check from
-- PlatformAuditAction when the table was first created, and ddl-auto=update never alters an
-- existing constraint. Action values added later (QUOTA_OVERRIDE, USAGE_LIMIT_EXCEEDED) are
-- rejected at the DB level; because the audit recorder writes best-effort, that rejection would
-- otherwise surface as a failed platform operation. Drop + recreate with the full current set.
-- ── TenantStatus enum CHECK constraint refresh ──────────────────────────────
-- Hibernate may have generated tenants_status_check from TenantStatus at first create; ddl-auto=update
-- never alters it, so the later PAST_DUE value (dunning grace) could be rejected at the DB level.
-- Drop + recreate with the full current set (incl. the deprecated legacy INACTIVE so old rows validate).
ALTER TABLE tenants DROP CONSTRAINT IF EXISTS tenants_status_check;
ALTER TABLE tenants ADD CONSTRAINT tenants_status_check
        CHECK (status IN ('ACTIVE','TRIAL','PAST_DUE','SUSPENDED','EXPIRED','INACTIVE'));

-- ── BillingStatus enum CHECK constraint refresh ─────────────────────────────
-- Hibernate generated billing_records_status_check from BillingStatus (UNPAID/PAID/VOID) at first
-- create; ddl-auto=update never alters it, so the later CREDIT value (mid-cycle downgrade credit
-- notes) would be rejected at the DB level. Drop + recreate with the full current set.
ALTER TABLE billing_records DROP CONSTRAINT IF EXISTS billing_records_status_check;
ALTER TABLE billing_records ADD CONSTRAINT billing_records_status_check
        CHECK (status IN ('UNPAID','PAID','VOID','CREDIT'));

ALTER TABLE platform_audit_logs DROP CONSTRAINT IF EXISTS platform_audit_logs_action_check;
ALTER TABLE platform_audit_logs ADD CONSTRAINT platform_audit_logs_action_check
        CHECK (action IN ('LOGIN','LOGIN_FAILED','LOGOUT',
                'TENANT_CREATE','TENANT_UPDATE','TENANT_SUSPEND','TENANT_REACTIVATE',
                'TENANT_SOFT_DELETE','TENANT_RESTORE','TENANT_HARD_DELETE',
                'PLAN_ASSIGN','PLAN_CHANGE','PLAN_UPDATE','SUBSCRIPTION_EXPIRED',
                'BILLING_ISSUE','BILLING_MARK_PAID','BILLING_MARK_UNPAID',
                'BILLING_VOID',
                'PAYMENT_ORDER_CREATED','PAYMENT_CAPTURED','PAYMENT_FAILED',
                'SUBSCRIPTION_ACTIVATED','SUBSCRIPTION_CANCELLED','TENANT_PAST_DUE',
                'UPGRADE_REQUEST_CREATE','UPGRADE_REQUEST_APPROVE','UPGRADE_REQUEST_REJECT','UPGRADE_REQUEST_CANCEL',
                'IMPERSONATION_START','IMPERSONATION_END','USER_FORCE_RESET','USER_LOCK','USER_UNLOCK',
                'FEATURE_FLAG_CHANGE','CONFIG_CHANGE','QUOTA_OVERRIDE','USAGE_LIMIT_EXCEEDED',
                'ANNOUNCEMENT_SEND','MAINTENANCE_TOGGLE','DATA_EXPORT'));

-- ── UpgradeRequest enum CHECK constraint refresh ────────────────────────────
-- upgrade_requests is a NEW table, so Hibernate creates its *_check constraints with the current enum
-- values at first create and inserts work immediately. These drop+recreate blocks are belt-and-suspenders
-- for any FUTURE value added to UpgradeRequestStatus / PaymentMode / OfflinePaymentMode (ddl-auto=update
-- never alters an existing constraint — same gotcha as billing_records_status_check above).
ALTER TABLE upgrade_requests DROP CONSTRAINT IF EXISTS upgrade_requests_status_check;
ALTER TABLE upgrade_requests ADD CONSTRAINT upgrade_requests_status_check
        CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED'));

ALTER TABLE upgrade_requests DROP CONSTRAINT IF EXISTS upgrade_requests_payment_mode_check;
ALTER TABLE upgrade_requests ADD CONSTRAINT upgrade_requests_payment_mode_check
        CHECK (payment_mode IN ('ONLINE','OFFLINE'));

ALTER TABLE upgrade_requests DROP CONSTRAINT IF EXISTS upgrade_requests_offline_mode_check;
ALTER TABLE upgrade_requests ADD CONSTRAINT upgrade_requests_offline_mode_check
        CHECK (offline_mode IS NULL OR offline_mode IN ('BANK_TRANSFER','CHEQUE','CASH'));