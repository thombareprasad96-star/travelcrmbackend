package com.crm.travelcrm.quotation.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Body for {@code POST /api/quotations/{publicId}/send-whatsapp} — sends the shareable quotation link
 * to the customer over WhatsApp. All fields are optional: when {@code toPhone} is blank the quotation's
 * snapshotted customer phone is used.
 */
@Data
public class QuotationWhatsAppRequestDto {

    @Size(max = 20, message = "Phone number must not exceed 20 characters")
    private String toPhone;
}
