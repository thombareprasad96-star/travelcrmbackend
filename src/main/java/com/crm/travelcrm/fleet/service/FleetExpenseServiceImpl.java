package com.crm.travelcrm.fleet.service;

import com.crm.travelcrm.common.context.TenantTimeZone;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.fleet.dto.FleetExpenseRequestDto;
import com.crm.travelcrm.fleet.dto.FleetExpenseResponseDto;
import com.crm.travelcrm.fleet.dto.FleetExpenseTypeDto;
import com.crm.travelcrm.fleet.entity.*;
import com.crm.travelcrm.fleet.enums.*;
import com.crm.travelcrm.fleet.mapper.FleetExpenseMapper;
import com.crm.travelcrm.fleet.money.FleetMoneyCalculator;
import com.crm.travelcrm.fleet.repository.*;
import com.crm.travelcrm.fleet.specification.FleetExpenseSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Fleet expense ledger. Every rupee that leaves for a vehicle passes through here.
 *
 * <p>Three rules shape this class, and each one is a defect that was caught before it shipped:
 * <ol>
 *   <li><b>The server owns all money.</b> The client sends an amount and a currency; the rate comes
 *       from the trip, the base amount is computed, the posting date is derived, the leg is
 *       resolved. Nothing financial is taken on trust from a request body.</li>
 *   <li><b>Immutability is keyed on the accounting period, not on a trip.</b> A trip-scoped lock
 *       leaves both back-dated new trips and trip-less rows — insurance, road tax, an off-duty
 *       challan, typically the largest non-fuel money in the book — permanently editable.</li>
 *   <li><b>Corrections are reversal rows, never edits or status flips.</b> A March receipt fixed in
 *       June keeps its March document date, so the vehicle's cost is corrected, and takes a June
 *       posting date, so a closed and filed period does not move.</li>
 * </ol>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FleetExpenseServiceImpl implements FleetExpenseService {

    private final FleetExpenseRepository expenseRepository;
    private final FleetVehicleRepository vehicleRepository;
    private final FleetTripRepository tripRepository;
    private final FleetDriverRepository driverRepository;
    private final FleetTripLegRepository legRepository;
    private final FleetPeriodCloseRepository periodCloseRepository;
    private final FleetTripSettlementRepository settlementRepository;
    private final FleetExpenseMapper mapper;
    private final TenantTimeZone tenantTimeZone;

    /** Types where the exact minute decides which leg — and therefore which driver — owns the cost. */
    private static final Set<FleetExpenseType> TIME_SENSITIVE =
            EnumSet.of(FleetExpenseType.TOLL, FleetExpenseType.PARKING, FleetExpenseType.CHALLAN);

    // ── Catalogue ───────────────────────────────────────────────────────────

    @Override
    public List<FleetExpenseTypeDto> expenseTypes() {
        return java.util.Arrays.stream(FleetExpenseType.values())
                .map(t -> new FleetExpenseTypeDto(
                        t.name(), t.label(), requiredFieldsFor(t),
                        t.isNepal() ? "NP" : null, t.gstLikely(), t.isSystemComputed()))
                .toList();
    }

    private List<String> requiredFieldsFor(FleetExpenseType type) {
        if (TIME_SENSITIVE.contains(type)) return List.of("documentTime");
        if (type == FleetExpenseType.OTHER) return List.of("description");
        return List.of();
    }

    // ── Commands ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public FleetExpenseResponseDto create(FleetExpenseRequestDto request) {
        Long tenantId = FleetContext.tenantId();

        FleetExpenseType type = request.getExpenseType();
        if (type.isSystemComputed()) {
            throw new BusinessException(
                    type.label() + " is calculated from the allowance policy at settlement — "
                            + "entering it by hand would count it twice",
                    HttpStatus.BAD_REQUEST);
        }

        FleetVehicle vehicle = resolveVehicle(request.getVehiclePublicId(), tenantId);
        FleetTrip trip = resolveTrip(request.getTripPublicId(), tenantId);
        FleetDriver driver = resolveDriver(request.getDriverPublicId(), tenantId);

        // A driver-paid row with no driver has no cash account to move, so it would vanish from the
        // imprest balance while still counting as cost. Reject rather than silently absorb it.
        if (request.getPaidBy() == FleetPaidBy.DRIVER_CASH && driver == null) {
            throw new BusinessException(
                    "Select the driver who paid — a cash spend has to come off someone's advance",
                    HttpStatus.BAD_REQUEST);
        }
        if (!request.isHasReceipt() && !StringUtils.hasText(request.getNoReceiptReason())) {
            throw new BusinessException(
                    "Say why there is no receipt — \"no receipt\" is a valid answer, an unexplained one is not",
                    HttpStatus.BAD_REQUEST);
        }
        if (TIME_SENSITIVE.contains(type) && request.getDocumentTime() == null && tripChangedHands(trip)) {
            throw new BusinessException(
                    "This trip changed vehicle or driver — enter the time on the receipt so the cost "
                            + "lands on the right one",
                    HttpStatus.BAD_REQUEST);
        }

        LocalDate documentDate = request.getDocumentDate();
        assertPeriodOpen(tenantId, documentDate);
        lockAndAssertAcceptsEntries(trip, driver);

        FleetExpense expense = mapper.toEntity(request);
        expense.setVehicle(vehicle);
        expense.setTrip(trip);
        expense.setDriver(driver);
        expense.setLeg(resolveLeg(trip, documentDate, request.getDocumentTime()));
        expense.setPostingDate(documentDate);          // create is always in-period; see assertPeriodOpen
        applyMoney(expense, request, trip);
        applyDefaults(expense, request);

        FleetExpense saved = expenseRepository.save(expense);
        log.info("Fleet expense created | id: {} | type: {} | base: {} | vehicle: {} | tenantId: {}",
                saved.getId(), type, saved.getBaseAmount(), vehicle.getVehicleNumber(), tenantId);
        return toDto(saved);
    }

    @Override
    @Transactional
    public FleetExpenseResponseDto update(UUID publicId, FleetExpenseRequestDto request) {
        FleetExpense expense = findOrThrow(publicId);
        Long tenantId = expense.getTenantId();
        lockSettlementFor(expense);

        if (!isEditable(expense)) {
            throw new BusinessException(
                    "This entry is settled or its period is closed — reverse it instead of editing, "
                            + "so the correction is dated and the original stays on record",
                    HttpStatus.CONFLICT);
        }
        if (expense.isReversal()) {
            throw new BusinessException("A reversal cannot be edited", HttpStatus.CONFLICT);
        }
        assertPeriodOpen(tenantId, request.getDocumentDate());

        mapper.updateEntity(request, expense);
        expense.setLeg(resolveLeg(expense.getTrip(), expense.getDocumentDate(), expense.getDocumentTime()));
        expense.setPostingDate(expense.getDocumentDate());
        applyMoney(expense, request, expense.getTrip());

        FleetExpense saved = expenseRepository.save(expense);
        log.info("Fleet expense updated | id: {} | tenantId: {}", saved.getId(), tenantId);
        return toDto(saved);
    }

    @Override
    @Transactional
    public FleetExpenseResponseDto reverse(UUID publicId, String reason) {
        FleetExpense original = findOrThrow(publicId);
        Long tenantId = original.getTenantId();

        if (original.isReversal()) {
            throw new BusinessException(
                    "This entry is already a reversal — reverse the original instead", HttpStatus.CONFLICT);
        }
        // Belt to the partial unique index's braces. Without both, a double-clicked Reverse inserts
        // two opposing rows and the vehicle's month goes negative.
        if (expenseRepository.existsByReversalOf_IdAndDeletedAtIsNull(original.getId())) {
            throw new BusinessException("This entry has already been reversed", HttpStatus.CONFLICT);
        }
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException("A reversal needs a reason", HttpStatus.BAD_REQUEST);
        }
        lockSettlementFor(original);

        // Document date follows the ORIGINAL so the vehicle's and trip's cost is genuinely corrected;
        // posting date is today so a closed period is never rewritten behind the accountant's back.
        LocalDate today = tenantTimeZone.todayFor(tenantId);
        FleetExpense reversal = FleetExpense.builder()
                .vehicle(original.getVehicle())
                .trip(original.getTrip())
                .leg(original.getLeg())
                .driver(original.getDriver())
                .expenseType(original.getExpenseType())
                .documentDate(original.getDocumentDate())
                .documentTime(original.getDocumentTime())
                .postingDate(openPostingDate(tenantId, today))
                .amount(original.getAmount())
                .currency(original.getCurrency())
                .fxRate(original.getFxRate())
                // Negative: the canonical aggregate is a naive SUM over every row, so the pair nets
                // to zero by arithmetic. No report needs to know reversals exist.
                .baseAmount(original.getBaseAmount().negate())
                .paidBy(original.getPaidBy())
                .hasReceipt(original.isHasReceipt())
                .noReceiptReason(original.getNoReceiptReason())
                .taxCharacter(original.getTaxCharacter())
                .itcEligible(original.isItcEligible())
                .reversalOf(original)
                .reversalReason(reason.trim())
                .description("Reversal of: " + safe(original.getDescription()))
                .enteredAt(tenantTimeZone.now())
                .enteredBy(FleetContext.username())
                .build();

        FleetExpense saved = expenseRepository.save(reversal);
        flagPostSettlementMovement(original);
        log.info("Fleet expense reversed | original: {} | reversal: {} | tenantId: {}",
                original.getId(), saved.getId(), tenantId);
        return toDto(saved);
    }

    @Override
    @Transactional
    public void delete(UUID publicId) {
        FleetExpense expense = findOrThrow(publicId);
        lockSettlementFor(expense);
        if (!isEditable(expense)) {
            throw new BusinessException(
                    "This entry is settled or its period is closed — reverse it instead of deleting",
                    HttpStatus.CONFLICT);
        }
        expense.softDelete(FleetContext.username());
        expenseRepository.save(expense);
        log.info("Fleet expense soft-deleted | id: {} | tenantId: {}", expense.getId(), expense.getTenantId());
    }

    // ── Queries ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public FleetExpenseResponseDto getByPublicId(UUID publicId) {
        return toDto(findOrThrow(publicId));
    }

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<FleetExpenseResponseDto> list(
            UUID vehiclePublicId, UUID tripPublicId, UUID driverPublicId,
            String type, String paidBy, LocalDate fromDate, LocalDate toDate,
            Boolean missingReceipt, String search, int page, int size) {

        Long tenantId = FleetContext.tenantId();
        var spec = FleetExpenseSpecification.build(tenantId, vehiclePublicId, tripPublicId, driverPublicId,
                parse(FleetExpenseType.class, type), parse(FleetPaidBy.class, paidBy),
                fromDate, toDate, missingReceipt, search);
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "documentDate").and(Sort.by(Sort.Direction.DESC, "id")));

        Page<FleetExpense> result = expenseRepository.findAll(spec, pageable);
        List<FleetExpenseResponseDto> data = result.map(this::toDto).getContent();
        return PagedApiResponse.of("Expenses fetched successfully", data, PaginationMeta.from(result));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private FleetExpense findOrThrow(UUID publicId) {
        return expenseRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, FleetContext.tenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Expense not found: " + publicId));
    }

    private FleetVehicle resolveVehicle(UUID publicId, Long tenantId) {
        return vehicleRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found: " + publicId));
    }

    private FleetTrip resolveTrip(UUID publicId, Long tenantId) {
        if (publicId == null) return null;
        return tripRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found: " + publicId));
    }

    private FleetDriver resolveDriver(UUID publicId, Long tenantId) {
        if (publicId == null) return null;
        return driverRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver not found: " + publicId));
    }

    /**
     * Which leg owns this cost. Resolved from the document instant against
     * {@code [start, end)} — half-open, so a handover instant belongs to exactly one leg.
     *
     * <p>Deliberately NOT defaulted from {@code trip.getVehicle()}: that pointer tracks the CURRENT
     * leg, so on a trip with a day-3 breakdown every late-arriving toll and challan would land on the
     * replacement vehicle and its driver — understating one vehicle's cost per km, overstating the
     * other's, and charging the wrong man for a fine. Null when nothing covers the instant; the
     * expense still records against the vehicle.
     */
    private FleetTripLeg resolveLeg(FleetTrip trip, LocalDate date, java.time.LocalTime time) {
        if (trip == null || date == null) return null;
        LocalDateTime instant = date.atTime(time != null ? time : java.time.LocalTime.NOON);
        return legRepository.findByTrip_IdAndDeletedAtIsNullOrderBySeqAsc(trip.getId()).stream()
                .filter(l -> l.covers(instant))
                .findFirst()
                .orElse(null);
    }

    /** True once a trip has more than one leg — i.e. vehicle or driver changed at some point. */
    private boolean tripChangedHands(FleetTrip trip) {
        return trip != null && legRepository.countByTrip_IdAndDeletedAtIsNull(trip.getId()) > 1;
    }

    private void applyMoney(FleetExpense expense, FleetExpenseRequestDto request, FleetTrip trip) {
        String currency = FleetMoneyCalculator.normaliseCurrency(request.getCurrency());
        BigDecimal rate = FleetMoneyCalculator.resolveRate(currency, trip == null ? null : trip.getFxRate());

        expense.setCurrency(currency);
        expense.setFxRate(rate);
        expense.setBaseAmount(FleetMoneyCalculator.toBase(request.getAmount(), rate));
    }

    private void applyDefaults(FleetExpense expense, FleetExpenseRequestDto request) {
        if (expense.getTaxCharacter() == null) {
            expense.setTaxCharacter(FleetTaxCharacter.defaultFor(request.getExpenseType()));
        }
        expense.setEnteredAt(tenantTimeZone.now());
        expense.setEnteredBy(FleetContext.username());
    }

    /** A new cost may never be dated into a period the tenant has already closed and reported. */
    private void assertPeriodOpen(Long tenantId, LocalDate date) {
        if (date == null) return;
        int fy = FleetMoneyCalculator.financialYearOf(date);
        if (periodCloseRepository.isClosed(tenantId, fy, date.getMonthValue())) {
            throw new BusinessException(
                    "That month is closed. Enter the cost with today's date, or ask an accountant to reopen "
                            + date.getMonth() + " " + date.getYear() + ".",
                    HttpStatus.CONFLICT);
        }
    }

    /** Today, unless today's own period is somehow closed — then the caller must reopen it explicitly. */
    private LocalDate openPostingDate(Long tenantId, LocalDate today) {
        assertPeriodOpen(tenantId, today);
        return today;
    }

    /**
     * Takes the trip's settlement row {@code FOR UPDATE} before any financial write, and refuses the
     * write when that sheet is locked.
     *
     * <p><b>Why a pessimistic lock and not {@code @Version}.</b> Optimistic locking only fires when
     * two writers touch the SAME row. The race that actually loses money touches two different
     * tables: a settle call reads this trip's expenses, sums 5,000 and computes that the driver owes
     * 1,800, while a clerk INSERTs a Rs 900 toll into {@code fleet_expenses}. Neither row version is
     * contended, no exception fires, and under READ COMMITTED the settle transaction never saw the
     * new row. The driver hands over 1,800, signs, and goes home — 900 short, on a signed sheet,
     * with every {@code @Version} present and working exactly as designed.
     *
     * <p>Serialising every expense write behind the settlement row closes that window, and also
     * makes the status check below safe: without the lock it is a read-then-write that two callers
     * can both pass.
     *
     * <p>No settlement row yet means no advance has been recorded, so there is nothing to race with —
     * {@code settle()} refuses to run at all in that state.
     */
    private void lockAndAssertAcceptsEntries(FleetTrip trip, FleetDriver driver) {
        if (trip == null || driver == null) return;
        settlementRepository.lockFor(trip.getId(), driver.getId())
                .ifPresent(settlement -> {
                    if (!settlement.getStatus().acceptsLateEntries()) {
                        throw new BusinessException(
                                "This trip's money is locked for " + driver.getName(), HttpStatus.CONFLICT);
                    }
                    if (!settlement.getStatus().isMutable()) {
                        // Allowed — late costs are normal — but the settled sheet and the driver's live
                        // balance now disagree, so put the trip back on someone's worklist rather than
                        // letting it silently report as squared.
                        settlement.setHasPostSettlementMovement(true);
                        settlementRepository.save(settlement);
                    }
                });
    }

    /** Same lock, for the paths that mutate an existing row rather than insert a new one. */
    private void lockSettlementFor(FleetExpense expense) {
        if (expense.getTrip() == null || expense.getDriver() == null) return;
        settlementRepository.lockFor(expense.getTrip().getId(), expense.getDriver().getId());
    }

    private void flagPostSettlementMovement(FleetExpense original) {
        if (original.getTrip() == null || original.getDriver() == null) return;
        settlementRepository
                .lockFor(original.getTrip().getId(), original.getDriver().getId())
                .ifPresent(settlement -> {
                    if (!settlement.getStatus().isMutable()) {
                        settlement.setHasPostSettlementMovement(true);
                        settlementRepository.save(settlement);
                    }
                });
    }

    /** Editable while its period is open AND its settlement (if any) has not been signed. */
    /**
     * Public because the FREEZE DEFINITION must have exactly one home: the attachment service asks
     * the same question ("is this row's money signed?") to decide whether evidence may still be
     * deleted. Duplicating these eight lines there is how the two answers drift apart.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean isEditable(FleetExpense expense) {
        LocalDate date = expense.getPostingDate();
        int fy = FleetMoneyCalculator.financialYearOf(date);
        if (periodCloseRepository.isClosed(expense.getTenantId(), fy, date.getMonthValue())) return false;
        if (expense.getTrip() == null || expense.getDriver() == null) return true;

        return settlementRepository
                .findByTrip_IdAndDriver_IdAndDeletedAtIsNull(expense.getTrip().getId(), expense.getDriver().getId())
                .map(s -> s.getStatus().isMutable())
                .orElse(true);
    }

    private FleetExpenseResponseDto toDto(FleetExpense expense) {
        FleetExpenseResponseDto dto = mapper.toDto(expense);
        if (expense.getTrip() != null) {
            dto.setTripRoute(route(expense.getTrip()));
        }
        dto.setReversed(expenseRepository.existsByReversalOf_IdAndDeletedAtIsNull(expense.getId()));
        dto.setEditable(isEditable(expense));
        return dto;
    }

    private String route(FleetTrip trip) {
        String from = safe(trip.getRouteFrom());
        String to = safe(trip.getRouteTo());
        if (from.isEmpty() && to.isEmpty()) return trip.getBookingCode();
        return (from + " → " + to).trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;   // an unknown filter value narrows nothing, same as the rest of this module
        }
    }
}
