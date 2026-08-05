package com.crm.travelcrm.common.config;

import com.crm.travelcrm.lead.enums.LeadOrigin;
import com.crm.travelcrm.customer.enums.CommunicationPreference;
import com.crm.travelcrm.lead.enums.DepartureMode;
import com.crm.travelcrm.lead.enums.LeadSource;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.lead.enums.LeadType;
import com.crm.travelcrm.leadsource.entity.LeadIngestStatus;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Refuses to start when an {@code @Enumerated(STRING)} column's CHECK constraint does not accept
 * every constant its Java enum currently declares.
 *
 * <p><b>Why this exists.</b> {@code spring.sql.init.continue-on-error=true}
 * ({@code application.properties:86}) means a failed statement in {@code db/indexes.sql} is
 * <em>silently swallowed</em>. Every enum refresh block in that file is a {@code DROP CONSTRAINT
 * IF EXISTS} followed by an {@code ADD CONSTRAINT}. The DROP always succeeds. If the ADD then fails —
 * because a pre-existing row violates the new set — the app boots <b>green with no constraint at
 * all</b>: weaker than before the change, logging nothing. {@code UserDetailsServiceImpl:28-30}
 * already documents this exact swallow in this codebase's own words.
 *
 * <p>The failure is invisible by construction. With the constraint gone the database accepts
 * anything, so the obvious acceptance test — insert a row carrying the new enum value and watch it
 * work — <b>passes vacuously</b>. It cannot detect its own failure mode. This runner is the only
 * control that converts that silence into a signal.
 *
 * <p><b>Why an {@link ApplicationRunner} and not a {@code BeanFactoryPostProcessor}.</b>
 * {@link ProductionConfigValidator} is deliberately the latter so it runs <em>before</em> the
 * DataSource exists — which is precisely why this check cannot live there: it has no connection to
 * query, and that class is {@code @Profile("prod")} so it would never run on the pilot or in dev.
 * An {@code ApplicationRunner} runs after {@code spring.jpa.defer-datasource-initialization=true}
 * has executed {@code indexes.sql}, which is exactly the ordering required: check the constraint
 * after the thing that is supposed to create it has run. This class borrows
 * {@code ProductionConfigValidator}'s collect-all-violations-then-hard-stop <em>style</em>, not its
 * bean type.
 *
 * <p><b>Adding a column.</b> One row in {@link #GUARDED}. The enum is read reflectively at runtime,
 * so a constant added to the Java enum is covered the moment it compiles — there is no list here to
 * forget to update. A guarded column with no matching refresh block in {@code db/indexes.sql} will
 * hard-stop the first time the two drift, which is the intent.
 */
@Component
public class SchemaEnumConstraintValidator implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(SchemaEnumConstraintValidator.class);

    /**
     * The enum-backed columns whose CHECK constraint must accept every current constant.
     *
     * <p>Scoped to {@code leads} deliberately: these two are the ones the lead-source integration
     * work grows, and {@code REOPENED} was added to {@link LeadStage} with no refresh block, so
     * {@code leads_lead_stage_check} may already be stale on any database whose {@code leads} table
     * predates it. The other ~14 tables in {@code indexes.sql} are not guarded yet — extending this
     * list is the cheap way to find out whether they are wrong too, but do it as its own change so a
     * pre-existing breakage elsewhere does not land as "the lead work broke the boot".
     */
    private static final List<GuardedColumn> GUARDED = List.of(
            new GuardedColumn("platform_audit_logs", "action", "platform_audit_logs_action_check",
                    PlatformAuditAction.class),
            new GuardedColumn("leads", "lead_source", "leads_lead_source_check", LeadSource.class),
            new GuardedColumn("leads", "lead_stage", "leads_lead_stage_check", LeadStage.class),
            new GuardedColumn("leads", "origin", "leads_origin_check", LeadOrigin.class),
            // lead_type is the highest-risk entry in this list: its constants were REPLACED, not
            // extended (FRESH_LEAD/REPEAT_CUSTOMER/CORPORATE/VIP -> FRESH/HOT/WARM/COLD), so a
            // database that missed V3 rejects every single lead write rather than just the new
            // values. Guarded so that failure surfaces at boot instead of at the first save.
            new GuardedColumn("leads", "lead_type", "leads_lead_type_check", LeadType.class),
            new GuardedColumn("leads", "departure_mode", "leads_departure_mode_check",
                    DepartureMode.class),
            new GuardedColumn("leads", "preferred_communication",
                    "leads_preferred_communication_check", CommunicationPreference.class),
            new GuardedColumn("booking_trip_snapshot", "departure_mode",
                    "booking_trip_snapshot_departure_mode_check", DepartureMode.class),
            new GuardedColumn("quotations", "template_style", "quotations_template_style_check",
                    com.crm.travelcrm.quotation.enums.TemplateStyle.class),
            new GuardedColumn("lead_ingest_events", "status", "lead_ingest_events_status_check",
                    LeadIngestStatus.class),

            // ── Lead claim window (V2 PART 18) ────────────────────────────────────────────────
            // Guarded from day one rather than retrofitted, on the same reasoning as the comm block
            // below: this column is written on every claim and every contact-stamp, so a CHECK that
            // has fallen behind the Java enum surfaces as a failed claim mid-scramble for a fresh
            // lead — the one moment the feature exists for. At boot it is one legible line instead.
            new GuardedColumn("lead_assignment_events", "event_type",
                    "lead_assignment_events_event_type_check",
                    com.crm.travelcrm.lead.assignment.history.LeadAssignmentEventType.class),

            // ── Communication Center (V2 PART 17) ──────────────────────────────────────────────
            // Guarded from day one rather than retrofitted. comm_messages is the highest-write table
            // in the application and its status/direction values are written by webhook threads, so
            // a CHECK constraint that has fallen behind the Java enum surfaces as an INSERT failure
            // on a provider callback at 2am — a delivery that is then retried and fails again. At
            // boot it is one legible line instead.
            new GuardedColumn("comm_conversations", "channel", "comm_conversations_channel_check",
                    com.crm.travelcrm.communication.enums.CommChannel.class),
            new GuardedColumn("comm_conversations", "status", "comm_conversations_status_check",
                    com.crm.travelcrm.communication.enums.ConversationStatus.class),
            new GuardedColumn("comm_messages", "channel", "comm_messages_channel_check",
                    com.crm.travelcrm.communication.enums.CommChannel.class),
            new GuardedColumn("comm_messages", "direction", "comm_messages_direction_check",
                    com.crm.travelcrm.communication.enums.MessageDirection.class),
            new GuardedColumn("comm_messages", "status", "comm_messages_status_check",
                    com.crm.travelcrm.communication.enums.MessageStatus.class),
            new GuardedColumn("comm_messages", "visibility", "comm_messages_visibility_check",
                    com.crm.travelcrm.communication.enums.MessageVisibility.class),
            new GuardedColumn("comm_calls", "direction", "comm_calls_direction_check",
                    com.crm.travelcrm.communication.enums.CallDirection.class),
            new GuardedColumn("comm_contact_identities", "identity_type",
                    "comm_contact_identities_type_check",
                    com.crm.travelcrm.communication.enums.ContactIdentityType.class));

    private final DataSource dataSource;

    public SchemaEnumConstraintValidator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!isPostgres()) {
            log.info("Schema enum-constraint check skipped: not PostgreSQL (pg_constraint unavailable).");
            return;
        }

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<String> violations = new ArrayList<>();

        for (GuardedColumn g : GUARDED) {
            if (!tableExists(jdbc, g.table())) {
                // First boot against an empty database: Hibernate has not created the table yet on
                // this connection's view, or the module is not deployed. Nothing to guard.
                log.info("Schema enum-constraint check skipped for {}: table does not exist yet.", g.table());
                continue;
            }
            violations.addAll(check(jdbc, g));
        }

        if (!violations.isEmpty()) {
            fail(violations);
        }
        log.info("Schema enum-constraint check passed for {} guarded column(s).", GUARDED.size());
    }

    /**
     * Reads the live constraint definition and asserts it names every current enum constant.
     *
     * <p>The match is a substring test against {@code pg_get_constraintdef}, which renders as
     * {@code CHECK ((lead_source)::text = ANY ((ARRAY['WEBSITE'::character varying, ...])::text[]))}.
     * A substring test is sufficient and deliberately not a parse: the goal is to catch a constant
     * that is <em>absent</em>, and any rendering Postgres chooses still contains the literal.
     * Quoting the needle ({@code 'WEBSITE'}) prevents a shorter constant matching inside a longer
     * one — without the quotes {@code FACEBOOK} would satisfy a check that only lists
     * {@code FACEBOOK_ADS}.
     */
    private List<String> check(JdbcTemplate jdbc, GuardedColumn g) {
        List<String> defs = jdbc.queryForList(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conrelid = ?::regclass AND contype = 'c' AND conname = ?",
                String.class, g.table(), g.constraintName());

        if (defs.isEmpty()) {
            return List.of(String.format(
                    "%s.%s: CHECK constraint '%s' does NOT EXIST. Either db/indexes.sql has no refresh "
                            + "block for it, or its ADD CONSTRAINT failed and continue-on-error swallowed "
                            + "the error. The column currently accepts ANY value.",
                    g.table(), g.column(), g.constraintName()));
        }

        String def = defs.get(0);
        List<String> missing = new ArrayList<>();
        for (Object constant : g.enumClass().getEnumConstants()) {
            String name = ((Enum<?>) constant).name();
            if (!def.contains("'" + name + "'")) {
                missing.add(name);
            }
        }
        if (missing.isEmpty()) {
            return List.of();
        }
        return List.of(String.format(
                "%s.%s: CHECK constraint '%s' rejects %d value(s) the %s enum declares: %s%n"
                        + "         Live constraint: %s%n"
                        + "         Inserting any of those values fails at the DB with a constraint "
                        + "violation. Refresh the DROP/ADD block in db/indexes.sql.",
                g.table(), g.column(), g.constraintName(), missing.size(),
                g.enumClass().getSimpleName(), missing, def));
    }

    private boolean tableExists(JdbcTemplate jdbc, String table) {
        Boolean found = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables "
                        + "WHERE table_schema = current_schema() AND table_name = ?)",
                Boolean.class, table);
        return Boolean.TRUE.equals(found);
    }

    /**
     * H2 and friends have no {@code pg_constraint}; querying it would throw and turn a guard into an
     * outage. Tests and any non-Postgres runtime skip rather than fail.
     */
    private boolean isPostgres() {
        try (Connection c = dataSource.getConnection()) {
            String product = c.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase(Locale.ROOT).contains("postgres");
        } catch (Exception e) {
            log.warn("Schema enum-constraint check skipped: could not read database metadata ({}).",
                    e.toString());
            return false;
        }
    }

    /** All violations together, so fixing this is not a guess-one-fix-one loop. */
    private void fail(List<String> violations) {
        StringBuilder sb = new StringBuilder("\n")
                .append("=".repeat(100)).append("\n")
                .append("REFUSING TO START — enum CHECK constraint(s) out of sync with the Java enums\n")
                .append("=".repeat(100)).append("\n");
        for (String v : violations) {
            sb.append("  • ").append(v).append("\n");
        }
        sb.append("-".repeat(100)).append("\n")
          .append("db/indexes.sql runs on every boot with continue-on-error=true, so a failed\n")
          .append("ADD CONSTRAINT leaves NO constraint and logs nothing. This check exists because\n")
          .append("that failure is otherwise invisible — the column silently accepts anything.\n")
          .append("=".repeat(100));
        log.fatal(sb.toString());
        throw new IllegalStateException(
                "Enum CHECK constraint(s) out of sync with the Java enums — see the log above.");
    }

    /** One guarded {@code @Enumerated(STRING)} column. */
    private record GuardedColumn(String table, String column, String constraintName,
                                 Class<?> enumClass) {}
}
