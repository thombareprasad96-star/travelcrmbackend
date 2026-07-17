package com.crm.travelcrm.common.feature;

import com.crm.travelcrm.common.dto.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Which optional features this deployment actually runs. The frontend calls this once at app load
 * and hides the corresponding UI, so a feature that is switched off server-side never shows up as a
 * button that fails when clicked.
 *
 * <p>Shares the {@code /api/me} base with {@code EntitlementController} / {@code OnboardingController}
 * and is open to any authenticated tenant user — the flags are identical for everyone on the box and
 * carry no tenant data, so there is nothing to scope by {@code TenantContext}.
 *
 * <p><strong>Not to be confused with {@code platform.entitlement.FeatureFlagController}</strong>,
 * which despite the name is the SuperAdmin API for per-tenant module entitlements. That answers
 * "what has this tenant paid for" (per-tenant, DB-driven, enforced by {@code ModuleAccessFilter});
 * this answers "what is switched on in this deployment at all" (env-driven, same for every tenant).
 * Keeping them apart matters: folding a build/deploy switch into the plan model would imply a tenant
 * could buy their way into a feature the server simply is not running. Hence the distinct class name
 * — the two would otherwise collide on the {@code featureFlagController} bean name.
 *
 * <p>This is the read side of the same property the backend enforces with: {@code disha.enabled}
 * gates {@code ChatController} into existence at all. One property drives both, so the UI can never
 * drift from what the server will actually answer — flipping {@code disha.enabled=true} and
 * restarting brings back both the endpoint and the widget, with no frontend rebuild.
 */
@RestController
@RequestMapping("/api/me")
public class DeploymentFeatureController {

    /**
     * Mirrors the {@code @ConditionalOnProperty} on {@code ChatController} — including its
     * {@code matchIfMissing = true}, so an absent property means "on" in both places.
     */
    private final boolean dishaEnabled;

    public DeploymentFeatureController(@Value("${disha.enabled:true}") boolean dishaEnabled) {
        this.dishaEnabled = dishaEnabled;
    }

    @GetMapping("/features")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<FeatureFlagsResponse>> features() {
        return ResponseEntity.ok(ApiResponse.success(
                "Feature flags fetched", new FeatureFlagsResponse(dishaEnabled)));
    }
}
