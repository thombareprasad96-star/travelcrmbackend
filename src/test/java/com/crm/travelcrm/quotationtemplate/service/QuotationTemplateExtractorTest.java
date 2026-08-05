package com.crm.travelcrm.quotationtemplate.service;

import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.entity.LeadItinerary;
import com.crm.travelcrm.quotation.entity.Quotation;
import com.crm.travelcrm.quotation.entity.QuotationAddon;
import com.crm.travelcrm.quotation.entity.QuotationFlightSegment;
import com.crm.travelcrm.quotation.entity.QuotationHotel;
import com.crm.travelcrm.quotation.entity.QuotationSightseeingActivity;
import com.crm.travelcrm.quotation.entity.QuotationSightseeingDay;
import com.crm.travelcrm.quotation.entity.QuotationVehicle;
import com.crm.travelcrm.quotationtemplate.dto.QuotationTemplateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The extractor is a pure function over already-loaded entities, so this suite builds quotations by
 * hand - no Spring, no repositories, no database. Every case here pins a decision that a quotation's
 * denormalized shape forced on us, which is the part worth locking down: the geography, the nights
 * and the hotel tier are all RECOVERED rather than read, and each recovery has a wrong answer that
 * looks plausible.
 */
class QuotationTemplateExtractorTest {

    /** The em dash the house style uses between a quotation title and its customer. */
    private static final String EM_DASH = "—";

    private final QuotationTemplateExtractor extractor = new QuotationTemplateExtractor();

    // -- Fixtures -------------------------------------------------------------

    private static Quotation quotation() {
        Quotation q = new Quotation();
        q.setTitle("Kerala Classic");
        q.setAdults(2);
        q.setChildren(1);
        return q;
    }

    private static QuotationHotel hotel(String name, String city, Integer stars,
                                        String checkIn, String checkOut) {
        return QuotationHotel.builder()
                .name(name).city(city).stars(stars).checkIn(checkIn).checkOut(checkOut)
                .build();
    }

    private static QuotationSightseeingDay day(int dayNumber, String attraction, String description) {
        QuotationSightseeingDay d = QuotationSightseeingDay.builder().dayNumber(dayNumber).build();
        if (attraction != null) {
            d.addActivity(QuotationSightseeingActivity.builder()
                    .attraction(attraction).description(description).build());
        }
        return d;
    }

    private static QuotationTemplateExtractor.ResolvedLeg leg(Long cityId, String city, int nights) {
        return new QuotationTemplateExtractor.ResolvedLeg(cityId, city, "Kerala", nights);
    }

    private QuotationTemplateRequest extract(Quotation q, List<QuotationTemplateExtractor.ResolvedLeg> legs) {
        return extractor.extract(q, legs, new BigDecimal("120000")).request();
    }

    // =========================================================================

    @Nested
    @DisplayName("naming")
    class Naming {

        @Test
        @DisplayName("strips the customer suffix so the template reads as a package, not one person's trip")
        void stripsCustomerSuffix() {
            Quotation q = quotation();
            q.setTitle("Kerala Classic " + EM_DASH + " Mr Sharma");
            q.setCustomerName("Mr Sharma");

            assertThat(extract(q, List.of()).getName()).isEqualTo("Kerala Classic");
        }

        @Test
        @DisplayName("a plain hyphen separator is stripped too")
        void stripsHyphenSeparator() {
            Quotation q = quotation();
            q.setTitle("Kerala Classic - Mr Sharma");
            q.setCustomerName("Mr Sharma");

            assertThat(extract(q, List.of()).getName()).isEqualTo("Kerala Classic");
        }

        @Test
        @DisplayName("a title that is ONLY the customer name survives - stripping it would fail @NotBlank")
        void neverStripsAwayTheWholeTitle() {
            Quotation q = quotation();
            q.setTitle("Mr Sharma");
            q.setCustomerName("Mr Sharma");

            assertThat(extract(q, List.of()).getName()).isEqualTo("Mr Sharma");
        }

        @Test
        @DisplayName("a title with no suffix is left alone")
        void leavesPlainTitles() {
            Quotation q = quotation();
            q.setCustomerName("Mr Sharma");

            assertThat(extract(q, List.of()).getName()).isEqualTo("Kerala Classic");
        }
    }

