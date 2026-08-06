package com.crm.travelcrm.quotation.pdf.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The formatting contract the LUXURY document depends on.
 *
 * <p>Most of these look trivial and are not. The Indian-grouping cases are the reason this class
 * exists at all: {@code ₹1,25,000} vs {@code ₹125,000} is a locale accident that renders correctly
 * on a developer's machine and wrongly in a container, with nothing failing in between. Pinning it
 * here is what turns that into a test failure instead of a customer noticing.
 */
class LuxuryDisplayFormatTest {

    private final LuxuryDisplayFormat fmt = new LuxuryDisplayFormat();

    @Nested
    @DisplayName("money")
    class Money {

        /** The three grouping shapes from the spec — 5, 6 and 7 significant digits. */
        @Test
        void groupsDigitsTheIndianWay() {
            assertThat(fmt.money(new BigDecimal("76650"))).isEqualTo("₹76,650");
            assertThat(fmt.money(new BigDecimal("125000"))).isEqualTo("₹1,25,000");
            assertThat(fmt.money(new BigDecimal("1050000"))).isEqualTo("₹10,50,000");
        }

        /**
         * Zero must survive as a printable value. A ₹0 discount or 0% GST is information the
         * customer needs to reconcile the total; anything that turns it into "-" or "" hides a line
         * the document is supposed to state.
         */
        @Test
        void rendersGenuineZero() {
            assertThat(fmt.money(BigDecimal.ZERO)).isEqualTo("₹0");
            assertThat(fmt.money(new BigDecimal("0.00"))).isEqualTo("₹0");
        }

        /** Null is a caller bug on the money page — showing ₹0 makes it visible, a dash hides it. */
        @Test
        void nullBecomesZeroNotADash() {
            assertThat(fmt.money(null)).isEqualTo("₹0");
        }

        @Test
        void roundsToWholeRupeesHalfUp() {
            assertThat(fmt.money(new BigDecimal("76650.49"))).isEqualTo("₹76,650");
            assertThat(fmt.money(new BigDecimal("76650.50"))).isEqualTo("₹76,651");
        }
    }

    @Nested
    @DisplayName("percent")
    class Percent {

        @Test
        void stripsTrailingZeros() {
            assertThat(fmt.percent(new BigDecimal("18.00"))).isEqualTo("18%");
            assertThat(fmt.percent(new BigDecimal("2.50"))).isEqualTo("2.5%");
        }

        /** Same reasoning as money: a 0% tax line must be able to say "0%". */
        @Test
        void keepsZero() {
            assertThat(fmt.percent(BigDecimal.ZERO)).isEqualTo("0%");
            assertThat(fmt.percent(null)).isEqualTo("0%");
        }
    }

    @Nested
    @DisplayName("dates")
    class Dates {

        @Test
        void formatsLocalDate() {
            assertThat(fmt.date(LocalDate.of(2026, 8, 20))).isEqualTo("20 Aug 2026");
        }

        /** Null hides the field rather than printing "-" into a design with no room for it. */
        @Test
        void nullDateIsNull() {
            assertThat(fmt.date((LocalDate) null)).isNull();
        }

        /** The builder writes dates in several shapes; all of them must reach the same output. */
        @Test
        void parsesEveryShapeTheBuilderWrites() {
            assertThat(fmt.date("2026-08-20")).isEqualTo("20 Aug 2026");
            assertThat(fmt.date("20/08/2026")).isEqualTo("20 Aug 2026");
            assertThat(fmt.date("20-08-2026")).isEqualTo("20 Aug 2026");
            assertThat(fmt.date("2026-08-20T00:00")).isEqualTo("20 Aug 2026");
        }

        /**
         * A hand-typed date is printed as written. Swallowing it would lose information the agent
         * deliberately entered; the only thing forbidden is letting a raw ISO string through, which
         * the case above already covers.
         */
        @Test
        void unparseableTextSurvivesUnchanged() {
            assertThat(fmt.date("Late August")).isEqualTo("Late August");
        }

        @Test
        void buildsRanges() {
            LocalDate from = LocalDate.of(2026, 8, 20);
            LocalDate to = LocalDate.of(2026, 8, 26);
            assertThat(fmt.dateRange(from, to)).isEqualTo("20 Aug 2026 – 26 Aug 2026");
        }

        /** A one-day trip prints one date, not "X – X". */
        @Test
        void collapsesASingleDayRange() {
            LocalDate d = LocalDate.of(2026, 8, 20);
            assertThat(fmt.dateRange(d, d)).isEqualTo("20 Aug 2026");
        }

        @Test
        void toleratesAMissingEnd() {
            assertThat(fmt.dateRange(LocalDate.of(2026, 8, 20), null)).isEqualTo("20 Aug 2026");
            assertThat(fmt.dateRange(null, null)).isNull();
        }
    }

    @Nested
    @DisplayName("counts and labels")
    class Counts {

        @Test
        void buildsDurationBothWays() {
            assertThat(fmt.duration(6, 7)).isEqualTo("6 Nights / 7 Days");
            assertThat(fmt.duration(1, 2)).isEqualTo("1 Night / 2 Days");
            assertThat(fmt.duration(null, null)).isNull();
        }

