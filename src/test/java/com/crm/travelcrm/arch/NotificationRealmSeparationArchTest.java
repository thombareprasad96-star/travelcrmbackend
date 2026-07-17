package com.crm.travelcrm.arch;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Fails the build when the tenant and platform notification realms start to merge.
 *
 * <p><b>The invariant.</b> There are two independent in-app feeds: {@code notification/} for tenant
 * users, keyed on {@code users.id}, and {@code platform/notification/} for the SuperAdmin, keyed on
 * {@code super_admins.id}. Those are separate {@code IDENTITY} sequences, so the SAME numeric value
 * is a valid id in both tables, and neither {@code recipient} column has a foreign key. Nothing at
 * the database level would object to an id from the wrong realm.
 *
 * <p>What keeps them apart is that they share no table, no repository and no SSE registry — so no
 * query and no push can span them. That separation is structural today. It stops being structural
 * the moment one realm imports the other's registry, entity or repository "to avoid duplication".
 *
 * <p><b>The specific disaster being prevented.</b> {@code SseEmitterRegistry} keys live browser
 * connections by a bare {@code Long} with no realm tag. Reusing it from the platform side would
 * push a tenant's revenue figures into tenant user #1's open tab, silently, with no error — the two
 * "1"s are indistinguishable once they are in the same map.
 *
 * <p><b>Why a test and not a code review.</b> The duplication between the two realms is small and
 * looks gratuitous: two feeds, two DTOs, two registries, ~250 near-identical lines. It will read to
 * a future contributor (or a future us) as an obvious cleanup. The duplicate IS the boundary, and
 * this test is the note explaining that, in the only place that cannot be skimmed past.
 *
 * <p><b>If this test fails</b>, do not add an exemption. The realms are meant to diverge — the
 * console feed wants tenant context and cross-tenant grouping that a tenant feed must never have.
 */
class NotificationRealmSeparationArchTest {

    private static final String TENANT_REALM   = "com.crm.travelcrm.notification..";
    private static final String PLATFORM_REALM = "com.crm.travelcrm.platform.notification..";

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.crm.travelcrm");
    }

    @Test
    void platformNotificationRealmDoesNotDependOnTheTenantRealm() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(PLATFORM_REALM)
                .should().dependOnClassesThat().resideInAPackage(TENANT_REALM)
                .because("""
                        the platform feed must not reuse the tenant module's entity, repository, DTO \
                        or SseEmitterRegistry. That registry keys emitters by a bare Long, and \
                        super_admins.id and users.id are separate IDENTITY sequences — sharing it \
                        would push a platform notification into a tenant user's live browser tab.""");

        rule.check(productionClasses);
    }

    @Test
    void tenantNotificationRealmDoesNotDependOnThePlatformRealm() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(TENANT_REALM)
                .should().dependOnClassesThat().resideInAPackage(PLATFORM_REALM)
                .because("""
                        the tenant feed predates the platform one and must stay independent of it; a \
                        dependency here would make a tenant-facing feed breakable by a console change.""");

        rule.check(productionClasses);
    }

    /**
     * The platform notification entity must never become tenant-scoped. {@code BaseTenantEntity}
     * would bring a {@code NOT NULL tenant_id} (a SuperAdmin has no tenant) and the
     * {@code tenantFilter} — and {@code TenantFilterAspect} returns early when {@code TenantContext}
     * is empty, which every platform request is. The filter would silently not apply, so the
     * "isolation" would be a comment rather than a mechanism.
     */
    @Test
    void platformNotificationEntitiesAreNotTenantScoped() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(PLATFORM_REALM)
                .should().beAssignableTo(BaseTenantEntity.class)
                .because("""
                        a SuperAdmin has no tenant. BaseTenantEntity's tenant_id is NOT NULL and its \
                        tenantFilter is skipped when TenantContext is empty (TenantFilterAspect), so \
                        extending it would fail inserts at the DB layer and provide no isolation in \
                        exchange. PlatformNotification.tenantId is display context, not a scope.""");

        rule.check(productionClasses);
    }
}