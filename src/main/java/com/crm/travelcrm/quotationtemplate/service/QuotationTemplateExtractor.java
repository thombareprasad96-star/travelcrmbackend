package com.crm.travelcrm.quotationtemplate.service;

import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.entity.LeadItinerary;
import com.crm.travelcrm.quotation.entity.Quotation;
import com.crm.travelcrm.quotation.entity.QuotationHotel;
import com.crm.travelcrm.quotation.entity.QuotationSightseeingActivity;
import com.crm.travelcrm.quotation.entity.QuotationSightseeingDay;
import com.crm.travelcrm.quotation.enums.QuotationSection;
import com.crm.travelcrm.quotationtemplate.dto.QuotationTemplateRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The mirror of {@link TemplateQuotationAssembler}: turns a finished quotation back into the payload
 * the template module already speaks, so "Save as Template" reuses
 * {@code QuotationTemplateService.create/update} verbatim instead of growing a second deep-copy.
 *
 * <p>That reuse is not just tidiness. {@code QuotationServiceImpl.copyForDuplicate} is the codebase's
 * cautionary tale — a hand-maintained field-by-field copy that has silently dropped
 * {@code allowedServices}, {@code templateStyle} and vehicle {@code imagePath} in turn, each
 * discovered in production. One more of those was not worth writing.
 *
 * <h2>Pure on purpose</h2>
 * Everything this class needs is handed to it: the quotation, the optional lead, the already-resolved
 * itinerary legs and the already-computed grand total. No repository, no {@code TenantContext}, no
 * lazy loading it did not ask for — so the whole capture is unit-testable without Spring, which is
 * how {@code TemplateScorer} and {@code CancellationCalculator} are already tested here.
 *
 * <h2>What a quotation cannot tell us</h2>
 * A {@code Quotation} is a denormalized name-snapshot document: not one child row carries a master
 * foreign key, {@code nights} is not stored anywhere, and the day-wise itinerary has no geography at
 * all. So three things are recovered rather than read, and one is declared lost:
 *
 * <ul>
 *   <li><b>Cities</b> come from the LEAD's itinerary legs when the quotation has a lead — those
 *       carry a destination qualifier <i>and</i> per-leg nights, strictly more than
 *       {@code QuotationHotel.city} does — and from the hotel rows otherwise. Either way the caller
 *       has already run them through {@link CityResolver}, which is the same bridge the matcher uses
 *       for leads, so a captured template resolves exactly as well as a lead does.</li>
 *   <li><b>Nights</b> are the lead legs' sum, else the hotel check-in/check-out spans. If neither
 *       yields a positive number the field is left <b>null</b>, never 0: null makes the duration
 *       dimension inapplicable and renormalizes, whereas a "0-night package" would be scored, and
 *       scored wrong. Non-ISO dates parse to nothing, so this is a real case, not a hypothetical.</li>
 *   <li><b>Hotel tier</b> is the modal star rating across the hotel rows, ties broken upward.</li>
 *   <li><b>Flights, cruises, vehicles and add-ons are dropped</b> — the template entity has no child
 *       table for them. That loss is reported through {@link Capture#droppedSections()} so the UI can
 *       state it before the agent commits, rather than discovering it on the next apply.</li>
 * </ul>
 */
@Component
public class QuotationTemplateExtractor {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    /** Mirrors {@code QuotationTemplateRequest.description}'s @Size(max = 5000). */
    private static final int MAX_DESCRIPTION = 5000;

    /**
     * The extraction result: the payload to save, plus an honest account of what could not come
     * along. {@code droppedSections} lists the human labels of sections the quotation had content in
     * and the template cannot hold.
     */
    public record Capture(
            QuotationTemplateRequest request,
            List<String> capturedSections,
            List<String> droppedSections) {

        public Capture {
            capturedSections = capturedSections == null ? List.of() : List.copyOf(capturedSections);
            droppedSections = droppedSections == null ? List.of() : List.copyOf(droppedSections);
        }
    }

    /**
     * One itinerary leg as the source document spells it, before any master lookup.
     *
     * @param cityIdHint the id the document already recorded, when it has one — a lead created
     *                   through the cascading dropdowns stamps {@code LeadItinerary.cityId}. Null for
     *                   hotel-derived legs, which carry nothing but a name.
     */
    public record RawLeg(Long cityIdHint, String destinationName, String cityName, Integer nights) {}

    /** The same leg after {@link CityResolver} has tried to attach a master City id. */
    public record ResolvedLeg(Long cityId, String cityName, String destinationName, Integer nights) {}

    // ════════════════════════════════════════════════════════════════════════

    /**
     * @param q          the quotation being captured
     * @param legs       itinerary legs already resolved to master city ids by {@link CityResolver}.
     *                   The originating lead is not passed in: everything this needs from it is
     *                   already distilled into these legs by {@link #legsOf}, and taking the entity
     *                   as well would invite reading fields off it that the capture has no business
     *                   depending on
     * @param grandTotal {@code QuotationMapper.computeTotals(q).getGrandTotal()} — passed in so this
     *                   class needs no mapper and no pricing knowledge of its own
     */
    public Capture extract(Quotation q, List<ResolvedLeg> legs, BigDecimal grandTotal) {
        QuotationTemplateRequest req = new QuotationTemplateRequest();

        req.setName(defaultName(q));
        // Quotation.notes is unbounded TEXT; the template's description is validated at 5000. Left
        // whole it would prefill the modal with a value the save then rejects as a bare 400.
        req.setDescription(truncate(q.getNotes(), MAX_DESCRIPTION));
        req.setCoverImageUrl(q.getCoverImageUrl());
        req.setActive(Boolean.TRUE);
        req.setBasePrice(positiveOrNull(grandTotal));
        req.setHotelTier(modalStars(q));
        // Empty = year-round, and year-round scores a perfect season fit. Narrowing a package to the
        // one month this quotation happened to travel in would silently suppress it for the other
        // eleven; the agent opts into a window in the modal instead.
        req.setSeasonMonths(Set.of());
        req.setServices(captureServices(q));

        req.setItinerary(buildItinerary(q, legs));
        req.setHotels(buildHotels(q));
        req.setInclusions(List.copyOf(q.getInclusions()));
        req.setExclusions(List.copyOf(q.getExclusions()));
        req.setDurationNights(resolveNights(q, legs));

        return new Capture(req, capturedSections(req), droppedSections(q));
    }

    // ── Naming ────────────────────────────────────────────────────────────────

    /**
     * "Kerala Classic" from a quotation titled "Kerala Classic — Mr Sharma". Quotation titles are
     * habitually suffixed with the customer's name; a template named after one traveller reads as
     * that person's trip rather than as a reusable package.
     *
     * <p>Only a suffix is stripped, and only when something is left over — "Mr Sharma" as the entire
     * title stays "Mr Sharma" rather than becoming empty and failing @NotBlank.
     */
    private static String defaultName(Quotation q) {
        String title = StringUtils.hasText(q.getTitle()) ? q.getTitle().trim() : "Package";
        String customer = q.getCustomerName();
        if (StringUtils.hasText(customer)) {
            String name = customer.trim();
            for (String dash : DASHES) {
                String suffix = dash + name;
                if (title.length() > suffix.length()
                        && title.regionMatches(true, title.length() - suffix.length(),
                                               suffix, 0, suffix.length())) {
                    title = title.substring(0, title.length() - suffix.length()).trim();
                    break;
                }
            }
        }
        if (title.isEmpty()) title = "Package";
        return title.length() > 150 ? title.substring(0, 150).trim() : title;
    }

    /** em dash, en dash, hyphen — the three separators quotation titles actually use. */
    private static final String[] DASHES = {" — ", " – ", " - "};

    // ── Scoring scalars ───────────────────────────────────────────────────────

    /**
     * The star tier the package is really pitched at: the most common rating across its hotels, and
     * the higher one when two ratings tie. Null when no hotel row declares stars — which leaves the
     * dimension inapplicable rather than inventing a tier.
     */
    private static Integer modalStars(Quotation q) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (QuotationHotel h : q.getHotels()) {
            Integer stars = h.getStars();
            if (stars == null || stars < 1 || stars > 5) continue;
            counts.merge(stars, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .max(Comparator.<Map.Entry<Integer, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparingInt(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * Which sections this package actually offers. Starts from the lead's snapshot
     * ({@code allowedServices}) because that is what the customer asked for, and intersects it with
     * the sections that carry content — a lead that wanted flights does not make a package that has
     * no flight rows a flight package. When there is no snapshot, the content alone decides.
     */
    private static List<String> captureServices(Quotation q) {
        Set<String> withContent = new LinkedHashSet<>();
        if (!q.getFlightSegments().isEmpty()) withContent.add(QuotationSection.FLIGHT.key());
        if (!q.getHotels().isEmpty()) withContent.add(QuotationSection.HOTEL.key());
        if (!q.getSightseeingDays().isEmpty()) withContent.add(QuotationSection.SIGHTSEEING.key());
        if (!q.getCruises().isEmpty()) withContent.add(QuotationSection.CRUISE.key());
        if (!q.getVehicles().isEmpty()) withContent.add(QuotationSection.VEHICLE.key());
        if (!q.getAddons().isEmpty()) withContent.add(QuotationSection.ADDON.key());

        List<String> allowed = q.getAllowedServices();
        if (allowed == null || allowed.isEmpty()) {
            return List.copyOf(withContent);
        }
        // Keep the lead's ordering — it is meaningful (the builder shows chosen services first).
        List<String> out = new ArrayList<>();
        for (String key : allowed) {
            if (key != null && withContent.contains(key)) out.add(key);
        }
        // A section with content the lead never asked for is still part of this package.
        for (String key : withContent) {
            if (!out.contains(key)) out.add(key);
        }
        return List.copyOf(out);
    }

    /** Lead legs first (they are authored geography), hotel spans second, null rather than zero. */
    private static Integer resolveNights(Quotation q, List<ResolvedLeg> legs) {
        int fromLegs = 0;
        if (legs != null) {
            for (ResolvedLeg leg : legs) {
                if (leg.nights() != null && leg.nights() > 0) fromLegs += leg.nights();
            }
        }
        if (fromLegs > 0) return fromLegs;

        int fromHotels = 0;
        for (QuotationHotel h : q.getHotels()) {
            fromHotels += nightsBetween(h.getCheckIn(), h.getCheckOut());
        }
        return fromHotels > 0 ? fromHotels : null;
    }

    // ── Children ──────────────────────────────────────────────────────────────

    /**
     * The day plan. Geography comes from {@code legs}; the narrative comes from the quotation's
     * sightseeing days, aligned by day number. A leg with no matching sightseeing day still becomes
     * a template day — the city is the point, the prose is a bonus.
     */
    private static List<QuotationTemplateRequest.ItineraryDay> buildItinerary(
            Quotation q, List<ResolvedLeg> legs) {

        List<ResolvedLeg> orderedLegs = legs == null ? List.of() : legs;

        // Sightseeing days are renumbered 1..N by their own order rather than trusted to be a
        // contiguous 1-based run: dayNumber is nullable and nothing enforces uniqueness, so keying a
        // map on it would silently drop days that collide.
        List<QuotationSightseeingDay> narratives = new ArrayList<>(q.getSightseeingDays());
        narratives.sort(Comparator.comparing(QuotationSightseeingDay::getDayNumber,
                Comparator.nullsLast(Comparator.naturalOrder())));

        // Whichever side runs longer decides the length: legs supply geography, sightseeing days
        // supply prose, and losing either because the other was shorter would be a silent loss.
        int dayCount = Math.max(orderedLegs.size(), narratives.size());
        List<QuotationTemplateRequest.ItineraryDay> out = new ArrayList<>(dayCount);

        for (int i = 0; i < dayCount; i++) {
            ResolvedLeg leg = i < orderedLegs.size() ? orderedLegs.get(i) : null;
            QuotationSightseeingDay narrative = i < narratives.size() ? narratives.get(i) : null;
            out.add(day(
                    i + 1,
                    leg == null ? null : leg.cityId(),
                    leg == null ? null : leg.cityName(),
                    leg == null ? null : leg.destinationName(),
                    leg == null ? null : leg.nights(),
                    narrative));
        }
        return out;
    }

    private static QuotationTemplateRequest.ItineraryDay day(
            int dayNumber, Long cityId, String cityName, String destinationName,
            Integer nights, QuotationSightseeingDay narrative) {

        QuotationTemplateRequest.ItineraryDay d = new QuotationTemplateRequest.ItineraryDay();
        d.setDayNumber(dayNumber);
        d.setCityId(cityId);
        d.setCityName(trimToNull(cityName));
        d.setDestinationName(trimToNull(destinationName));
        d.setNights(nights);
        if (narrative != null) {
            QuotationSightseeingActivity first = narrative.getActivities().isEmpty()
                    ? null : narrative.getActivities().getFirst();
            if (first != null) {
                d.setTitle(trimToNull(first.getAttraction()));
                d.setDescription(first.getDescription());
            }
            d.setPricePerPax(narrative.getPricePerPax());
        }
        return d;
    }

    /**
     * Hotel rows carry across almost field for field — the two entities were designed to mirror each
     * other. The one difference is {@code nights}: the template stores it as a number (the apply flow
     * walks check-in dates forward from the lead's travel date), while the quotation only has the two
     * date strings.
     */
    private static List<QuotationTemplateRequest.HotelItem> buildHotels(Quotation q) {
        List<QuotationTemplateRequest.HotelItem> out = new ArrayList<>();
        for (QuotationHotel h : q.getHotels()) {
            QuotationTemplateRequest.HotelItem item = new QuotationTemplateRequest.HotelItem();
            item.setName(trimToNull(h.getName()));
            item.setCity(trimToNull(h.getCity()));
            item.setStars(h.getStars());
            item.setRoomType(trimToNull(h.getRoomType()));
            item.setMealPlan(trimToNull(h.getMealPlan()));
            item.setRefundable(h.getRefundable());
            item.setPricePerRoom(h.getPricePerRoom());
            // Null rooms means "however many the lead needs", resolved at apply time — so an
            // unspecified room count is carried across as unspecified, not as 1.
            item.setRooms(positiveOrNull(h.getRooms()));
            int nights = nightsBetween(h.getCheckIn(), h.getCheckOut());
            item.setNights(nights > 0 ? nights : null);
            item.setImagePath(h.getImagePath());
            out.add(item);
        }
        return out;
    }

    // ── Honesty about the losses ──────────────────────────────────────────────

    private static List<String> capturedSections(QuotationTemplateRequest req) {
        List<String> out = new ArrayList<>();
        if (req.getItinerary() != null && !req.getItinerary().isEmpty()) out.add("Day-wise itinerary");
        if (req.getHotels() != null && !req.getHotels().isEmpty()) out.add("Hotels");
        if (req.getInclusions() != null && !req.getInclusions().isEmpty()) out.add("Inclusions");
        if (req.getExclusions() != null && !req.getExclusions().isEmpty()) out.add("Exclusions");
        return out;
    }

    /**
     * Sections this quotation has content in that a template has nowhere to put. Reported, never
     * silently discarded — the modal shows this list before the agent commits.
     */
    private static List<String> droppedSections(Quotation q) {
        List<String> out = new ArrayList<>();
        if (!q.getFlightSegments().isEmpty()) out.add("Flights");
        if (!q.getCruises().isEmpty()) out.add("Cruise");
        if (!q.getVehicles().isEmpty()) out.add("Vehicles");
        if (!q.getAddons().isEmpty()) out.add("Add-on services");
        if (!q.getPaymentPolicies().isEmpty()) out.add("Payment policies");
        if (!q.getCancellationPolicies().isEmpty()) out.add("Cancellation policy text");
        if (!q.getBookingTerms().isEmpty()) out.add("Booking terms");
        if (hasPricingAdjustment(q)) out.add("Discount / tax / markup");
        return out;
    }

    /** Totals are always recomputed from the cloned line items, so the adjusters cannot travel. */
    private static boolean hasPricingAdjustment(Quotation q) {
        return isPositive(q.getDiscount()) || isPositive(q.getTax()) || isPositive(q.getMarkup());
    }

    // ── Small helpers ─────────────────────────────────────────────────────────

    /**
     * The BEST geography a quotation can offer, as unresolved legs: the lead's itinerary when there
     * is one, the hotel rows otherwise.
     *
     * <p>The lead wins because it is strictly richer — its legs carry a destination qualifier (which
     * makes city resolution far more accurate than a bare name) and an explicit night count, neither
     * of which a {@code QuotationHotel} has. The quotation is the fallback, not the source of truth,
     * precisely because it threw that information away.
     */
    public static List<RawLeg> legsOf(Quotation q, Lead lead) {
        List<RawLeg> fromLead = leadLegs(lead);
        return fromLead.isEmpty() ? hotelLegs(q) : fromLead;
    }

    /** Lead itinerary legs in day order. */
    static List<RawLeg> leadLegs(Lead lead) {
        if (lead == null || lead.getItinerary() == null || lead.getItinerary().isEmpty()) return List.of();
        List<LeadItinerary> legs = new ArrayList<>(lead.getItinerary());
        // The Lead module puts no @OrderBy on the collection; dayNumber is nullable, so fall back to
        // the insertion id for a deterministic order either way.
        legs.sort(Comparator
                .comparing(LeadItinerary::getDayNumber, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingLong(LeadItinerary::getId));

        List<RawLeg> out = new ArrayList<>(legs.size());
        for (LeadItinerary leg : legs) {
            if (!StringUtils.hasText(leg.getCity())) continue;
            out.add(new RawLeg(leg.getCityId(), trimToNull(leg.getDestination()),
                    leg.getCity().trim(), leg.getNights()));
        }
        return List.copyOf(out);
    }

    /**
     * Distinct hotel cities in row order, each carrying the nights its hotel rows add up to. Two
     * rows in the same city (a hotel change mid-stay) collapse to one leg with the combined nights,
     * which is what an itinerary day plan means.
     */
    static List<RawLeg> hotelLegs(Quotation q) {
        Map<String, String> displayName = new LinkedHashMap<>();
        Map<String, Integer> nights = new LinkedHashMap<>();
        for (QuotationHotel h : q.getHotels()) {
            String city = h.getCity();
            if (!StringUtils.hasText(city)) continue;
            String key = city.trim().toLowerCase(Locale.ROOT);
            displayName.putIfAbsent(key, city.trim());
            nights.merge(key, nightsBetween(h.getCheckIn(), h.getCheckOut()), Integer::sum);
        }

        List<RawLeg> out = new ArrayList<>(displayName.size());
        for (Map.Entry<String, String> e : displayName.entrySet()) {
            int n = nights.getOrDefault(e.getKey(), 0);
            out.add(new RawLeg(null, null, e.getValue(), n > 0 ? n : null));
        }
        return List.copyOf(out);
    }

    private static int nightsBetween(String checkIn, String checkOut) {
        if (!StringUtils.hasText(checkIn) || !StringUtils.hasText(checkOut)) return 0;
        try {
            long d = ChronoUnit.DAYS.between(
                    LocalDate.parse(checkIn.trim(), ISO), LocalDate.parse(checkOut.trim(), ISO));
            return d > 0 ? (int) d : 0;
        } catch (Exception ignored) {
            return 0;   // non-ISO or malformed dates contribute nothing
        }
    }

    private static boolean isPositive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    private static BigDecimal positiveOrNull(BigDecimal v) {
        return isPositive(v) ? v : null;
    }

    private static Integer positiveOrNull(Integer v) {
        return v != null && v > 0 ? v : null;
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String truncate(String value, int max) {
        if (!StringUtils.hasText(value)) return null;
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
