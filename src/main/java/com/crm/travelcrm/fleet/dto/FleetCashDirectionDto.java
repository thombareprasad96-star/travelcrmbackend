package com.crm.travelcrm.fleet.dto;

/**
 * One entry of {@code GET /api/fleet/cash-directions} — the movement catalogue plus the metadata the
 * entry form needs to render itself.
 *
 * <p><b>Why the server owns this.</b> Same reason as {@code FleetExpenseTypeDto}: any alternative
 * ends with the frontend keeping its own copy of the enum, and that copy drifts. The lead module is
 * the cautionary tale in this codebase — its stage strings are duplicated across four frontend files
 * with three different memberships, one containing a value that never existed in the backend enum.
 * Here the drift would be worse than cosmetic: {@code signum} is what decides whether a movement
 * increases or decreases what a driver owes.
 *
 * @param code            enum name — the wire value
 * @param label           human-readable, for the dropdown
 * @param signum          +1 increases what the driver owes the company, -1 discharges it
 * @param requiresReason  true for RECOVERY and both ADJUSTMENTs — charging a driver or writing off a
 *                        shortfall without a recorded reason is what turns into a dispute later
 * @param customerMoney   true when the rupees belong to a customer rather than the company float, so
 *                        the form asks whose money it is and the settlement sheet keeps it separate
 */
public record FleetCashDirectionDto(
        String code,
        String label,
        int signum,
        boolean requiresReason,
        boolean customerMoney
) {
}