    @Nested
    @DisplayName("nights")
    class Nights {

        @Test
        @DisplayName("the lead's legs win - they are authored, the hotel dates are inferred")
        void prefersLegs() {
            Quotation q = quotation();
            q.addHotel(hotel("Taj", "Kochi", 5, "2026-09-01", "2026-09-09"));

            assertThat(extract(q, List.of(leg(11L, "Kochi", 2), leg(12L, "Munnar", 3))).getDurationNights())
                    .isEqualTo(5);
        }

        @Test
        @DisplayName("falls back to the hotel check-in/check-out spans when there are no legs")
        void fallsBackToHotelSpans() {
            Quotation q = quotation();
            q.addHotel(hotel("Taj", "Kochi", 5, "2026-09-01", "2026-09-03"));
            q.addHotel(hotel("Windermere", "Munnar", 4, "2026-09-03", "2026-09-06"));

            assertThat(extract(q, List.of()).getDurationNights()).isEqualTo(5);
        }

        @Test
        @DisplayName("unparseable dates yield NULL, never 0 - a 0-night package would be scored, and scored wrong")
        void malformedDatesGiveNullNotZero() {
            Quotation q = quotation();
            q.addHotel(hotel("Taj", "Kochi", 5, "01/09/2026", "03/09/2026"));   // not ISO

            // Null makes the duration dimension INAPPLICABLE, so the scorer renormalizes over the
            // rest. Zero would be treated as a real duration and lose against every honest package.
            assertThat(extract(q, List.of()).getDurationNights()).isNull();
        }
    }

    @Nested
    @DisplayName("hotel tier")
    class HotelTier {

        @Test
        @DisplayName("the modal star rating, not the first row's and not the average")
        void modalStars() {
            Quotation q = quotation();
            q.addHotel(hotel("A", "Kochi", 3, null, null));
            q.addHotel(hotel("B", "Munnar", 4, null, null));
            q.addHotel(hotel("C", "Alleppey", 4, null, null));

            assertThat(extract(q, List.of()).getHotelTier()).isEqualTo(4);
        }

        @Test
        @DisplayName("a tie resolves upward - a package is pitched at its best room")
        void tiesResolveUpward() {
            Quotation q = quotation();
            q.addHotel(hotel("A", "Kochi", 3, null, null));
            q.addHotel(hotel("B", "Munnar", 5, null, null));

            assertThat(extract(q, List.of()).getHotelTier()).isEqualTo(5);
        }

        @Test
        @DisplayName("no stars anywhere leaves it null rather than inventing a tier")
        void noStarsIsNull() {
            Quotation q = quotation();
            q.addHotel(hotel("A", "Kochi", null, null, null));

            assertThat(extract(q, List.of()).getHotelTier()).isNull();
        }
    }

    @Nested
    @DisplayName("itinerary")
    class Itinerary {

        @Test
        @DisplayName("geography comes from the legs, prose from the sightseeing days, aligned by position")
        void mergesLegsAndNarrative() {
            Quotation q = quotation();
            q.addSightseeingDay(day(1, "Fort Kochi walk", "Colonial quarter"));
            q.addSightseeingDay(day(2, "Tea gardens", "Munnar estates"));

            List<QuotationTemplateRequest.ItineraryDay> days =
                    extract(q, List.of(leg(11L, "Kochi", 2), leg(12L, "Munnar", 3))).getItinerary();

            assertThat(days).hasSize(2);
            assertThat(days.get(0).getDayNumber()).isEqualTo(1);
            assertThat(days.get(0).getCityId()).isEqualTo(11L);
            assertThat(days.get(0).getCityName()).isEqualTo("Kochi");
            assertThat(days.get(0).getNights()).isEqualTo(2);
            assertThat(days.get(0).getTitle()).isEqualTo("Fort Kochi walk");
            assertThat(days.get(1).getCityName()).isEqualTo("Munnar");
            assertThat(days.get(1).getTitle()).isEqualTo("Tea gardens");
        }

