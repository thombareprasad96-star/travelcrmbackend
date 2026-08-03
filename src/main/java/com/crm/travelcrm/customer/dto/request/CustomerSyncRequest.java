package com.crm.travelcrm.customer.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * The optional "I corrected some details while taking this booking" patch, sent when the agent
 * ticks <em>Edit details</em> on a matched customer.
 *
 * <p><b>There is deliberately no {@code phone} field.</b> Phone is the per-tenant natural key —
 * {@code uq_customers_phone_tenant} is built on it, the lead-to-booking conversion resolves on it,
 * and the traveller portal logs in with it. Letting a booking form rewrite it would silently
 * re-point an existing customer at a different person, or collide with the unique index and 500.
 * Omitting the field makes that structural: the rule cannot be forgotten by a future edit to the
 * service, because there is nothing to forget.
 *
 * <p>Every field is optional. Null means "leave it alone" — see
 * {@code CustomerServiceImpl.applySync} for which of these overwrite and which only fill a gap.
 */
@Data
public class CustomerSyncRequest {

    @Size(min = 2, max = 150, message = "Name must be between 2 and 150 characters")
    private String name;

    @Email(message = "Enter a valid email address")
    @Size(max = 150, message = "Email must not exceed 150 characters")
    private String email;

    @JsonFormat(pattern = "yyyy-MM-dd")
    @PastOrPresent(message = "Birthday cannot be in the future")
    private LocalDate birthday;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate anniversary;
}