        @Test
        void pluralisesChildrenIrregularly() {
            assertThat(fmt.travellers(2, 1, null)).isEqualTo("2 Adults, 1 Child");
            assertThat(fmt.travellers(2, 3, 1)).isEqualTo("2 Adults, 3 Children, 1 Infant");
        }

        /** A party with no children must not print "0 Children". */
        @Test
        void omitsZeroTravellerCategories() {
            assertThat(fmt.travellers(2, 0, 0)).isEqualTo("2 Adults");
            assertThat(fmt.travellers(null, null, null)).isNull();
        }

        @Test
        void drawsFiveStarsAlways() {
            assertThat(fmt.stars(4)).isEqualTo("★★★★☆");
            assertThat(fmt.stars(5)).isEqualTo("★★★★★");
            // Out-of-range data must not produce a row of 40 stars.
            assertThat(fmt.stars(9)).isEqualTo("★★★★★");
            assertThat(fmt.stars(0)).isNull();
            assertThat(fmt.stars(null)).isNull();
        }

        /** No SCREAMING_SNAKE constant may reach a customer's eyes. */
        @Test
        void humanisesEnumNames() {
            assertThat(fmt.label("PARTIALLY_PAID")).isEqualTo("Partially Paid");
            assertThat(fmt.label("SENT")).isEqualTo("Sent");
            assertThat(fmt.label((String) null)).isNull();
        }
    }

    @Nested
    @DisplayName("rich text")
    class RichText {

        /** contentEditable markup would otherwise break a fixed-height A4 page. */
        @Test
        void stripsTagsAndEntities() {
            assertThat(fmt.plain("<p>Visit&nbsp;the <b>fort</b></p><br/>at dawn"))
                    .isEqualTo("Visit the fort at dawn");
        }

        @Test
        void emptyMarkupBecomesNullSoTheBlockHides() {
            assertThat(fmt.plain("<p></p>")).isNull();
            assertThat(fmt.plain("   ")).isNull();
            assertThat(fmt.plain(null)).isNull();
        }

        /**
         * The structure the agent typed is information. plain() flattens it — right for a caption,
         * wrong for a description — so bullets() keeps one entry per authored line.
         */
        @Test
        void bulletsKeepOneEntryPerAuthoredLine() {
            assertThat(fmt.bullets("<ul><li>Shikara ride</li><li>Mughal gardens</li></ul>"))
                    .containsExactly("Shikara ride", "Mughal gardens");
            assertThat(fmt.bullets("<p>Morning transfer</p><p>Evening at leisure</p>"))
                    .containsExactly("Morning transfer", "Evening at leisure");
            assertThat(fmt.bullets("Line one<br/>Line two"))
                    .containsExactly("Line one", "Line two");
        }

        /** Plain prose with no markup is one line, not one word per line. */
        @Test
        void unmarkedProseStaysASingleLine() {
            assertThat(fmt.bullets("A quiet morning by the lake"))
                    .containsExactly("A quiet morning by the lake");
        }

        /** The template draws its own markers — a carried-over bullet would print "• • text". */
        @Test
        void stripsMarkersTheEditorLeftBehind() {
            assertThat(fmt.bullets("<ul><li>• Already bulleted</li></ul>"))
                    .containsExactly("Already bulleted");
        }

        @Test
        void emptyInputYieldsNoLines() {
            assertThat(fmt.bullets(null)).isEmpty();
            assertThat(fmt.bullets("<ul><li></li></ul>")).isEmpty();
        }

        /**
         * Plain prose with no markup still becomes points — most agents type a description as
         * sentences rather than building a list, and a paragraph wearing one bullet reads as a bug.
         */
        @Test
        void unmarkedProseSplitsIntoSentences() {
            assertThat(fmt.bullets("Check-out and departure. Transfer to airport. Assistance provided."))
                    .containsExactly("Check-out and departure.", "Transfer to airport.",
                                     "Assistance provided.");
        }

        /** Structure the agent typed WINS — their grouping is not second-guessed. */
        @Test
        void authoredListIsNotResplitBySentence() {
            assertThat(fmt.bullets("<ul><li>Arrive. Check in.</li><li>Dinner at the hotel.</li></ul>"))
                    .containsExactly("Arrive. Check in.", "Dinner at the hotel.");
        }

        /** A decimal is not a sentence boundary — no space and no capital after the stop. */
        @Test
        void decimalsAndAmountsSurvive() {
            assertThat(fmt.bullets("Drive takes 2.5 hours. Cost is ₹1,25,000.50 per couple."))
                    .containsExactly("Drive takes 2.5 hours.", "Cost is ₹1,25,000.50 per couple.");
        }

        /** …nor is an abbreviation, which would otherwise produce a bullet reading only "Mr.". */
        @Test
        void abbreviationsDoNotEndASentence() {
            assertThat(fmt.bullets("Pickup by Mr. Sharma at the airport. Transfer follows."))
                    .containsExactly("Pickup by Mr. Sharma at the airport.", "Transfer follows.");
        }

        @Test
        void aSingleSentenceStaysOneLine() {
            assertThat(fmt.bullets("Check-out and departure from Marine Drive."))
                    .containsExactly("Check-out and departure from Marine Drive.");
        }
    }
}
