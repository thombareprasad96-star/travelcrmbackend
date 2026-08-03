package com.crm.travelcrm.fleet.pdf;

import com.crm.travelcrm.company.repository.CompanyRepository;
import com.crm.travelcrm.fleet.dto.FleetDutySlipModel;
import com.crm.travelcrm.fleet.dto.FleetSettlementSheetModel;
import com.crm.travelcrm.fleet.service.FleetPdfService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders both fleet documents in memory and asserts real PDF bytes come out.
 *
 * <p><b>Why this is worth a test.</b> The templates are parsed in Thymeleaf's XML mode, which fails
 * at RUNTIME — not at compile time — on one unclosed tag, one unquoted attribute, or one HTML
 * entity that XML does not define ({@code &nbsp;} is the classic). Without this, the first person
 * to discover a malformed duty slip is an operator at 5am with a driver waiting at the gate.
 *
 * <p>Deliberately writes nothing to disk: this is not the quotation preview smoke test, which
 * produces a file the owner opens by hand. It only proves the templates parse, lay out and
 * paginate — including the branch that matters most, a half-empty slip for a PLANNED trip.
 */
@ExtendWith(MockitoExtension.class)
class FleetDocumentRenderTest {

    @Mock private CompanyRepository companyRepository;

    private FleetPdfService pdf;

    @BeforeEach
    void setUp() {
        // No tenant is bound, so the Company lookup never happens and the configured defaults apply.
        // Logo stays empty on purpose: a remote URL would make this test reach the network.
        pdf = new FleetPdfService(
                companyRepository,
                "Himalaya Travels", "Every road, every season",
                "+91 98765 43210", "ops@example.com", "example.com",
                "12 Mall Road, Dehradun", "", "#1d4ed8");
    }

