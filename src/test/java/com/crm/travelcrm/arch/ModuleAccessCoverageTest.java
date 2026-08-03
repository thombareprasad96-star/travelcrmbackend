package com.crm.travelcrm.arch;

import com.crm.travelcrm.platform.entitlement.filter.ModuleAccessFilter;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every tenant-facing {@code /api} prefix must be a deliberate decision: either it requires a module
 * ({@code ModuleAccessFilter.RULES}) or it is explicitly platform-neutral ({@code ALWAYS_ALLOWED}).
 * A new controller that is neither fails this test.
 *
 * <p><b>Why this test is the control, and not runtime fail-closed.</b> {@code ModuleAccessFilter} is
 * fail-OPEN: an unmapped prefix is permitted. That is a silent opt-out, and it is how Accounting,
 * Marketing, Tasks, Calendar, Reminders and the CRM Dashboard ended up reachable by every tenant on
 * every plan regardless of what they bought — including, once Fleet is sold standalone, by a
 * customer who bought no CRM at all. Flipping the filter to deny-by-default would close it, but on a
 * live single-tenant deployment it also risks 403-ing a path nobody enumerated, in production, with
 * no warning.
 *
 * <p>So the guarantee moves to build time. The runtime stays forgiving; the compiler does not. A
 * developer adding {@code @RequestMapping("/api/whatever")} must classify it here, which is exactly
 * the decision that was being skipped. {@code app.entitlement.fail-closed=true} can then make the
 * runtime strict too, once this test has been green across a few releases.
 *
 * <p>Companion to {@code TenantIsolationArchTest} and {@code LeadSourceAdapterPurityArchTest}: the
 * same idea — an architectural promise nobody can quietly break.
 */
class ModuleAccessCoverageTest {

    private static JavaClasses controllers;

    @BeforeAll
    static void importControllers() {
        controllers = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.crm.travelcrm");
    }

    @Test
    @DisplayName("every /api controller prefix is either module-gated or explicitly allowlisted")
    void everyApiPrefixIsClassified() {
        Set<String> rules = ModuleAccessFilter.rulePrefixes();
        Set<String> allowed = ModuleAccessFilter.allowedPrefixes();
        Set<String> unclassified = new TreeSet<>();

        for (var clazz : controllers) {
            for (String path : effectivePaths(clazz)) {
                if (!path.startsWith("/api")) continue;          // /ai/chat and friends are not tenant API
                if (isCovered(path, rules) || isCovered(path, allowed)) continue;
                unclassified.add(path + "  (" + clazz.getSimpleName() + ")");
            }
        }

        assertThat(unclassified)
                .as("""
                        These controller paths are neither module-gated nor allowlisted, so they are \
                        reachable by ANY authenticated tenant user regardless of plan — including a \
                        Fleet-only tenant who bought no CRM.

                        Add each to ModuleAccessFilter.RULES with the module it belongs to, or to \
                        ALWAYS_ALLOWED if it is genuinely a platform capability every tenant needs \
                        (auth, identity, users, company, notifications, trash).

                        Do not "fix" this by deleting the assertion.""")
                .isEmpty();
    }

    @Test
    @DisplayName("a path is never both gated and allowlisted")
    void rulesAndAllowlistDoNotOverlap() {
        Set<String> overlap = new TreeSet<>(ModuleAccessFilter.rulePrefixes());
        overlap.retainAll(ModuleAccessFilter.allowedPrefixes());

        assertThat(overlap)
                .as("an ambiguous prefix means the gate depends on iteration order — pick one")
                .isEmpty();
    }

    /**
     * Mirrors the filter's own matching: exact, or prefix followed by '/'. Deliberately NOT a bare
     * {@code startsWith} — that would let {@code /api/leads} claim {@code /api/lead-sources}, which is
     * the precise bug the filter's own comment warns about for {@code /api/quotation-templates}.
     */
    private boolean isCovered(String path, Set<String> prefixes) {
        return prefixes.stream().anyMatch(p -> path.equals(p) || path.startsWith(p + "/"));
    }

    /**
     * Full URLs a controller actually serves = class-level prefix + each method-level path.
     *
     * <p>Class-level alone is not enough, and the omission is not theoretical: {@code
     * DestinationController} is mapped to a bare {@code "/api"} and serves {@code /api/destinations}
     * and {@code /api/countries/{id}/destinations} from its methods. Reading only the class
     * annotation reports an unclassifiable {@code "/api"}; reading only method annotations misses
     * every controller that puts the whole path on the class. Composing them is what matches what
     * Spring actually routes — and therefore what the filter actually sees.
     */
    private Set<String> effectivePaths(com.tngtech.archunit.core.domain.JavaClass clazz) {
        Set<String> classPrefixes = new TreeSet<>(pathsOf(clazz.tryGetAnnotationOfType(RequestMapping.class)
                .map(RequestMapping::value).orElse(null)));
        if (classPrefixes.isEmpty()) classPrefixes.add("");

        Set<String> methodPaths = new TreeSet<>();
        for (var method : clazz.getMethods()) {
            for (Class<? extends java.lang.annotation.Annotation> type : MAPPING_ANNOTATIONS) {
                if (!method.isAnnotatedWith(type)) continue;
                Set<String> declared = pathsOf(invokeValue(method.getAnnotationOfType(type)));
                // A mapping with NO path maps to the class prefix itself — but only then. Adding ""
                // unconditionally re-introduces the bare class prefix ("/api") as if it were a real
                // endpoint, which is the very thing this composition exists to resolve.
                methodPaths.addAll(declared.isEmpty() ? Set.of("") : declared);
            }
        }
        if (methodPaths.isEmpty()) methodPaths.add("");

        Set<String> effective = new TreeSet<>();
        for (String prefix : classPrefixes) {
            for (String suffix : methodPaths) {
                effective.add((prefix + suffix).isEmpty() ? "/" : prefix + suffix);
            }
        }
        return effective;
    }

    private static final Set<Class<? extends java.lang.annotation.Annotation>> MAPPING_ANNOTATIONS = Set.of(
            org.springframework.web.bind.annotation.GetMapping.class,
            org.springframework.web.bind.annotation.PostMapping.class,
            org.springframework.web.bind.annotation.PutMapping.class,
            org.springframework.web.bind.annotation.DeleteMapping.class,
            org.springframework.web.bind.annotation.PatchMapping.class,
            RequestMapping.class);

    private Set<String> pathsOf(String[] values) {
        return values == null ? Set.of() : Set.of(values);
    }

    /** {@code value()} is declared separately on each mapping annotation; reflection keeps this generic. */
    private String[] invokeValue(Object annotation) {
        try {
            return (String[]) annotation.getClass().getMethod("value").invoke(annotation);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
