package com.crm.travelcrm.quotationtemplate.matching;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rule-based similarity between a lead and a package template, over six weighted dimensions:
 * destination, duration, hotel tier, budget, season and services.
 *
 * <p><b>Pure by construction.</b> Every method takes records and returns records; the class holds
 * no repository and no entity. Its unit test constructs it with nothing but a {@link MatchWeights},
 * so any accidental database access would fail to compile rather than fail in production.
 *
 * <h2>Renormalization, not silent zeroes</h2>
 * A lead with no budget on file must not be docked the 20 points the budget dimension carries. So a
 * component whose inputs are missing is marked <i>not applicable</i>, excluded from the sum, and its
 * weight is redistributed across the rest — the percentage is always
 * {@code Σ(wᵢ·sᵢ) / Σ(wᵢ)} over the applicable components. When nothing applies at all, the score is
 * 0 rather than a division by zero.
 *
 * <p>That rule is also what makes new dimensions safe to add: a template authored before
 * {@code services} existed lists none, the dimension goes inapplicable, and its score is bit-for-bit
 * what it was before.
 *
 * <h2>One deliberate asymmetry</h2>
 * Missing data on the <i>template</i> side also yields "not applicable" — an agent who hasn't set a
 * base price shouldn't see their template penalised — <b>except for destination</b>, which scores a
 * hard 0. A package that goes nowhere covers no city the customer asked for, and that absence is
 * itself the answer, not a gap in our knowledge.
 */
@Component
public class TemplateScorer {

    static final String DESTINATION = "destination";
    static final String DURATION    = "duration";
    static final String HOTEL_TIER  = "hotelTier";
    static final String BUDGET      = "budget";
    static final String SEASON      = "season";
    static final String SERVICES    = "services";

    /** Coverage ("did it include what they asked for") dominates; Jaccard breaks ties on bloat. */
    private static final double COVERAGE_SHARE = 0.75;

    /** Stars run 1–5, so the widest possible tier gap is 4. */
    private static final double MAX_TIER_GAP = 4.0;

    private final MatchWeights weights;

