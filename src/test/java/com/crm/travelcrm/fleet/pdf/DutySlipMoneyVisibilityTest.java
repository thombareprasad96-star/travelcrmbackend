package com.crm.travelcrm.fleet.pdf;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.context.TenantTimeZone;
import com.crm.travelcrm.fleet.dto.FleetDutySlipModel;
import com.crm.travelcrm.fleet.entity.FleetDriver;
import com.crm.travelcrm.fleet.entity.FleetExpense;
import com.crm.travelcrm.fleet.entity.FleetTrip;
import com.crm.travelcrm.fleet.entity.FleetVehicle;
import com.crm.travelcrm.fleet.enums.FleetExpenseType;
import com.crm.travelcrm.fleet.enums.FleetPaidBy;
import com.crm.travelcrm.fleet.enums.FleetTripStatus;
import com.crm.travelcrm.fleet.integration.spi.FleetJobReferencePort;
import com.crm.travelcrm.fleet.mapper.FleetTripMapper;
import com.crm.travelcrm.fleet.repository.FleetDriverRepository;
import com.crm.travelcrm.fleet.repository.FleetExpenseRepository;
import com.crm.travelcrm.fleet.repository.FleetTripRepository;
import com.crm.travelcrm.fleet.repository.FleetVehicleRepository;
import com.crm.travelcrm.fleet.service.FleetPdfService;
import com.crm.travelcrm.fleet.service.FleetTripLegManager;
import com.crm.travelcrm.fleet.service.FleetTripServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The duty slip must not leak cost structure to someone without the money grant.
 *
 * <p><b>Why this test exists.</b> The slip endpoint is deliberately gated on plain
 * {@code FLEET_READ}: printing it is the dispatcher's job, and putting it behind
 * {@code FLEET_MONEY_READ} would take the product's central document away from the person who
 * needs it at 5am. That makes the route gate insufficient on its own — the "for office use" cost
 * block has to be withheld INSIDE the response. Gating a route and forgetting what the route
 * returns is the shape of the leak, and it fails silently: the PDF looks fine to whoever generated
 * it, because they had the permission.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DutySlipMoneyVisibilityTest {

    private static final Long TENANT = 5L;
    private static final UUID TRIP_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock private FleetTripRepository tripRepository;
    @Mock private FleetVehicleRepository vehicleRepository;
    @Mock private FleetDriverRepository driverRepository;
    @Mock private FleetJobReferencePort jobReferencePort;
    @Mock private FleetExpenseRepository expenseRepository;
    @Mock private FleetTripLegManager legManager;
    @Mock private FleetTripMapper mapper;
    @Mock private TenantTimeZone tenantTimeZone;
    @Mock private FleetPdfService pdfService;

    private FleetTripServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FleetTripServiceImpl(
                tripRepository, vehicleRepository, driverRepository, jobReferencePort,
                expenseRepository, legManager, mapper, pdfService, tenantTimeZone);

        TenantContext.setTenantId(TENANT);
        when(tenantTimeZone.today()).thenReturn(LocalDate.of(2026, 4, 10));

        FleetVehicle vehicle = FleetVehicle.builder()
                .vehicleNumber("UK07 AB 1234").type("Innova Crysta").build();
        FleetDriver driver = FleetDriver.builder()
                .name("Ram Singh").phone("+91 90000 00000").licenseNumber("UK0720110001234").build();
        FleetTrip trip = FleetTrip.builder()
                .vehicle(vehicle).driver(driver)
                .status(FleetTripStatus.COMPLETED)
                .routeFrom("Dehradun").routeTo("Kedarnath")
                .startDatetime(LocalDateTime.of(2026, 4, 2, 6, 0))
                .endDatetime(LocalDateTime.of(2026, 4, 5, 18, 0))
                .build();
        trip.setTenantId(TENANT);
        trip.setPublicId(TRIP_ID);

        when(tripRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(TRIP_ID, TENANT))
                .thenReturn(Optional.of(trip));
        when(legManager.legsOf(trip)).thenReturn(List.of());

        FleetExpense toll = FleetExpense.builder()
                .expenseType(FleetExpenseType.TOLL)
                .paidBy(FleetPaidBy.DRIVER_CASH)
                .documentDate(LocalDate.of(2026, 4, 2))
                .baseAmount(new BigDecimal("640.00"))
                .description("Mohand plaza")
                .build();
        when(expenseRepository.findByTenantIdAndTrip_IdAndDeletedAtIsNullOrderByDocumentDateAscIdAsc(
                anyLong(), anyLong())).thenReturn(List.of(toll));
        when(expenseRepository.sumTripCost(anyLong(), anyLong())).thenReturn(new BigDecimal("640.00"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private void authenticateWith(String... authorities) {
        var granted = java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("someone", "n/a", granted));
    }

    private FleetDutySlipModel renderedModel() {
        service.dutySlip(TRIP_ID);
        ArgumentCaptor<FleetDutySlipModel> captor = ArgumentCaptor.forClass(FleetDutySlipModel.class);
        verify(pdfService).renderDutySlip(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a dispatcher with only FLEET_READ gets a slip with no costs on it at all")
    void dispatcherSeesNoCosts() {
        authenticateWith("FLEET_READ");

        FleetDutySlipModel model = renderedModel();

        assertThat(model.getExpenses()).isEmpty();
        assertThat(model.getExpenseTotal()).isNull();
        // Not merely blanked in the model — the figures are never read from the database either.
        verify(expenseRepository, never()).sumTripCost(anyLong(), anyLong());
        verify(expenseRepository, never())
                .findByTenantIdAndTrip_IdAndDeletedAtIsNullOrderByDocumentDateAscIdAsc(anyLong(), anyLong());

        // Everything the slip exists for is still there.
        assertThat(model.getVehicleNumber()).isEqualTo("UK07 AB 1234");
        assertThat(model.getDriverName()).isEqualTo("Ram Singh");
        assertThat(model.getSlipNo()).isEqualTo("DS-11111111");
    }

    @Test
    @DisplayName("an accountant with FLEET_MONEY_READ gets the office-use cost block")
    void moneyRoleSeesCosts() {
        authenticateWith("FLEET_READ", "FLEET_MONEY_READ");

        FleetDutySlipModel model = renderedModel();

        assertThat(model.getExpenses()).hasSize(1);
        assertThat(model.getExpenses().get(0).getAmount()).isEqualByComparingTo("640.00");
        assertThat(model.getExpenseTotal()).isEqualByComparingTo("640.00");
    }

    @Test
    @DisplayName("no authentication at all is treated as no money access, not as full access")
    void anonymousIsClosedNotOpen() {
        SecurityContextHolder.clearContext();

        FleetDutySlipModel model = renderedModel();

        assertThat(model.getExpenses()).isEmpty();
        assertThat(model.getExpenseTotal()).isNull();
    }

    @Test
    @DisplayName("the printed total is the canonical aggregate, not a sum over the printed lines")
    void totalComesFromTheCanonicalAggregate() {
        authenticateWith("FLEET_READ", "FLEET_MONEY_READ");
        // A reversal chain makes these two disagree in real data; the slip must follow sumTripCost,
        // which is the definition every other screen uses.
        when(expenseRepository.sumTripCost(anyLong(), anyLong())).thenReturn(new BigDecimal("0.00"));

        FleetDutySlipModel model = renderedModel();

        assertThat(model.getExpenses()).hasSize(1);
        assertThat(model.getExpenseTotal()).isEqualByComparingTo("0.00");
        verify(expenseRepository).sumTripCost(anyLong(), anyLong());
    }

    @Test
    @DisplayName("the slip renders for a trip whose legs are unavailable rather than failing")
    void noLegsIsFine() {
        authenticateWith("FLEET_READ");
        FleetDutySlipModel model = renderedModel();
        assertThat(model.getLegs()).isEmpty();
        verify(pdfService).renderDutySlip(any());
    }
}
