package com.crm.travelcrm.auth.security;

import com.crm.travelcrm.auth.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

/**
 * The post-load acceptance checks a JWT principal must pass before it is authenticated.
 *
 * <p>A valid signature only proves the token was minted by us and has not expired. It says nothing
 * about whether the principal is still allowed in <i>right now</i> — deactivation, locking and
 * force-reset all happen after minting and cannot retroactively invalidate a signature. These
 * checks are what make those controls effective, so they must run at <b>every</b> entry point that
 * turns a token into an {@code Authentication}.
 *
 * <p>Shared by {@link JwtAuthFilter} (header-based) and {@link TokenAuthenticatorImpl}
 * (header-less, for SSE). It lives here rather than being duplicated because it previously
 * <i>was</i> duplicated — and the SSE copy omitted the checks entirely, leaving
 * {@code /api/notifications/stream?token=} as a live feed for principals every other endpoint had
 * already locked out. One implementation, one place to add the next check.
 */
@Component
@RequiredArgsConstructor
public class JwtPrincipalValidator {

    private final JwtUtil jwtUtil;

    /**
     * True when this principal may be authenticated for this token. Callers must treat
     * {@code false} as "authenticate nobody" — not as a reason to fall back to a weaker identity.
     */
    public boolean isAcceptable(UserDetails userDetails, String token) {
        // Soft-deleted users are already excluded by the loaders, so they surface as "not found"
        // before reaching here. (User.isEnabled() == isActive; SuperAdmin.isEnabled() == enabled.)
        return userDetails.isEnabled()
                && userDetails.isAccountNonLocked()
                && !isStaleToken(userDetails, token);
    }

    /**
     * "Reset + kick": a tenant user's live tokens are invalidated when a SuperAdmin force-reset or
     * lock bumped {@code User.tokenVersion}. Old tokens carry a lower (or absent → 0) 'tv' claim, so
     * they stop matching and are rejected on the very next request. SuperAdmin tokens are not
     * versioned, so they are never considered stale.
     */
    private boolean isStaleToken(UserDetails userDetails, String token) {
        if (userDetails instanceof User user) {
            Integer claimTv = jwtUtil.extractTokenVersion(token);
            int tokenTv = claimTv != null ? claimTv : 0;
            return tokenTv != user.getTokenVersionOrZero();
        }
        return false;
    }
}