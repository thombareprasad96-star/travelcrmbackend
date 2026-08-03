package com.crm.travelcrm.fleet.money;

import com.crm.travelcrm.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * The one place fleet money is converted and rounded. A pure function — no Spring, no database, no
 * clock — in the spirit of {@code ExpenseSettlementCalculator} and {@code CancellationCalculator}.
 *
 * <p><b>The rounding rule is HALF_UP at 2 decimal places, applied exactly once, at
 * {@code baseAmount} write time.</b> The superseded plan named a rule and never chose one; after a
 * month of NPR data that choice is unfixable, because every stored base amount would have to be
 * recomputed and no two reports would agree in the meantime.
 *
 * <p><b>The FX rate never comes from a client.</b> The plan's own sample request posted
 * {@code "fxRateToBase": 0.62500000} from a driver's phone on the same page that promised the server
 * does not trust client money. A rate is a commercial fact about a trip, set once by the office; the
 * driver enters NPR as NPR and never sees a rate at all. It is carried at {@code numeric(18,8)} —
 * at {@code numeric(14,2)} a rate of 0.625 stores as 0.63 and turns NPR 100,000 of Bhansar into
 * Rs 63,000 instead of Rs 62,500.
 *
 * <p><b>Aggregate over {@code baseAmount}; never re-convert a total.</b> Per-row HALF_UP and a
 * trip-level conversion do not agree — seven rows of NPR 1001 at 0.625 sum to Rs 4,379.41 per-row
 * against Rs 4,379.38 converted whole. Both are defensible; only one can be the definition, and it
 * is the stored per-row figure, because that is what the receipt line shows.
 */
public final class FleetMoneyCalculator {

    /** Matches {@code numeric(14,2)} on every fleet money column. */
    public static final int MONEY_SCALE = 2;

    /** Matches {@code numeric(18,8)} on the fx_rate columns. */
    public static final int RATE_SCALE = 8;

    public static final String BASE_CURRENCY = "INR";

    private FleetMoneyCalculator() {
    }

    /**
     * Converts an entered amount to base currency.
     *
     * @throws BusinessException when the amount is not positive, or the rate is not positive.
     *         A non-positive amount is rejected rather than normalised: a negative is how a
     *         correction is expressed (a reversal row), so silently accepting one here would give
     *         two different mechanisms for the same thing, and the reversal is the auditable one.
     */
    public static BigDecimal toBase(BigDecimal amount, BigDecimal fxRate) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Amount must be greater than 0", HttpStatus.BAD_REQUEST);
        }
        if (fxRate == null || fxRate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Exchange rate must be greater than 0", HttpStatus.BAD_REQUEST);
        }
        return amount.multiply(fxRate).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * The rate to use for a currency. Base-to-base is always exactly 1 — never a stored rate, so no
     * INR row can ever be scaled by a stale or mistyped figure.
     *
     * @param tripRate the rate the office set on the trip; required for any foreign currency
     */
    public static BigDecimal resolveRate(String currency, BigDecimal tripRate) {
        if (currency == null || currency.isBlank() || BASE_CURRENCY.equalsIgnoreCase(currency.trim())) {
            return BigDecimal.ONE;
        }
        if (tripRate == null || tripRate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(
                    "No exchange rate is set for this trip — set the " + currency.toUpperCase()
                            + " rate on the trip before entering " + currency.toUpperCase() + " costs",
                    HttpStatus.BAD_REQUEST);
        }
        return tripRate.setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Indian financial year containing {@code date} — 1 Apr to 31 Mar, named by its start year.
     * March 2027 is FY 2026; April 2027 is FY 2027. Used to key the period lock.
     */
    public static int financialYearOf(LocalDate date) {
        return date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
    }

    /** Normalises a currency code, defaulting to base. */
    public static String normaliseCurrency(String currency) {
        return (currency == null || currency.isBlank())
                ? BASE_CURRENCY
                : currency.trim().toUpperCase();
    }
}
