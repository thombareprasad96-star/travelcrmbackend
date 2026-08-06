package com.crm.travelcrm.quotation.pdf.mapper;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.company.entity.Company;
import com.crm.travelcrm.company.repository.CompanyRepository;
import com.crm.travelcrm.quotation.dto.QuotationResponseDto;
import com.crm.travelcrm.quotation.pdf.dto.LuxuryQuotationPdfDto;
import com.crm.travelcrm.subagent.service.SubAgentBrandingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds the display-ready {@link LuxuryQuotationPdfDto} the LUXURY template renders.
 *
 * <p><b>This class formats; it never calculates.</b> Every rupee figure it prints comes straight
 * out of {@code QuotationResponseDto.Totals}, which the existing {@code QuotationMapper.computeTotals}
 * produced on the read path. Nothing here adds, multiplies or re-derives a total. That boundary is
 * the whole reason the Luxury design could be added without touching pricing: if this class could
 * compute, then two engines would own the arithmetic and a rounding difference between them would
 * ship to a customer as a document whose lines do not sum to its own total.
 *
 * <p><b>Branding resolution mirrors {@code QuotationPdfService} exactly</b> — tenant Company profile
 * over configured defaults, then the sub-agent's white-label brand over that. Reimplementing it
 * differently would mean a tenant's logo appeared on three designs and not the fourth.
 *
 * <p><b>No fabrication.</b> A field the quotation does not carry becomes {@code null} and the
 * template hides that block. Nothing here invents a placeholder sentence, a stock photograph URL or
 * a default cancellation window — an empty section is honest, invented content is not.
 */
@Component
@Slf4j
public class LuxuryQuotationPdfMapper {

    /**
     * Root-relative fallback artwork, served from {@code src/main/resources/static/images/pdf/fallbacks}.
     *
     * <p>Root-relative rather than absolute on purpose: the page is fetched by Chromium from the
     * loopback origin and by a human from the API origin, and a relative URL resolves correctly
     * under both without the mapper needing to know which one it is rendering for.
     */
    private static final String FALLBACK_DIR = "/images/pdf/fallbacks/";
    private static final String FALLBACK_COVER = FALLBACK_DIR + "luxury-cover.jpg";
    private static final String FALLBACK_LOGO = FALLBACK_DIR + "company-logo.png";
    private static final String FALLBACK_HOTEL = FALLBACK_DIR + "hotel-placeholder.jpg";
    private static final String FALLBACK_VEHICLE = FALLBACK_DIR + "vehicle-placeholder.jpg";
    private static final String FALLBACK_SIGHTSEEING = FALLBACK_DIR + "sightseeing-placeholder.jpg";
    private static final String FALLBACK_AGENT = FALLBACK_DIR + "agent-placeholder.png";

    private final CompanyRepository companyRepository;
    private final SubAgentBrandingService brandingService;
    private final UserRepository userRepository;
    private final LuxuryDisplayFormat fmt;
    private final LuxuryPdfPaginator paginator;

    private final String defaultCompanyName;
    private final String defaultCompanyTagline;
    private final String defaultCompanyPhone;
    private final String defaultCompanyEmail;
    private final String defaultCompanyWebsite;
    private final String defaultCompanyAddress;
    private final String defaultCompanyLogoUrl;

