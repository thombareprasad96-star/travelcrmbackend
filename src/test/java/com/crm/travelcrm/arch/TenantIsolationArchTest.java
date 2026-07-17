package com.crm.travelcrm.arch;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fails the build when tenant isolation is bypassed.
 *
 * <p><b>The invariant.</b> Tenant isolation rests on Hibernate's {@code @Filter("tenantFilter")},
 * declared on {@link BaseTenantEntity}. Hibernate applies that filter only to <i>generated</i>
 * queries — JPQL and Spring Data derived queries. It is <b>not</b> applied to
 * {@code EntityManager.find()}, and therefore not to any of:
 *
 * <ul>
 *   <li>{@code repository.findById(Long)} / {@code findAllById}</li>
 *   <li>{@code repository.getById} / {@code getOne} / {@code getReferenceById}</li>
 *   <li>{@code entityManager.find} / {@code getReference}, {@code session.get} / {@code load}</li>
 * </ul>
 *
 * <p>Each of those loads a row by primary key with no {@code tenant_id} predicate. Reachable from
 * user input, each is a cross-tenant IDOR. The correct form is a tenant-scoped derived query —
 * {@code findByIdAndTenantId}, {@code findByPublicIdAndTenantIdAndDeletedAtIsNull} — which goes
 * through JPQL, so the filter applies. The same reasoning covers {@code softDeleteFilter}: a
 * {@code findById} on master data would silently resurrect trashed rows too.
 *
 * <p><b>Why a test and not a code review.</b> As of writing, production has zero violations — the
 * convention is real and has been followed. But it was enforced only by comments, and the failure
 * mode is silent: {@code findById} compiles, passes every unit test, and returns the right answer
 * on a single-tenant dev database. It misbehaves only in production, with real tenants, as a data
 * leak. This test is what stops the convention from regressing unnoticed.
 *
 * <p><b>Scope.</b> Only repositories typed over a {@link BaseTenantEntity} are checked.
 * Platform-level types ({@code Tenant}, {@code SuperAdmin}, {@code BillingRecord}) extend
 * {@code BaseEntity}, carry no tenant filter, and are legitimately loaded by id — the platform
 * layer is <i>meant</i> to work across tenants.
 *
 * <p><b>If this test fails</b>, do not add your class to {@link #EXEMPT_CLASSES}. Replace the call
 * with a tenant-scoped finder. That list is for the narrow case of an internal-safe lookup that
 * genuinely cannot be expressed as one; each entry states why it cannot leak, and adding one is a
 * decision to be reviewed — not a way to make the build green.
 */
class TenantIsolationArchTest {

    /** Repository methods that load an entity by primary key, bypassing every Hibernate filter. */
    private static final Set<String> BYPASSING_REPO_METHODS = Set.of(
            "findById", "findAllById", "getById", "getOne", "getReferenceById");

    /**
     * Internal-safe exemptions. Each states WHY the call cannot leak: what guarantees the id
     * already came from a tenant-scoped query, or what post-load check catches a mismatch.
     */
    private static final Set<String> EXEMPT_CLASSES = Set.of(
            // Scheduler-driven: campaignId comes from dueCampaignIds(), whose queries carry an
            // explicit tenantId predicate. The call site additionally re-asserts
            // tenantId.equals(campaign.getTenantId()) after the load, before any use.
            "com.crm.travelcrm.marketing.service.CampaignDispatchService",

            // Internal FK traversal: serviceItemId is read off a BookingPayment that was itself
            // loaded via findByPublicIdAndDeletedAtIsNull (filter-covered), and is only ever
            // written from a service line validated against that same booking. The request DTO
            // carries serviceItemPublicId (UUID) — no user-supplied Long reaches this call.
            "com.crm.travelcrm.booking.service.BookingPaymentServiceImpl"
    );

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.crm.travelcrm");
    }

    @Test
    void noPrimaryKeyLookupsOnTenantScopedRepositories() {
        List<String> violations = new ArrayList<>();

        for (JavaClass caller : productionClasses) {
            if (EXEMPT_CLASSES.contains(caller.getFullName())) {
                continue;
            }
            for (JavaMethodCall call : caller.getMethodCallsFromSelf()) {
                if (!BYPASSING_REPO_METHODS.contains(call.getName())) {
                    continue;
                }
                tenantScopedEntityOf(call.getTargetOwner()).ifPresent(entity -> violations.add(
                        String.format("%s.%s(...) loads %s (a BaseTenantEntity) by primary key, "
                                        + "bypassing tenantFilter%n    at %s",
                                call.getTargetOwner().getSimpleName(), call.getName(),
                                entity.getSimpleName(), call.getSourceCodeLocation())));
            }
        }

        assertThat(violations)
                .as("""
                        Primary-key lookups on tenant-scoped repositories. Hibernate's tenantFilter \
                        does not apply to EntityManager.find(), so these load a row with no \
                        tenant_id predicate — a cross-tenant read if reachable from user input. \
                        Use a tenant-scoped derived query instead: findByIdAndTenantId or \
                        findByPublicIdAndTenantIdAndDeletedAtIsNull.""")
                .isEmpty();
    }

    @Test
    void noDirectEntityManagerOrSessionLookups() {
        List<String> violations = new ArrayList<>();

        for (JavaClass caller : productionClasses) {
            for (JavaMethodCall call : caller.getMethodCallsFromSelf()) {
                String owner = call.getTargetOwner().getFullName();
                boolean isEntityManager = owner.equals("jakarta.persistence.EntityManager")
                        && (call.getName().equals("find") || call.getName().equals("getReference"));
                boolean isSession = owner.equals("org.hibernate.Session")
                        && (call.getName().equals("find") || call.getName().equals("get")
                            || call.getName().equals("load") || call.getName().equals("getReference"));
                if (isEntityManager || isSession) {
                    violations.add(String.format("%s.%s(...)%n    at %s",
                            call.getTargetOwner().getSimpleName(), call.getName(),
                            call.getSourceCodeLocation()));
                }
            }
        }

        // Unlike the repository rule, this cannot tell which entity is being loaded — the type is a
        // Class<T> argument, not a resolvable type parameter. So it bans the calls outright. That is
        // deliberate: these APIs bypass tenantFilter for every entity, and the codebase currently
        // uses none of them, so there is no cost to keeping it that way.
        assertThat(violations)
                .as("""
                        EntityManager.find/getReference and Session.get/load bypass every Hibernate \
                        filter, including tenantFilter. Load entities through a repository derived \
                        query instead.""")
                .isEmpty();
    }

    /**
     * If {@code repositoryClass} is a Spring Data repository typed over a {@link BaseTenantEntity},
     * returns that entity. Reads the entity from the {@code JpaRepository<T, ID>} type argument, so
     * it holds for any repository regardless of naming.
     */
    private static Optional<JavaClass> tenantScopedEntityOf(JavaClass repositoryClass) {
        if (!repositoryClass.isInterface()) {
            return Optional.empty();
        }
        boolean isSpringDataRepository = repositoryClass.getAllRawInterfaces().stream()
                .anyMatch(i -> i.getFullName().startsWith("org.springframework.data."));
        if (!isSpringDataRepository) {
            return Optional.empty();
        }
        return repositoryClass.getInterfaces().stream()
                .filter(JavaParameterizedType.class::isInstance)
                .map(JavaParameterizedType.class::cast)
                .flatMap(parameterized -> parameterized.getActualTypeArguments().stream())
                .map(JavaType::toErasure)
                .filter(TenantIsolationArchTest::isTenantScoped)
                .findFirst();
    }

    private static boolean isTenantScoped(JavaClass candidate) {
        for (JavaClass current = candidate; current != null;
             current = current.getRawSuperclass().orElse(null)) {
            if (current.getFullName().equals(BaseTenantEntity.class.getName())) {
                return true;
            }
        }
        return false;
    }
}