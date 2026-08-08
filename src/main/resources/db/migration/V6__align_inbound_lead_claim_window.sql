-- Keep the database index aligned with the application's claim-window definition.
-- Manual leads already have an explicitly chosen owner; only machine-created INTEGRATION/SYSTEM
-- leads belong in the tenant-wide incoming feed and first-response SLA queue.
--
-- V2 created this index without the origin predicate. V2 may already be recorded in production, so
-- changing it would cause a Flyway checksum failure. Replace the index in this forward migration.

DROP INDEX IF EXISTS idx_leads_open_to_claim;

CREATE INDEX idx_leads_open_to_claim
        ON leads (tenant_id, created_at DESC)
        WHERE deleted_at IS NULL
          AND origin IN ('INTEGRATION', 'SYSTEM')
          AND first_contacted_at IS NULL
          AND lead_stage NOT IN ('CONVERTED', 'LOST');
