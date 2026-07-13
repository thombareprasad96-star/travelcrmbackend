package com.crm.travelcrm.onboarding.dto;

import java.util.List;

/**
 * The onboarding widget payload — wrapped by {@code ApiResponse<T>}. {@code percentComplete} and
 * {@code allComplete} are derived from the per-step {@code complete} flags (done-or-dismissed).
 */
public record OnboardingChecklistResponse(
        int totalSteps,
        int completedSteps,
        int percentComplete,
        boolean allComplete,
        List<OnboardingStepDto> steps) {
}