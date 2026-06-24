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
        CHECK (role IN ('SUPERADMIN','TENANT_ADMIN','MANAGER','TRAVEL_AGENT','STAFF','ACCOUNTANT'));