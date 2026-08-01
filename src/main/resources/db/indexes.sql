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

-- ── Staff login: EMAIL → USERNAME ───────────────────────────────────────────
-- RETIRES the platform-wide unique email. Requiring one address per account forced every
-- staff member of an agency to hold a personal mailbox; an agency with a single shared
-- info@ could provision exactly one user. Email is now a contact/display field, free to
-- repeat, and the login identity is users.username.
--
-- Something must stay unique or the login lookup is ambiguous, and it must be unique
-- GLOBALLY: the login form takes no organization, so the tenant is a RESULT of the lookup
-- (AuthServiceImpl.userLogin), never an input to it. Under a per-tenant constraint that
-- lookup would match several rows and throw NonUniqueResultException — a pre-auth 500.
--
-- This mirrors PART 2 of V2__lead_code.sql for the ddl-auto=update path. Both must stay in
-- step: Flyway databases get the column from V2, ddl-auto databases get it from Hibernate
-- (User.username is JPA-nullable precisely so that ALTER succeeds on a populated table),
-- and this file supplies the backfill + partial index that neither Hibernate nor JPA can.
--
-- SuperAdmin is untouched — separate table, separate endpoint, still email-keyed.

-- The index is case-SENSITIVE, so uniqueness only means anything if every stored value is
-- already normalized — otherwise Prasad and prasad are two accounts and "one username, one
-- account" is a fiction. All four writers lowercase on the way in via UsernamePolicy
-- (UserServiceImpl, SubAgentServiceImpl, TenantServiceImpl, DevDataSeeder); the UPDATEs
-- below retire rows written before that. Idempotent.
UPDATE users SET email = LOWER(email) WHERE email <> LOWER(email);
UPDATE users SET username = LOWER(username) WHERE username <> LOWER(username);

-- Backfill from the email local-part. Identical logic to V2 — see the long comment there for
-- why the first claimant of a stem keeps it clean and only genuine duplicates get "_<id>".
-- Touches only NULL/empty username, so re-running is a no-op.
WITH candidate AS (
    SELECT id,
           COALESCE(
               NULLIF(left(regexp_replace(lower(split_part(email, '@', 1)), '[^a-z0-9._-]', '', 'g'),
                           60), ''),
               'user'
           ) AS stem
      FROM users
     WHERE username IS NULL OR username = ''
),
ranked AS (
    SELECT c.id,
           c.stem,
           row_number() OVER (PARTITION BY c.stem ORDER BY c.id) AS rn,
           EXISTS (SELECT 1 FROM users x WHERE x.username = c.stem) AS taken
      FROM candidate c
)
UPDATE users u
   SET username = CASE WHEN r.rn = 1 AND NOT r.taken THEN r.stem
                       ELSE r.stem || '_' || u.id END
  FROM ranked r
 WHERE u.id = r.id;

-- ddl-auto=update never drops an existing constraint, so both DROPs are required to retire
-- the email uniqueness on databases created before this change. uq_users_email_active is an
-- index (CREATE UNIQUE INDEX), uq_user_email_tenant was a table constraint — hence the two
-- different verbs. Leaving either in place would silently defeat the whole change: the
-- second user given the shared office address would still be rejected.
DROP INDEX IF EXISTS uq_users_email_active;
ALTER TABLE users DROP CONSTRAINT IF EXISTS uq_user_email_tenant;

-- ⚠ spring.sql.init.continue-on-error=true: if duplicate live usernames exist, the CREATE
-- below FAILS and startup CONTINUES — leaving NO unique constraint on the login identifier.
-- It is not enough to see a clean boot; verify with:
--   SELECT indexname FROM pg_indexes WHERE tablename='users' AND indexname='uq_users_username_active';
-- and find offenders with:
--   SELECT username, count(*) FROM users WHERE deleted_at IS NULL GROUP BY username HAVING count(*) > 1;
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_username_active
        ON users (username)
        WHERE deleted_at IS NULL;

