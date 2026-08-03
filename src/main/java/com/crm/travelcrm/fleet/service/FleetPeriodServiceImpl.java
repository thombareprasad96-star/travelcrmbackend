package com.crm.travelcrm.fleet.service;

import com.crm.travelcrm.common.context.TenantTimeZone;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.fleet.dto.FleetPeriodDto;
import com.crm.travelcrm.fleet.entity.FleetPeriodClose;
import com.crm.travelcrm.fleet.enums.FleetSettlementStatus;
import com.crm.travelcrm.fleet.money.FleetMoneyCalculator;
import com.crm.travelcrm.fleet.repository.FleetPeriodCloseRepository;
import com.crm.travelcrm.fleet.repository.FleetTripSettlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Closing and reopening a fleet accounting month.
 *
 * <p><b>This is the enforcement point for immutability.</b> A settlement's {@code LOCKED} status is a
 * property of one trip's row, which leaves two holes a trip-scoped lock can never cover: a brand-new
 * trip back-dated into a closed month gets a brand-new OPEN settlement, and an expense with no trip
 * at all — insurance, road tax, an off-duty challan, typically the largest non-fuel rows in the book
 * — has no settlement row to consult in the first place. So the lock is keyed on the PERIOD, and
 * every financial write checks its posting date against this table regardless of any trip.
 *
 * <p>It is also what makes {@link FleetSettlementStatus#LOCKED} reachable at all: {@code settle()}
 * stops at SETTLED, and closing the month is what freezes it beyond correction.
 *
 * <p>By financial year and month, closed by a human holding {@code FLEET_PERIOD_CLOSE} — deliberately
 * NOT a rolling day-count timer. Challans and pump bills routinely arrive 30-60 days late, so a
 * 7-day cut-off does not protect the books; it just means those costs are never recorded.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FleetPeriodServiceImpl implements FleetPeriodService {

    private final FleetPeriodCloseRepository periodRepository;
    private final FleetTripSettlementRepository settlementRepository;
    private final TenantTimeZone tenantTimeZone;

    @Override
    @Transactional(readOnly = true)
    public List<FleetPeriodDto> forFinancialYear(Integer financialYear) {
        Long tenantId = FleetContext.tenantId();
        LocalDate today = tenantTimeZone.todayFor(tenantId);
        int fy = financialYear != null ? financialYear : FleetMoneyCalculator.financialYearOf(today);

        List<FleetPeriodClose> closes =
                periodRepository.findByTenantIdAndFinancialYearAndDeletedAtIsNullOrderByPeriodMonthAsc(tenantId, fy);

        // The Indian FY runs April → March, so the twelve months of FY 2026 are Apr-2026 … Mar-2027.
        // Returning all twelve — closed or not — lets the UI render a year at a glance instead of
        // only the months somebody happens to have closed.
        List<FleetPeriodDto> out = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            int month = ((3 + i) % 12) + 1;                 // 4,5,…,12,1,2,3
            int year = month >= 4 ? fy : fy + 1;
            FleetPeriodClose close = closes.stream()
                    .filter(c -> c.getPeriodMonth().equals(month) && c.isActive())
                    .findFirst().orElse(null);

            LocalDate start = LocalDate.of(year, month, 1);
            boolean ended = !start.plusMonths(1).isAfter(today);

            out.add(new FleetPeriodDto(
                    close == null ? null : close.getPublicId(),
                    fy, month, Month.of(month).name(), year,
                    close != null,
                    ended,
                    close == null ? null : close.getClosedAt(),
                    close == null ? null : close.getClosedBy(),
                    unsettledIn(tenantId, start)));
        }
        return out;
    }

    @Override
    @Transactional
    public FleetPeriodDto close(int financialYear, int month) {
        Long tenantId = FleetContext.tenantId();
        LocalDate today = tenantTimeZone.todayFor(tenantId);

        if (month < 1 || month > 12) {
            throw new BusinessException("Month must be 1-12", HttpStatus.BAD_REQUEST);
        }
        int year = month >= 4 ? financialYear : financialYear + 1;
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate next = start.plusMonths(1);

        // A month that has not ended cannot be closed: entries for it are still legitimately arriving,
        // and locking it would refuse today's own receipts.
        if (next.isAfter(today)) {
            throw new BusinessException(
                    Month.of(month) + " " + year + " has not ended yet — a month is closed after it finishes",
                    HttpStatus.BAD_REQUEST);
        }
        periodRepository.findByTenantIdAndFinancialYearAndPeriodMonthAndDeletedAtIsNull(tenantId, financialYear, month)
                .filter(FleetPeriodClose::isActive)
                .ifPresent(c -> {
                    throw new BusinessException(
                            Month.of(month) + " " + year + " is already closed", HttpStatus.CONFLICT);
                });

        // Refuse while driver cash is unsquared. Closing here is a trap with no exit: the lock stops
        // every further entry, so the cash return that would have squared the sheet can never be
        // recorded, and the money stays permanently unaccounted.
        long unsettled = unsettledIn(tenantId, start);
        if (unsettled > 0) {
            throw new BusinessException(
                    unsettled + " trip settlement(s) in " + Month.of(month) + " " + year
                            + " are still open. Square the drivers' cash first — once the month is "
                            + "locked, the returns that would settle them can no longer be recorded.",
                    HttpStatus.CONFLICT);
        }

        FleetPeriodClose close = periodRepository.save(FleetPeriodClose.builder()
                .financialYear(financialYear)
                .periodMonth(month)
                .closedAt(tenantTimeZone.now())
                .closedBy(FleetContext.username())
                .build());

        // Freeze the signed sheets. This is the only place LOCKED is ever set.
        int locked = settlementRepository.reStatusInPeriod(tenantId,
                FleetSettlementStatus.SETTLED, FleetSettlementStatus.LOCKED,
                start.atStartOfDay(), next.atStartOfDay());

        log.info("Fleet period closed | FY{} {} | settlements locked: {} | by: {} | tenantId: {}",
                financialYear, Month.of(month), locked, close.getClosedBy(), tenantId);

        return new FleetPeriodDto(close.getPublicId(), financialYear, month, Month.of(month).name(),
                year, true, true, close.getClosedAt(), close.getClosedBy(), 0);
    }

    @Override
    @Transactional
    public FleetPeriodDto reopen(UUID publicId, String reason) {
        Long tenantId = FleetContext.tenantId();
        FleetPeriodClose close = periodRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Period not found: " + publicId));

        if (!close.isActive()) {
            throw new BusinessException("That period is already reopened", HttpStatus.CONFLICT);
        }
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException(
                    "Reopening a closed period needs a reason — it may already have been reported on",
                    HttpStatus.BAD_REQUEST);
        }

        // Recorded, never deleted. Reopening a filed month is a serious act and the audit trail
        // should show that it happened, not merely that the row is gone.
        close.setReopenedAt(tenantTimeZone.now());
        close.setReopenedBy(FleetContext.username());
        close.setReopenReason(reason.trim());
        periodRepository.save(close);

        int month = close.getPeriodMonth();
        int year = month >= 4 ? close.getFinancialYear() : close.getFinancialYear() + 1;
        LocalDate start = LocalDate.of(year, month, 1);

        int unlocked = settlementRepository.reStatusInPeriod(tenantId,
                FleetSettlementStatus.LOCKED, FleetSettlementStatus.SETTLED,
                start.atStartOfDay(), start.plusMonths(1).atStartOfDay());

        log.warn("Fleet period REOPENED | FY{} {} | settlements unlocked: {} | by: {} | reason: {}",
                close.getFinancialYear(), Month.of(month), unlocked, close.getReopenedBy(), reason);

        return new FleetPeriodDto(close.getPublicId(), close.getFinancialYear(), month,
                Month.of(month).name(), year, false, true, null, null,
                unsettledIn(tenantId, start));
    }

    private long unsettledIn(Long tenantId, LocalDate monthStart) {
        return settlementRepository.countUnsettledInPeriod(
                tenantId, monthStart.atStartOfDay(), monthStart.plusMonths(1).atStartOfDay());
    }
}
