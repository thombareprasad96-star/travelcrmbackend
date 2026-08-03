package com.crm.travelcrm.hotelmarketplace.catalog.enums;

/**
 * Standard hotel meal-plan codes.
 *
 * <p>The tenant's own {@code MealPlan} master stores a free-text name, which cannot be matched
 * across a sync — "Breakfast", "With breakfast" and "CP" are the same plan to a human and three
 * different plans to a string comparison. The catalog therefore carries a stable code, and the
 * projection keeps the code alongside the display name so a re-sync can find the row it wrote last
 * time.</p>
 */
public enum MealPlanCode {

    /** European Plan — room only. */
    EP("Room Only"),

    /** Continental Plan — breakfast included. */
    CP("Breakfast"),

    /** Modified American Plan — breakfast plus one major meal. */
    MAP("Breakfast + 1 Meal"),

    /** American Plan — all major meals. */
    AP("All Meals"),

    /** Anything the four standard codes do not describe; the display name carries the detail. */
    CUSTOM("Custom");

    private final String defaultLabel;

    MealPlanCode(String defaultLabel) {
        this.defaultLabel = defaultLabel;
    }

    /** Fallback display name when the SuperAdmin does not type one. */
    public String defaultLabel() {
        return defaultLabel;
    }
}
