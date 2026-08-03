package com.crm.travelcrm.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Keeps the Fleet module sellable on its own.
 *
 * <p><b>Why this is a build rule and not a review checklist.</b> Fleet is meant to ship both as a
 * CRM feature and as an independent product. That claim is only true while it is mechanically true:
 * one {@code import com.crm.travelcrm.booking.entity.Booking} in a service is enough to drag the
 * entire booking module — its tables, its permissions, its lifecycle — into a Fleet-only deployment,
 * and nobody notices until the standalone build fails to start.
 *
 * <p>The coupling was four files. They were replaced by two ports
 * ({@code FleetJobReferencePort}, {@code FleetPartyPort}) with a CRM adapter and a standalone
 * fallback each. This test is what stops the fifth file from appearing: the adapter package under
 * {@code fleet.integration.adapter.crm} is the ONLY place allowed to name a CRM type.
 *
 * <p>Companion to {@code LeadSourceAdapterPurityArchTest} and {@code TenantIsolationArchTest} —
 * same idea, an architectural promise nobody can quietly break.
 */
class FleetBoundaryArchTest {

    /** Everything in the module except the CRM adapter package. */
    private static final String[] FLEET_CORE = {
            "com.crm.travelcrm.fleet.entity..",
            "com.crm.travelcrm.fleet.enums..",
            "com.crm.travelcrm.fleet.dto..",
            "com.crm.travelcrm.fleet.mapper..",
            "com.crm.travelcrm.fleet.money..",
            "com.crm.travelcrm.fleet.repository..",
            "com.crm.travelcrm.fleet.service..",
            "com.crm.travelcrm.fleet.controller..",
            "com.crm.travelcrm.fleet.specification..",
            "com.crm.travelcrm.fleet.scheduler..",
            "com.crm.travelcrm.fleet.integration.spi..",
            "com.crm.travelcrm.fleet.integration.adapter.standalone..",
    };

    /** CRM business modules a Fleet-only deployment does not ship. */
    private static final String[] CRM_MODULES = {
            "com.crm.travelcrm.booking..",
            "com.crm.travelcrm.vendor..",
            "com.crm.travelcrm.lead..",
            "com.crm.travelcrm.customer..",
            "com.crm.travelcrm.quotation..",
            "com.crm.travelcrm.master..",
            "com.crm.travelcrm.accounting..",
            "com.crm.travelcrm.marketing..",
    };

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.crm.travelcrm");
    }

    @Test
    @DisplayName("fleet core never imports a CRM business module — only its CRM adapter may")
    void fleetCoreIsCrmIndependent() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(FLEET_CORE)
                .should().dependOnClassesThat().resideInAnyPackage(CRM_MODULES)
                .because("""
                        Fleet ships as an independent product as well as a CRM feature. A single import \
                        of a CRM type drags that whole module into a Fleet-only deployment, and the \
                        breakage shows up as a startup failure on a customer's server, not here.

                        Resolve it through a port instead:
                          fleet.integration.spi.FleetJobReferencePort  — what a trip was run FOR
                          fleet.integration.spi.FleetPartyPort         — who a vehicle belongs to

                        Only fleet.integration.adapter.crm may name a CRM type. If you need something \
                        new from the CRM, widen a port and add it to the CRM adapter — do not import \
                        the entity here.""");

        rule.check(classes);
    }

    @Test
    @DisplayName("the standalone adapters are genuinely standalone")
    void standaloneAdaptersHaveNoCrmDependency() {
        // The fallback is what a Fleet-only deployment actually runs. If it reached into the CRM the
        // whole arrangement would be decorative: @ConditionalOnMissingBean would pick a "standalone"
        // implementation that still needs the booking tables to exist.
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.crm.travelcrm.fleet.integration.adapter.standalone..")
                .should().dependOnClassesThat().resideInAnyPackage(CRM_MODULES)
                .because("the standalone adapter is the implementation a Fleet-only customer runs");

        rule.check(classes);
    }

    @Test
    @DisplayName("nothing outside fleet reaches into its internals")
    void crmDoesNotReachIntoFleetInternals() {
        // The reverse direction matters too, and for the same reason: if a booking service starts
        // reading FleetTrip directly, extracting Fleet later stops being a packaging decision and
        // becomes a rewrite. Anything the CRM needs from Fleet should come through a controller or
        // a published event.
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(CRM_MODULES)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.crm.travelcrm.fleet.entity..",
                        "com.crm.travelcrm.fleet.repository..",
                        "com.crm.travelcrm.fleet.service..")
                .because("""
                        Fleet's internals are its own. A CRM module that reads FleetTrip or a fleet \
                        repository turns a future extraction from a packaging change into a rewrite.""");

        rule.check(classes);
    }
}
