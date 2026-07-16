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
                'SUBAGENT_LICENSE_CREATE','SUBAGENT_LICENSE_APPROVE','SUBAGENT_LICENSE_REJECT','SUBAGENT_LICENSE_CANCEL',
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

-- ── SubAgentStatus enum CHECK constraint refresh ─────────────────────────────
-- Hibernate generated sub_agent_profiles_status_check from SubAgentStatus (originally ACTIVE/SUSPENDED)
-- when the table was first created; ddl-auto=update never alters it, so the later PENDING_LICENSE value
-- (over-cap sub-agent awaiting a seat purchase) would be rejected at the DB level — breaking sub-agent
-- creation when a tenant is over its cap. Drop + recreate with the full current set.
ALTER TABLE sub_agent_profiles DROP CONSTRAINT IF EXISTS sub_agent_profiles_status_check;
ALTER TABLE sub_agent_profiles ADD CONSTRAINT sub_agent_profiles_status_check
        CHECK (status IN ('PENDING_LICENSE','ACTIVE','SUSPENDED'));

-- ── SubAgentLicenseRequest enum CHECK constraint refresh ─────────────────────
-- sub_agent_license_requests is a NEW table, so Hibernate creates its *_check constraints with the
-- current enum values at first create and inserts work immediately. These drop+recreate blocks are
-- belt-and-suspenders for any FUTURE value added to the status / PaymentMode / OfflinePaymentMode enums
-- (ddl-auto=update never alters an existing constraint — same gotcha as above).
ALTER TABLE sub_agent_license_requests DROP CONSTRAINT IF EXISTS sub_agent_license_requests_status_check;
ALTER TABLE sub_agent_license_requests ADD CONSTRAINT sub_agent_license_requests_status_check
        CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED'));

ALTER TABLE sub_agent_license_requests DROP CONSTRAINT IF EXISTS sub_agent_license_requests_payment_mode_check;
ALTER TABLE sub_agent_license_requests ADD CONSTRAINT sub_agent_license_requests_payment_mode_check
        CHECK (payment_mode IN ('ONLINE','OFFLINE'));

ALTER TABLE sub_agent_license_requests DROP CONSTRAINT IF EXISTS sub_agent_license_requests_offline_mode_check;
ALTER TABLE sub_agent_license_requests ADD CONSTRAINT sub_agent_license_requests_offline_mode_check
        CHECK (offline_mode IS NULL OR offline_mode IN ('BANK_TRANSFER','CHEQUE','CASH'));

-- ── Task enum CHECK constraint refresh ───────────────────────────────────────
-- tasks is a NEW table, so Hibernate creates its *_check constraints with the current enum values at
-- first create and inserts work immediately. These drop+recreate blocks are belt-and-suspenders for
-- any FUTURE value added to TaskStatus / TaskPriority / TaskCategory (ddl-auto=update never alters an
-- existing constraint — same gotcha as billing_records_status_check above). Update the value lists
-- here whenever a new enum constant is added, or inserts with it fail at the DB.
ALTER TABLE tasks DROP CONSTRAINT IF EXISTS tasks_status_check;
ALTER TABLE tasks ADD CONSTRAINT tasks_status_check
        CHECK (status IN ('TODO','IN_PROGRESS','DONE','CANCELLED'));

ALTER TABLE tasks DROP CONSTRAINT IF EXISTS tasks_priority_check;
ALTER TABLE tasks ADD CONSTRAINT tasks_priority_check
        CHECK (priority IN ('LOW','MEDIUM','HIGH','URGENT'));

ALTER TABLE tasks DROP CONSTRAINT IF EXISTS tasks_category_check;
ALTER TABLE tasks ADD CONSTRAINT tasks_category_check
        CHECK (category IN ('GENERAL','FOLLOW_UP','CALL','MEETING','PAYMENT','DOCUMENT','VISA','TRAVEL','OTHER'));

-- The calendar task-range feed + overdue/due-today counters filter on COALESCE(start_at, due_date)
-- (the "calendar anchor"); a plain single-column index can't serve that expression, so add a partial
-- expression index over live rows. Additive + idempotent.
CREATE INDEX IF NOT EXISTS idx_task_anchor
        ON tasks (tenant_id, COALESCE(start_at, due_date)) WHERE deleted_at IS NULL;

-- ============================================================================
-- ACCOUNTING / GST DEPTH MODULE (com.crm.travelcrm.accounting)
-- ============================================================================