-- Audit trail: backfill activity_logs.username from the acting user. Hibernate adds the (nullable)
-- column itself on this path; only the backfill needs doing here. Rows whose user is gone keep NULL
-- and fall back to ActivityReportMapper's legacy derivation. Idempotent. Mirrors part 8 of V2.
UPDATE activity_logs a
   SET username = u.username
  FROM users u
 WHERE a.user_id = u.id
   AND a.username IS NULL;

-- SuperAdmin MFA / token-version backfill. Hibernate adds these nullable columns on old
-- databases. Null token_version means a pre-MFA row; set it to 1 so older SuperAdmin
-- JWTs with no 'tv' claim (treated as 0) are rejected immediately after deployment.
UPDATE super_admins SET mfa_enabled = FALSE WHERE mfa_enabled IS NULL;
UPDATE super_admins SET token_version = 1 WHERE token_version IS NULL;
UPDATE super_admins SET failed_login_attempts = 0 WHERE failed_login_attempts IS NULL;
UPDATE super_admins SET must_change_password = FALSE WHERE must_change_password IS NULL;
UPDATE super_admins SET created_via_invite = FALSE WHERE created_via_invite IS NULL;

-- NOTE: tenants(organization_code) is intentionally left on its existing absolute
-- UNIQUE constraint. Converting it to a partial index would require dropping a
-- Hibernate-managed constraint on the table backing the protected Create-Organization
-- flow, so that change is deferred to a deliberate, manually-reviewed migration.

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
                'MFA_SETUP','MFA_CHALLENGE','MFA_VERIFY','MFA_VERIFY_FAILED',
                'MFA_ENABLE_FAILED','MFA_ENABLED','MFA_DISABLE_FAILED','MFA_DISABLED',
                'SUPER_ADMIN_MFA_RESET',
                'SUPER_ADMIN_PASSWORD_CHANGE',
                'SUPER_ADMIN_INVITE_CREATE','SUPER_ADMIN_INVITE_ACCEPT',
                'LOGIN_NOTIFICATION','LOGIN_NOTIFICATION_FAILED',
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
-- ── LeadSource enum CHECK constraint refresh ────────────────────────────────
-- Same story as users_role_check above: Hibernate generated leads_lead_source_check from the
-- LeadSource enum when the leads table was first created (the original 9 sources), and
-- ddl-auto=update never alters an existing constraint. The 16 constants added for the lead-source
-- integration framework would be rejected at the DB level on any database whose leads table
-- predates them -- i.e. every real one. Verified present with exactly the original 9 on a live
-- database before this block was written.
-- (DROP-then-ADD on every startup keeps it idempotent.)
--
-- NOTE: this file runs with spring.sql.init.continue-on-error=true, so if the ADD below fails the
-- error is SWALLOWED and the table is left with NO constraint at all -- weaker than before, and
-- silent. SchemaEnumConstraintValidator asserts at boot that this constraint exists and names every
-- LeadSource constant, and refuses to start otherwise. That runner is the only thing that makes a
-- failure here visible; do not delete it.
ALTER TABLE leads DROP CONSTRAINT IF EXISTS leads_lead_source_check;
ALTER TABLE leads ADD CONSTRAINT leads_lead_source_check
        CHECK (lead_source IN (
            'SOCIAL_MEDIA','WEBSITE','GOOGLE_ADS','FACEBOOK','INSTAGRAM','WHATSAPP','REFERRAL',
            'DIRECT_CALL','OTHER',
            'MANUAL','WALK_IN','PHONE_MANUAL','TRAVEL_MARKETPLACE',
            'JUSTDIAL','INDIAMART','TRADEINDIA','SULEKHA',
            'META_ADS','INSTAGRAM_DM','FB_MESSENGER',
            'IVR_CALL',
            'WEBSITE_FORM','WEBLINK_ENQUIRY',
            'SUB_AGENT','REPEAT_CUSTOMER'));

