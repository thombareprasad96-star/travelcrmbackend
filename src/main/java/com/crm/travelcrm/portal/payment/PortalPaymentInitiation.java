package com.crm.travelcrm.portal.payment;

import com.crm.travelcrm.portal.payment.dto.PaymentIntentResponse;

/**
 * Integration seam between the portal and a (future) Payments module / gateway. The portal validates
 * ownership and amount, then delegates the actual intent/link creation here. Swap the default
 * {@link StubPortalPaymentInitiation} for a Razorpay/Stripe/PayU-backed bean to go live — no portal
 * code changes.
 */
public interface PortalPaymentInitiation {
    PaymentIntentResponse initiate(PaymentInitiationContext context);
}
