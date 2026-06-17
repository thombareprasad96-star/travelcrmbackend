package com.crm.travelcrm.master.geography.support;

import com.crm.travelcrm.common.context.TenantContext;
import org.springframework.data.domain.Sort;
import org.springframework.util.StringUtils;

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

    /** Build a {@link Sort}; defaults to {@code createdAt} and descending order. */
    public static Sort buildSort(String sortBy, String sortDir) {
        String property = StringUtils.hasText(sortBy) ? sortBy : "createdAt";
        return "asc".equalsIgnoreCase(sortDir)
                ? Sort.by(property).ascending()
                : Sort.by(property).descending();
    }
}