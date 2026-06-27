package com.crm.travelcrm.portal.payment;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Everything a payment provider needs to start a charge, decoupled from the Booking entity so the
 * hook never touches CRM internals. Amounts are already validated (0 &lt; amount ≤ pending).
 */
public record PaymentInitiationContext(
        Long tenantId,
        Long customerId,
        UUID bookingPublicId,
        String bookingCode,
        BigDecimal amount,
        BigDecimal pendingAmount
) {}
