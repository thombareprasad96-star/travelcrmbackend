package com.crm.travelcrm.portal.payment;

import com.crm.travelcrm.portal.payment.dto.PaymentIntentResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Default payment hook used until a real Payments module exists. Returns {@code UNAVAILABLE} (never
 * pretends a charge happened) so the endpoint + contract are usable by the FE now. It is registered
 * as the fallback {@link PortalPaymentInitiation} by {@link PortalPaymentConfig} via
 * {@code @ConditionalOnMissingBean}, so a real gateway bean automatically takes over with no other changes.
 */
@Slf4j
public class StubPortalPaymentInitiation implements PortalPaymentInitiation {

    @Override
    public PaymentIntentResponse initiate(PaymentInitiationContext ctx) {
        log.info("[PORTAL-PAY][stub] intent requested: booking={} amount={} (pending {}) tenant={} customer={} "
                        + "— no payment provider wired",
                ctx.bookingCode(), ctx.amount(), ctx.pendingAmount(), ctx.tenantId(), ctx.customerId());
        return PaymentIntentResponse.builder()
                .bookingPublicId(ctx.bookingPublicId())
                .amount(ctx.amount())
                .currency("INR")
                .status(PaymentIntentStatus.UNAVAILABLE)
                .message("Online payment is not enabled yet. Please contact your travel agent to pay.")
                .build();
    }
}