    public TemplateScorer(MatchWeights weights) {
        this.weights = weights;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Public API
    // ════════════════════════════════════════════════════════════════════════

    /** Score one template against one lead. Never throws; missing inputs become non-applicable. */
    public MatchScore score(MatchInput in, TemplateProfile template) {
        List<ComponentScore> components = List.of(
                scoreDestination(in, template),
                scoreDuration(in, template),
                scoreHotelTier(in, template),
                scoreBudget(in, template),
                scoreSeason(in, template),
                scoreServices(in, template));
        return new MatchScore(renormalize(components), components);
    }

    /**
     * Score every template and order them so the same inputs always produce the same list — but
     * filter nothing. The caller decides what to do with the ones below
     * {@link MatchWeights#getMinScore()}: {@link #rank} drops them, the cold-start fallback offers
     * the best of them when nothing at all cleared the bar.
     */
    public List<RankedTemplate> rankAll(MatchInput in, Collection<TemplateProfile> templates) {
        if (templates == null || templates.isEmpty()) return List.of();
        List<RankedTemplate> ranked = new ArrayList<>(templates.size());
        for (TemplateProfile t : templates) {
            ranked.add(new RankedTemplate(t, score(in, t)));
        }
        ranked.sort(RANKING);
        return List.copyOf(ranked);
    }

    /**
     * Score every template, drop those under {@link MatchWeights#getMinScore()}, and order them so
     * the same inputs always produce the same list: percentage desc, then the template that has
     * actually been used more, then the one that could be scored on more dimensions, then name,
     * then id.
     */
    public List<RankedTemplate> rank(MatchInput in, Collection<TemplateProfile> templates) {
        int floor = weights.getMinScore();
        return rankAll(in, templates).stream()
                .filter(r -> r.score().percentage() >= floor)
                .toList();
    }

    /**
     * The highest percentage a template covering NONE of the requested cities can still reach —
     * {@code 100·(Σw − w_destination)/Σw} with every other dimension perfect and applicable.
     *
     * <p>This is the safety bound for any future SQL pre-filter on geography. Pre-selecting
     * candidates by city is only sound when {@link MatchWeights#getMinScore()} is <b>above</b> this
     * number. At the shipped weights (0.35 destination out of 1.10 total) it is <b>68</b>, far above
     * the threshold of 40 — so a city pre-filter would silently discard templates that were going to
     * be shown. See the note on {@code TemplateMatchServiceImpl.loadCandidates}.
     */
    public int maxScoreWithoutDestination() {
        double total = weight(weights.getDestination()) + weight(weights.getDuration())
                + weight(weights.getHotelTier()) + weight(weights.getBudget())
                + weight(weights.getSeason()) + weight(weights.getServices());
        if (total <= 0) return 0;
        return (int) Math.round(100.0 * (total - weight(weights.getDestination())) / total);
    }

    private static final Comparator<RankedTemplate> RANKING =
            Comparator.<RankedTemplate>comparingInt(r -> r.score().percentage()).reversed()
                    // Popularity breaks EXACT ties only — percentage is compared first, so this can
                    // never lift a worse match above a better one.
                    .thenComparing(Comparator.<RankedTemplate>comparingInt(r -> r.template().timesApplied()).reversed())
                    .thenComparing(Comparator.<RankedTemplate>comparingLong(r -> r.score().applicableCount()).reversed())
                    .thenComparing(r -> r.template().name(),
                            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                    .thenComparingLong(r -> r.template().id());

    // ════════════════════════════════════════════════════════════════════════
    //  Components
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Coverage of the lead's cities by the template's, blended with Jaccard so that between two
     * templates covering every requested city, the one carrying less unrequested baggage wins.
     *
     * <p>Coverage is <b>graded</b>: an exact city is worth 1, a different city in the same
     * destination {@link MatchWeights#getNearMissDestination()}, a different destination in the same
     * country {@link MatchWeights#getNearMissCountry()}. A Sikkim package should not read as a near
     * total miss for a Sikkim trip that happens to name different towns. Jaccard stays on strict
     * identity, so near-misses raise coverage without also disguising bloat.
     */
    private ComponentScore scoreDestination(MatchInput in, TemplateProfile t) {
        double w = weight(weights.getDestination());
        List<CityRef> wanted = distinctCities(in.cities());
        List<CityRef> offered = distinctCities(t.cities());

        if (wanted.isEmpty()) {
            return ComponentScore.notApplicable(DESTINATION, "Destination", w,
                    "Lead has no itinerary cities");
        }
        if (offered.isEmpty()) {
            return ComponentScore.of(DESTINATION, "Destination", w, 0.0,
                    "Package lists no cities");
        }

        double creditSum = 0.0;
        int exact = 0;
        int near = 0;
        for (CityRef c : wanted) {
            double credit = bestCredit(c, offered);
            creditSum += credit;
            if (credit >= 1.0) exact++;
            else if (credit > 0.0) near++;
        }
        long offeredHits = offered.stream().filter(c -> containsMatch(wanted, c)).count();

        // Matching is by id OR by name, so it is not necessarily a bijection. The smaller count is
        // the only safe size for the intersection.
        long intersection = Math.min(exact, offeredHits);
        long union = wanted.size() + offered.size() - intersection;

        double coverage = creditSum / wanted.size();
        double jaccard = union == 0 ? 0.0 : (double) intersection / union;
        double score = COVERAGE_SHARE * coverage + (1 - COVERAGE_SHARE) * jaccard;

        int extra = offered.size() - (int) offeredHits;
        StringBuilder detail = new StringBuilder()
                .append(exact).append(" of ").append(wanted.size()).append(" requested ")
                .append(wanted.size() == 1 ? "city" : "cities").append(" covered");
        if (near > 0) {
            detail.append(", ").append(near).append(" nearby");
        }
        if (extra > 0) {
            detail.append(", ").append(extra).append(" extra in package");
        }
        return ComponentScore.of(DESTINATION, "Destination", w, score, detail.toString());
    }

    /** Relative to what the customer asked for: a 1-night gap matters more on a 3-night trip. */
    private ComponentScore scoreDuration(MatchInput in, TemplateProfile t) {
        double w = weight(weights.getDuration());
        Integer wanted = in.nights();
        if (wanted == null || wanted <= 0) {
            return ComponentScore.notApplicable(DURATION, "Duration", w, "Lead itinerary has no nights");
        }
        Integer offered = t.nights();
        if (offered == null || offered <= 0) {
            return ComponentScore.notApplicable(DURATION, "Duration", w, "Package has no duration set");
        }
        int gap = Math.abs(wanted - offered);
        double score = 1.0 - (double) gap / wanted;
        String detail = gap == 0
                ? offered + " nights, exactly as requested"
                : offered + " nights vs " + wanted + " requested";
        return ComponentScore.of(DURATION, "Duration", w, score, detail);
    }

    /** The one dimension a {@code Lead} has no column for — the agent supplies it in the panel. */
    private ComponentScore scoreHotelTier(MatchInput in, TemplateProfile t) {
        double w = weight(weights.getHotelTier());
        Integer wanted = in.hotelTier();
        if (wanted == null) {
            return ComponentScore.notApplicable(HOTEL_TIER, "Hotel tier", w, "No star preference given");
        }
        Integer offered = t.hotelTier();
        if (offered == null) {
            return ComponentScore.notApplicable(HOTEL_TIER, "Hotel tier", w, "Package has no star tier set");
        }
        int a = clampTier(wanted);
        int b = clampTier(offered);
        double score = 1.0 - Math.abs(a - b) / MAX_TIER_GAP;
        String detail = a == b
                ? b + "-star, exactly as requested"
                : b + "-star vs " + a + "-star requested";
        return ComponentScore.of(HOTEL_TIER, "Hotel tier", w, score, detail);
    }

    /**
     * Asymmetric on purpose. A package under the customer's budget is a mild negative (probably
     * under-specced, still sellable); one over budget is a hard negative, because it is the single
     * most common reason a quotation is rejected. At the default slopes, 20 % under scores 0.90 and
     * 20 % over scores 0.70.
     */
    private ComponentScore scoreBudget(MatchInput in, TemplateProfile t) {
        double w = weight(weights.getBudget());
        BigDecimal budget = in.budget();
        if (budget == null || budget.signum() <= 0) {
            return ComponentScore.notApplicable(BUDGET, "Budget", w, "Lead has no budget");
        }
        BigDecimal price = t.basePrice();
        if (price == null || price.signum() <= 0) {
            return ComponentScore.notApplicable(BUDGET, "Budget", w, "Package has no base price");
        }

        double relative = (price.doubleValue() - budget.doubleValue()) / budget.doubleValue();
        double score = relative <= 0
                ? 1.0 - (-relative) * nonNegative(weights.getUnderBudgetPenalty())
                : 1.0 - relative * nonNegative(weights.getOverBudgetPenalty());

        long pct = Math.round(Math.abs(relative) * 100);
        String detail = pct == 0 ? "On budget"
                : relative < 0 ? pct + "% under budget"
                : pct + "% over budget";
        return ComponentScore.of(BUDGET, "Budget", w, score, detail);
    }

    /**
     * December and January are one month apart, not eleven — the distance is circular. A package
     * with no months declared is sold year-round and fits any date perfectly.
     */
    private ComponentScore scoreSeason(MatchInput in, TemplateProfile t) {
        double w = weight(weights.getSeason());
        Integer month = in.travelMonth();
        if (month == null || month < 1 || month > 12) {
            return ComponentScore.notApplicable(SEASON, "Season", w, "Lead has no travel date");
        }
        if (t.seasonMonths().isEmpty()) {
            return ComponentScore.of(SEASON, "Season", w, 1.0, "Sold year-round");
        }
        if (t.seasonMonths().contains(month)) {
            return ComponentScore.of(SEASON, "Season", w, 1.0, "In season (" + monthName(month) + ")");
        }

        int nearest = 6; // the largest possible circular distance between two months
        for (Integer m : t.seasonMonths()) {
            if (m == null || m < 1 || m > 12) continue;
            nearest = Math.min(nearest, circularMonthDistance(month, m));
        }
        double tolerance = Math.max(1e-9, nonNegative(weights.getSeasonToleranceMonths()));
        double score = 1.0 - nearest / tolerance;
        String detail = monthName(month) + " is " + nearest + (nearest == 1 ? " month" : " months")
                + " outside the season window";
        return ComponentScore.of(SEASON, "Season", w, score, detail);
    }

    /**
     * Does the package cover what the customer actually asked for? Importance-weighted, because a
     * package missing the flights is far more wrong than one missing the visa assistance, and a
     * plain set overlap cannot say so.
     *
     * <p>Asymmetric like destination: the denominator is what was <i>requested</i>, so extra
     * services the package throws in are free. Both sides arrive already normalised to the section
     * vocabulary by the service layer — the scorer only lower-cases and de-duplicates, so it stays
     * ignorant of the domain and testable with any strings.
     */
    private ComponentScore scoreServices(MatchInput in, TemplateProfile t) {
        double w = weight(weights.getServices());
        List<String> wanted = distinctServices(in.services());
        if (wanted.isEmpty()) {
            return ComponentScore.notApplicable(SERVICES, "Services", w, "Lead listed no services");
        }
        Set<String> offered = Set.copyOf(distinctServices(t.services()));
        if (offered.isEmpty()) {
            // "Not stated", not "covers nothing" — every template authored before this dimension
            // existed lists none, and must keep exactly the score it had.
            return ComponentScore.notApplicable(SERVICES, "Services", w,
                    "Package does not list its services");
        }

        int total = 0;
        int matched = 0;
        int matchedCount = 0;
        List<String> missing = new ArrayList<>();
        for (String s : wanted) {
            int importance = importanceOf(s);
            total += importance;
            if (offered.contains(s)) {
                matched += importance;
                matchedCount++;
            } else {
                missing.add(s);
            }
        }

        double score = total <= 0 ? 0.0 : (double) matched / total;
        String detail = missing.isEmpty()
                ? "all " + wanted.size() + " requested "
                        + (wanted.size() == 1 ? "service" : "services") + " included"
                : matchedCount + " of " + wanted.size() + " requested services — no "
                        + String.join(", ", missing);
        return ComponentScore.of(SERVICES, "Services", w, score, detail);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Weighted mean over the applicable components only. Returns 0 — not NaN — when every component
     * was inapplicable or every weight was configured to zero.
     */
    private static int renormalize(List<ComponentScore> components) {
        double weighted = 0.0;
        double totalWeight = 0.0;
        for (ComponentScore c : components) {
            if (!c.applicable()) continue;
            weighted += c.weight() * c.score();
            totalWeight += c.weight();
        }
        if (totalWeight <= 0) return 0;
        int pct = (int) Math.round(100.0 * weighted / totalWeight);
        return Math.max(0, Math.min(100, pct));
    }

    /** Best credit any offered city earns against this requested one: exact 1, then the ladder. */
    private double bestCredit(CityRef wanted, List<CityRef> offered) {
        double best = 0.0;
        for (CityRef o : offered) {
            double credit;
            if (wanted.matches(o)) {
                return 1.0;                       // nothing can beat an exact city
            } else if (wanted.sameDestinationAs(o)) {
                credit = clampUnit(weights.getNearMissDestination());
            } else if (wanted.sameCountryAs(o)) {
                credit = clampUnit(weights.getNearMissCountry());
            } else {
                continue;
            }
            if (credit > best) best = credit;
        }
        return best;
    }

    /** Distinct by canonical key, blanks dropped, order preserved for stable detail strings. */
    private static List<CityRef> distinctCities(List<CityRef> cities) {
        if (cities == null || cities.isEmpty()) return List.of();
        Map<String, CityRef> byKey = new LinkedHashMap<>();
        for (CityRef c : cities) {
            if (c == null || c.isBlank()) continue;
            byKey.putIfAbsent(c.canonicalKey(), c);
        }
        return List.copyOf(byKey.values());
    }

    /** Lower-cased, trimmed, de-duplicated, order preserved. Blanks dropped. */
    private static List<String> distinctServices(List<String> services) {
        if (services == null || services.isEmpty()) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        for (String s : services) {
            if (s == null) continue;
            String key = s.trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty()) seen.add(key);
        }
        return List.copyOf(seen);
    }

    /** Configured importance, or the default for a service id nobody has declared. */
    private int importanceOf(String service) {
        Map<String, Integer> map = weights.getServiceImportance();
        Integer configured = map == null ? null : map.get(service);
        int value = configured != null ? configured : weights.getDefaultServiceImportance();
        return Math.max(0, value);
    }

    private static boolean containsMatch(List<CityRef> haystack, CityRef needle) {
        for (CityRef c : haystack) {
            if (needle.matches(c)) return true;
        }
        return false;
    }

    static int circularMonthDistance(int a, int b) {
        int raw = Math.abs(a - b);
        return Math.min(raw, 12 - raw);
    }

    private static String monthName(int month) {
        return Month.of(month).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    private static int clampTier(int tier) {
        return Math.max(1, Math.min(5, tier));
    }

    /** A negative weight in config would invert the dimension; treat it as "switched off". */
    private static double weight(double configured) {
        return nonNegative(configured);
    }

    private static double nonNegative(double v) {
        return Double.isNaN(v) || v < 0 ? 0.0 : v;
    }

    /** A near-miss credit above 1 would outrank an exact city; clamp rather than trust config. */
    private static double clampUnit(double v) {
        if (Double.isNaN(v)) return 0.0;
        return Math.max(0.0, Math.min(1.0, v));
    }
}
