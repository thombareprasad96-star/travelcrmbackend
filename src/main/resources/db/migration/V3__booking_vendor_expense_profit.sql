-- bookings.total_vendor_costs — the supplier-side half of the expense ledger, denormalised onto the
-- booking exactly the way total_internal_costs already is, plus the net_profit backfill that follows
-- from it.
--
-- WHY. netProfit was customerAmount − vendorCost − totalInternalCosts, on the assumption that the
-- `Vendor Cost` field on the booking form "already represents everything paid to suppliers". In
-- practice that field is optional and agencies itemise supplier spend through the expense ledger
-- instead, where every row defaults to costType=VENDOR — a class that fed NOTHING. A ₹1,00,000
-- booking with ₹75,940 of ledger spend therefore reported ₹1,00,000 of profit. The owner's decision
-- is that the typed field and the ledger are ADDITIVE, not two spellings of one number:
--
--     netProfit = customer_amount − vendor_cost − total_vendor_costs − total_internal_costs
--
-- MARKETPLACE ROWS ARE EXCLUDED FROM THE NEW COLUMN, and that exclusion is load-bearing rather than
-- tidiness. A hotel-marketplace payable is written as a VENDOR expense row AND is simultaneously
-- folded into bookings.vendor_cost, which BookingServiceImpl defends with a floor check
-- ("Vendor cost ₹X is below the ₹Y payable to the hotel marketplace"). Summing those rows here as
-- well would subtract the same rupees twice — the precise failure this schema is being changed to
-- stop. The predicate `marketplace_booking_public_id IS NULL` keeps the two terms disjoint, and it
-- mirrors the split BookingExpenseRepository.sumMarketplacePayable already relies on.
--
-- SELF-SUFFICIENT AND IDEMPOTENT, like V2: under JPA_DDL_AUTO=validate Hibernate creates nothing, so
-- the column, its backfill and the profit recompute all have to happen here. Every statement is a
-- no-op on a database that already has them.

-- ── 1. Column ───────────────────────────────────────────────────────────────
-- numeric(12,2) NOT NULL DEFAULT 0 mirrors total_internal_costs precisely — @Column(precision = 12,
-- scale = 2, nullable = false) on Booking.totalVendorCosts. A width or nullability mismatch here is
-- exactly what ddl-auto=validate refuses to boot on.
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS total_vendor_costs numeric(12,2) NOT NULL DEFAULT 0;

-- Booking is @Audited: Envers needs the column on the audit twin too, or the SessionFactory refuses
-- to build at all ("Schema-validation: missing column [total_vendor_costs] in table [bookings_aud]")
-- and the application does not boot. No NOT NULL/DEFAULT here — _aud rows are historical snapshots
-- and Envers writes NULL for revisions recorded before the column existed. Same treatment
-- total_internal_costs got in V2 PART 2.
ALTER TABLE bookings_aud ADD COLUMN IF NOT EXISTS total_vendor_costs numeric(12,2);

-- ── 2. Backfill the new column ──────────────────────────────────────────────
-- Soft-deleted expense rows are excluded (deleted_at IS NULL) because the runtime sum excludes them;
-- a backfill that disagreed with the live query would show a figure no later write could reproduce.
UPDATE bookings b
SET total_vendor_costs = COALESCE((
        SELECT SUM(e.amount)
        FROM booking_expenses e
        WHERE e.booking_id = b.id
          AND e.cost_type = 'VENDOR'
          AND e.marketplace_booking_public_id IS NULL
          AND e.deleted_at IS NULL
    ), 0)
WHERE b.total_vendor_costs IS DISTINCT FROM COALESCE((
        SELECT SUM(e.amount)
        FROM booking_expenses e
        WHERE e.booking_id = b.id
          AND e.cost_type = 'VENDOR'
          AND e.marketplace_booking_public_id IS NULL
          AND e.deleted_at IS NULL
    ), 0);

