package com.crm.travelcrm.lead.ingest;

import com.crm.travelcrm.common.util.PhoneCanonicalizer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Populates {@code leads.phone_normalized} on rows that predate the column, then reports whether the
 * canonical key is safe to make unique.
 *
 * <p><b>Why Java and not SQL.</b> The backfill must derive the key with the <em>exact</em>
 * {@link PhoneCanonicalizer#canonical} the write path uses. A SQL reimplementation that disagrees on
 * one edge case is precisely the migration risk — it would leave rows keyed two different ways with
 * nothing to detect it.
 *
 * <p><b>Why JdbcTemplate and not the repository.</b> This is a cross-tenant maintenance sweep. Going
 * through Hibernate would put it at the mercy of {@code tenantFilter}, which {@code TenantFilterAspect}
 * leaves disabled when {@code TenantContext} is null — the sweep would work today only because that
 * aspect fails OPEN. Depending on a fail-open for correctness is how a filter fix later becomes a
 * silent half-backfill. Raw JDBC says what it means: every tenant, deliberately.
 *
 * <p><b>Idempotent.</b> Only touches rows where {@code phone_normalized IS NULL}. Re-running is
 * harmless, which matters because it runs on every boot. Rows whose phone cannot be canonicalised
 * stay NULL and are retried each boot — cheap, and they are exactly the rows worth re-examining if
 * the canonicaliser is ever taught a new format.
 */
@Component
@Order(20)   // after SchemaEnumConstraintValidator — no point backfilling a schema that is rejected
public class LeadPhoneNormalizationBackfill implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(LeadPhoneNormalizationBackfill.class);

    /** Bounded so a large table cannot hold the whole result set in memory at boot. */
    private static final int BATCH = 500;

    private final DataSource dataSource;
    private final String defaultCountryCode;

    public LeadPhoneNormalizationBackfill(
            DataSource dataSource,
            @Value("${app.lead.default-country-code:+91}") String defaultCountryCode) {
        this.dataSource = dataSource;
        this.defaultCountryCode = defaultCountryCode;
    }

    @Override
    public void run(ApplicationArguments args) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        if (!columnExists(jdbc)) {
            // First boot against a database where Hibernate has not added the column yet.
            log.info("Phone canonicalisation backfill skipped: leads.phone_normalized does not exist yet.");
            return;
        }

        int updated = backfill(jdbc);
        int unresolved = jdbc.queryForObject(
                "SELECT count(*) FROM leads WHERE phone_normalized IS NULL AND deleted_at IS NULL",
                Integer.class);

        if (updated > 0 || unresolved > 0) {
            log.info("Phone canonicalisation backfill: {} row(s) populated, {} live row(s) still "
                    + "un-canonicalisable (phone has no recognisable form — they simply never match "
                    + "on the canonical key).", updated, unresolved);
        }

        reportCollisions(jdbc);
    }

    /**
     * @return how many rows were given a canonical phone
     */
    private int backfill(JdbcTemplate jdbc) {
        int total = 0;
        while (true) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, phone FROM leads WHERE phone_normalized IS NULL AND phone IS NOT NULL "
                            + "ORDER BY id LIMIT " + BATCH + " OFFSET " + total);
            if (rows.isEmpty()) break;

            List<Object[]> batch = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String canonical = PhoneCanonicalizer.canonical((String) row.get("phone"), defaultCountryCode);
                if (canonical != null) {
                    batch.add(new Object[]{canonical, ((Number) row.get("id")).longValue()});
                }
            }
            if (!batch.isEmpty()) {
                jdbc.batchUpdate("UPDATE leads SET phone_normalized = ? WHERE id = ?", batch);
            }

            // OFFSET advances by rows READ, not rows written: a row that canonicalises to null keeps
            // phone_normalized NULL and would otherwise be re-selected forever, looping this method.
            total += rows.size();
            if (rows.size() < BATCH) break;
        }
        return total;
    }

    /**
     * The gate on making the canonical phone unique, reported rather than assumed.
     *
     * <p>Two pre-existing OPEN leads that canonicalise identically ("+91 98765 43210" and
     * "09876543210" are one person, two legal rows today) would BOTH become permanently un-editable
     * the moment dedup moves onto this column — every save would trip the duplicate check against
     * the other. That is why {@code uq_leads_phone_norm_tenant_open} ships commented out in
     * {@code db/indexes.sql}, and this is the number that decides when it can be uncommented.
     *
     * <p>Logged at WARN when non-zero so it is not lost in a boot log: it is a decision input, not
     * an error — nothing is broken today.
     */
    private void reportCollisions(JdbcTemplate jdbc) {
        List<Map<String, Object>> collisions = jdbc.queryForList(
                "SELECT phone_normalized, tenant_id, count(*) AS n FROM leads "
                        + "WHERE deleted_at IS NULL "
                        + "  AND lead_stage NOT IN ('CONVERTED','LOST') "
                        + "  AND phone_normalized IS NOT NULL "
                        + "GROUP BY phone_normalized, tenant_id HAVING count(*) > 1 "
                        + "ORDER BY count(*) DESC LIMIT 20");

        if (collisions.isEmpty()) {
            log.info("Canonical-phone collision report: CLEAN — no two open leads share a canonical "
                    + "phone. uq_leads_phone_norm_tenant_open in db/indexes.sql can be uncommented "
                    + "(together with moving validateNoDuplicates + checkTrashedForRestore onto the "
                    + "canonical column, in the same change).");
            return;
        }

        StringBuilder sb = new StringBuilder(
                "Canonical-phone collision report: " + collisions.size()
                        + " canonical phone(s) are shared by more than one OPEN lead"
                        + (collisions.size() == 20 ? " (showing the first 20)" : "") + ".\n");
        for (Map<String, Object> c : collisions) {
            sb.append("    tenant=").append(c.get("tenant_id"))
              .append("  phone=").append(c.get("phone_normalized"))
              .append("  leads=").append(c.get("n")).append('\n');
        }
        sb.append("  NOTHING IS BROKEN TODAY — dedup still compares the raw phone. But each group "
                + "above would become permanently un-editable if the canonical unique index were "
                + "enabled: every save would trip the duplicate check against its twin. Merge these "
                + "leads before uncommenting uq_leads_phone_norm_tenant_open.");
        log.warn(sb.toString());
    }

    private boolean columnExists(JdbcTemplate jdbc) {
        Boolean found = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = 'leads' "
                        + "AND column_name = 'phone_normalized')",
                Boolean.class);
        return Boolean.TRUE.equals(found);
    }
}
