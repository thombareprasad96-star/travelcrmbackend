package com.crm.travelcrm.lead.claim;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.enums.Role;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.enums.LeadSource;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.lead.enums.LeadStageGroups;
import com.crm.travelcrm.lead.enums.LeadType;
import com.crm.travelcrm.lead.repository.LeadRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the one property of the claim window that no unit test can: <b>two agents clicking Claim at
 * the same instant produce exactly one owner</b>, and the loser is told rather than silently
 * overwritten.
 *
 * <p>That guarantee lives entirely in the WHERE clause of a single SQL statement
 * ({@code LeadRepository.claimLead}). A mocked repository returns whatever it was told to, so
 * {@code LeadClaimServiceTest} can only prove the service reacts correctly to a given row count —
 * it cannot prove the database produces the right counts. This does, against a real Postgres, with
 * two real threads in two real transactions.
 *
 * <p><b>Not run by {@code mvn test}.</b> The {@code IT} suffix matches none of Surefire's default
 * include patterns, so a normal build skips it: it needs a live database and it writes rows. Run it
 * deliberately:
 *
 * <pre>{@code ./mvnw test -Dtest=LeadClaimConcurrencyIT}</pre>
 *
 * <p>It borrows two existing active users from whatever tenant the connected database already has
 * (rather than seeding a tenant, which would need the whole registration flow), creates ONE lead,
 * and deletes it again in {@link #cleanUp()} whatever happens. If the database has no tenant with
 * two active users the test skips rather than failing — an empty dev database is not a defect in
 * this code.
 */
@SpringBootTest
class LeadClaimConcurrencyIT {

    /** How many threads pile onto the same lead. Two is the real scenario; more is free confidence. */
    private static final int CONTENDERS = 8;

    @Autowired private LeadRepository leadRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate txTemplate;
    private Long createdLeadId;
    private Long tenantId;

    @AfterEach
    void cleanUp() {
        if (createdLeadId != null) {
            // Hard delete, not the soft delete the app uses: this row is test litter, and leaving it
            // in Trash would put a fake "Rohit Concurrency" enquiry in front of a real user.
            newTx().execute(status -> {
                leadRepository.findById(createdLeadId).ifPresent(leadRepository::delete);
                return null;
            });
            createdLeadId = null;
        }
        TenantContext.clear();
    }

    @Test
    @DisplayName("N simultaneous claims on one open lead: exactly one wins, the rest get zero rows")
    void simultaneousClaimsProduceExactlyOneWinner() throws Exception {
        List<User> contenders = borrowContenders();
        Assumptions.assumeTrue(contenders.size() >= 2,
                "needs a tenant with at least 2 active, non-deleted users — skipping");

        Lead lead = createOpenLead(contenders.get(0));

        // Every thread submits the SAME expected version, which is the whole point: they all read
        // the lead in the same state, exactly as N browsers showing the same alert toast would.
        int expectedVersion = 0;

        CyclicBarrier startLine = new CyclicBarrier(CONTENDERS);
        ExecutorService pool = Executors.newFixedThreadPool(CONTENDERS);
        try {
            List<Callable<Integer>> attempts = new ArrayList<>();
            for (int i = 0; i < CONTENDERS; i++) {
                User claimer = contenders.get(i % contenders.size());
                attempts.add(() -> {
                    startLine.await(10, TimeUnit.SECONDS);   // release all threads together
                    return newTx().execute(status -> leadRepository.claimLead(
                            lead.getId(), tenantId, claimer, expectedVersion,
                            LeadStageGroups.TERMINAL_STAGES));
                });
            }

            List<Integer> results = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(attempts, 30, TimeUnit.SECONDS)) {
                results.add(f.get());
            }

            Map<Integer, Long> byOutcome = results.stream()
                    .collect(Collectors.groupingBy(r -> r, Collectors.counting()));

            assertThat(byOutcome.getOrDefault(1, 0L))
                    .as("exactly one claim may update a row — %s", byOutcome)
                    .isEqualTo(1L);
            assertThat(byOutcome.getOrDefault(0, 0L))
                    .as("every other claim must match NO row and be told it lost — %s", byOutcome)
                    .isEqualTo(CONTENDERS - 1L);
        } finally {
            pool.shutdownNow();
        }

        Lead after = leadRepository.findById(createdLeadId).orElseThrow();
        assertThat(after.getClaimVersion())
                .as("the counter advanced exactly once, not once per contender")
                .isEqualTo(1);
        assertThat(after.getAssignedUser()).isNotNull();
    }

    @Test
    @DisplayName("a claim against a stale version matches nothing, however open the lead still is")
    void staleVersionNeverWins() {
        List<User> contenders = borrowContenders();
        Assumptions.assumeTrue(contenders.size() >= 2, "needs 2 active users — skipping");

        Lead lead = createOpenLead(contenders.get(0));

        int first = newTx().execute(s -> leadRepository.claimLead(
                lead.getId(), tenantId, contenders.get(1), 0, LeadStageGroups.TERMINAL_STAGES));
        assertThat(first).isEqualTo(1);

        // Version is now 1. A client that still believes it is 0 — a tab left open — must lose,
        // even though the lead is genuinely still open to claim.
        int stale = newTx().execute(s -> leadRepository.claimLead(
                lead.getId(), tenantId, contenders.get(0), 0, LeadStageGroups.TERMINAL_STAGES));
        assertThat(stale).isZero();

        int current = newTx().execute(s -> leadRepository.claimLead(
                lead.getId(), tenantId, contenders.get(0), 1, LeadStageGroups.TERMINAL_STAGES));
        assertThat(current)
                .as("re-submitting with the fresh version is how the loser retries")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the contact stamp is unrepeatable — N simultaneous stamps yield one first-response time")
    void simultaneousContactStampsYieldOneMeasurement() throws Exception {
        List<User> contenders = borrowContenders();
        Assumptions.assumeTrue(contenders.size() >= 2, "needs 2 active users — skipping");

        Lead lead = createOpenLead(contenders.get(0));

        CyclicBarrier startLine = new CyclicBarrier(CONTENDERS);
        ExecutorService pool = Executors.newFixedThreadPool(CONTENDERS);
        try {
            List<Callable<Integer>> attempts = new ArrayList<>();
            for (int i = 0; i < CONTENDERS; i++) {
                User actor = contenders.get(i % contenders.size());
                long claimedSeconds = 60L * (i + 1);   // each thread would record a DIFFERENT time
                attempts.add(() -> {
                    startLine.await(10, TimeUnit.SECONDS);
                    return newTx().execute(status -> leadRepository.markContacted(
                            lead.getId(), tenantId, LocalDateTime.now(), actor.getId(),
                            claimedSeconds, LeadStage.CONTACTED));
                });
            }

            long winners = pool.invokeAll(attempts, 30, TimeUnit.SECONDS).stream()
                    .map(f -> {
                        try {
                            return f.get();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .filter(r -> r == 1)
                    .count();

            assertThat(winners)
                    .as("first_contacted_at IS NULL makes the stamp physically unrepeatable")
                    .isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }

        Lead after = leadRepository.findById(createdLeadId).orElseThrow();
        assertThat(after.getFirstContactedAt()).isNotNull();
        assertThat(after.getFirstResponseSeconds())
                .as("one of the offered measurements won outright — none was blended or overwritten")
                .isIn(60L, 120L, 180L, 240L, 300L, 360L, 420L, 480L);
        assertThat(after.getLeadStage()).isEqualTo(LeadStage.CONTACTED);
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    /**
     * Two or more active users from a single tenant in the connected database. Grouping by tenant
     * matters: claiming across tenants is meant to be impossible, so a pair from different tenants
     * would make the CAS fail for the RIGHT reason and prove nothing about concurrency.
     */
    private List<User> borrowContenders() {
        Map<Long, List<User>> byTenant = userRepository.findAll().stream()
                .filter(u -> u.getTenantId() != null)
                .filter(u -> u.getDeletedAt() == null)
                .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .filter(u -> u.getRole() != Role.SUPERADMIN)
                .collect(Collectors.groupingBy(User::getTenantId));

        List<User> best = byTenant.values().stream()
                .max(Comparator.comparingInt(List::size))
                .orElse(List.of());

        if (best.size() >= 2) {
            this.tenantId = best.get(0).getTenantId();
        }
        return best;
    }

    private Lead createOpenLead(User initialOwner) {
        return newTx().execute(status -> {
            TenantContext.setTenantId(tenantId);
            try {
                Lead lead = new Lead();
                lead.setTenantId(tenantId);
                lead.setLeadCode("LD-IT-" + System.nanoTime() % 100000);
                lead.setCustomerName("Concurrency Fixture");
                // A phone that cannot collide with a real contact under uq_leads_phone_tenant_open.
                lead.setPhone("+0000" + (System.nanoTime() % 1_000_000));
                lead.setLeadSource(LeadSource.OTHER);
                lead.setLeadType(LeadType.values()[0]);
                lead.setLeadStage(LeadStage.NEW_LEAD);
                lead.setAssignedUser(initialOwner);
                lead.setClaimVersion(0);
                lead.setSlaTargetSeconds(300);
                Lead saved = leadRepository.saveAndFlush(lead);
                this.createdLeadId = saved.getId();
                return saved;
            } finally {
                TenantContext.clear();
            }
        });
    }

    /**
     * A fresh REQUIRES_NEW-equivalent transaction per call. Each contending thread must commit
     * independently — sharing one transaction would serialise them in the application and the race
     * being tested would never happen.
     */
    private TransactionTemplate newTx() {
        if (txTemplate == null) {
            txTemplate = new TransactionTemplate(transactionManager);
        }
        return txTemplate;
    }
}
