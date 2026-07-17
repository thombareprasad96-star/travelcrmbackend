package com.crm.travelcrm.arch;

import com.crm.travelcrm.leadsource.spi.LeadSourceAdapter;
import com.crm.travelcrm.leadsource.spi.LeadSourceFetcher;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Keeps lead-source adapters PURE — the property the framework's tenant isolation actually rests on.
 *
 * <p><b>Why purity is a security property and not a style preference.</b> Adapters run on a public,
 * unauthenticated endpoint, before (and during) tenant resolution. The argument that an adapter cannot
 * read another tenant's data is not "we reviewed it carefully" — it is <b>"an adapter cannot read
 * anything at all"</b>. That argument only holds while it is mechanically true, which is what these
 * rules make it. It is also what makes a stored raw payload replayable: the same bytes must yield the
 * same parse next month, on another machine, with no database.
 *
 * <p>This is why the design REJECTED "tenant resolution should be a method on the adapter": resolution
 * needs a DB lookup by definition, and that one method would have dissolved the whole argument.
 */
class LeadSourceAdapterPurityArchTest {

    private static final String ADAPTER_PACKAGE = "com.crm.travelcrm.leadsource.adapter..";

    private static JavaClasses adapters;
    private static JavaClasses allClasses;

    @BeforeAll
    static void importClasses() {
        adapters = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.crm.travelcrm.leadsource.adapter");
        allClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.crm.travelcrm");
    }

    @Test
    @DisplayName("an adapter cannot reach the database, the tenant context, or the gateway")
    void adaptersArePure() {
        ArchRule rule = noClasses().that().resideInAPackage(ADAPTER_PACKAGE)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..repository..",
                        "jakarta.persistence..",
                        "org.springframework.data..",
                        "org.springframework.transaction..",
                        "com.crm.travelcrm.common.context..",
                        "com.crm.travelcrm.leadsource.gateway..",
                        "com.crm.travelcrm.leadsource.registry..")
                .because("""
                        an adapter parses bytes on a public endpoint before the tenant is fully \
                        trusted. "An adapter cannot query the wrong tenant" is only guaranteed while \
                        "an adapter cannot query" is mechanically true. A repository or TenantContext \
                        dependency here dissolves the framework's entire tenant-isolation argument, \
                        and would also make the stored raw payload un-replayable.""");

        rule.check(adapters);
    }

    @Test
    @DisplayName("an adapter holds no mutable state — it is a singleton shared by every tenant")
    void adaptersHaveNoMutableState() {
        ArchRule rule = fields().that().areDeclaredInClassesThat().resideInAPackage(ADAPTER_PACKAGE)
                .and().areNotStatic()
                .should().beFinal()
                .because("""
                        adapters are Spring singletons serving EVERY tenant's traffic concurrently. \
                        A non-final instance field is a cross-tenant data leak that no existing test \
                        would catch — it appears only under concurrent load from two tenants, which \
                        is exactly the condition no dev machine reproduces.""")
                // Vacuously true TODAY and that is the point: the only adapter shipped so far holds
                // nothing but static finals, which is the desired end state, not an oversight.
                // ArchUnit fails an empty should() by default to catch typo'd predicates; here the
                // empty set IS the passing condition, so the rule is preventive rather than
                // descriptive. Without this it would fail the build for having nothing to complain
                // about.
                .allowEmptyShould(true);

        rule.check(adapters);
    }

    @Test
    @DisplayName("everything in the adapter package IS an adapter or a fetcher")
    void adapterPackageContainsOnlyAdapters() {
        ArchRule rule = classes().that().resideInAPackage(ADAPTER_PACKAGE)
                .and().areNotNestedClasses()
                .and().areNotInterfaces()
                .should().implement(LeadSourceAdapter.class)
                .orShould().implement(LeadSourceFetcher.class)
                .because("""
                        the purity rules above are scoped to this package. A helper class parked here \
                        would be silently exempt from them while looking, to a reader, like it is \
                        covered — put shared helpers in the framework packages, where the rules do \
                        not need to apply.""");

        rule.check(adapters);
    }

    @Test
    @DisplayName("no class-level @Transactional anywhere")
    void noClassLevelTransactional() {
        // TenantFilterAspect's pointcut is @annotation(@Transactional), which matches METHOD level
        // ONLY. A class-level @Transactional therefore gets NO tenant filter at all — every query in
        // that class silently spans all tenants, with no error and no log.
        //
        // There are zero violations today; this codifies the convention so it stops costing every
        // reviewer the same grep, and so the first violation fails the build rather than shipping.
        ArchRule rule = noClasses().should().beAnnotatedWith(
                        org.springframework.transaction.annotation.Transactional.class)
                .because("""
                        TenantFilterAspect matches @Transactional at METHOD level only, so a \
                        class-level annotation gets no tenant filter at all — every query in the \
                        class silently spans every tenant. Annotate the methods instead.""");

        rule.check(allClasses);
    }
}
