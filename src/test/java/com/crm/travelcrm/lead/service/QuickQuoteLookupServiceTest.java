package com.crm.travelcrm.lead.service;

import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.customer.dto.response.CustomerMatchResponse;
import com.crm.travelcrm.customer.service.CustomerService;
import com.crm.travelcrm.lead.dto.LeadResponseDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuickQuoteLookupServiceTest {

    @Mock
    private LeadService leadService;

    @Mock
    private CustomerService customerService;

    @InjectMocks
    private QuickQuoteLookupService service;

    @Test
    void returnsOpenLeadAndCustomerInOneReadModel() {
        LeadResponseDto lead = LeadResponseDto.builder()
                .id(UUID.randomUUID())
                .customerName("Asha Mehta")
                .build();
        CustomerMatchResponse customer = CustomerMatchResponse.builder()
                .matched(true)
                .name("Asha Mehta")
                .build();
        when(leadService.findOpenLeadForQuickQuote("+91 98765 43210", "asha@example.com"))
                .thenReturn(Optional.of(lead));
        when(customerService.lookup("+91 98765 43210", "asha@example.com"))
                .thenReturn(customer);

        var result = service.lookup("  +91 98765 43210  ", " ASHA@EXAMPLE.COM ");

        assertThat(result.getLead()).isSameAs(lead);
        assertThat(result.getCustomer()).isSameAs(customer);
        verify(leadService).findOpenLeadForQuickQuote("+91 98765 43210", "asha@example.com");
    }

    @Test
    void aNewContactReturnsAnEmptyLeadWithoutTurningTheMissIntoAnError() {
        when(leadService.findOpenLeadForQuickQuote("9876543210", null))
                .thenReturn(Optional.empty());
        when(customerService.lookup("9876543210", null))
                .thenReturn(CustomerMatchResponse.noMatch());

        var result = service.lookup("9876543210", null);

        assertThat(result.getLead()).isNull();
        assertThat(result.getCustomer().isMatched()).isFalse();
    }

    @Test
    void rejectsAProbeWithoutEitherContactIdentifier() {
        assertThatThrownBy(() -> service.lookup("  ", null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Enter a phone number or email address");
    }
}
