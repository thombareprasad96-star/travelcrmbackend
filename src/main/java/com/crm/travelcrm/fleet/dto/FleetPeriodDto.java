package com.crm.travelcrm.fleet.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One month of a fleet financial year, closed or not.
 *
 * <p>The list endpoint returns all twelve months of an Indian FY (April → March), not only the ones
 * somebody has closed — a year read at a glance is the point, and a sparse list makes "which months
 * are still open" a counting exercise.
 *
 * @param publicId       null when the month has never been closed
 * @param financialYear  Indian FY start year — 2026 means Apr 2026 → Mar 2027
 * @param month          calendar month, 1-12
 * @param monthName      for display, so the client does not localise a number
 * @param calendarYear   the actual year this month falls in (Jan-Mar of FY 2026 are 2027)
 * @param closed         currently locked
 * @param ended          the month is over, so it CAN be closed
 * @param unsettledCount open driver settlements in it — a close is refused while this is non-zero,
 *                       because locking the month makes the cash return that would square them
 *                       impossible to record
 */
public record FleetPeriodDto(
        UUID publicId,
        int financialYear,
        int month,
        String monthName,
        int calendarYear,
        boolean closed,
        boolean ended,
        LocalDateTime closedAt,
        String closedBy,
        long unsettledCount
) {
}