        @Test
        @DisplayName("whichever side is longer decides the length, so neither prose nor geography is lost")
        void neitherSideTruncatesTheOther() {
            Quotation q = quotation();
            q.addSightseeingDay(day(1, "Day one", null));
            q.addSightseeingDay(day(2, "Day two", null));
            q.addSightseeingDay(day(3, "Day three", null));

            List<QuotationTemplateRequest.ItineraryDay> days =
                    extract(q, List.of(leg(11L, "Kochi", 2))).getItinerary();

            assertThat(days).hasSize(3);
            assertThat(days.get(0).getCityName()).isEqualTo("Kochi");
            assertThat(days.get(2).getTitle()).isEqualTo("Day three");
            assertThat(days.get(2).getCityName()).isNull();
        }

        @Test
        @DisplayName("day numbers are rewritten 1..N - the source numbering is nullable and not unique")
        void renumbersDays() {
            Quotation q = quotation();
            q.addSightseeingDay(day(7, "Later", null));
            q.addSightseeingDay(day(3, "Earlier", null));

            List<QuotationTemplateRequest.ItineraryDay> days = extract(q, List.of()).getItinerary();

            assertThat(days).extracting(QuotationTemplateRequest.ItineraryDay::getDayNumber)
                    .containsExactly(1, 2);
            assertThat(days).extracting(QuotationTemplateRequest.ItineraryDay::getTitle)
                    .containsExactly("Earlier", "Later");
        }
    }

    @Nested
    @DisplayName("services")
    class Services {

        @Test
        @DisplayName("only sections that actually carry content are declared")
        void contentDecides() {
            Quotation q = quotation();
            q.addHotel(hotel("Taj", "Kochi", 4, null, null));
            q.addSightseeingDay(day(1, "Walk", null));
            // The lead asked for flights too, but this quotation has no flight rows.
            q.getAllowedServices().addAll(List.of("flight", "hotel", "sightseeing"));

            assertThat(extract(q, List.of()).getServices()).containsExactly("hotel", "sightseeing");
        }

        @Test
        @DisplayName("the lead's ordering is kept, and a section it never asked for is still included")
        void keepsLeadOrderAndAddsExtras() {
            Quotation q = quotation();
            q.addHotel(hotel("Taj", "Kochi", 4, null, null));
            q.addVehicle(QuotationVehicle.builder().type("Innova").build());
            q.getAllowedServices().addAll(List.of("vehicle", "hotel"));

            assertThat(extract(q, List.of()).getServices()).containsExactly("vehicle", "hotel");
        }

        @Test
        @DisplayName("with no lead snapshot the content alone decides")
        void noSnapshotFallsBackToContent() {
            Quotation q = quotation();
            q.addHotel(hotel("Taj", "Kochi", 4, null, null));

            assertThat(extract(q, List.of()).getServices()).containsExactly("hotel");
        }
    }

    @Nested
    @DisplayName("honesty about the losses")
    class Losses {

        @Test
        @DisplayName("every section a template cannot hold is reported, never silently dropped")
        void reportsDroppedSections() {
            Quotation q = quotation();
            q.addHotel(hotel("Taj", "Kochi", 4, null, null));
            q.addFlightSegment(QuotationFlightSegment.builder().airline("IndiGo").build());
            q.addVehicle(QuotationVehicle.builder().type("Innova").build());
            q.addAddon(QuotationAddon.builder().serviceType("Visa").build());
            q.getBookingTerms().add("50% advance");
            q.setDiscount(new BigDecimal("5000"));

            QuotationTemplateExtractor.Capture capture =
                    extractor.extract(q, List.of(), new BigDecimal("120000"));

            assertThat(capture.droppedSections())
                    .containsExactly("Flights", "Vehicles", "Add-on services",
                                     "Booking terms", "Discount / tax / markup");
            assertThat(capture.capturedSections()).contains("Hotels");
        }

        @Test
        @DisplayName("a quotation with nothing to drop reports nothing")
        void nothingToDrop() {
            Quotation q = quotation();
            q.addHotel(hotel("Taj", "Kochi", 4, null, null));

            assertThat(extractor.extract(q, List.of(), BigDecimal.TEN).droppedSections()).isEmpty();
        }
    }

