package com.crm.travelcrm.booking.service;

import com.crm.travelcrm.accounting.settings.service.AccountingSettingsService;
import com.crm.travelcrm.booking.assignment.BookingAssigneeResolver;
import com.crm.travelcrm.booking.assignment.BookingAssigneeView;
import com.crm.travelcrm.booking.assignment.BookingAssigneeViewFactory;
import com.crm.travelcrm.booking.cancellation.repository.BookingCancellationRepository;
import com.crm.travelcrm.booking.cancellation.service.CancellationCalculator;
import com.crm.travelcrm.booking.cancellation.service.CancellationDocumentService;
import com.crm.travelcrm.booking.cancellation.service.CancellationPolicyResolver;
import com.crm.travelcrm.booking.dto.request.CreateBookingRequestDTO;
import com.crm.travelcrm.booking.dto.response.BookingResponseDTO;
import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.mapper.BookingMapper;
import com.crm.travelcrm.booking.repository.BookingPaymentRepository;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.booking.util.BookingCodeGenerator;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.customer.dto.request.CustomerResolveRequest;
import com.crm.travelcrm.customer.entity.Customer;
import com.crm.travelcrm.customer.repository.CustomerRepository;
import com.crm.travelcrm.customer.service.CustomerMatcher;
import com.crm.travelcrm.customer.service.CustomerService;
import com.crm.travelcrm.customer.util.CustomerCodeGenerator;
import com.crm.travelcrm.lead.repository.LeadRepository;
import com.crm.travelcrm.lead.service.LeadAccessGuard;
import com.crm.travelcrm.permission.service.SubAgentScope;
import com.crm.travelcrm.quotation.repository.QuotationRepository;
import com.crm.travelcrm.subagent.service.SubAgentCommissionService;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Regression coverage for the direct create-booking path used by CreateBookingClean.jsx. */
@ExtendWith(MockitoExtension.class)
class BookingCreateServiceTest {

    private static final long TENANT_ID = 17L;

    @Mock private BookingRepository bookingRepository;
    @Mock private BookingPaymentRepository paymentRepository;
    @Mock private BookingMapper bookingMapper;
    @Mock private BookingCodeGenerator bookingCodeGenerator;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private CustomerRepository customerRepository;
    @Mock private CustomerCodeGenerator customerCodeGenerator;
    @Mock private CustomerService customerService;
    @Mock private CustomerMatcher customerMatcher;
    @Mock private LeadRepository leadRepository;
    @Mock private LeadAccessGuard leadAccessGuard;
    @Mock private QuotationRepository quotationRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private CancellationPolicyResolver policyResolver;
    @Mock private CancellationCalculator cancellationCalculator;
    @Mock private BookingCancellationRepository cancellationRepository;
    @Mock private CancellationDocumentService cancellationDocumentService;
    @Mock private SubAgentScope subAgentScope;
    @Mock private SubAgentCommissionService commissionService;
    @Mock private BookingProfitService profitService;
    @Spy private BookingTaxCalculator bookingTaxCalculator = new BookingTaxCalculator();
    @Mock private AccountingSettingsService accountingSettings;
    @Mock private BookingAssigneeResolver assigneeResolver;
    @Mock private BookingAssigneeViewFactory assigneeViewFactory;

    @InjectMocks private BookingServiceImpl service;

    private CreateBookingRequestDTO request;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);

        request = new CreateBookingRequestDTO();
        request.setCustomer(new CustomerResolveRequest());
        request.setDestination("Goa");
        request.setTravelDate(LocalDate.now().plusDays(30));
        request.setCustomerAmount(new BigDecimal("10000.00"));
        request.setVendorCost(new BigDecimal("6500.00"));
        request.setServices(List.of("Hotel", "Flight"));

        Booking mapped = new Booking();
        mapped.setTravelDate(request.getTravelDate());
        when(bookingMapper.toEntity(request)).thenReturn(mapped);
        when(bookingCodeGenerator.generate(TENANT_ID)).thenReturn("BKG-26-0001");
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.empty());

        Customer customer = new Customer();
        customer.setId(41L);
        customer.setPublicId(UUID.randomUUID());
        customer.setCustomerCode("CUS-0001");
        customer.setName("Asha Sharma");
        when(customerService.resolveOrCreate(any(CustomerResolveRequest.class), isNull()))
                .thenReturn(customer);

        // lenient() from here down: these four stub the HAPPY path, and the tests that assert a
        // validation failure — an opening payment above the server-computed total, say — throw
        // before any of them is reached. Strict stubs would fail those tests for not persisting a
        // booking they are specifically asserting must never be persisted.
        lenient().when(policyResolver.resolveForNewBooking(any(), any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> {
            Booking booking = invocation.getArgument(0);
            booking.setId(99L);
            booking.setPublicId(UUID.randomUUID());
            return booking;
        });
        lenient().when(assigneeViewFactory.of(any(Booking.class))).thenReturn(BookingAssigneeView.empty());
        lenient().when(bookingMapper.toResponse(any(Booking.class), any(BookingAssigneeView.class)))
                .thenReturn(BookingResponseDTO.builder().build());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void omittedBookingDateDefaultsToTodayAndSelectedServicesArePersisted() {
        service.create(request);

        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(saved.capture());
        assertThat(saved.getValue().getBookingDate()).isEqualTo(LocalDate.now());
        assertThat(saved.getValue().getServices()).containsExactly("Hotel", "Flight");
    }

    @Test
    void openingPaymentCannotExceedServerCalculatedTotalPayable() {
        request.setPaidAmount(new BigDecimal("20000.00"));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("exceeds total payable");
        verify(bookingRepository, never()).save(any());
        verify(paymentRepository, never()).save(any());
    }
}
