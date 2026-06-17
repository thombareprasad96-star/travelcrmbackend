package com.crm.travelcrm.customer.dto.request;

import com.crm.travelcrm.customer.enums.CustomerStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Body for {@code PATCH /api/customers/{id}/status}.
 * Matches {@code customerService.updateStatus(id, status)} → {@code { status }}.
 */
@Data
public class StatusUpdateRequest {

    @NotNull(message = "Status is required")
    private CustomerStatus status;
}