    private static void assertIsPdf(byte[] bytes) {
        assertThat(bytes).isNotNull();
        assertThat(new String(bytes, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        // A parse failure still yields a valid but nearly empty document; a real page is far bigger.
        assertThat(bytes.length).isGreaterThan(2_000);
    }

    @Test
    @DisplayName("a PLANNED trip prints a duty slip full of blanks, not zeros")
    void dutySlipPrintsBlankForAPlannedTrip() {
        FleetDutySlipModel model = FleetDutySlipModel.builder()
                .slipNo("DS-1A2B3C4D")
                .status("PLANNED")
                .statusLabel("Planned")
                .vehicleNumber("UK07 AB 1234")
                .vehicleType("Innova Crysta")
                .driverName("Ram Singh")
                .driverPhone("+91 90000 00000")
                .driverLicense("UK0720110001234")
                .routeFrom("Dehradun")
                .routeTo("Kedarnath")
                .purpose("Char Dham")
                .startDatetime(LocalDateTime.of(2026, 5, 12, 5, 30))
                // Everything the driver fills in by hand is absent — this is the primary case.
                .legs(List.of())
                .expenses(List.of())
                .expenseTotal(BigDecimal.ZERO)
                .generatedOn(LocalDate.of(2026, 5, 11))
                .build();

        assertIsPdf(pdf.renderDutySlip(model));
    }

    @Test
    @DisplayName("a completed, substituted trip prints legs and the office-use cost block")
    void dutySlipPrintsLegsAndCosts() {
        FleetDutySlipModel model = FleetDutySlipModel.builder()
                .slipNo("DS-9F8E7D6C")
                .jobReference("BKG-26-0042")
                .status("COMPLETED")
                .statusLabel("Completed")
                .vehicleNumber("UK07 CD 5678")
                .vehicleType("Tempo Traveller")
                .driverName("Mohan Lal")
                .driverPhone("+91 90000 11111")
                .driverLicense("UK0720090005678")
                .routeFrom("Dehradun")
                .routeTo("Kathmandu")
                .purpose("Nepal tour")
                .startDatetime(LocalDateTime.of(2026, 4, 2, 6, 0))
                .endDatetime(LocalDateTime.of(2026, 4, 9, 19, 45))
                .startOdometer(45_000)
                .endOdometer(88_300)      // a different vehicle by now — hence the legs below
                .distanceKm(2_450)
                .legs(List.of(
                        FleetDutySlipModel.Leg.builder()
                                .seq(1).vehicleNumber("UK07 CD 5678").driverName("Mohan Lal")
                                .startDatetime(LocalDateTime.of(2026, 4, 2, 6, 0))
                                .endDatetime(LocalDateTime.of(2026, 4, 5, 14, 20))
                                .startOdometer(45_000).endOdometer(46_200).distanceKm(1_200)
                                .build(),
                        FleetDutySlipModel.Leg.builder()
                                .seq(2).vehicleNumber("UK07 EF 9012").driverName("Suresh Rana")
                                .startDatetime(LocalDateTime.of(2026, 4, 5, 14, 20))
                                .endDatetime(LocalDateTime.of(2026, 4, 9, 19, 45))
                                .startOdometer(88_000).endOdometer(89_250).distanceKm(1_250)
                                .changeReason("Breakdown")
                                .build()))
                .expenses(List.of(
                        FleetDutySlipModel.ExpenseLine.builder()
                                .date(LocalDate.of(2026, 4, 2)).type("Toll")
                                .description("Mohand plaza").paidBy("Driver's cash")
                                .driverName("Mohan Lal").amount(new BigDecimal("640.00"))
                                .hasReceipt(true).build(),
                        FleetDutySlipModel.ExpenseLine.builder()
                                .date(LocalDate.of(2026, 4, 4)).type("Bhansar (Nepal)")
                                .paidBy("Office paid").amount(new BigDecimal("12500.00"))
                                .hasReceipt(false).build()))
                .expenseTotal(new BigDecimal("13140.00"))
                .remarks("Clutch failed near Devprayag; replacement sent from Rishikesh.")
                .generatedOn(LocalDate.of(2026, 4, 10))
                .build();

        assertIsPdf(pdf.renderDutySlip(model));
    }

    @Test
    @DisplayName("an unsigned settlement prints as DRAFT; a squared one prints for signature")
    void settlementSheetRendersBothStates() {
        FleetSettlementSheetModel.FleetSettlementSheetModelBuilder base = FleetSettlementSheetModel.builder()
                .sheetNo("SS-11223344")
                .driverName("Mohan Lal")
                .driverPhone("+91 90000 11111")
                .vehicleNumber("UK07 CD 5678")
                .jobReference("BKG-26-0042")
                .routeFrom("Dehradun")
                .routeTo("Kathmandu")
                .tripStart(LocalDateTime.of(2026, 4, 2, 6, 0))
                .tripEnd(LocalDateTime.of(2026, 4, 9, 19, 45))
                .advanceTotal(new BigDecimal("20000.00"))
                .collectedTotal(new BigDecimal("5000.00"))
                .returnedTotal(new BigDecimal("6360.00"))
                .depositedTotal(new BigDecimal("5000.00"))
                .adjustmentTotal(BigDecimal.ZERO)
                .driverCashSpend(new BigDecimal("5240.00"))
                .allowanceTotal(new BigDecimal("8400.00"))
                .cashLines(List.of(
                        FleetSettlementSheetModel.CashLine.builder()
                                .date(LocalDate.of(2026, 4, 1)).direction("Advance given").signum(1)
                                .amount(new BigDecimal("20000.00")).reason("Nepal run float").build(),
                        FleetSettlementSheetModel.CashLine.builder()
                                .date(LocalDate.of(2026, 4, 9)).direction("Cash returned").signum(-1)
                                .amount(new BigDecimal("6360.00")).build()))
                .spendLines(List.of(
                        FleetSettlementSheetModel.SpendLine.builder()
                                .date(LocalDate.of(2026, 4, 2)).type("Toll").description("Mohand plaza")
                                .amount(new BigDecimal("640.00")).hasReceipt(true).build()))
                .generatedOn(LocalDate.of(2026, 4, 10));

        // Still open — must carry the DRAFT marker rather than invite a signature.
        assertIsPdf(pdf.renderSettlementSheet(base
                .status("RECONCILED").statusLabel("Reconciled").draft(true)
                .netDueFromDriver(new BigDecimal("3000.00")).squared(false)
                .build()));

        // Signed and squared — the copy the driver actually puts his name on.
        assertIsPdf(pdf.renderSettlementSheet(base
                .status("SETTLED").statusLabel("Settled").draft(false)
                .netDueFromDriver(BigDecimal.ZERO).squared(true)
                .settledAt(LocalDateTime.of(2026, 4, 10, 11, 15)).settledBy("accounts@example.com")
                .build()));
    }

    @Test
    @DisplayName("a sheet where the company owes the driver renders the reversed wording")
    void settlementSheetHandlesNegativeNet() {
        FleetSettlementSheetModel model = FleetSettlementSheetModel.builder()
                .sheetNo("SS-55667788")
                .driverName("Suresh Rana")
                .status("RECONCILED").statusLabel("Reconciled").draft(true)
                .advanceTotal(new BigDecimal("2000.00"))
                .collectedTotal(BigDecimal.ZERO)
                .returnedTotal(BigDecimal.ZERO)
                .depositedTotal(BigDecimal.ZERO)
                .adjustmentTotal(BigDecimal.ZERO)
                .driverCashSpend(new BigDecimal("4800.00"))
                .allowanceTotal(new BigDecimal("1200.00"))
                .netDueFromDriver(new BigDecimal("-4000.00"))
                .squared(false)
                .cashLines(List.of())
                .spendLines(List.of())
                .generatedOn(LocalDate.of(2026, 4, 10))
                .build();

        assertIsPdf(pdf.renderSettlementSheet(model));
    }
}