-- ── LeadStage enum CHECK constraint refresh ─────────────────────────────────
-- REOPENED was added to LeadStage with no refresh block. On any database whose leads table predates
-- it, REOPENED is rejected at the DB -- which means a converted booking that gets cancelled cannot
-- flip its lead back, and nobody would see why. Not observed on the dev database (its constraint
-- already lists all 8), but that database post-dates the enum change; an older one will not.
ALTER TABLE leads DROP CONSTRAINT IF EXISTS leads_lead_stage_check;
ALTER TABLE leads ADD CONSTRAINT leads_lead_stage_check
        CHECK (lead_stage IN (
            'NEW_LEAD','CONTACTED','FOLLOW_UP','QUALIFIED','PROPOSAL_SENT','CONVERTED',
            'REOPENED','LOST'));

-- ── LeadOrigin enum CHECK constraint ────────────────────────────────────────
-- New column (Lead.origin). Hibernate generates leads_origin_check itself the first time it adds the
-- column, so this block is belt-and-braces for databases where the column arrives before the enum
-- gains a constant. Backfill first: ddl-auto=update adds the column as NULL on every existing row,
-- and every pre-existing lead was, by definition, created by a human.
UPDATE leads SET origin = 'MANUAL' WHERE origin IS NULL;
ALTER TABLE leads DROP CONSTRAINT IF EXISTS leads_origin_check;
ALTER TABLE leads ADD CONSTRAINT leads_origin_check
        CHECK (origin IN ('MANUAL','INTEGRATION','SYSTEM'));

-- ── Quotation TemplateStyle enum CHECK constraint ───────────────────────────
-- New column (Quotation.templateStyle) — the Lead.origin shape exactly. ddl-auto=update adds the
-- column as NULL on every existing row; every pre-existing quotation is, by definition, the original
-- design. The column itself stays NULLABLE and code treats null as CLASSIC (TemplateStyle.orDefault),
-- so this backfill is about honest data, not about making reads work — reads are already safe.
-- The constraint deliberately allows NULL (a CHECK passes on NULL): rows born between the column
-- arriving and this file running must not be rejected.
UPDATE quotations SET template_style = 'CLASSIC' WHERE template_style IS NULL;
ALTER TABLE quotations DROP CONSTRAINT IF EXISTS quotations_template_style_check;
ALTER TABLE quotations ADD CONSTRAINT quotations_template_style_check
        CHECK (template_style IN ('CLASSIC','MODERN','PREMIUM'));

