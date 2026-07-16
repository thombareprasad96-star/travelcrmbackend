package com.crm.travelcrm.subagent.dto;

import com.crm.travelcrm.platform.subscription.upgrade.enums.OfflinePaymentMode;
import com.crm.travelcrm.platform.subscription.upgrade.enums.PaymentMode;
import com.crm.travelcrm.subagent.enums.MarkupType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** Provision a new sub-agent: creates the login User (role SUB_AGENT) + its markup/branding profile. */
@Data
public class CreateSubAgentRequest {

    @NotBlank
    private String name;

    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Size(min = 6, message = "Password must be at least 6 characters")
    private String password;

    private String phoneNumber;

    // ── Markup ──
    private MarkupType markupType = MarkupType.PERCENT;

    @DecimalMin(value = "0.0", message = "Markup value must be zero or positive")
    private BigDecimal markupValue = BigDecimal.ZERO;

    // ── Optional white-label branding ──
    private String brandName;
    private String logoUrl;
    private String contactPhone;
    private String contactEmail;
    private String brandColor;

    // ── Seat licensing (used only when the tenant is over its seat cap) ──
    // How to pay for the seat if one must be purchased. Defaults to ONLINE. When OFFLINE, offlineMode +
    // offlineReference are required (validated when the seat-license request is opened). Ignored when a
    // free licensed seat is available.
    private PaymentMode paymentMode;
    private OfflinePaymentMode offlineMode;
    private String offlineReference;
    private String offlineNotes;
}