    @Nested
    @DisplayName("hotels and pricing")
    class HotelsAndPricing {

        @Test
        @DisplayName("hotel rows carry across, with nights derived from the date strings")
        void hotelsCarryAcross() {
            Quotation q = quotation();
            q.addHotel(hotel("Taj Malabar", "Kochi", 5, "2026-09-01", "2026-09-03"));

            QuotationTemplateRequest.HotelItem item = extract(q, List.of()).getHotels().getFirst();
            assertThat(item.getName()).isEqualTo("Taj Malabar");
            assertThat(item.getCity()).isEqualTo("Kochi");
            assertThat(item.getStars()).isEqualTo(5);
            assertThat(item.getNights()).isEqualTo(2);
        }

        @Test
        @DisplayName("an unspecified room count stays unspecified - apply resolves it to the lead's rooms")
        void nullRoomsStayNull() {
            Quotation q = quotation();
            q.addHotel(hotel("Taj", "Kochi", 5, null, null));

            assertThat(extract(q, List.of()).getHotels().getFirst().getRooms()).isNull();
        }

        @Test
        @DisplayName("basePrice is the grand total the caller computed; season defaults to year-round")
        void pricingAndSeasonDefaults() {
            QuotationTemplateRequest req = extract(quotation(), List.of());

            assertThat(req.getBasePrice()).isEqualByComparingTo("120000");
            // Empty = sold year-round = a perfect season fit. Narrowing to the month this quotation
            // happened to travel in would suppress the package for the other eleven.
            assertThat(req.getSeasonMonths()).isEmpty();
        }

        @Test
        @DisplayName("a zero grand total is stored as null, so the budget dimension is skipped not failed")
        void zeroTotalIsNull() {
            assertThat(extractor.extract(quotation(), List.of(), BigDecimal.ZERO)
                    .request().getBasePrice()).isNull();
        }
    }

    @Nested
    @DisplayName("leg sources")
    class LegSources {

        @Test
        @DisplayName("the lead's itinerary wins over the hotel rows - it has qualifiers and nights")
        void leadBeatsHotels() {
            Quotation q = quotation();
            q.addHotel(hotel("Taj", "Kochi", 5, "2026-09-01", "2026-09-03"));

            Lead lead = new Lead();
            lead.setItinerary(new ArrayList<>(List.of(
                    LeadItinerary.builder().destination("Kerala").city("Munnar").cityId(12L)
                            .nights(3).dayNumber(1).build())));

            List<QuotationTemplateExtractor.RawLeg> legs = QuotationTemplateExtractor.legsOf(q, lead);
            assertThat(legs).hasSize(1);
            assertThat(legs.getFirst().cityName()).isEqualTo("Munnar");
            assertThat(legs.getFirst().destinationName()).isEqualTo("Kerala");
            assertThat(legs.getFirst().cityIdHint()).isEqualTo(12L);
            assertThat(legs.getFirst().nights()).isEqualTo(3);
        }

        @Test
        @DisplayName("without a lead, hotel rows become legs and same-city rows merge their nights")
        void hotelRowsCollapseByCity() {
            Quotation q = quotation();
            q.addHotel(hotel("Taj", "Kochi", 5, "2026-09-01", "2026-09-03"));
            q.addHotel(hotel("Brunton", "kochi", 4, "2026-09-03", "2026-09-04"));   // same city, other case
            q.addHotel(hotel("Windermere", "Munnar", 4, "2026-09-04", "2026-09-07"));

            List<QuotationTemplateExtractor.RawLeg> legs = QuotationTemplateExtractor.legsOf(q, null);

            assertThat(legs).hasSize(2);
            assertThat(legs.get(0).cityName()).isEqualTo("Kochi");
            assertThat(legs.get(0).nights()).isEqualTo(3);          // 2 + 1
            assertThat(legs.get(0).cityIdHint()).isNull();          // a quotation records no ids
            assertThat(legs.get(1).cityName()).isEqualTo("Munnar");
            assertThat(legs.get(1).nights()).isEqualTo(3);
        }
    }
}
