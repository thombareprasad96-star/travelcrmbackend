package com.crm.travelcrm.booking.dto.request;

import com.crm.travelcrm.booking.enums.BookingStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StatusUpdateRequestDTO {

    @NotNull(message = "Status is required")
    private BookingStatus status;

    @Size(max = 500, message = "Reason cannot exceed 500 characters")
    private String reason;
}