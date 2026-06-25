package com.crm.travelcrm.booking.dto.request;

import com.crm.travelcrm.booking.enums.CancelAction;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Body for {@code POST /api/bookings/{publicId}/cancel}.
 * {@code action} decides what happens to the associated lead; the booking is always retained.
 */
@Getter
@Setter
public class CancelBookingRequestDTO {

    @NotNull(message = "Cancel action is required (MOVE_TO_LEAD or PERMANENT_DELETE_LEAD)")
    private CancelAction action;
}