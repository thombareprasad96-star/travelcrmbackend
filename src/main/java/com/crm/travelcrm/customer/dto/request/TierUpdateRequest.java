package com.crm.travelcrm.customer.dto.request;

import com.crm.travelcrm.customer.enums.LoyaltyTier;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Body for {@code PATCH /api/customers/{id}/tier}.
 * Matches {@code customerService.updateTier(id, tier)} → {@code { tier }}.
 */
@Data
public class TierUpdateRequest {

    @NotNull(message = "Tier is required")
    private LoyaltyTier tier;
}