-- ── 3. Recompute net_profit on ACTIVE bookings ──────────────────────────────
-- Only bookings whose profit follows the active formula. A CANCELLED/REFUNDED booking's net_profit is
-- the frozen cancellation margin (retained charge − sunk vendor − sunk internal), written by the
-- cancel flow and deliberately NOT customerAmount-based; recomputing it here would report the margin
-- of a trip that never happened and would overwrite an anti-retroactivity guarantee that the credit
-- note already quotes to the customer. Those rows are corrected by the cancellation engine on its own
-- terms, never by a migration.
--
-- Deleted bookings are skipped for the same reason a soft-deleted expense is: they are not part of
-- any figure the product reports.
UPDATE bookings
SET net_profit = ROUND(
        COALESCE(customer_amount, 0)
      - COALESCE(vendor_cost, 0)
      - COALESCE(total_vendor_costs, 0)
      - COALESCE(total_internal_costs, 0), 2)
WHERE deleted_at IS NULL
  AND status NOT IN ('CANCELLED', 'REFUNDED')
  AND net_profit IS DISTINCT FROM ROUND(
        COALESCE(customer_amount, 0)
      - COALESCE(vendor_cost, 0)
      - COALESCE(total_vendor_costs, 0)
      - COALESCE(total_internal_costs, 0), 2);

-- ── 4. bookings.vendor_public_id / vendor_name ──────────────────────────────
-- Which supplier the typed vendor_cost is owed to. The booking carried a cost with no payee: the
-- Create Booking form asked for a number and nothing else, so "₹20,000 vendor cost" recorded an
-- amount nobody could attribute. The form now gates the amount behind a vendor picked from the
-- master, and this is where that pick lands.
--
-- publicId + name SNAPSHOT, matching customer_public_id + customer_name_snapshot on this same table:
-- the UUID is the durable link, the name is what every list and PDF renders without joining, and it
-- keeps reading correctly if the vendor is later renamed or soft-deleted. Deliberately NOT a real FK
-- — vendors are soft-deleted, and a constraint would either block that or orphan the booking.
--
-- Both NULLABLE: a booking with no supplier chosen is the normal case now that vendor_cost is
-- optional and the expense ledger carries the itemised spend instead.
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS vendor_public_id uuid;
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS vendor_name      varchar(200);

-- Envers twin again — see the note in PART 1. Missing here, the app does not boot.
ALTER TABLE bookings_aud ADD COLUMN IF NOT EXISTS vendor_public_id uuid;
ALTER TABLE bookings_aud ADD COLUMN IF NOT EXISTS vendor_name      varchar(200);

-- Answers "what do I owe this vendor across bookings" without a full scan. Partial on the same
-- predicate every tenant-scoped booking query already carries.
CREATE INDEX IF NOT EXISTS idx_booking_vendor
    ON bookings (tenant_id, vendor_public_id) WHERE deleted_at IS NULL;

-- ── 5. Index note ───────────────────────────────────────────────────────────
-- idx_bkexp_cost_type (booking_id, cost_type, deleted_at) already serves the INTERNAL sum and serves
-- the VENDOR sum equally well; the extra marketplace predicate filters a handful of rows after the
-- index lookup. No new index is warranted — adding one per predicate combination is how a hot write
-- path acquires ten indexes nobody can justify later.


