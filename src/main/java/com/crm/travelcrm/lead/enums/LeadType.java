package com.crm.travelcrm.lead.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How warm the lead is — the agent's read on how close this enquiry is to booking.
 *
 * <p><b>This is a priority vocabulary, not a business-category one.</b> It replaced
 * {@code FRESH_LEAD / REPEAT_CUSTOMER / CORPORATE / VIP}, which mixed two unrelated ideas: how hot
 * the lead is, and what kind of client they are. The second belongs on {@code Customer.type}
 * ({@code INDIVIDUAL / CORPORATE / VIP} already lives there), so nothing is lost by the split —
 * but note the historical rows were folded, not preserved: the migration maps
 * {@code REPEAT_CUSTOMER -> WARM} and {@code CORPORATE|VIP -> HOT}, and those old distinctions are
 * not recoverable from {@code leads} afterwards.
 *
 * <p><b>Changing this enum is a three-part deploy and all three must land together:</b>
 * <ol>
 *   <li>these constants,</li>
 *   <li>the {@code leads_lead_type_check} constraint + row rewrite (migration
 *       {@code V3__lead_booking_fe_alignment.sql}),</li>
 *   <li>the {@code LEAD_TYPES} arrays on the frontend ({@code CreateLead.jsx},
 *       {@code LeadInformation.jsx}).</li>
 * </ol>
 * Ship the constraint alone and every lead write fails against it. Ship the enum alone and every
 * write fails at the database. Ship the frontend alone and the dropdown offers values the API
 * rejects.
 *
 * <p>The wire format is the {@code displayName} ({@code "Fresh"}), never the constant name.
 */
public enum LeadType {

    FRESH("Fresh"),
    HOT("Hot"),
    WARM("Warm"),
    COLD("Cold");

    private final String displayName;

    LeadType(String displayName) {
        this.displayName = displayName;
    }

    @JsonValue
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Parse from either the display name or the constant name, case-insensitively.
     *
     * <p><b>Blank returns null rather than throwing</b>, and that is load-bearing. The form's
     * {@code leadType} select carries no {@code required} rule, so an untouched dropdown posts
     * {@code ""}. Throwing here happens during Jackson deserialization — <em>before</em> bean
     * validation runs — so the client got an opaque {@code HttpMessageNotReadableException} 400 with
     * no {@code fieldErrors} to place beside the input, and the friendly
     * {@code @NotNull("Lead type is required")} message could never fire. Returning null lets
     * validation do its job and produces a 400 the form can actually render inline.
     */
    @JsonCreator
    public static LeadType fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (LeadType type : values()) {
            if (type.displayName.equalsIgnoreCase(value)
                    || type.name().equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown LeadType: " + value);
    }
}
