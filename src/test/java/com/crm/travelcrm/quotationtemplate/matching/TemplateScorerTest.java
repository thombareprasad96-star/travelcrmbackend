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

    private static CityRef city(long id, String name, long destinationId, long countryId) {
        return CityRef.of(id, name, destinationId, countryId);
    }

    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("every score reports all six dimensions, applicable or not")
    void alwaysReportsSixComponents() {
        MatchScore score = scorer.score(lead().build(), template("T").build());
        assertThat(score.components()).extracting(ComponentScore::key)
                .containsExactly("destination", "duration", "hotelTier", "budget", "season", "services");
    }

    @Test
    @DisplayName("a template matching on all six dimensions scores 100")
    void perfectMatch() {
        MatchInput in = lead()
                .cities(List.of(city(1, "Delhi"), city(2, "Agra")))
                .nights(5).hotelTier(4).budget(new BigDecimal("100000")).travelMonth(10)
                .services(List.of("hotel", "flight"))
                .build();
        TemplateProfile t = template("Golden Triangle")
                .cities(List.of(city(1, "Delhi"), city(2, "Agra")))
                .nights(5).hotelTier(4).basePrice(new BigDecimal("100000")).seasonMonths(Set.of(10))
                .services(List.of("hotel", "flight"))
                .build();

        assertThat(scorer.score(in, t).percentage()).isEqualTo(100);
    }

    @Test
    @DisplayName("a template authored before the services dimension existed keeps the score it had")
    void addingTheServicesDimensionIsScoreNeutralForOldTemplates() {
        // The lead asks for services; the template — written before the column existed — lists none.
        // If that scored 0 instead of "not applicable", every pre-existing package would have been
        // silently marked down the day this dimension shipped.
        MatchInput in = lead()
                .cities(List.of(city(1, "Delhi"))).nights(5)
                .services(List.of("hotel", "flight"))
                .build();
        TemplateProfile legacy = template("Legacy").cities(List.of(city(1, "Delhi"))).nights(5).build();

        MatchScore score = scorer.score(in, legacy);
        assertThat(component(score, "services").applicable()).isFalse();
        assertThat(score.percentage()).isEqualTo(100);
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
    @DisplayName("destination — the near-miss ladder")
    class NearMiss {

        // Gangtok and Pelling are both in Sikkim (destination 7, country 1); Lachung is the third.
        private final MatchInput sikkimTrip = lead()
                .cities(List.of(city(31, "Gangtok", 7, 1), city(32, "Pelling", 7, 1)))
                .nights(4)
                .build();

        private final TemplateProfile sikkimPackage = TemplateProfile.builder()
                .id(1L).publicId(UUID.randomUUID()).name("Sikkim Explorer")
                .cities(List.of(city(31, "Gangtok", 7, 1), city(33, "Lachung", 7, 1)))
                .nights(4)
                .build();

        @Test
        @DisplayName("a different town in the same destination earns half credit, not zero")
        void sameDestinationIsAPartialHit() {
            // coverage (1 + 0.5)/2 = .75, jaccard 1/3 = .333  →  .75*.75 + .25*.333 = .6458
            // → .35*.6458 + .20*1.0 = .4260, over applicable weight .55 → 77
            assertThat(scorer.score(sikkimTrip, sikkimPackage).percentage()).isEqualTo(77);
        }

        @Test
        @DisplayName("turning the ladder off restores exact-city-or-nothing scoring")
        void zeroNearMissReproducesTheOldBehaviour() {
            weights.setNearMissDestination(0);
            weights.setNearMissCountry(0);
            // coverage 1/2 = .5, jaccard .333 → .4583 → .35*.4583 + .20 = .3604 / .55 → 66
            assertThat(scorer.score(sikkimTrip, sikkimPackage).percentage()).isEqualTo(66);
        }

        @Test
        @DisplayName("the detail counts exact hits and near-misses separately")
        void detailNamesTheNearMisses() {
            MatchScore score = scorer.score(sikkimTrip, sikkimPackage);
            assertThat(component(score, "destination").detail())
                    .isEqualTo("1 of 2 requested cities covered, 1 nearby, 1 extra in package");
        }

        @Test
        @DisplayName("same country but a different destination is barely a signal")
        void sameCountryIsWorthVeryLittle() {
            MatchInput in = lead().cities(List.of(city(1, "Delhi", 5, 1))).build();
            TemplateProfile t = template("Goa Beaches").cities(List.of(city(9, "Goa", 6, 1))).build();

            // coverage .2, jaccard 0 → .75*.2 = .15, and destination is the only applicable dimension
            assertThat(scorer.score(in, t).percentage()).isEqualTo(15);
        }

        @Test
        @DisplayName("an exact city always beats a near-miss, whatever the ladder is set to")
        void exactAlwaysWins() {
            weights.setNearMissDestination(5.0);   // nonsense config, clamped to 1.0

            MatchInput in = lead().cities(List.of(city(31, "Gangtok", 7, 1))).build();
            TemplateProfile exact = template("Exact").cities(List.of(city(31, "Gangtok", 7, 1))).build();
            TemplateProfile nearby = template("Nearby").cities(List.of(city(33, "Lachung", 7, 1))).build();

            assertThat(scorer.score(in, exact).percentage())
                    .isGreaterThanOrEqualTo(scorer.score(in, nearby).percentage());
        }

        @Test
        @DisplayName("cities with no geography resolved earn no near-miss credit at all")
        void unresolvedCitiesAreUnaffected() {
            MatchInput in = lead().cities(List.of(city(31, "Gangtok"))).build();
            TemplateProfile t = template("T").cities(List.of(city(33, "Lachung"))).build();

            assertThat(component(scorer.score(in, t), "destination").scorePercent()).isZero();
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
    @DisplayName("services")
    class Services {

        @Test
        @DisplayName("the package that actually includes the flights wins a tie that used to be alphabetical")
        void servicesBreakWhatWasPreviouslyATie() {
            MatchInput in = lead()
                    .cities(List.of(city(1, "Kochi"))).nights(5)
                    .services(List.of("hotel", "flight"))
                    .build();

            TemplateProfile withFlights = TemplateProfile.builder()
                    .id(1L).publicId(UUID.randomUUID()).name("Zeta Grand")
                    .cities(List.of(city(1, "Kochi"))).nights(5)
                    .services(List.of("hotel", "flight"))
                    .build();
            TemplateProfile withoutFlights = TemplateProfile.builder()
                    .id(2L).publicId(UUID.randomUUID()).name("Alpha Classic")
                    .cities(List.of(city(1, "Kochi"))).nights(5)
                    .services(List.of("hotel"))
                    .build();

            // Both are a perfect city + duration match. Before this dimension existed they tied on
            // 100 and sorted by name, so "Alpha Classic" — the one with no flights — came first.
            assertThat(scorer.score(in, withFlights).percentage()).isEqualTo(100);
            assertThat(scorer.score(in, withoutFlights).percentage()).isEqualTo(92);
            assertThat(scorer.rank(in, List.of(withoutFlights, withFlights)))
                    .extracting(r -> r.template().name())
                    .containsExactly("Zeta Grand", "Alpha Classic");
        }

        @Test
        @DisplayName("importance-weighted: missing the flights costs far more than missing the add-ons")
        void importanceWeighted() {
            MatchInput in = lead().services(List.of("flight", "addons")).build();

            // flight 3 + addons 1 = 4 requested. Cover the flight and you have 3/4; cover only the
            // add-ons and you have 1/4. A plain set overlap would call both 50 %.
            TemplateProfile flightOnly = template("Flights").services(List.of("flight")).build();
            TemplateProfile addonsOnly = template("Addons").services(List.of("addons")).build();

            assertThat(scorer.score(in, flightOnly).percentage()).isEqualTo(75);
            assertThat(scorer.score(in, addonsOnly).percentage()).isEqualTo(25);
        }

        @Test
        @DisplayName("extra services the package throws in are free")
        void extrasAreNotPenalised() {
            MatchInput in = lead().services(List.of("hotel")).build();
            TemplateProfile generous = template("Everything")
                    .services(List.of("hotel", "flight", "cruise", "vehicle")).build();

            assertThat(scorer.score(in, generous).percentage()).isEqualTo(100);
        }

        @Test
        @DisplayName("comparison is case-insensitive and de-duplicated on both sides")
        void normalisesBothSides() {
            MatchInput in = lead().services(List.of(" Hotel ", "HOTEL", "flight")).build();
            TemplateProfile t = template("T").services(List.of("HOTEL", "Flight")).build();

            assertThat(scorer.score(in, t).percentage()).isEqualTo(100);
            assertThat(component(scorer.score(in, t), "services").detail())
                    .isEqualTo("all 2 requested services included");
        }

        @Test
        @DisplayName("the detail names what is missing, so the agent knows what to add")
        void detailNamesTheGap() {
            MatchInput in = lead().services(List.of("hotel", "flight", "vehicle")).build();
            TemplateProfile t = template("T").services(List.of("hotel")).build();

            assertThat(component(scorer.score(in, t), "services").detail())
                    .isEqualTo("1 of 3 requested services — no flight, vehicle");
        }

        @Test
        @DisplayName("a lead that listed no services skips the dimension")
        void inapplicableWithoutRequestedServices() {
            MatchScore score = scorer.score(
                    lead().nights(5).build(), template("T").nights(5).services(List.of("hotel")).build());
            assertThat(component(score, "services").applicable()).isFalse();
            assertThat(score.percentage()).isEqualTo(100);
        }

        @Test
        @DisplayName("an unknown service id falls back to the default importance instead of scoring 0")
        void unknownServiceIdDegradesQuietly() {
            MatchInput in = lead().services(List.of("teleportation")).build();
            TemplateProfile t = template("T").services(List.of("teleportation")).build();

            assertThat(scorer.score(in, t).percentage()).isEqualTo(100);
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

        @Test
        @DisplayName("a package that has actually been sold wins an exact tie")
        void popularityBreaksAnExactTie() {
            TemplateProfile popular = TemplateProfile.builder()
                    .id(9L).publicId(UUID.randomUUID()).name("Zeta")
                    .cities(List.of(city(1, "Delhi"))).nights(5).timesApplied(12)
                    .build();
            TemplateProfile unused = TemplateProfile.builder()
                    .id(7L).publicId(UUID.randomUUID()).name("Alpha")
                    .cities(List.of(city(1, "Delhi"))).nights(5).timesApplied(0)
                    .build();

            // Identical scores. "Alpha" would win on name alone; twelve real sales outrank the alphabet.
            assertThat(scorer.rank(in, List.of(unused, popular)))
                    .extracting(r -> r.template().name()).containsExactly("Zeta", "Alpha");
        }

        @Test
        @DisplayName("popularity can never lift a worse match above a better one")
        void popularityNeverBeatsScore() {
            TemplateProfile popularButWrong = TemplateProfile.builder()
                    .id(1L).publicId(UUID.randomUUID()).name("Popular")
                    .cities(List.of(city(1, "Delhi"))).nights(1).timesApplied(500)
                    .build();
            TemplateProfile freshAndRight = TemplateProfile.builder()
                    .id(2L).publicId(UUID.randomUUID()).name("Fresh")
                    .cities(List.of(city(1, "Delhi"))).nights(5).timesApplied(0)
                    .build();

            assertThat(scorer.rank(in, List.of(popularButWrong, freshAndRight)))
                    .extracting(r -> r.template().name()).containsExactly("Fresh", "Popular");
        }

        @Test
        @DisplayName("rankAll keeps the below-threshold candidates that rank drops — the cold-start set")
        void rankAllKeepsEverythingSorted() {
            TemplateProfile good = candidate(1, "Good", 1, 5);        // 100
            TemplateProfile poor = candidate(2, "Poor", 9, 5);        // 36, under the 40 floor

            assertThat(scorer.rank(in, List.of(poor, good)))
                    .extracting(r -> r.template().name()).containsExactly("Good");
            assertThat(scorer.rankAll(in, List.of(poor, good)))
                    .extracting(r -> r.template().name()).containsExactly("Good", "Poor");
        }
    }

    @Nested
    @DisplayName("pre-filter safety bound")
    class PrefilterBound {

        @Test
        @DisplayName("a template covering none of the requested cities can still clear the threshold")
        void aCityPrefilterWouldDiscardValidMatches() {
            // This is the whole argument against pre-selecting candidates by city in SQL. Destination
            // scores a hard 0, yet renormalization over the other five dimensions still lands at 68 —
            // comfortably above the 40 % floor, so such a template WOULD have been shown.
            MatchInput in = lead()
                    .cities(List.of(city(1, "Delhi"))).nights(5).hotelTier(4)
                    .budget(new BigDecimal("100000")).travelMonth(10).services(List.of("hotel"))
                    .build();
            TemplateProfile elsewhere = template("Elsewhere")
                    .cities(List.of(city(99, "Reykjavik"))).nights(5).hotelTier(4)
                    .basePrice(new BigDecimal("100000")).seasonMonths(Set.of(10))
                    .services(List.of("hotel"))
                    .build();

            assertThat(scorer.maxScoreWithoutDestination()).isEqualTo(68);
            assertThat(scorer.score(in, elsewhere).percentage()).isEqualTo(68);
            assertThat(scorer.maxScoreWithoutDestination()).isGreaterThan(weights.getMinScore());
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