-- HSN/SAC rate master: one live row per (tenant, code). Soft-delete-aware partial unique index so a
-- code can be re-added after the original row is soft-deleted.
CREATE UNIQUE INDEX IF NOT EXISTS uq_hsn_sac_code_tenant
        ON hsn_sac_rates (code, tenant_id) WHERE deleted_at IS NULL;

-- At most ONE default rate per tenant (the fallback applied to invoice lines without a code).
CREATE UNIQUE INDEX IF NOT EXISTS uq_hsn_sac_default_tenant
        ON hsn_sac_rates (tenant_id) WHERE is_default = true AND deleted_at IS NULL;

-- accounting_settings.gst_scheme CHECK refresh. All accounting tables are NEW, so Hibernate creates
-- their *_check constraints with the current enum values at first create and inserts work immediately.
-- These drop+recreate blocks are belt-and-suspenders for any FUTURE enum value (ddl-auto=update never
-- alters an existing constraint — the recurring gotcha across this file). Update the value lists here
-- whenever a new enum constant is added, or inserts with it fail at the DB.
ALTER TABLE accounting_settings DROP CONSTRAINT IF EXISTS accounting_settings_gst_scheme_check;
ALTER TABLE accounting_settings ADD CONSTRAINT accounting_settings_gst_scheme_check
        CHECK (gst_scheme IN ('REGULAR','COMPOSITION','UNREGISTERED'));

-- tax_invoices enum CHECK refreshes (invoice_type / status / supply_type).
ALTER TABLE tax_invoices DROP CONSTRAINT IF EXISTS tax_invoices_invoice_type_check;
ALTER TABLE tax_invoices ADD CONSTRAINT tax_invoices_invoice_type_check
        CHECK (invoice_type IN ('TAX_INVOICE','BILL_OF_SUPPLY','SIMPLE_INVOICE'));
ALTER TABLE tax_invoices DROP CONSTRAINT IF EXISTS tax_invoices_status_check;
ALTER TABLE tax_invoices ADD CONSTRAINT tax_invoices_status_check
        CHECK (status IN ('ISSUED','CANCELLED'));
ALTER TABLE tax_invoices DROP CONSTRAINT IF EXISTS tax_invoices_supply_type_check;
ALTER TABLE tax_invoices ADD CONSTRAINT tax_invoices_supply_type_check
        CHECK (supply_type IS NULL OR supply_type IN ('INTRA_STATE','INTER_STATE'));

-- vendor_bills enum CHECK refreshes (tds_section nullable / status).
ALTER TABLE vendor_bills DROP CONSTRAINT IF EXISTS vendor_bills_tds_section_check;
ALTER TABLE vendor_bills ADD CONSTRAINT vendor_bills_tds_section_check
        CHECK (tds_section IS NULL OR tds_section IN ('SEC_194C','SEC_194H','SEC_194J'));
ALTER TABLE vendor_bills DROP CONSTRAINT IF EXISTS vendor_bills_status_check;
ALTER TABLE vendor_bills ADD CONSTRAINT vendor_bills_status_check
        CHECK (status IN ('UNPAID','PARTIALLY_PAID','PAID','CANCELLED'));

-- vendor_payments idempotency: a resubmit with the same key collapses onto the original disbursement.
-- Partial unique index (only where the key is set), scoped per tenant + bill.
CREATE UNIQUE INDEX IF NOT EXISTS uq_vendor_payment_idem
        ON vendor_payments (tenant_id, vendor_bill_id, idempotency_key)
        WHERE idempotency_key IS NOT NULL;

-- vendor_bills.row_version (@Version) — legacy NULL backfill so the first optimistic update won't choke.
UPDATE vendor_bills SET row_version = 0 WHERE row_version IS NULL;

-- ── Calendar hot path: booking service-line date lookups ─────────────────────
-- The unified calendar derives flight/hotel/visa events from booking_service_items filtered by
-- (tenant_id, service_date) — a column with no existing index. Additive + idempotent.
CREATE INDEX IF NOT EXISTS idx_bksvc_service_date ON booking_service_items(tenant_id, service_date);

-- ============================================================================
-- MARKETING & CAMPAIGNS (com.crm.travelcrm.marketing)
-- All marketing_* tables are NEW, so Hibernate creates their *_check constraints with the current
-- enum values at first create and inserts work immediately. These refreshes exist so that when a
-- future enum value is added, the check list here is the single place to update (ddl-auto=update
-- never alters an existing constraint — same gotcha as billing_records_status_check above).
-- ============================================================================

-- ── Enum CHECK constraint refreshes ──────────────────────────────────────────
ALTER TABLE marketing_segments DROP CONSTRAINT IF EXISTS marketing_segments_match_type_check;
ALTER TABLE marketing_segments ADD CONSTRAINT marketing_segments_match_type_check
        CHECK (match_type IN ('ALL','ANY'));

