package com.crm.travelcrm.company.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.List;

/**
 * The tenant's own subscription snapshot for {@code GET /api/company/subscription}. The original
 * fields ({@code plan}, {@code startDate}, {@code endDate}, {@code status}, {@code daysLeft},
 * {@code features}) are preserved for backward compatibility; the rest expose the real plan pricing
 * and per-plan limits now that a billing source exists.
 */
@Value
@Builder
public class SubscriptionDTO {
    String       plan;            // display name (kept for BC — was "Standard Plan")
    String       planCode;        // enum code (STARTER/PRO/…)
    BigDecimal   monthlyPrice;
    String       currency;
    String       startDate;       // formatted "MMM dd, yyyy"
    String       endDate;
    String       status;          // ACTIVE / TRIAL / PAST_DUE / SUSPENDED / EXPIRED
    Integer      daysLeft;        // days to endDate; null when no end date
    String       pastDueSince;    // set only while in the dunning grace window
    List<String> features;        // effective module keys

    // Effective plan limits (null = unlimited).
    Integer      maxUsers;
    Integer      maxLeads;
    Integer      maxBookingsPerMonth;
    Integer      maxStorageMb;
}