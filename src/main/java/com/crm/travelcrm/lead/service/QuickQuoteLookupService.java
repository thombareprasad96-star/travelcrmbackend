package com.crm.travelcrm.lead.service;

import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.customer.dto.response.CustomerMatchResponse;
import com.crm.travelcrm.customer.service.CustomerService;
import com.crm.travelcrm.lead.dto.LeadResponseDto;
import com.crm.travelcrm.lead.dto.QuickQuoteContactResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Builds the contact read model used by the keyboard-first Quick Quote intake. */
@Service
@RequiredArgsConstructor
public class QuickQuoteLookupService {

    private final LeadService leadService;
    private final CustomerService customerService;

    @Transactional(readOnly = true)
    public QuickQuoteContactResponseDto lookup(String phone, String email) {
        String cleanPhone = StringUtils.hasText(phone) ? phone.trim() : null;
        String cleanEmail = StringUtils.hasText(email) ? email.trim().toLowerCase() : null;
        if (cleanPhone == null && cleanEmail == null) {
            throw new BusinessException("Enter a phone number or email address", HttpStatus.BAD_REQUEST);
        }

        LeadResponseDto lead = leadService.findOpenLeadForQuickQuote(cleanPhone, cleanEmail)
                .orElse(null);

        CustomerMatchResponse customer = customerService.lookup(cleanPhone, cleanEmail);
        return QuickQuoteContactResponseDto.builder()
                .lead(lead)
                .customer(customer)
                .build();
    }

}