ALTER TABLE marketing_campaigns DROP CONSTRAINT IF EXISTS marketing_campaigns_channel_check;
ALTER TABLE marketing_campaigns ADD CONSTRAINT marketing_campaigns_channel_check
        CHECK (channel IN ('WHATSAPP','EMAIL'));
ALTER TABLE marketing_campaigns DROP CONSTRAINT IF EXISTS marketing_campaigns_status_check;
ALTER TABLE marketing_campaigns ADD CONSTRAINT marketing_campaigns_status_check
        CHECK (status IN ('DRAFT','SCHEDULED','SENDING','SENT','FAILED','CANCELLED'));
ALTER TABLE marketing_campaigns DROP CONSTRAINT IF EXISTS marketing_campaigns_audience_type_check;
ALTER TABLE marketing_campaigns ADD CONSTRAINT marketing_campaigns_audience_type_check
        CHECK (audience_type IN ('SEGMENT','ALL_CUSTOMERS'));

ALTER TABLE marketing_campaign_recipients DROP CONSTRAINT IF EXISTS marketing_campaign_recipients_channel_check;
ALTER TABLE marketing_campaign_recipients ADD CONSTRAINT marketing_campaign_recipients_channel_check
        CHECK (channel IN ('WHATSAPP','EMAIL'));
ALTER TABLE marketing_campaign_recipients DROP CONSTRAINT IF EXISTS marketing_campaign_recipients_status_check;
ALTER TABLE marketing_campaign_recipients ADD CONSTRAINT marketing_campaign_recipients_status_check
        CHECK (status IN ('PENDING','SENT','FAILED','SKIPPED'));

ALTER TABLE marketing_drip_sequences DROP CONSTRAINT IF EXISTS marketing_drip_sequences_status_check;
ALTER TABLE marketing_drip_sequences ADD CONSTRAINT marketing_drip_sequences_status_check
        CHECK (status IN ('DRAFT','ACTIVE','PAUSED'));
ALTER TABLE marketing_drip_sequences DROP CONSTRAINT IF EXISTS marketing_drip_sequences_audience_type_check;
ALTER TABLE marketing_drip_sequences ADD CONSTRAINT marketing_drip_sequences_audience_type_check
        CHECK (audience_type IN ('SEGMENT','MANUAL'));

ALTER TABLE marketing_drip_steps DROP CONSTRAINT IF EXISTS marketing_drip_steps_channel_check;
ALTER TABLE marketing_drip_steps ADD CONSTRAINT marketing_drip_steps_channel_check
        CHECK (channel IN ('WHATSAPP','EMAIL'));

ALTER TABLE marketing_drip_enrollments DROP CONSTRAINT IF EXISTS marketing_drip_enrollments_status_check;
ALTER TABLE marketing_drip_enrollments ADD CONSTRAINT marketing_drip_enrollments_status_check
        CHECK (status IN ('ACTIVE','COMPLETED','CANCELLED'));

ALTER TABLE marketing_automation_triggers DROP CONSTRAINT IF EXISTS marketing_automation_triggers_trigger_type_check;
ALTER TABLE marketing_automation_triggers ADD CONSTRAINT marketing_automation_triggers_trigger_type_check
        CHECK (trigger_type IN ('BIRTHDAY','ANNIVERSARY'));
ALTER TABLE marketing_automation_triggers DROP CONSTRAINT IF EXISTS marketing_automation_triggers_channel_check;
ALTER TABLE marketing_automation_triggers ADD CONSTRAINT marketing_automation_triggers_channel_check
        CHECK (channel IN ('WHATSAPP','EMAIL'));

-- ── Uniqueness (soft-delete-aware where the table soft-deletes) ──────────────
-- Exactly one automation config row per (tenant, trigger_type). These rows are never soft-deleted
-- (they are config, provisioned once and toggled), so an absolute unique index is correct.
CREATE UNIQUE INDEX IF NOT EXISTS uq_mkt_auto_tenant_type
        ON marketing_automation_triggers (tenant_id, trigger_type);

-- A customer is enrolled in a given sequence at most once while that enrollment is live. The service
-- already guards with existsBySequenceIdAndTenantIdAndCustomerId; this index is the hard backstop
-- against a race double-enrolling. Partial on live rows so a re-enroll after soft-delete is allowed.
CREATE UNIQUE INDEX IF NOT EXISTS uq_mkt_enroll_seq_customer
        ON marketing_drip_enrollments (tenant_id, sequence_id, customer_id)
        WHERE deleted_at IS NULL;