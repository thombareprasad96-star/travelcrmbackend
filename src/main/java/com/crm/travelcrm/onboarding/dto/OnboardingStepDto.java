package com.crm.travelcrm.onboarding.dto;

/**
 * One checklist item. Exposes the stable step {@code key} (never an internal entity id).
 * {@code complete} = {@code done || dismissed} — the uniform predicate used for progress + celebration.
 */
public record OnboardingStepDto(
        String key,
        String label,
        String description,
        boolean done,
        boolean dismissed,
        boolean complete) {
}