-- ╔══════════════════════════════════════════════════════════════════════════╗
-- ║  PART 6 — Save-as-Template (folded in from a draft V4)                   ║
-- ╚══════════════════════════════════════════════════════════════════════════╝
--
-- This shipped as a separate V4__quotation_template_save_as.sql while it was being written. Neither
-- file had reached production, so the two were merged into this one before the deploy rather than
-- going out as two versions — it is a single release, and one history row is easier to reason about
-- than two that must always be applied together. The V4 file is deleted; do not recreate it, and the
-- next NEW migration is V4 again (the version number was never consumed anywhere but locally).
--
-- Save-as-Template: turn a finished quotation into a reusable package blueprint, and give the
-- matcher two signals it never had — which SERVICES a package covers, and how often it is used.
--
-- WHY THE SERVICES TABLE. The matcher scored destination / duration / hotelTier / budget / season
-- and nothing else, so two Kerala packages — one with flights, one without — were indistinguishable
-- to a lead who asked for flights. They tied on percentage and then tie-broke ALPHABETICALLY. The
-- data to fix that already existed on the quotation side (quotation_allowed_services, snapshotted
-- from the lead) and had no counterpart on the template. This table is that counterpart, and its
-- shape deliberately mirrors quotation_allowed_services exactly (varchar service + sort_order, no
-- enum, no CHECK constraint) so the lead vocabulary stays OPEN — QuotationSection.normalize()
-- silently drops ids it does not recognise rather than rejecting a lead.
--
-- WHY times_applied / last_applied_at. Ranking previously broke exact ties on applicableCount, then
-- name, then id — i.e. on alphabetical order. A package the agency has sold thirty times should win
-- that tie. NOT NULL DEFAULT 0 so every existing row starts neutral and no backfill is needed.
--
-- WHY THE TWO publicId LINKS. quotation_templates.source_quotation_public_id records provenance
-- (and lets "update the template I made this from" find its target); quotations.source_template_public_id
-- closes the chain template -> quotation -> booking, because bookings.source_quotation_public_id
-- already exists. Together they make a real conversion-rate tie-breaker computable later without a
-- second migration. UUID columns rather than bigint FKs, matching how every other cross-aggregate
-- reference in this codebase is modelled (Booking.leadId, QuotationTemplateItinerary.cityId): the
-- reference is logical and validated in the service, so a soft-deleted target degrades to "no link"
-- instead of blocking the delete.
--
-- Every change here is additive and nullable-or-defaulted, so it is safe against a populated
-- database and needs no backfill. No @Enumerated column is introduced, so there is nothing to add to
-- SchemaEnumConstraintValidator.GUARDED.

-- ── 6a. quotation_templates: provenance + usage counters ────────────────────
ALTER TABLE quotation_templates ADD COLUMN IF NOT EXISTS source_quotation_public_id uuid;
ALTER TABLE quotation_templates ADD COLUMN IF NOT EXISTS times_applied integer NOT NULL DEFAULT 0;
ALTER TABLE quotation_templates ADD COLUMN IF NOT EXISTS last_applied_at timestamp(6);

-- ── 6b. quotation_template_services: the sixth scoring dimension ────────────
-- Column shape copied from quotation_allowed_services (V1 line 98) so the two sides of the
-- comparison are stored identically.
CREATE TABLE IF NOT EXISTS quotation_template_services (
    template_id bigint  NOT NULL,
    service     varchar(50),
    sort_order  integer NOT NULL,
    PRIMARY KEY (template_id, sort_order)
);

-- Drop-then-add on the table this migration just created, so re-running is clean. This is the only
-- DROP in the file and it touches a constraint, never data.
ALTER TABLE IF EXISTS quotation_template_services
    DROP CONSTRAINT IF EXISTS fk_qtpl_service_template;
ALTER TABLE IF EXISTS quotation_template_services
    ADD CONSTRAINT fk_qtpl_service_template
    FOREIGN KEY (template_id) REFERENCES quotation_templates;

-- ── 6c. quotations: which template this quotation came from ─────────────────
ALTER TABLE quotations ADD COLUMN IF NOT EXISTS source_template_public_id uuid;

-- ── 6d. Indexes ─────────────────────────────────────────────────────────────
-- Both support the same question asked from either end ("what came from this?"), which is how the
-- conversion rate will be computed. idx_qtpl_applied backs the cold-start fallback, which orders
-- active templates by popularity when nothing clears the match threshold.
CREATE INDEX IF NOT EXISTS idx_qtpl_src_quotation
    ON quotation_templates (source_quotation_public_id);
CREATE INDEX IF NOT EXISTS idx_qtpl_applied
    ON quotation_templates (tenant_id, times_applied);
CREATE INDEX IF NOT EXISTS idx_quotation_src_template
    ON quotations (source_template_public_id);


-- ╔══════════════════════════════════════════════════════════════════════════╗
-- ║  VERIFY AFTER DEPLOY                                                     ║
-- ╚══════════════════════════════════════════════════════════════════════════╝
--   SELECT booking_code, customer_amount, vendor_cost, total_vendor_costs,
--          total_internal_costs, net_profit
--   FROM bookings
--   WHERE deleted_at IS NULL AND total_vendor_costs > 0
--   ORDER BY id DESC LIMIT 20;
--
-- net_profit must equal customer_amount − vendor_cost − total_vendor_costs − total_internal_costs on
-- every row returned. If any row disagrees, BookingProfitService did not run for it — recompute by
-- touching one expense on that booking, not by hand-editing the column.
