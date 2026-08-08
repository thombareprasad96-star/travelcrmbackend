package com.crm.travelcrm.lead.dto;

import com.crm.travelcrm.customer.dto.response.CustomerMatchResponse;
import lombok.Builder;
import lombok.Data;

/**
 * One read model for the Quick Quote contact probe.
 *
 * <p>The create-lead screen needs two answers after a phone/email is typed: whether an enquiry
 * already exists and whether the person is an existing customer. Returning them together removes
 * a second browser round-trip while retaining the Lead and Customer modules as the owners of their
 * matching rules.</p>
 */
@Data
@Builder
public class QuickQuoteContactResponseDto {
    private LeadResponseDto lead;
    private CustomerMatchResponse customer;
}
