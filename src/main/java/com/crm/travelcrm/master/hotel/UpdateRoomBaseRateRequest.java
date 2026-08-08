package com.crm.travelcrm.master.hotel;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Updates only the tenant-owned selling rate of a room type.
 *
 * <p>The value may be {@code null} to clear an obsolete rate. This narrow request is also used for
 * marketplace-projected rooms: their descriptive fields remain platform-owned, while the selling
 * rate belongs to the tenant and is never overwritten by catalog synchronization.
 */
@Data
public class UpdateRoomBaseRateRequest {

    @DecimalMin(value = "0.0", message = "Room rate cannot be negative")
    @Digits(integer = 13, fraction = 2, message = "Room rate is invalid")
    private BigDecimal baseRate;
}
