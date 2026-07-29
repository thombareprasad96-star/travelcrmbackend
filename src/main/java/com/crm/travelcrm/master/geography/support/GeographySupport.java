package com.crm.travelcrm.master.geography.support;

import com.crm.travelcrm.common.context.TenantContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;

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

    /** Build a {@link Sort}; defaults to {@code createdAt} and descending order. */
    public static Sort buildSort(String sortBy, String sortDir) {
        return buildSort(sortBy, sortDir, null);
    }

    /**
     * Build a {@link Sort} with a STABLE tiebreaker, optionally restricted to a whitelist.
     *
     * <p>The trailing {@code id DESC} is load-bearing, not decoration. Sorting a paged query on a
     * non-unique column ({@code createdAt} across bulk-created rows, {@code name} across duplicates)
     * leaves the order of the tied rows up to Postgres, and it may answer page 1 and page 2 with a
     * different arrangement of the same ties. The visible symptom is one row showing on two pages
     * while another never appears at all — which reads to the user as "the record got deleted".
     * {@code id} is the primary key on every entity here, so appending it makes the ordering total
     * and the paging reproducible.
     *
     * @param allowed when non-null, {@code sortBy} must name one of these properties; anything else
     *                falls back to {@code createdAt}. Unvalidated input otherwise reaches
     *                {@code PageRequest.of(...)} and surfaces as a 500 on an unknown property.
     */
    public static Sort buildSort(String sortBy, String sortDir, Set<String> allowed) {
        String property = StringUtils.hasText(sortBy) ? sortBy.trim() : "createdAt";
        if (allowed != null && !allowed.contains(property)) {
            property = "createdAt";
        }
        Sort primary = "asc".equalsIgnoreCase(sortDir)
                ? Sort.by(property).ascending()
                : Sort.by(property).descending();
        return "id".equals(property) ? primary : primary.and(Sort.by(Sort.Direction.DESC, "id"));
    }

    // ── Page-request guards ──────────────────────────────────────────────────

    /** Largest page a client may ask for; stops {@code ?size=100000} from becoming an OOM. */
    public static final int MAX_PAGE_SIZE = 200;

    /** Page size used when the caller sends nothing usable. */
    public static final int DEFAULT_PAGE_SIZE = 25;

    /** Clamp a client-supplied page/size pair into a safe {@link PageRequest}. */
    public static PageRequest pageRequest(int page, int size, Sort sort) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(safePage, safeSize, sort);
    }
}