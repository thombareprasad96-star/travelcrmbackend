package com.crm.travelcrm.quotationtemplate.matching;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scorer is a pure function, so this suite constructs it with nothing but a {@link MatchWeights}
 * — no repositories, no Spring context, no database. If someone later reaches for a repository
 * inside {@code TemplateScorer}, this file stops compiling, which is the point.
 */
class TemplateScorerTest {

    private final MatchWeights weights = new MatchWeights();
    private final TemplateScorer scorer = new TemplateScorer(weights);

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private static MatchInput.MatchInputBuilder lead() {
        return MatchInput.builder();
    }

    private static TemplateProfile.TemplateProfileBuilder template(String name) {
        return TemplateProfile.builder().id(1L).publicId(UUID.randomUUID()).name(name);
    }

    private static CityRef city(long id, String name) {
        return CityRef.of(id, name);
    }

    private ComponentScore component(MatchScore score, String key) {
        return score.components().stream()
                .filter(c -> c.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no component: " + key));
    }

    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("every score reports all five dimensions, applicable or not")
    void alwaysReportsFiveComponents() {
        MatchScore score = scorer.score(lead().build(), template("T").build());
        assertThat(score.components()).extracting(ComponentScore::key)
                .containsExactly("destination", "duration", "hotelTier", "budget", "season");
    }

    @Test
    @DisplayName("a template matching on all five dimensions scores 100")
    void perfectMatch() {
        MatchInput in = lead()
                .cities(List.of(city(1, "Delhi"), city(2, "Agra")))
                .nights(5).hotelTier(4).budget(new BigDecimal("100000")).travelMonth(10)
                .build();
        TemplateProfile t = template("Golden Triangle")
                .cities(List.of(city(1, "Delhi"), city(2, "Agra")))
                .nights(5).hotelTier(4).basePrice(new BigDecimal("100000")).seasonMonths(Set.of(10))
                .build();

        assertThat(scorer.score(in, t).percentage()).isEqualTo(100);
    }

    @Test
    @DisplayName("a lead with no data at all scores 0 rather than dividing by zero")
    void noApplicableComponentsScoresZeroNotNaN() {
        MatchScore score = scorer.score(lead().build(), template("T").nights(5).build());

        assertThat(score.percentage()).isZero();
        assertThat(score.components()).allMatch(c -> !c.applicable());
        assertThat(score.applicableCount()).isZero();
    }

    @Test
    @DisplayName("weights are renormalized: a lead with only cities is not docked for the rest")
    void renormalizesOverApplicableComponentsOnly() {
        MatchInput in = lead().cities(List.of(city(1, "Delhi"))).build();
        TemplateProfile t = template("T").cities(List.of(city(1, "Delhi"))).build();

        MatchScore score = scorer.score(in, t);

        // Destination is perfect and is the only applicable dimension, so the total is 100 —
        // not 35, which is what a naive Σ(w·s) would give.
        assertThat(score.percentage()).isEqualTo(100);
        assertThat(score.applicableCount()).isEqualTo(1);
    }

    @Nested
    @DisplayName("destination")
    class Destination {

        @Test
        @DisplayName("blends coverage with Jaccard, so extra cities cost a little")
        void partialCoverageWithBloat() {
            MatchInput in = lead()
                    .cities(List.of(city(1, "Delhi"), city(2, "Agra"), city(3, "Jaipur"), city(4, "Goa")))
                    .build();
            TemplateProfile t = template("T")
                    .cities(List.of(city(1, "Delhi"), city(2, "Agra"), city(3, "Jaipur"), city(9, "Shimla")))
                    .build();

            // coverage 3/4 = .75, jaccard 3/5 = .60  →  .75*.75 + .25*.60 = .7125
            MatchScore score = scorer.score(in, t);
            assertThat(score.percentage()).isEqualTo(71);
            assertThat(component(score, "destination").detail())
                    .isEqualTo("3 of 4 requested cities covered, 1 extra in package");
        }

        @Test
        @DisplayName("a package that lists no cities scores 0 — absence is the answer, not a gap")
        void templateWithNoCitiesScoresZeroAndStaysApplicable() {
            MatchInput in = lead().cities(List.of(city(1, "Delhi"))).build();
            MatchScore score = scorer.score(in, template("T").build());

            ComponentScore destination = component(score, "destination");
            assertThat(destination.applicable()).isTrue();
            assertThat(destination.scorePercent()).isZero();
            assertThat(score.percentage()).isZero();
        }

        @Test
        @DisplayName("an unresolved lead city still matches a template city by name")
        void fallsBackToNameWhenTheLeadCityHasNoMasterId() {
            MatchInput in = lead().cities(List.of(CityRef.of(null, "  DELHI "))).build();
            TemplateProfile t = template("T").cities(List.of(city(5, "delhi"))).build();

            assertThat(scorer.score(in, t).percentage()).isEqualTo(100);
        }

        @Test
        @DisplayName("a lead with no cities makes the dimension inapplicable, not zero")
        void leadWithoutCitiesIsNotApplicable() {
            MatchScore score = scorer.score(lead().build(), template("T").cities(List.of(city(1, "Delhi"))).build());
            assertThat(component(score, "destination").applicable()).isFalse();
        }
    }

    @Nested
    @DisplayName("duration")
    class Duration {

        @Test
        @DisplayName("a one-night gap costs more on a short trip than a long one")
        void scoredRelativeToWhatWasAsked() {
            MatchScore five = scorer.score(lead().nights(5).build(), template("T").nights(6).build());
            MatchScore twenty = scorer.score(lead().nights(20).build(), template("T").nights(21).build());

            assertThat(five.percentage()).isEqualTo(80);    // 1 - 1/5
            assertThat(twenty.percentage()).isEqualTo(95);  // 1 - 1/20
        }

        @Test
        @DisplayName("a package twice as long as requested scores 0, never negative")
        void clampsAtZero() {
            MatchScore score = scorer.score(lead().nights(5).build(), template("T").nights(15).build());
            assertThat(score.percentage()).isZero();
            assertThat(component(score, "duration").scorePercent()).isZero();
        }

        @Test
        @DisplayName("exact match says so")
        void exactDetail() {
            MatchScore score = scorer.score(lead().nights(5).build(), template("T").nights(5).build());
            assertThat(component(score, "duration").detail()).isEqualTo("5 nights, exactly as requested");
        }
    }

    @Nested
    @DisplayName("hotel tier")
    class HotelTier {

        @Test
        @DisplayName("one star of distance costs a quarter of the dimension")
        void linearOverTheFiveStarRange() {
            assertThat(scorer.score(lead().hotelTier(5).build(), template("T").hotelTier(4).build()).percentage())
                    .isEqualTo(75);
            assertThat(scorer.score(lead().hotelTier(5).build(), template("T").hotelTier(3).build()).percentage())
                    .isEqualTo(50);
            assertThat(scorer.score(lead().hotelTier(5).build(), template("T").hotelTier(1).build()).percentage())
                    .isZero();
        }

        @Test
        @DisplayName("no star preference means the dimension is skipped, not failed")
        void inapplicableWithoutAPreference() {
            MatchScore score = scorer.score(lead().nights(5).build(), template("T").nights(5).hotelTier(3).build());
            assertThat(component(score, "hotelTier").applicable()).isFalse();
            assertThat(score.percentage()).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("budget")
    class Budget {

        private MatchScore scoreAt(String budget, String price) {
            return scorer.score(
                    lead().budget(new BigDecimal(budget)).build(),
                    template("T").basePrice(new BigDecimal(price)).build());
        }

        @Test
        @DisplayName("over budget is penalised three times as hard as under budget")
        void asymmetricPenalty() {
            assertThat(scoreAt("100000", "80000").percentage()).isEqualTo(90);   // 20% under → 1 - .2*0.5
            assertThat(scoreAt("100000", "120000").percentage()).isEqualTo(70);  // 20% over  → 1 - .2*1.5
        }

        @Test
        @DisplayName("on budget scores 100 and says so")
        void exactBudget() {
            MatchScore score = scoreAt("100000", "100000");
            assertThat(score.percentage()).isEqualTo(100);
            assertThat(component(score, "budget").detail()).isEqualTo("On budget");
        }

        @Test
        @DisplayName("two thirds over budget is a total miss, clamped at 0")
        void wildlyOverBudgetClampsAtZero() {
            assertThat(scoreAt("100000", "170000").percentage()).isZero();
        }

        @Test
        @DisplayName("details name the direction")
        void detailStrings() {
            assertThat(component(scoreAt("100000", "80000"), "budget").detail()).isEqualTo("20% under budget");
            assertThat(component(scoreAt("100000", "120000"), "budget").detail()).isEqualTo("20% over budget");
        }

        @Test
        @DisplayName("a lead with no budget, or a template with no price, skips the dimension")
        void inapplicableWhenEitherSideIsMissing() {
            assertThat(component(scorer.score(lead().build(), template("T").basePrice(BigDecimal.TEN).build()),
                    "budget").applicable()).isFalse();
            assertThat(component(scorer.score(lead().budget(BigDecimal.TEN).build(), template("T").build()),
                    "budget").applicable()).isFalse();
        }

        @Test
        @DisplayName("a zero or negative budget is treated as absent, not as a divide by zero")
        void zeroBudgetIsAbsent() {
            MatchScore score = scorer.score(
                    lead().budget(BigDecimal.ZERO).build(), template("T").basePrice(BigDecimal.TEN).build());
            assertThat(component(score, "budget").applicable()).isFalse();
            assertThat(score.percentage()).isZero();
        }
    }

    @Nested
    @DisplayName("season")
    class Season {

        @Test
        @DisplayName("travelling inside the window is a perfect fit")
        void inSeason() {
            MatchScore score = scorer.score(
                    lead().travelMonth(10).build(), template("T").seasonMonths(Set.of(10, 11, 12)).build());
            assertThat(score.percentage()).isEqualTo(100);
            assertThat(component(score, "season").detail()).isEqualTo("In season (October)");
        }

        @Test
        @DisplayName("a package with no months declared is sold year-round")
        void yearRound() {
            MatchScore score = scorer.score(lead().travelMonth(6).build(), template("T").build());
            assertThat(score.percentage()).isEqualTo(100);
            assertThat(component(score, "season").detail()).isEqualTo("Sold year-round");
        }

        @Test
        @DisplayName("December and January are one month apart, not eleven")
        void monthDistanceIsCircular() {
            assertThat(TemplateScorer.circularMonthDistance(1, 12)).isEqualTo(1);
            assertThat(TemplateScorer.circularMonthDistance(12, 1)).isEqualTo(1);
            assertThat(TemplateScorer.circularMonthDistance(1, 7)).isEqualTo(6);

            // January against a Oct–Dec window: nearest is December, one month away → 1 - 1/3
            MatchScore score = scorer.score(
                    lead().travelMonth(1).build(), template("T").seasonMonths(Set.of(10, 11, 12)).build());
            assertThat(score.percentage()).isEqualTo(67);
            assertThat(component(score, "season").detail())
                    .isEqualTo("January is 1 month outside the season window");
        }

        @Test
        @DisplayName("beyond the tolerance the dimension scores 0, never negative")
        void farOutOfSeasonClampsAtZero() {
            MatchScore score = scorer.score(
                    lead().travelMonth(7).build(), template("T").seasonMonths(Set.of(1)).build());
            assertThat(component(score, "season").scorePercent()).isZero();
        }

        @Test
        @DisplayName("no travel date means the dimension is skipped")
        void inapplicableWithoutATravelDate() {
            MatchScore score = scorer.score(lead().nights(5).build(), template("T").nights(5).build());
            assertThat(component(score, "season").applicable()).isFalse();
        }
    }

    @Nested
    @DisplayName("ranking")
    class Ranking {

        private final MatchInput in = lead().cities(List.of(city(1, "Delhi"))).nights(5).build();

        private TemplateProfile candidate(long id, String name, long cityId, int nights) {
            return TemplateProfile.builder()
                    .id(id).publicId(UUID.randomUUID()).name(name)
                    .cities(List.of(city(cityId, "X"))).nights(nights)
                    .build();
        }

        @Test
        @DisplayName("orders by percentage descending and drops anything under the threshold")
        void ordersAndFilters() {
            TemplateProfile perfect = candidate(1, "Alpha", 1, 5);      // 100
            TemplateProfile close = candidate(2, "Beta", 1, 4);         // 93
            TemplateProfile wrongCity = candidate(3, "Gamma", 9, 5);    // 36 → below minScore 40

            List<RankedTemplate> ranked = scorer.rank(in, List.of(wrongCity, close, perfect));

            assertThat(ranked).extracting(r -> r.template().name()).containsExactly("Alpha", "Beta");
            assertThat(ranked.get(0).score().percentage()).isEqualTo(100);
            assertThat(ranked.get(1).score().percentage()).isEqualTo(93);
        }

        @Test
        @DisplayName("on a tie, the template we could score on more dimensions wins")
        void tieBreaksOnApplicableCountBeforeName() {
            MatchInput withBudget = lead()
                    .cities(List.of(city(1, "Delhi"))).nights(5).budget(new BigDecimal("100000"))
                    .build();

            TemplateProfile priced = TemplateProfile.builder()
                    .id(1L).publicId(UUID.randomUUID()).name("Zeta")
                    .cities(List.of(city(1, "Delhi"))).nights(5).basePrice(new BigDecimal("100000"))
                    .build();
            TemplateProfile unpriced = TemplateProfile.builder()
                    .id(2L).publicId(UUID.randomUUID()).name("Alpha")
                    .cities(List.of(city(1, "Delhi"))).nights(5)
                    .build();

            List<RankedTemplate> ranked = scorer.rank(withBudget, List.of(unpriced, priced));

            assertThat(ranked).allMatch(r -> r.score().percentage() == 100);
            // "Zeta" sorts after "Alpha" by name, but it scored on 3 dimensions rather than 2.
            assertThat(ranked).extracting(r -> r.template().name()).containsExactly("Zeta", "Alpha");
        }

        @Test
        @DisplayName("a full tie falls back to name, then id, so the order never wobbles")
        void tieBreaksOnNameThenId() {
            TemplateProfile zeta = candidate(9, "Zeta", 1, 5);
            TemplateProfile alpha = candidate(7, "Alpha", 1, 5);

            assertThat(scorer.rank(in, List.of(zeta, alpha)))
                    .extracting(r -> r.template().name()).containsExactly("Alpha", "Zeta");
        }

        @Test
        @DisplayName("an empty candidate set is not an error")
        void emptyCandidates() {
            assertThat(scorer.rank(in, List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("weight configuration")
    class Weighting {

        @Test
        @DisplayName("a dimension weighted 0 is switched off, and the rest carry the score")
        void zeroWeightDisablesADimension() {
            weights.setDestination(0);

            MatchInput in = lead().cities(List.of(city(1, "Delhi"))).nights(5).build();
            // Destination would be a total miss, but it carries no weight, so duration decides.
            TemplateProfile t = template("T").cities(List.of(city(9, "Elsewhere"))).nights(5).build();

            assertThat(scorer.score(in, t).percentage()).isEqualTo(100);
        }

        @Test
        @DisplayName("a negative weight is clamped to 0 rather than inverting the dimension")
        void negativeWeightIsTreatedAsOff() {
            weights.setDestination(-1);

            MatchInput in = lead().cities(List.of(city(1, "Delhi"))).nights(5).build();
            TemplateProfile t = template("T").cities(List.of(city(9, "Elsewhere"))).nights(5).build();

            assertThat(scorer.score(in, t).percentage()).isEqualTo(100);
        }

        @Test
        @DisplayName("zeroing every weight yields 0, not NaN")
        void allWeightsZero() {
            weights.setDestination(0);
            weights.setDuration(0);
            weights.setHotelTier(0);
            weights.setBudget(0);
            weights.setSeason(0);

            MatchInput in = lead()
                    .cities(List.of(city(1, "Delhi"))).nights(5).hotelTier(4)
                    .budget(new BigDecimal("100000")).travelMonth(10)
                    .build();
            TemplateProfile t = template("T")
                    .cities(List.of(city(1, "Delhi"))).nights(5).hotelTier(4)
                    .basePrice(new BigDecimal("100000")).seasonMonths(Set.of(10))
                    .build();

            assertThat(scorer.score(in, t).percentage()).isZero();
        }

        @Test
        @DisplayName("minScore filters the ranking, and is inclusive")
        void minScoreIsInclusive() {
            weights.setMinScore(100);

            MatchInput in = lead().cities(List.of(city(1, "Delhi"))).build();
            TemplateProfile exact = template("Exact").cities(List.of(city(1, "Delhi"))).build();

            assertThat(scorer.rank(in, List.of(exact))).hasSize(1);
        }
    }
}
