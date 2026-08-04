package com.crm.travelcrm.hotelmarketplace.commission.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * A manual SuperAdmin correction to the earning ledger.
 *
 * <p>The reason is mandatory. An unexplained signed amount on a revenue ledger is indistinguishable
 * from a mistake six months later, and this is the one write on the table a human originates.</p>
 */
@Data
public class CommissionAdjustmentRequest {

    /**
     * Signed. Negative writes earning down, positive writes it up. Zero is rejected — a no-op row
     * would be an audit entry claiming money moved when none did.
     */
    @NotNull(message = "Adjustment amount is required")
    private BigDecimal amount;

    @NotBlank(message = "An adjustment must say why")
    @Size(max = 1000)
    private String reason;

    /**
     * Distinguishes this correction from every other one on the same booking.
     *
     * <p>Caller-supplied rather than generated, because it is what makes a retried request land once:
     * two legitimate adjustments need two keys, but a double-clicked one needs the same key twice.</p>
     */
    @NotBlank(message = "A reference is required so a retried adjustment is not applied twice")
    @Size(max = 60, message = "Reference must be 60 characters or fewer")
    @Pattern(regexp = "^[A-Za-z0-9._:-]+$",
            message = "Reference may contain only letters, digits and . _ : -")
    private String referenceSuffix;
}
