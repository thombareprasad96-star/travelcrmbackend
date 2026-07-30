package com.crm.travelcrm.master.geography.support;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.util.PageSupport;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

/**
 * Small shared helpers for the master/geography + services modules: tenant
 * resolution and {@link Sort} construction. Static-only utility — keeps the seven
 * service implementations free of copy-pasted boilerplate.
 */
public final class GeographySupport {

    private GeographySupport() {}

    /** Resolve the current tenant or fail fast if the filter chain didn't populate it. */
    public static Long currentTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "TenantContext is empty. Ensure JwtAuthFilter ran and the JWT carries a tenantId claim.");
        }
        return tenantId;
    }

    /** Logged-in username for the soft-delete audit trail; {@code "system"} if unauthenticated. */
    public static String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    // ── Paging (delegates to the shared implementation) ──────────────────────
    // These forward to PageSupport so the stable-sort rule and the size clamp have exactly one
    // definition across master/, vendor/ and customer/. Kept as thin wrappers rather than deleted
    // because the nine master services already call them by this name.

    /** @see PageSupport#buildSort(String, String) */
    public static Sort buildSort(String sortBy, String sortDir) {
        return PageSupport.buildSort(sortBy, sortDir);
    }

    /** @see PageSupport#buildSort(String, String, Set) */
    public static Sort buildSort(String sortBy, String sortDir, Set<String> allowed) {
        return PageSupport.buildSort(sortBy, sortDir, allowed);
    }

    /** @see PageSupport#pageRequest(int, int, Sort) */
    public static PageRequest pageRequest(int page, int size, Sort sort) {
        return PageSupport.pageRequest(page, size, sort);
    }

    /** @see PageSupport#MAX_PAGE_SIZE */
    public static final int MAX_PAGE_SIZE = PageSupport.MAX_PAGE_SIZE;
}