    public LuxuryQuotationPdfMapper(
            CompanyRepository companyRepository,
            SubAgentBrandingService brandingService,
            UserRepository userRepository,
            LuxuryDisplayFormat fmt,
            LuxuryPdfPaginator paginator,
            // The SAME properties QuotationPdfService reads. One source of default branding across
            // all four designs — a second set of keys would drift the moment one was updated.
            @Value("${quotation.pdf.company-name:TravelCRM}") String defaultCompanyName,
            @Value("${quotation.pdf.company-tagline:Your Journey, Our Passion}") String defaultCompanyTagline,
            @Value("${quotation.pdf.company-phone:}") String defaultCompanyPhone,
            @Value("${quotation.pdf.company-email:}") String defaultCompanyEmail,
            @Value("${quotation.pdf.company-website:}") String defaultCompanyWebsite,
            @Value("${quotation.pdf.company-address:}") String defaultCompanyAddress,
            @Value("${quotation.pdf.company-logo-url:}") String defaultCompanyLogoUrl) {
        this.companyRepository = companyRepository;
        this.brandingService = brandingService;
        this.userRepository = userRepository;
        this.fmt = fmt;
        this.paginator = paginator;
        this.defaultCompanyName = defaultCompanyName;
        this.defaultCompanyTagline = defaultCompanyTagline;
        this.defaultCompanyPhone = defaultCompanyPhone;
        this.defaultCompanyEmail = defaultCompanyEmail;
        this.defaultCompanyWebsite = defaultCompanyWebsite;
        this.defaultCompanyAddress = defaultCompanyAddress;
        this.defaultCompanyLogoUrl = defaultCompanyLogoUrl;
    }

    public LuxuryQuotationPdfDto map(QuotationResponseDto q) {
        QuotationResponseDto.Customer customer = q.getCustomer();
        CompanyBrand brand = resolveBrand(q);

        List<LuxuryQuotationPdfDto.ItineraryDay> days = itineraryDays(q);
        List<LuxuryQuotationPdfDto.HotelSection> hotels = hotels(q);
        List<String> terms = terms(q);

        String destination = customer != null ? fmt.blankToNull(customer.getDestination()) : null;
        String coverImage = fmt.blankToNull(q.getCoverImageUrl());

        return LuxuryQuotationPdfDto.builder()
                .quotationCode(quotationCode(q))
                .customerName(customer != null ? fmt.blankToNull(customer.getName()) : null)
                .packageTitle(fmt.blankToNull(q.getTitle()))
                .packageSubtitle(destination)
                .destination(destination)
                .duration(fmt.duration(q.getNights(), q.getDays()))
                .travelDateRange(travelDateRange(q))
                .travellerSummary(customer == null ? null
                        : fmt.travellers(customer.getAdults(), customer.getChildren(), customer.getInfants()))
                .transportSummary(transportSummary(q))
                // Not carried anywhere on the quotation — see the mapper's class note. Left null so
                // the template hides the tile rather than guessing from the destination string.
                .tripType(null)

                .coverImageUrl(coverImage)
                // The snapshot and closing spreads reuse the cover artwork: the quotation carries
                // exactly one image of its own, and pulling a second one from an unrelated master
                // record would put a photograph of somewhere else on the page.
                .snapshotImageUrl(coverImage)
                .inclusionBackgroundImageUrl(coverImage)
                .closingImageUrl(coverImage)

                .welcomeMessage(fmt.plain(q.getNotes()))
                // No closing-copy field exists on the quotation; the template's own static sign-off
                // is used instead of inventing a sentence in the agent's voice.
                .closingMessage(null)

                // The share link is minted by a separate endpoint and is not part of the render
                // model; no QR is embedded rather than printing a code that resolves to nothing.
                .qrCodeImageUrl(null)
                .qrCaption(null)

                .fallbackCoverImageUrl(FALLBACK_COVER)
                .fallbackLogoUrl(FALLBACK_LOGO)
                .fallbackHotelImageUrl(FALLBACK_HOTEL)
                .fallbackVehicleImageUrl(FALLBACK_VEHICLE)
                .fallbackSightseeingImageUrl(FALLBACK_SIGHTSEEING)
                .fallbackAgentImageUrl(FALLBACK_AGENT)

                .company(brand.section())
                .agent(agent(q))
                .snapshotItems(snapshotItems(q, brand))
                .itineraryPages(itineraryPages(days))
                .hotelPages(hotelPages(hotels,
                        q.getHotel() != null ? fmt.bullets(q.getHotel().getNotes()) : List.of()))
                .transport(transport(q))
                .sightseeingItems(sightseeingItems(q))
                .sightseeingImages(sightseeingImages(q))
                .inclusions(cleanList(q.getInclusions()))
                .exclusions(cleanList(q.getExclusions()))
                .pricing(pricing(q))
                .paymentSchedule(paymentSchedule(q))
                .cancellationRows(cancellationRows(q))
                .terms(paginator.termsPages(terms))
                .policyPages(policyPages(q, terms))
                .build();
    }

