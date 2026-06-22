package com.crm.travelcrm.quotation.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Body for {@code POST /api/quotations/{publicId}/send-email} — emails the
 * generated quotation PDF to the customer.
 */
@Data
public class QuotationEmailRequestDto {

    @NotBlank(message = "Recipient email is required")
    @Email(message = "Enter a valid email address")
    private String toEmail;

    @Size(max = 200, message = "Subject must not exceed 200 characters")
    private String subject;

    @Size(max = 5000, message = "Message must not exceed 5000 characters")
    private String message;
}