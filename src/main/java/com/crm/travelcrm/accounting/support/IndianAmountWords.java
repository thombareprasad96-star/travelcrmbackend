package com.crm.travelcrm.accounting.support;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Renders a rupee amount in the Indian numbering system (lakh/crore) as words, e.g.
 * {@code "Rupees One Lakh Twenty Three Thousand Four Hundred Fifty and Fifty Paise Only"} — the
 * amount-in-words line required on a tax invoice.
 */
public final class IndianAmountWords {

    private IndianAmountWords() {}

    private static final String[] ONES = {
            "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
            "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen",
            "Eighteen", "Nineteen"
    };
    private static final String[] TENS = {
            "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"
    };

    public static String toWords(BigDecimal amount) {
        if (amount == null) amount = BigDecimal.ZERO;
        BigDecimal abs = amount.abs().setScale(2, RoundingMode.HALF_UP);
        long rupees = abs.longValue();
        int paise = abs.subtract(BigDecimal.valueOf(rupees)).movePointRight(2).intValue();

        StringBuilder sb = new StringBuilder();
        if (amount.signum() < 0) sb.append("Minus ");
        sb.append("Rupees ");
        sb.append(rupees == 0 ? "Zero" : rupeesToWords(rupees).trim());
        if (paise > 0) {
            sb.append(" and ").append(twoDigits(paise).trim()).append(" Paise");
        }
        sb.append(" Only");
        return sb.toString();
    }

    private static String rupeesToWords(long n) {
        StringBuilder sb = new StringBuilder();
        long crore = n / 10_000_000;      n %= 10_000_000;
        long lakh  = n / 100_000;         n %= 100_000;
        long thousand = n / 1_000;        n %= 1_000;
        long hundred = n / 100;           n %= 100;

        if (crore > 0)    sb.append(rupeesToWords(crore)).append(" Crore ");
        if (lakh > 0)     sb.append(rupeesToWords(lakh)).append(" Lakh ");
        if (thousand > 0) sb.append(rupeesToWords(thousand)).append(" Thousand ");
        if (hundred > 0)  sb.append(ONES[(int) hundred]).append(" Hundred ");
        if (n > 0) {
            if (sb.length() > 0) sb.append("and ");
            sb.append(twoDigits((int) n));
        }
        return sb.toString();
    }

    private static String twoDigits(int n) {
        if (n < 20) return ONES[n];
        return (TENS[n / 10] + " " + ONES[n % 10]).trim();
    }
}