    // ── Identity ──────────────────────────────────────────────────────────────

    /**
     * {@code QT-26-0045}, assembled from the per-tenant {@code quoteNo} and the creation year.
     *
     * <p>The quotation has no stored code column — unlike Lead ({@code LD-26-0001}) and Booking, it
     * carries a bare sequential {@code quoteNo}. Composing the reference here rather than persisting
     * one keeps this change out of the schema; the trade-off is that the same number renders
     * differently if the display convention is ever changed, which is why the shape mirrors the
     * existing lead code exactly.
     *
     * <p>Falls back to the version label and finally to null — never to the publicId. A UUID printed
     * as a document reference is unusable to the customer who has to quote it back on the phone.
     */
    private String quotationCode(QuotationResponseDto q) {
        if (q.getQuoteNo() != null && q.getQuoteNo() > 0) {
            int year = (q.getCreatedAt() != null ? q.getCreatedAt().getYear() : Year.now().getValue()) % 100;
            return String.format("QT-%02d-%04d", year, q.getQuoteNo());
        }
        return fmt.blankToNull(q.getVersion());
    }

    private String travelDateRange(QuotationResponseDto q) {
        LocalDate start = q.getCustomer() != null ? q.getCustomer().getTravelDate() : null;
        if (start == null) return null;
        // The end date is not stored: the customer snapshot holds only a departure date. Derived
        // from the trip length, which IS stored — not invented, just arithmetic on a known span.
        Integer nights = q.getNights();
        LocalDate end = (nights != null && nights > 0) ? start.plusDays(nights) : null;
        return fmt.dateRange(start, end);
    }

    // ── Branding ──────────────────────────────────────────────────────────────

    /** The resolved company block plus the logo, kept together so callers cannot use one without the other. */
    private record CompanyBrand(LuxuryQuotationPdfDto.CompanySection section) {}

    private CompanyBrand resolveBrand(QuotationResponseDto q) {
        String name = defaultCompanyName;
        String logo = defaultCompanyLogoUrl;
        String phone = defaultCompanyPhone;
        String email = defaultCompanyEmail;
        String website = defaultCompanyWebsite;
        String address = defaultCompanyAddress;
        String gstin = null;
        Integer reviews = null;
        Integer years = null;

        Long tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            Company co = companyRepository.findByTenantId(tenantId).orElse(null);
            if (co != null) {
                if (StringUtils.hasText(co.getName())) name = co.getName();
                if (StringUtils.hasText(co.getLogoUrl())) logo = co.getLogoUrl();
                if (StringUtils.hasText(co.getPhone())) phone = co.getPhone();
                if (StringUtils.hasText(co.getEmail())) email = co.getEmail();
                if (StringUtils.hasText(co.getWebsite())) website = co.getWebsite();
                if (StringUtils.hasText(co.getAddress())) address = co.getAddress();
                gstin = co.getGstin();
                reviews = co.getTotalReviews();
                if (co.getOperatingSince() != null) {
                    years = Year.now().getValue() - co.getOperatingSince();
                }
            }
        }

        // White-label: an active sub-agent's brand replaces the tenant's identity field by field —
        // identity only. Address, website, GST and review counts stay the parent's, because they are
        // legal/registration facts about the company actually delivering the trip.
        var branding = brandingService.resolve(q.getOwnerUserId());
        if (branding.isPresent()) {
            var b = branding.get();
            if (StringUtils.hasText(b.brandName())) name = b.brandName();
            if (StringUtils.hasText(b.logoUrl())) logo = b.logoUrl();
            if (StringUtils.hasText(b.contactPhone())) phone = b.contactPhone();
            if (StringUtils.hasText(b.contactEmail())) email = b.contactEmail();
        }