-- ── quotation_allowed_services: join-table FK index ─────────────────────────
-- @ElementCollection table (the lead's chosen-services snapshot). Hibernate creates the table and its
-- FK but no index on the owning column, and EVERY read of it is "all rows for one quotation" — the
-- collection is loaded on any render of the PDF or the public weblink.
-- Deliberately NO CHECK constraint on `service`: the lead service vocabulary is open by design
-- (QuotationSection ignores what it doesn't know), so pinning it here would turn a harmless unknown
-- id into a failed save.
CREATE INDEX IF NOT EXISTS idx_quotation_allowed_services_quotation
        ON quotation_allowed_services(quotation_id);

-- ── leads.email: DROP NOT NULL ──────────────────────────────────────────────
-- An inbound enquiry may have a phone and nothing else — an IVR call is the obvious case, and
-- several marketplace payloads withhold the email until the lead is claimed. ddl-auto=update ADDS
-- columns but never RELAXES an existing NOT NULL, so this has to be hand-written here.
--
-- Safe against uq_leads_email_tenant_open: Postgres treats NULLs as DISTINCT in a unique index, so
-- any number of email-less open leads coexist without tripping it.
--
-- Idempotent: DROP NOT NULL on an already-nullable column is a no-op, not an error.
ALTER TABLE leads ALTER COLUMN email DROP NOT NULL;

-- ── leads.phone_normalized: WRITE-ONLY for now ──────────────────────────────
-- The canonical shadow key. Dedup compares the RAW phone today (LeadServiceImpl.validateNoDuplicates)
-- and uq_leads_phone_tenant_open indexes the raw column. Real rows carry separators (DevDataSeeder
-- writes "+91 91234 5000" + i, with spaces) while inbound channels deliver bare E.164, so
-- "+919123450001" never string-matches "+91 91234 50001": the append-on-repeat path would fire
-- never, and every repeat caller would silently become a duplicate lead.
--
-- The raw `phone` column is NEVER rewritten in place. PhoneNormalizer's javadoc records why —
-- stronger canonicalisation "belongs with a deliberate one-time data migration, not a passive
-- read-path change" — and Customer.phone / uq_customers_phone_tenant key off the same strings, so a
-- non-atomic rewrite would spawn duplicate customers.
--
-- The BACKFILL IS JAVA-SIDE (LeadPhoneNormalizationBackfill), deliberately not SQL: it must reuse
-- the exact PhoneCanonicalizer.canonical() the write path uses. A SQL reimplementation that
-- disagrees on one edge case IS the migration risk.

-- Non-unique lookup index. Safe to ship immediately — the append path probes by canonical phone.
CREATE INDEX IF NOT EXISTS idx_leads_phone_norm_tenant
        ON leads (tenant_id, phone_normalized) WHERE phone_normalized IS NOT NULL;

-- ── DELIBERATELY COMMENTED OUT — DO NOT UNCOMMENT WITHOUT READING THIS ──────
-- The unique constraint on the canonical phone, and the move of the dedup check onto it, are a
-- LATER phase. Uncommenting early is a foot-gun with a large blast radius:
--
-- Two pre-existing OPEN leads that canonicalise to the same value (e.g. "+91 98765 43210" and
-- "09876543210" — different raw strings, one canonical form) are legal today. The moment dedup
-- reads the canonical column, BOTH become permanently un-editable for a human: every save trips the
-- duplicate check against the other. That is a bigger blast radius than the un-creatable case, and
-- its volume is unknown until the report below is run.
--
-- REQUIRED FIRST — the collision report must come back EMPTY on the real database:
--
--   SELECT phone_normalized, tenant_id, count(*)
--     FROM leads
--    WHERE deleted_at IS NULL
--      AND lead_stage NOT IN ('CONVERTED','LOST')
--      AND phone_normalized IS NOT NULL
--    GROUP BY phone_normalized, tenant_id
--   HAVING count(*) > 1;
--
-- Only once it is clean:
--   1. uncomment the index below,
--   2. move LeadServiceImpl.validateNoDuplicates onto phone_normalized, and
--   3. move checkTrashedForRestore's phone finder IN THE SAME CHANGE — leaving it on the raw phone
--      silently kills the restore affordance for exactly the format-mismatch cases it exists for.
--
-- The machine path reads the canonical column from day one; it has no legacy rows to collide with.
--
-- CREATE UNIQUE INDEX IF NOT EXISTS uq_leads_phone_norm_tenant_open
--         ON leads (phone_normalized, tenant_id)
--         WHERE deleted_at IS NULL AND phone_normalized IS NOT NULL
--           AND lead_stage NOT IN ('CONVERTED', 'LOST');

-- ── leads.lead_code: human-readable reference (LD-YY-NNNN) ──────────────────
-- The lead's DISPLAY identity, issued by LeadCodeGenerator from the per-tenant lead_sequences
-- counter. publicId stays the API/routing key — this exists because the UI had nothing to print
-- but a raw UUID, which is not something a human can read out on a call. Bookings solved this
-- long ago (BKG-YY-NNNN); leads simply never got the same treatment.
--
-- THE BACKFILL AND COUNTER SEED FOR THIS COLUMN ARE NOT HERE — they live in
-- db/migration/V2__lead_code.sql, per DEPLOYMENT.md's rule "keep data backfills in versioned
-- migrations, not in db/indexes.sql". Only the index survives here, because indexes are what this
-- file is for and a dev database (ddl-auto=update, Flyway off) still needs it.
--
-- So on a dev database that predates the column: rows created before it show no code, and that is
-- expected — nothing back-fills them here. New leads are unaffected, because
-- LeadCodeGenerator.createInitial() seeds a missing counter from the tenant's row count rather than
-- from 0, so a generated code can never collide with a code some other path already issued.
CREATE UNIQUE INDEX IF NOT EXISTS uq_leads_code_tenant
        ON leads (lead_code, tenant_id) WHERE lead_code IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_lead_code ON leads (tenant_id, lead_code);

-- ═══════════════════════════════════════════════════════════════════════════
-- LEAD SOURCE INTEGRATION FRAMEWORK
-- ═══════════════════════════════════════════════════════════════════════════

-- ── lead_source_integrations ────────────────────────────────────────────────
-- NOTE: `channel` is deliberately a plain VARCHAR holding LeadSourceChannel.slug() — NOT an
-- @Enumerated column, and there is NO check constraint for it here, on purpose. The registry is the
-- constraint and a strictly stricter one: it rejects an unknown slug at save time AND again at
-- ingest, whereas a CHECK only validates spelling and would happily accept a 'meta_ads' row on a node
-- with no Meta adapter deployed. It also keeps "add a channel" to "write one adapter" rather than
-- "write one adapter and remember to refresh a constraint, or every insert is rejected".

-- The ingest token is THE tenant resolver, so its uniqueness is a security property, not hygiene.
-- Partial (WHERE ... IS NOT NULL) because PROVIDER_ACCOUNT connections have no token: Postgres treats
-- NULLs as distinct in a unique index, but being explicit documents the intent.
CREATE UNIQUE INDEX IF NOT EXISTS uq_lsi_token_hash
        ON lead_source_integrations (ingest_token_hash)
        WHERE ingest_token_hash IS NOT NULL AND deleted_at IS NULL;

-- The PREVIOUS token needs its own unique index for the same reason: during an overlap window it is
-- just as capable of resolving a tenant as the current one.
CREATE UNIQUE INDEX IF NOT EXISTS uq_lsi_token_hash_previous
        ON lead_source_integrations (ingest_token_hash_previous)
        WHERE ingest_token_hash_previous IS NOT NULL AND deleted_at IS NULL;

-- PROVIDER_ACCOUNT resolution: one provider account maps to exactly one connection, or a Meta page
-- delivering a lead would be ambiguous between two tenants — the worst possible ambiguity.
CREATE UNIQUE INDEX IF NOT EXISTS uq_lsi_channel_external_account
        ON lead_source_integrations (channel, external_account_id)
        WHERE external_account_id IS NOT NULL AND deleted_at IS NULL;

-- Deliberately NO unique on (tenant_id, channel): the row is a CONNECTION, not a config. Two JustDial
-- city accounts are two rows. Forcing one-per-channel pushes connections into a child table later —
-- the same mistake at a different grain.

-- The cross-tenant credential-expiry sweep. This partial index is the entire reason
-- credentials_expire_at is a typed column rather than living inside the encrypted blob: the sweep must
-- ask "which connections expire within 7 days" WITHOUT decrypting every row.
CREATE INDEX IF NOT EXISTS idx_lsi_credentials_expire
        ON lead_source_integrations (credentials_expire_at)
        WHERE credentials_expire_at IS NOT NULL AND deleted_at IS NULL;

-- ── lead_attributions ───────────────────────────────────────────────────────
-- lead_id is a LOGICAL FK: there is deliberately NO REFERENCES clause. A real FK would make the
-- nightly trash purge — one transactional loop over every TrashableType calling em.remove() — throw on
-- a 31-day-old trashed inbound lead, rolling back EVERY TrashableType purge for that tenant, forever,
-- because the same row is retried the next night. Consistent with the codebase's cross-aggregate rule
-- (Booking.customerId) and with lead_ingest_events.lead_id.
--
-- The unique index below still enforces 1:1, so the logical FK costs nothing.
CREATE UNIQUE INDEX IF NOT EXISTS uq_lead_attributions_lead
        ON lead_attributions (lead_id);

-- Reporting: "which campaigns produced leads". Partial — most rows have no campaign.
CREATE INDEX IF NOT EXISTS idx_lead_attributions_campaign
        ON lead_attributions (tenant_id, campaign_name) WHERE campaign_name IS NOT NULL;

-- ── lead_ingest_events ──────────────────────────────────────────────────────
-- Content-level idempotency, scoped to (tenant, channel) and deliberately NOT platform-wide.
-- An external event id is provider-controlled and namespaced by an account we do not administer, so a
-- platform-wide unique would let any ONE connection permanently poison a key for every other tenant —
-- silent cross-tenant lead LOSS.
CREATE UNIQUE INDEX IF NOT EXISTS uq_lie_external_event
        ON lead_ingest_events (tenant_id, channel, external_event_id)
        WHERE external_event_id IS NOT NULL;

-- The delivery-history list (newest first, per connection).
CREATE INDEX IF NOT EXISTS idx_lie_tenant_integration_created
        ON lead_ingest_events (tenant_id, integration_id, created_at DESC);

-- The transport-window dedup probe.
CREATE INDEX IF NOT EXISTS idx_lie_dedup_key
        ON lead_ingest_events (tenant_id, channel, dedup_key) WHERE dedup_key IS NOT NULL;

-- LeadIngestStatus CHECK. lead_ingest_events is a NEW table, so Hibernate creates this constraint
-- itself with the current enum values the first time it creates the table; this block is
-- belt-and-braces for any FUTURE value (ddl-auto=update never alters an existing constraint).
-- SchemaEnumConstraintValidator guards it at boot.
ALTER TABLE lead_ingest_events DROP CONSTRAINT IF EXISTS lead_ingest_events_status_check;
ALTER TABLE lead_ingest_events ADD CONSTRAINT lead_ingest_events_status_check
        CHECK (status IN ('RECEIVED','PROCESSED','APPENDED','DUPLICATE','IGNORED','DEFERRED',
                          'QUARANTINED_QUOTA','FAILED'));

-- ── lead_logs: FK gains ON DELETE CASCADE ───────────────────────────────────
-- A PRE-EXISTING hole, widened by this framework and fixed here.
--
-- LeadLog.lead is a real FK (optional=false); Lead has no LeadLog collection and does not cascade to
-- it; TrashableType has LEAD but no LEAD_LOG. So the nightly purge's em.remove() on a 31-day-old
-- trashed lead hits its live lead_logs rows, Postgres throws, and the whole tenant's purge — every
-- type — rolls back and retries the same row forever.
--
-- It survives today only because lead_logs rows exist when a HUMAN wrote one. Under owner decision 1
-- every repeat inbound contact writes a LeadLog, so the blast radius goes from incidental to
-- universal.
--
-- ON DELETE CASCADE is correct here, unlike lead_attributions' logical FK: a log genuinely SHOULD die
-- with its lead (it is a child), whereas an attribution row is queried by reporting and an ingest
-- event is an audit record that must survive.
--
-- Ordering is safe and idempotent: Hibernate runs first and leaves a same-named constraint alone; this
-- then drops and re-adds it with the cascade, every boot.
ALTER TABLE lead_logs DROP CONSTRAINT IF EXISTS fk_lead_log_lead;
ALTER TABLE lead_logs ADD CONSTRAINT fk_lead_log_lead
        FOREIGN KEY (lead_id) REFERENCES leads (id) ON DELETE CASCADE;
