package com.crm.travelcrm.platform.billing.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * The platform-wide sub-agent (Travel Partner) seat pricing — the SAME across every tenant (unless a
 * per-tenant override is set separately). {@code recurringSeatFee} is billed monthly per ACTIVE seat;
 * {@code oneTimeLicenseFee} is the one-time unlock charged when a partner is purchased. When the
 * one-time fee is unset it falls back to the recurring rate — {@code effectiveLicenseFee} is the value
 * actually charged and {@code licenseFeeUsingRecurringFallback} says whether that fallback is in effect.
 */
@Getter
@Builder
public class SubAgentPricingResponse {

    /** Platform-flat recurring monthly fee per ACTIVE seat. */
    private BigDecimal recurringSeatFee;

    /** Explicitly-configured one-time license fee, or null when unset (then the recurring rate applies). */
    private BigDecimal oneTimeLicenseFee;

    /** The one-time unlock actually charged on a purchase (explicit fee, or the recurring fallback). */
    private BigDecimal effectiveLicenseFee;

    /** True when no explicit one-time fee is set, so the recurring rate is used for the unlock. */
    private boolean licenseFeeUsingRecurringFallback;

    /** Display currency for the console (platform default). */
    private String currency;
}