        return new CompanyBrand(LuxuryQuotationPdfDto.CompanySection.builder()
                .name(fmt.blankToNull(name))
                .tagline(fmt.blankToNull(defaultCompanyTagline))
                .logoUrl(fmt.blankToNull(logo))
                .phone(fmt.blankToNull(phone))
                .email(fmt.blankToNull(email))
                .website(fmt.blankToNull(website))
                .address(fmt.blankToNull(address))
                .gstin(fmt.blankToNull(gstin))
                .experienceLabel(years != null && years > 0 ? years + " Years of Experience" : null)
                .reviewsLabel(reviews != null && reviews > 0 ? reviews + " Google Reviews" : null)
                .build());
    }

    /**
     * The owning agent, resolved tenant-scoped.
     *
     * <p><b>Null on the public share-link path</b>, which runs with no {@link TenantContext}. The
     * alternative — a bare {@code findById(ownerUserId)} — reads a user row without any tenant
     * predicate, and this project's rule is that such a lookup is a cross-tenant read waiting to
     * happen. A hidden agent block is a smaller cost than a bypass, so the customer-facing link
     * simply carries company branding without the individual's card.
     */
    private LuxuryQuotationPdfDto.AgentSection agent(QuotationResponseDto q) {
        Long ownerId = q.getOwnerUserId();
        Long tenantId = TenantContext.getTenantId();
        if (ownerId == null || tenantId == null) return null;

        Optional<User> owner = userRepository.findByIdAndTenantIdAndDeletedAtIsNull(ownerId, tenantId);
        if (owner.isEmpty()) return null;
        User u = owner.get();

        return LuxuryQuotationPdfDto.AgentSection.builder()
                .name(fmt.blankToNull(u.getName()))
                .designation(fmt.label(u.getRole()))
                .phone(fmt.blankToNull(u.getPhoneNumber()))
                .email(fmt.blankToNull(u.getEmail()))
                // No profile-photo or signature column exists on User; the template falls back to
                // the neutral placeholder rather than showing a broken frame.
                .photoUrl(null)
                .signatureUrl(null)
                .build();
    }

    // ── Journey snapshot ──────────────────────────────────────────────────────

    private List<LuxuryQuotationPdfDto.SnapshotItem> snapshotItems(QuotationResponseDto q, CompanyBrand brand) {
        List<LuxuryQuotationPdfDto.SnapshotItem> items = new ArrayList<>();
        addSnapshot(items, "destination", "Destination", q.getCustomer() != null
                ? fmt.blankToNull(q.getCustomer().getDestination()) : null);
        addSnapshot(items, "duration", "Duration", fmt.duration(q.getNights(), q.getDays()));
        addSnapshot(items, "calendar", "Travel Dates", travelDateRange(q));
        addSnapshot(items, "travellers", "Travellers", q.getCustomer() == null ? null
                : fmt.travellers(q.getCustomer().getAdults(), q.getCustomer().getChildren(),
                                 q.getCustomer().getInfants()));
        addSnapshot(items, "rooms", "Accommodation", fmt.rooms(q.getRooms()));
        addSnapshot(items, "transport", "Transport", transportSummary(q));
        return items;
    }

    /** Only tiles with a real value are added — an empty grid cell reads as a rendering fault. */
    private void addSnapshot(List<LuxuryQuotationPdfDto.SnapshotItem> items,
                             String iconKey, String label, String value) {
        if (value == null) return;
        items.add(LuxuryQuotationPdfDto.SnapshotItem.builder()
                .iconKey(iconKey).label(label).value(value).build());
    }

    // ── Itinerary ─────────────────────────────────────────────────────────────

    /**
     * The day-by-day narrative, flattened from the sightseeing section.
     *
     * <p>The quotation models a day as a container of activities; the Luxury page presents one card
     * per day. Activity titles become the card heading and their descriptions are joined into the
     * body, so a day with three attractions reads as one paragraph rather than three stacked cards
     * that would blow the page height.
     */
    private List<LuxuryQuotationPdfDto.ItineraryDay> itineraryDays(QuotationResponseDto q) {
        var section = q.getSightseeing();
        if (section == null || !Boolean.TRUE.equals(section.getIncluded()) || section.getDays() == null) {
            return List.of();
        }
        List<LuxuryQuotationPdfDto.ItineraryDay> out = new ArrayList<>();
        for (var day : section.getDays()) {
            if (day == null) continue;
            List<String> titles = new ArrayList<>();
            List<String> bodies = new ArrayList<>();
            List<String> meals = new ArrayList<>();
            String transfer = null;
            String image = null;

            if (day.getActivities() != null) {
                for (var a : day.getActivities()) {
                    if (a == null) continue;
                    String title = fmt.blankToNull(a.getAttraction());
                    if (title != null) titles.add(title);
                    // bullets(), not plain(): the agent wrote these as a list in the rich-text
                    // editor and each point becomes its own line on the day card.
                    bodies.addAll(fmt.bullets(a.getDescription()));
                    if (a.getMeals() != null) {
                        for (String m : a.getMeals()) {
                            String cleaned = fmt.blankToNull(m);
                            if (cleaned != null && !meals.contains(cleaned)) meals.add(cleaned);
                        }
                    }
                    if (transfer == null) transfer = fmt.blankToNull(a.getTransfer());
                    if (image == null) image = fmt.blankToNull(a.getImagePath());
                }
            }

            out.add(LuxuryQuotationPdfDto.ItineraryDay.builder()
                    .dayLabel(day.getDay() != null ? "Day " + day.getDay() : null)
                    .date(fmt.date(day.getDate()))
                    .title(titles.isEmpty() ? null : String.join(" • ", titles))
                    .descriptionLines(List.copyOf(bodies))
                    .meals(meals.isEmpty() ? null : String.join(", ", meals))
                    .transfer(transfer)
                    .imageUrl(image)
                    .build());
        }
        return out;
    }

    private List<LuxuryQuotationPdfDto.ItineraryPage> itineraryPages(
            List<LuxuryQuotationPdfDto.ItineraryDay> days) {
        // The paginator measures prose length to decide 3-vs-4 days per page; the lines are joined
        // purely for that measurement, never for display.
        var chunks = paginator.itineraryPages(days,
                d -> String.join(" ", d.getDescriptionLines()));
        List<LuxuryQuotationPdfDto.ItineraryPage> pages = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            pages.add(LuxuryQuotationPdfDto.ItineraryPage.builder()
                    .pageNumber(i + 1).days(chunks.get(i)).build());
        }
        return pages;
    }

    // ── Hotels ────────────────────────────────────────────────────────────────

    private List<LuxuryQuotationPdfDto.HotelSection> hotels(QuotationResponseDto q) {
        var section = q.getHotel();
        if (section == null || !Boolean.TRUE.equals(section.getIncluded()) || section.getHotels() == null) {
            return List.of();
        }
        List<LuxuryQuotationPdfDto.HotelSection> out = new ArrayList<>();
        for (var h : section.getHotels()) {
            if (h == null) continue;
            LocalDate in = fmt.parse(h.getCheckIn());
            LocalDate out2 = fmt.parse(h.getCheckOut());
            Integer nights = (in != null && out2 != null && out2.isAfter(in))
                    ? (int) java.time.temporal.ChronoUnit.DAYS.between(in, out2) : null;

            out.add(LuxuryQuotationPdfDto.HotelSection.builder()
                    .name(fmt.blankToNull(h.getName()))
                    .city(fmt.blankToNull(h.getCity()))
                    .starLabel(fmt.stars(h.getStars()))
                    .checkIn(fmt.date(h.getCheckIn()))
                    .checkOut(fmt.date(h.getCheckOut()))
                    .nightsLabel(fmt.nights(nights))
                    .roomType(fmt.blankToNull(h.getRoomType()))
                    .mealPlan(fmt.blankToNull(h.getMealPlan()))
                    .roomsLabel(fmt.rooms(h.getRooms()))
                    // Tri-state on purpose: null means the agent never answered, and asserting
                    // "Non-Refundable" on silence is a claim the document cannot support.
                    .refundableLabel(h.getRefundable() == null ? null
                            : (h.getRefundable() ? "Refundable" : "Non-Refundable"))
                    .imageUrl(fmt.blankToNull(h.getImagePath()))
                    .build());
        }
        return out;
    }

    private List<LuxuryQuotationPdfDto.HotelPage> hotelPages(List<LuxuryQuotationPdfDto.HotelSection> hotels,
                                                             List<String> noteLines) {
        var chunks = paginator.hotelPages(hotels);
        List<LuxuryQuotationPdfDto.HotelPage> pages = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            pages.add(LuxuryQuotationPdfDto.HotelPage.builder()
                    .pageNumber(i + 1)
                    .hotels(chunks.get(i))
                    // Notes belong to the whole section, so they print once, under the first page.
                    .noteLines(i == 0 ? noteLines : List.of())
                    .build());
        }
        return pages;
    }

    // ── Transport & sightseeing ───────────────────────────────────────────────

    private String transportSummary(QuotationResponseDto q) {
        var section = q.getVehicle();
        if (section == null || !Boolean.TRUE.equals(section.getIncluded())
                || section.getVehicles() == null || section.getVehicles().isEmpty()) {
            return null;
        }
        List<String> types = new ArrayList<>();
        for (var v : section.getVehicles()) {
            String t = v == null ? null : fmt.blankToNull(v.getType());
            if (t != null && !types.contains(t)) types.add(t);
        }
        return types.isEmpty() ? null : String.join(", ", types);
    }

    private LuxuryQuotationPdfDto.TransportSection transport(QuotationResponseDto q) {
        var section = q.getVehicle();
        if (section == null || !Boolean.TRUE.equals(section.getIncluded())) return null;

        List<LuxuryQuotationPdfDto.DisplayValueItem> rows = new ArrayList<>();
        if (section.getVehicles() != null) {
            for (var v : section.getVehicles()) {
                if (v == null) continue;
                String label = fmt.blankToNull(v.getType());
                if (label == null) continue;
                List<String> parts = new ArrayList<>();
                String pickup = fmt.blankToNull(v.getPickup());
                String drop = fmt.blankToNull(v.getDrop());
                if (pickup != null && drop != null) parts.add(pickup + " → " + drop);
                else if (pickup != null) parts.add(pickup);
                else if (drop != null) parts.add(drop);
                if (v.getQty() != null && v.getQty() > 0) {
                    parts.add(v.getQty() + (v.getQty() == 1 ? " Vehicle" : " Vehicles"));
                }
                rows.add(LuxuryQuotationPdfDto.DisplayValueItem.builder()
                        .label(label)
                        .value(parts.isEmpty() ? null : String.join(" · ", parts))
                        .build());
            }
        }
        if (rows.isEmpty()) return null;

        // Per-vehicle notes are section-level in the document: they are collected across the rows
        // and de-duplicated, because agents commonly type the same note ("Toll and parking extra")
        // on every vehicle and repeating it under each one reads as a mistake.
        List<String> notes = new ArrayList<>();
        if (section.getVehicles() != null) {
            for (var v : section.getVehicles()) {
                if (v == null) continue;
                for (String line : fmt.bullets(v.getNotes())) {
                    if (!notes.contains(line)) notes.add(line);
                }
            }
        }

        return LuxuryQuotationPdfDto.TransportSection.builder()
                .summary(transportSummary(q))
                .vehicles(rows)
                .noteLines(List.copyOf(notes))
                .build();
    }

    private List<LuxuryQuotationPdfDto.SightseeingItem> sightseeingItems(QuotationResponseDto q) {
        var section = q.getSightseeing();
        if (section == null || !Boolean.TRUE.equals(section.getIncluded()) || section.getDays() == null) {
            return List.of();
        }
        List<LuxuryQuotationPdfDto.SightseeingItem> out = new ArrayList<>();
        for (var day : section.getDays()) {
            if (day == null || day.getActivities() == null) continue;
            for (var a : day.getActivities()) {
                if (a == null) continue;
                String title = fmt.blankToNull(a.getAttraction());
                if (title == null) continue;
                out.add(LuxuryQuotationPdfDto.SightseeingItem.builder()
                        .title(title)
                        .dayLabel(day.getDay() != null ? "Day " + day.getDay() : null)
                        .descriptionLines(fmt.bullets(a.getDescription()))
                        .imageUrl(fmt.blankToNull(a.getImagePath()))
                        .build());
            }
        }
        return out;
    }

    private List<String> sightseeingImages(QuotationResponseDto q) {
        List<String> urls = new ArrayList<>();
        for (var item : sightseeingItems(q)) {
            if (item.getImageUrl() != null && !urls.contains(item.getImageUrl())) urls.add(item.getImageUrl());
        }
        return paginator.sightseeingGallery(urls);
    }

    // ── Money ─────────────────────────────────────────────────────────────────

    /**
     * The pricing page, built entirely from {@code Totals} — the figures the server already computed.
     *
     * <p>Every adjustment line is emitted even when its value is <b>zero</b>. That is the point of
     * having them as named fields rather than rows: a ₹0 discount and a 0% tax are statements the
     * document must be able to make. Hiding them on falsiness is how a customer ends up with a total
     * they cannot reconcile against the lines above it.
     */
    private LuxuryQuotationPdfDto.PricingSection pricing(QuotationResponseDto q) {
        var t = q.getTotals();
        if (t == null) return null;

        List<LuxuryQuotationPdfDto.PricingRow> rows = new ArrayList<>();
        addPricingRow(rows, "Flights", q.getFlight() == null ? null : q.getFlight().getIncluded(),
                q.getFlight() == null ? null : q.getFlight().getAmount(),
                q.getFlight() == null ? null : q.getFlight().getJourney());
        addPricingRow(rows, "Accommodation", q.getHotel() == null ? null : q.getHotel().getIncluded(),
                q.getHotel() == null ? null : q.getHotel().getAmount(), fmt.rooms(q.getRooms()));
        addPricingRow(rows, "Sightseeing", q.getSightseeing() == null ? null : q.getSightseeing().getIncluded(),
                q.getSightseeing() == null ? null : q.getSightseeing().getAmount(), null);
        addPricingRow(rows, "Cruise", q.getCruise() == null ? null : q.getCruise().getIncluded(),
                q.getCruise() == null ? null : q.getCruise().getAmount(), null);
        addPricingRow(rows, "Transport", q.getVehicle() == null ? null : q.getVehicle().getIncluded(),
                q.getVehicle() == null ? null : q.getVehicle().getAmount(), transportSummary(q));
        addPricingRow(rows, "Add-on Services", q.getAddons() == null ? null : q.getAddons().getIncluded(),
                q.getAddons() == null ? null : q.getAddons().getAmount(), null);

        // The discount label carries the RATE the agent entered when it is a percentage, because
        // "Discount (10%)  −₹7,650" is checkable by the customer and a bare "−₹7,650" is not.
        String discountLabel = "Discount";
        if (t.getDiscountType() != null && t.getDiscount() != null
                && "PERCENT".equalsIgnoreCase(t.getDiscountType().name())) {
            discountLabel = "Discount (" + fmt.percent(t.getDiscount()) + ")";
        }

        return LuxuryQuotationPdfDto.PricingSection.builder()
                .rows(rows)
                .subtotal(fmt.money(t.getSubtotal()))
                .discountLabel(discountLabel)
                .discountAmount(fmt.money(t.getDiscountAmount()))
                .markup(fmt.money(t.getMarkup()))
                .taxLabel("GST (" + fmt.percent(t.getTaxPercent()) + ")")
                .taxAmount(fmt.money(t.getTaxAmount()))
                .grandTotal(fmt.money(t.getGrandTotal()))
                // Null, not ₹0: "per adult" is meaningless when the traveller count is unknown, and
                // printing ₹0 there would read as a free trip rather than as missing information.
                .perAdult(t.getPerAdult() != null ? fmt.money(t.getPerAdult()) : null)
                .statusLabel(fmt.label(q.getQuotationStage()))
                .statusCode(q.getQuotationStage() != null ? q.getQuotationStage().name() : null)
                .build();
    }

    /**
     * A section line appears when the section is included AND carries money — the same rule
     * {@code QuotationPdfService.rendersSomething} applies to the other three designs, so the money
     * page cannot disagree with the body of the document about which sections exist.
     */
    private void addPricingRow(List<LuxuryQuotationPdfDto.PricingRow> rows, String label,
                               Boolean included, BigDecimal amount, String detail) {
        if (!Boolean.TRUE.equals(included)) return;
        if (amount == null || amount.signum() == 0) return;
        rows.add(LuxuryQuotationPdfDto.PricingRow.builder()
                .label(label).detail(fmt.blankToNull(detail)).amount(fmt.money(amount)).build());
    }

    /**
     * Payment milestones, as the agent typed them.
     *
     * <p>The quotation stores these as free-text lines, not as structured amount/date pairs, so they
     * are carried through as the milestone text with no due date or amount. Splitting the sentence
     * to guess "50% on booking" into a date and a figure would be the mapper inventing a payment
     * obligation the customer could be held to.
     */
    private List<LuxuryQuotationPdfDto.PaymentScheduleItem> paymentSchedule(QuotationResponseDto q) {
        List<LuxuryQuotationPdfDto.PaymentScheduleItem> out = new ArrayList<>();
        for (String line : cleanList(q.getPaymentPolicies())) {
            out.add(LuxuryQuotationPdfDto.PaymentScheduleItem.builder()
                    .milestone(line).dueLabel(null).amount(null).build());
        }
        return out;
    }

    /** Same shape as the payment schedule: free text in, one row out, nothing parsed or assumed. */
    private List<LuxuryQuotationPdfDto.CancellationRow> cancellationRows(QuotationResponseDto q) {
        List<LuxuryQuotationPdfDto.CancellationRow> out = new ArrayList<>();
        for (String line : cleanList(q.getCancellationPolicies())) {
            out.add(LuxuryQuotationPdfDto.CancellationRow.builder().window(line).charge(null).build());
        }
        return out;
    }

    private List<String> terms(QuotationResponseDto q) {
        return cleanList(q.getBookingTerms());
    }

    /**
     * The closing policy blocks, in reading order, packed onto as few sheets as they fit.
     *
     * <p>Order is payment → cancellation → conditions because that is the order a customer needs
     * them: what to pay, what happens if they back out, then the general terms. The packer keeps
     * that order rather than rearranging blocks to fill pages more tightly.
     *
     * <p>Empty blocks are omitted here rather than filtered later, so a quotation with no
     * cancellation policy simply has no such heading — not an empty one.
     */
    private List<LuxuryQuotationPdfDto.PolicyPage> policyPages(QuotationResponseDto q, List<String> terms) {
        List<LuxuryQuotationPdfDto.PolicyBlock> blocks = new ArrayList<>();
        addPolicyBlock(blocks, "Payment Schedule", cleanList(q.getPaymentPolicies()));
        addPolicyBlock(blocks, "Cancellation Policy", cleanList(q.getCancellationPolicies()));
        addPolicyBlock(blocks, "Terms & Conditions", terms);
        return paginator.packPolicyPages(blocks);
    }

    private void addPolicyBlock(List<LuxuryQuotationPdfDto.PolicyBlock> blocks,
                                String title, List<String> items) {
        if (items == null || items.isEmpty()) return;
        blocks.add(LuxuryQuotationPdfDto.PolicyBlock.builder()
                .title(title).items(items).continued(false).build());
    }

    /** Drops nulls and blanks and flattens rich text — never returns null, so {@code th:each} is safe. */
    private List<String> cleanList(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : raw) {
            String cleaned = fmt.plain(s);
            if (cleaned != null) out.add(cleaned);
        }
        return out;
    }
}
