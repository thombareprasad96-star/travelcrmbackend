package com.crm.travelcrm.accounting.support;

import java.time.LocalDate;

/**
 * The Indian financial year (1 April – 31 March) a date falls in. GST invoice serials reset each FY
 * (Rule 46(b)), so this is the discriminator that partitions every invoice-number series.
 *
 * <p>{@link #label()} is the human/DB form ("2026-27"); {@link #compact()} is the 4-digit form
 * ("2627") embedded in the printed number to keep it within the 16-char GST cap.
 */
public record FinancialYear(int startYear) {

    public static FinancialYear of(LocalDate date) {
        int y = date.getYear();
        // Jan–Mar belong to the FY that started the previous April.
        return new FinancialYear(date.getMonthValue() >= 4 ? y : y - 1);
    }

    public int endYear() {
        return startYear + 1;
    }

    /** e.g. {@code "2026-27"} — stored on the sequence row and the invoice. */
    public String label() {
        return startYear + "-" + String.format("%02d", endYear() % 100);
    }

    /** e.g. {@code "2627"} — printed inside the invoice number. */
    public String compact() {
        return String.format("%02d%02d", startYear % 100, endYear() % 100);
    }
}