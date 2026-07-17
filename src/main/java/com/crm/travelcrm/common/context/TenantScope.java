package com.crm.travelcrm.common.context;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Supplier;

/**
 * Runs work under a given tenant, safely.
 *
 * <p>Every invariant here closes a specific, named landmine in this codebase. None of them are
 * theoretical.
 *
 * <h2>1. Null-rejecting</h2>
 * {@code TenantFilterAspect} returns WITHOUT enabling {@code tenantFilter} when the context is null —
 * it <b>fails OPEN</b>: every query silently spans all tenants, with no error and no log. Worse, the
 * {@code softDeleteFilter} still enables, so the session <em>looks</em> filtered. A null tenant here
 * therefore throws rather than being passed through.
 *
 * <h2>2. Save/restore, not set/clear</h2>
 * {@link TenantContext} is a bare {@code ThreadLocal<Long>} with no stack, so a nested
 * {@code set → clear} destroys the OUTER value and everything after the inner block runs with a null
 * context — i.e. unfiltered. This captures the previous value and restores it (or clears, if there was
 * none). {@code TenantContext} itself is deliberately left unchanged.
 *
 * <h2>3. The transaction guard — the point of the whole helper</h2>
 * {@code TenantFilterAspect} is {@code @Before} on {@code @Transactional}, so the filter is latched
 * <b>before the method body runs</b>. That makes {@code TenantContext.setTenantId()} <em>inside</em> a
 * transactional method <b>too late</b>: the filter stays off for that entire transaction. This is
 * invisible in single-tenant dev and a cross-tenant leak in production — the worst possible failure
 * signature. The guard converts it into a loud exception at the offending call site.
 *
 * <p>The consequence for callers: <b>enter this scope on a NON-transactional thread, then call
 * CROSS-BEAN into a transactional method.</b> Self-invocation defeats both the transaction proxy and
 * the aspect, so an inlined "private @Transactional helper" silently reintroduces the bug.
 *
 * <p>The ~9 existing hand-rolled {@code set/clear} sites are deliberately NOT migrated to this: out of
 * scope, nonzero regression risk, no user-visible benefit.
 */
public final class TenantScope {

    private TenantScope() {}

    /** Run {@code work} as {@code tenantId} and return its result. */
    public static <T> T call(Long tenantId, Supplier<T> work) {
        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "TenantScope requires a non-null tenantId — a null context makes TenantFilterAspect "
                            + "fail OPEN (every query spans all tenants, silently).");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "TenantScope must be entered BEFORE a transaction begins. TenantFilterAspect is "
                            + "@Before on @Transactional, so setting the tenant inside a transactional "
                            + "method is too late — the filter stays off for the whole transaction and "
                            + "every query silently spans all tenants. Enter the scope on a "
                            + "non-transactional thread and call CROSS-BEAN into the transactional "
                            + "method.");
        }

        Long previous = TenantContext.getTenantId();
        TenantContext.setTenantId(tenantId);
        try {
            return work.get();
        } finally {
            // Restore rather than clear: TenantContext has no stack, so clearing would destroy an
            // outer scope's value.
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.setTenantId(previous);
            }
        }
    }

    /** Run {@code work} as {@code tenantId}. */
    public static void run(Long tenantId, Runnable work) {
        call(tenantId, () -> {
            work.run();
            return null;
        });
    }
}
