package com.crm.travelcrm.common.context;

/**
 * Names the "platform / god-mode" execution state — the deliberate counterpart to
 * {@link TenantContext}. It is populated ONLY for an authenticated platform SuperAdmin
 * (by {@code JwtAuthFilter}), so that operating across all tenants is an <b>intentional,
 * inspectable, auditable</b> condition rather than the accidental side effect of
 * "TenantContext happens to be null".
 *
 * <p><b>What it does NOT do:</b> it does not by itself disable the tenant {@code @Filter}
 * or grant any access. Authorization is still enforced by Spring Security
 * ({@code hasRole('SUPER_ADMIN')}). This marker exists so the platform layer can
 * (a) resolve the acting SuperAdmin for the {@code platform_audit_logs} trail and
 * (b) assert, where it matters, that a cross-tenant operation is genuinely running as the
 * platform actor — without widening the known {@code findById} filter-bypass (H3) for
 * ordinary tenant code.
 *
 * <p>Like {@link TenantContext} it is backed by a {@link ThreadLocal} and <b>must</b> be
 * cleared in a {@code finally} block to avoid leaking across pooled request threads.
 */
public final class PlatformContext {

    private static final ThreadLocal<PlatformActor> CURRENT = new ThreadLocal<>();

    private PlatformContext() {}

    /** Marks the current thread as executing as the given platform SuperAdmin. */
    public static void enter(PlatformActor actor) {
        CURRENT.set(actor);
    }

    /** The acting SuperAdmin, or {@code null} when this is not a platform request. */
    public static PlatformActor getActor() {
        return CURRENT.get();
    }

    /** True when the current thread is executing as the platform SuperAdmin. */
    public static boolean isPlatform() {
        return CURRENT.get() != null;
    }

    public static void clear() {
        CURRENT.remove(); // MUST call this — prevents leaks across thread-pool reuse
    }
}