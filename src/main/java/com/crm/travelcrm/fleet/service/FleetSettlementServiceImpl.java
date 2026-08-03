package com.crm.travelcrm.fleet.service;

import com.crm.travelcrm.common.context.TenantTimeZone;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.fleet.dto.FleetCashDirectionDto;
import com.crm.travelcrm.fleet.dto.FleetCashEntryRequestDto;
import com.crm.travelcrm.fleet.dto.FleetSettlementResponseDto;
import com.crm.travelcrm.fleet.dto.FleetSettlementSheetModel;
import com.crm.travelcrm.fleet.entity.*;
import com.crm.travelcrm.fleet.enums.FleetCashDirection;
import com.crm.travelcrm.fleet.enums.FleetSettlementStatus;
import com.crm.travelcrm.fleet.money.FleetMoneyCalculator;
import com.crm.travelcrm.fleet.money.FleetSettlementCalculator;
import com.crm.travelcrm.fleet.money.FleetSettlementCalculator.CashMovement;
import com.crm.travelcrm.fleet.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The driver cash loop: <em>peshgi</em> out, spend against it, cash back, signed <em>hisaab</em>.
 *
 * <h2>Why every write goes through a pessimistic lock</h2>
 * {@code @Version} only fires when two writers touch the SAME row. The race that actually loses
 * money touches two different tables: a settle call reads the expenses, sums 5,000 and computes that
 * the driver owes 1,800, while a clerk INSERTS a Rs 900 toll into {@code fleet_expenses}. Neither
 * row version is contended, no optimistic-lock exception fires, and under READ COMMITTED the settle
 * transaction never saw the new row. The driver hands over 1,800, signs, and goes home — 900 short,
 * on a signed sheet, with every {@code @Version} present and working exactly as designed.
 *
 * <p>So the settlement row is the aggregate root and the mutex: {@code lockFor()} takes it
 * {@code FOR UPDATE} before any financial write on that trip, and {@code settle()} re-derives every
 * total from the ledger INSIDE that lock rather than trusting the stored figures. That also
 * serialises the {@code isMutable()} status check, which is otherwise a read-then-write.
 *
 * <h2>Signing is only possible at zero</h2>
 * "A trip cannot be marked settled until the driver's cash is squared, and it stays visibly open
 * until it is" — the owner's words, and the single most valuable invariant in this module.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FleetSettlementServiceImpl implements FleetSettlementService {

    private final FleetTripSettlementRepository settlementRepository;
    private final FleetCashEntryRepository cashRepository;
    private final FleetExpenseRepository expenseRepository;
    private final FleetTripRepository tripRepository;
    private final FleetDriverRepository driverRepository;
    private final FleetTripLegRepository legRepository;
    private final FleetAllowancePolicyRepository allowanceRepository;
    private final FleetPeriodCloseRepository periodCloseRepository;
    private final TenantTimeZone tenantTimeZone;
    private final FleetPdfService pdfService;

    @Override
    public List<FleetCashDirectionDto> cashDirections() {
        return java.util.Arrays.stream(FleetCashDirection.values())
                .map(d -> new FleetCashDirectionDto(
                        d.name(), d.label(), d.signum(), d.requiresReason(), d.isCustomerMoney()))
                .toList();
    }

    // ── Cash movements ──────────────────────────────────────────────────────

    @Override
    @Transactional
    public FleetSettlementResponseDto recordCash(FleetCashEntryRequestDto request) {
        Long tenantId = FleetContext.tenantId();
        FleetDriver driver = resolveDriver(request.getDriverPublicId(), tenantId);
        FleetTrip trip = resolveTrip(request.getTripPublicId(), tenantId);
        FleetCashDirection direction = request.getDirection();

        if (direction.requiresReason() && !StringUtils.hasText(request.getReason())) {
            throw new BusinessException(
                    direction.label() + " needs a reason — an unexplained charge against a driver is "
                            + "what turns into a dispute six weeks later",
                    HttpStatus.BAD_REQUEST);
        }
        if (direction.isCustomerMoney() && !StringUtils.hasText(request.getPartyReference())) {
            throw new BusinessException(
                    "Say whose money this is (booking code or party name) — otherwise it cannot be "
                            + "matched to what it should be receipted against",
                    HttpStatus.BAD_REQUEST);
        }

        LocalDate entryDate = request.getEntryDate() != null
                ? request.getEntryDate() : tenantTimeZone.todayFor(tenantId);
        assertPeriodOpen(tenantId, entryDate);

        // Take the settlement lock BEFORE writing, so a concurrent settle cannot compute a total
        // that misses this row. Creating the settlement here is what makes a first advance open it.
        FleetTripSettlement settlement = (trip == null) ? null : lockOrCreate(trip, driver);
        if (settlement != null && !settlement.getStatus().acceptsLateEntries()) {
            throw new BusinessException(
                    "This trip's money is locked for " + driver.getName(), HttpStatus.CONFLICT);
        }

        String currency = FleetMoneyCalculator.normaliseCurrency(request.getCurrency());
        BigDecimal rate = FleetMoneyCalculator.resolveRate(currency, trip == null ? null : trip.getFxRate());

        FleetCashEntry entry = FleetCashEntry.builder()
                .driver(driver).trip(trip)
                .direction(direction)
                .amount(request.getAmount())
                .currency(currency).fxRate(rate)
                .baseAmount(FleetMoneyCalculator.toBase(request.getAmount(), rate))
                .entryDate(entryDate)
                .postingDate(entryDate)
                .reason(request.getReason())
                .referenceNumber(request.getReferenceNumber())
                .partyReference(request.getPartyReference())
                .notes(request.getNotes())
                .build();
        cashRepository.save(entry);

        log.info("Fleet cash | {} {} | driver: {} | tenantId: {}",
                direction, entry.getBaseAmount(), driver.getName(), tenantId);

        if (settlement == null) {
            // A movement with no trip (opening balance, general float) still moves the driver's
            // running balance; there is simply no per-trip sheet to recompute.
            return null;
        }
        return recompute(settlement);
    }

    // ── Settlement lifecycle ────────────────────────────────────────────────

    @Override
    @Transactional
    public FleetSettlementResponseDto reconcile(UUID tripPublicId, UUID driverPublicId) {
        FleetTripSettlement settlement = lockExisting(tripPublicId, driverPublicId);
        FleetSettlementResponseDto dto = recompute(settlement);

        if (!settlement.getStatus().isMutable()) {
            throw new BusinessException("This settlement is already signed", HttpStatus.CONFLICT);
        }
        settlement.setStatus(FleetSettlementStatus.RECONCILED);
        settlementRepository.save(settlement);
        return dto;
    }

    @Override
    @Transactional
    public FleetSettlementResponseDto settle(UUID tripPublicId, UUID driverPublicId, boolean driverAcknowledged) {
        FleetTripSettlement settlement = lockExisting(tripPublicId, driverPublicId);

        if (!settlement.getStatus().isMutable()) {
            throw new BusinessException("This settlement is already signed", HttpStatus.CONFLICT);
        }
        // Re-derive from the ledger INSIDE the lock. Never trust the stored totals — they may have
        // been computed before a row that landed a second ago.
        recompute(settlement);

        if (!settlement.isSquared()) {
            BigDecimal net = settlement.getNetDueFromDriver();
            String who = net.signum() > 0
                    ? "The driver still holds " + net.abs()
                    : "The company still owes the driver " + net.abs();
            throw new BusinessException(
                    who + ". Record the cash movement first — a trip cannot be signed off while money "
                            + "is still outstanding.",
                    HttpStatus.CONFLICT);
        }
        if (!driverAcknowledged) {
            throw new BusinessException(
                    "The driver has to acknowledge the sheet before it is signed off",
                    HttpStatus.BAD_REQUEST);
        }

        settlement.setStatus(FleetSettlementStatus.SETTLED);
        settlement.setSettledAt(tenantTimeZone.now());
        settlement.setSettledBy(FleetContext.username());
        settlement.setDriverAcknowledgedAt(tenantTimeZone.now());
        // Cleared here rather than on every read: if something moves AFTER this, the flag goes back
        // up and the trip returns to the unsquared worklist.
        settlement.setHasPostSettlementMovement(false);
        settlementRepository.save(settlement);

        log.info("Fleet settlement signed | trip: {} | driver: {} | tenantId: {}",
                settlement.getTrip().getId(), settlement.getDriver().getName(), settlement.getTenantId());
        return toDto(settlement);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FleetSettlementResponseDto> forTrip(UUID tripPublicId) {
        Long tenantId = FleetContext.tenantId();
        FleetTrip trip = tripRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(tripPublicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found: " + tripPublicId));
        return settlementRepository.findByTrip_IdAndDeletedAtIsNullOrderByIdAsc(trip.getId())
                .stream().map(this::toDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] settlementSheet(UUID tripPublicId, UUID driverPublicId) {
        Long tenantId = FleetContext.tenantId();
        FleetTrip trip = resolveTrip(tripPublicId, tenantId);
        FleetDriver driver = resolveDriver(driverPublicId, tenantId);

        FleetTripSettlement s = settlementRepository
                .findByTrip_IdAndDriver_IdAndDeletedAtIsNull(trip.getId(), driver.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No settlement for " + driver.getName() + " on this trip"));

        List<FleetSettlementSheetModel.CashLine> cashLines = cashRepository
                .findByTrip_IdAndDriver_IdAndDeletedAtIsNullOrderByEntryDateAscIdAsc(trip.getId(), driver.getId())
                .stream()
                .map(c -> FleetSettlementSheetModel.CashLine.builder()
                        .date(c.getEntryDate())
                        .direction(c.getDirection().label())
                        .signum(c.getDirection().signum())
                        .amount(c.getBaseAmount())
                        .reason(c.getReason())
                        .reference(c.getReferenceNumber())
                        .partyReference(c.getPartyReference())
                        .build())
                .toList();

        List<FleetSettlementSheetModel.SpendLine> spendLines = expenseRepository
                .findDriverCashSpend(tenantId, trip.getId(), driver.getId())
                .stream()
                .map(e -> FleetSettlementSheetModel.SpendLine.builder()
                        .date(e.getDocumentDate())
                        .type(e.getExpenseType().label())
                        .description(e.getDescription())
                        .amount(e.getBaseAmount())
                        .hasReceipt(e.isHasReceipt())
                        .build())
                .toList();

        FleetSettlementSheetModel model = FleetSettlementSheetModel.builder()
                .sheetNo(FleetTripServiceImpl.slipNo("SS", s.getPublicId()))
                .driverName(driver.getName())
                .driverPhone(driver.getPhone())
                .vehicleNumber(trip.getVehicle().getVehicleNumber())
                .jobReference(trip.getBookingCode())
                .routeFrom(trip.getRouteFrom())
                .routeTo(trip.getRouteTo())
                .tripStart(trip.getStartDatetime())
                .tripEnd(trip.getEndDatetime())
                .status(s.getStatus().name())
                .statusLabel(s.getStatus().label())
                .advanceTotal(s.getAdvanceTotal())
                .collectedTotal(s.getCollectedTotal())
                .returnedTotal(s.getReturnedTotal())
                .depositedTotal(s.getDepositedTotal())
                .adjustmentTotal(s.getAdjustmentTotal())
                .driverCashSpend(s.getDriverCashSpend())
                .allowanceTotal(s.getAllowanceTotal())
                .netDueFromDriver(s.getNetDueFromDriver())
                .squared(s.isSquared())
                // Anything not yet signed prints as DRAFT. A sheet signed while the status is still
                // mutable is a signature on figures that can legitimately still move.
                .draft(s.getStatus().isMutable())
                .postSettlementMovement(s.isHasPostSettlementMovement())
                .settledAt(s.getSettledAt())
                .settledBy(s.getSettledBy())
                .cashLines(cashLines)
                .spendLines(spendLines)
                .notes(s.getNotes())
                .generatedOn(tenantTimeZone.today())
                .build();

        return pdfService.renderSettlementSheet(model);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FleetSettlementResponseDto> openSettlements() {
        return settlementRepository.findByTenantIdAndStatusInAndDeletedAtIsNullOrderByIdDesc(
                        FleetContext.tenantId(),
                        List.of(FleetSettlementStatus.OPEN, FleetSettlementStatus.RECONCILED))
                .stream().map(this::toDto).toList();
    }

    // ── The arithmetic ──────────────────────────────────────────────────────

    /** Re-derives every total from the ledger and stores them. Always called under the row lock. */
    private FleetSettlementResponseDto recompute(FleetTripSettlement settlement) {
        Long tripId = settlement.getTrip().getId();
        Long driverId = settlement.getDriver().getId();
        Long tenantId = settlement.getTenantId();

        List<CashMovement> movements = cashRepository
                .findByTrip_IdAndDriver_IdAndDeletedAtIsNullOrderByEntryDateAscIdAsc(tripId, driverId)
                .stream()
                .map(c -> new CashMovement(c.getDirection(), c.getBaseAmount()))
                .toList();

        // Already excludes bata / night halt at the query level — see the repository. Doing it here
        // as well would be belt and braces; doing it in NEITHER place hands the driver his bata twice.
        BigDecimal spend = expenseRepository.sumDriverCashSpend(tenantId, tripId, driverId);
        BigDecimal allowance = computeAllowance(settlement.getTrip(), driverId);

        var result = FleetSettlementCalculator.settle(movements, spend, allowance);

        settlement.setAdvanceTotal(result.advanceTotal());
        settlement.setCollectedTotal(result.collectedTotal());
        settlement.setReturnedTotal(result.returnedTotal());
        settlement.setDepositedTotal(result.depositedTotal());
        settlement.setAdjustmentTotal(result.adjustmentTotal());
        settlement.setDriverCashSpend(result.driverCashSpend());
        settlement.setAllowanceTotal(result.allowanceTotal());
        settlement.setNetDueFromDriver(result.netDueFromDriver());
        settlementRepository.save(settlement);

        return toDto(settlement);
    }

    /**
     * Bata + night halt from the policy in force on the trip's START date — not today's policy, so a
     * rate rise in October cannot restate a June trip that was already signed.
     *
     * <p>Days are counted from this driver's OWN legs, not the whole trip: on a six-day tour handed
     * over on day three, each man is paid for the days he actually drove.
     */
    private BigDecimal computeAllowance(FleetTrip trip, Long driverId) {
        // BaseEntity.id is a primitive long, so this is == and not equals().
        long driverPk = driverId;
        List<FleetTripLeg> legs = legRepository.findByTrip_IdAndDeletedAtIsNullOrderBySeqAsc(trip.getId())
                .stream().filter(l -> l.getDriver().getId() == driverPk).toList();
        if (legs.isEmpty()) return BigDecimal.ZERO;

        String vehicleClass = legs.get(0).getVehicle().getType();
        LocalDate onDate = trip.getStartDatetime().toLocalDate();
        var policies = allowanceRepository.findEffective(trip.getTenantId(), vehicleClass, onDate);
        if (policies.isEmpty()) return BigDecimal.ZERO;   // no policy configured yet — pay nothing automatically
        FleetAllowancePolicy policy = policies.get(0);

        long days = 0;
        for (FleetTripLeg leg : legs) {
            LocalDateTime start = leg.getStartDatetime();
            LocalDateTime end = leg.getEndDatetime() != null ? leg.getEndDatetime() : start;
            long legDays = policy.isCountPartialDaysAsFull()
                    ? start.toLocalDate().datesUntil(end.toLocalDate().plusDays(1)).count()
                    : Math.max(1, Duration.between(start, end).toDays());
            days += legDays;
        }
        long nights = Math.max(0, days - 1);

        return policy.getBataPerDay().multiply(BigDecimal.valueOf(days))
                .add(policy.getNightHaltPerDay().multiply(BigDecimal.valueOf(nights)));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Takes the settlement row FOR UPDATE, creating it on first use (i.e. the first advance). */
    private FleetTripSettlement lockOrCreate(FleetTrip trip, FleetDriver driver) {
        return settlementRepository.lockFor(trip.getId(), driver.getId())
                .orElseGet(() -> settlementRepository.save(FleetTripSettlement.builder()
                        .trip(trip).driver(driver)
                        .status(FleetSettlementStatus.OPEN)
                        .build()));
    }

    private FleetTripSettlement lockExisting(UUID tripPublicId, UUID driverPublicId) {
        Long tenantId = FleetContext.tenantId();
        FleetTrip trip = tripRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(tripPublicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found: " + tripPublicId));
        FleetDriver driver = resolveDriver(driverPublicId, tenantId);
        return settlementRepository.lockFor(trip.getId(), driver.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No settlement for " + driver.getName() + " on this trip yet — "
                                + "it opens when the first advance is recorded"));
    }

    private FleetDriver resolveDriver(UUID publicId, Long tenantId) {
        return driverRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver not found: " + publicId));
    }

    private FleetTrip resolveTrip(UUID publicId, Long tenantId) {
        if (publicId == null) return null;
        return tripRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found: " + publicId));
    }

    private void assertPeriodOpen(Long tenantId, LocalDate date) {
        int fy = FleetMoneyCalculator.financialYearOf(date);
        if (periodCloseRepository.isClosed(tenantId, fy, date.getMonthValue())) {
            throw new BusinessException(
                    "That month is closed. Record it with today's date, or ask an accountant to reopen "
                            + date.getMonth() + " " + date.getYear() + ".",
                    HttpStatus.CONFLICT);
        }
    }

    private FleetSettlementResponseDto toDto(FleetTripSettlement s) {
        FleetSettlementResponseDto dto = new FleetSettlementResponseDto();
        dto.setPublicId(s.getPublicId());
        dto.setTripPublicId(s.getTrip().getPublicId());
        dto.setDriverPublicId(s.getDriver().getPublicId());
        dto.setDriverName(s.getDriver().getName());
        dto.setStatus(s.getStatus());
        dto.setStatusLabel(s.getStatus().label());
        dto.setAdvanceTotal(s.getAdvanceTotal());
        dto.setCollectedTotal(s.getCollectedTotal());
        dto.setReturnedTotal(s.getReturnedTotal());
        dto.setDepositedTotal(s.getDepositedTotal());
        dto.setAdjustmentTotal(s.getAdjustmentTotal());
        dto.setDriverCashSpend(s.getDriverCashSpend());
        dto.setAllowanceTotal(s.getAllowanceTotal());
        dto.setNetDueFromDriver(s.getNetDueFromDriver());
        dto.setSquared(s.isSquared());
        dto.setSettledAt(s.getSettledAt());
        dto.setSettledBy(s.getSettledBy());
        dto.setDriverAcknowledgedAt(s.getDriverAcknowledgedAt());
        dto.setHasPostSettlementMovement(s.isHasPostSettlementMovement());
        return dto;
    }
}
