package com.crm.travelcrm.quotationtemplate.matching;

/**
 * One weighted dimension's contribution to a match, and the sentence the UI shows the agent when
 * they expand the badge. The point of the whole matcher is that a percentage is never a black box.
 *
 * @param key        stable machine key: {@code destination|duration|hotelTier|budget|season}
 * @param label      human label for the chip
 * @param weight     the configured weight, before renormalization
 * @param applicable false when the lead gives us nothing to score this on; the component is then
 *                   excluded from the total and its weight redistributed across the rest
 * @param score      0.0–1.0; meaningless when {@code !applicable}
 * @param detail     e.g. "3 of 4 cities matched" or "6 % over budget"
 */
public record ComponentScore(
        String key,
        String label,
        double weight,
        boolean applicable,
        double score,
        String detail
) {
    public static ComponentScore of(String key, String label, double weight, double score, String detail) {
        return new ComponentScore(key, label, weight, true, clamp(score), detail);
    }

    /** The lead carries no data for this dimension — say so rather than scoring it zero. */
    public static ComponentScore notApplicable(String key, String label, double weight, String reason) {
        return new ComponentScore(key, label, weight, false, 0.0, reason);
    }

    private static double clamp(double v) {
        if (Double.isNaN(v)) return 0.0;
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** 0–100, for the UI. */
    public int scorePercent() {
        return (int) Math.round(score * 100);
    }
}