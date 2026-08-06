package com.crm.travelcrm.quotation.pdf.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * The display-ready model the LUXURY template binds to, handed to Thymeleaf as {@code ${pdf}}.
 *
 * <p><b>Every field is a finished String.</b> No {@code BigDecimal}, no {@code LocalDate}, no enum,
 * no entity. Formatting, rounding, currency grouping, date ranges, pluralisation and pagination all
 * happen in {@link com.crm.travelcrm.quotation.pdf.mapper.LuxuryQuotationPdfMapper} before this
 * object exists. Two reasons, and the second is the one that bites:
 *
 * <ol>
 *   <li>A template that can call {@code .add()} or {@code #numbers.formatDecimal()} is a template
 *       that can compute money, and then the document and the database disagree about the total.
 *   <li>Chromium renders with whatever locale the container happens to have. {@code ₹1,25,000}
 *       (Indian grouping) and {@code ₹125,000} (Western grouping) are the same number to a computer
 *       and different documents to a customer. Formatting in Java pins it.
 * </ol>
 *
 * <p><b>Lombok classes, not records</b>, for the nested sections. Records were the natural fit —
 * these are immutable view models — but their accessors are {@code label()}, not {@code getLabel()},
 * and Thymeleaf resolves {@code ${item.label}} through SpEL's JavaBean property lookup. Using
 * records would make every nested field silently unresolvable in the template. This also matches
 * {@code QuotationResponseDto}'s own nested-class convention.
 *
 * <p>Null means "hide this section". The template guards every optional block with
 * {@code th:if}, so an unavailable field produces a shorter document, never the text "null".
 */
@Getter
@Builder
public class LuxuryQuotationPdfDto {

    // ── Identity & headline ───────────────────────────────────────────────────
    /** Human reference, e.g. {@code QT-26-0045}. Never the publicId — a UUID is not a document number. */
    private final String quotationCode;
    private final String customerName;
    private final String packageTitle;
    private final String packageSubtitle;
    private final String destination;
    /** e.g. {@code 6 Nights / 7 Days}. */
    private final String duration;
    /** e.g. {@code 20 Aug 2026 – 26 Aug 2026}. */
    private final String travelDateRange;
    /** e.g. {@code 2 Adults, 1 Child}. */
    private final String travellerSummary;
    private final String transportSummary;
    /** e.g. {@code Domestic} / {@code International}, or null when unknown. */
    private final String tripType;

    // ── Artwork ───────────────────────────────────────────────────────────────
    private final String coverImageUrl;
    private final String snapshotImageUrl;
    private final String inclusionBackgroundImageUrl;
    private final String closingImageUrl;

    // ── Copy ──────────────────────────────────────────────────────────────────
    private final String welcomeMessage;
    private final String closingMessage;

    // ── Share ─────────────────────────────────────────────────────────────────
    private final String qrCodeImageUrl;
    private final String qrCaption;

    /**
     * Local classpath fallbacks, as root-relative URLs.
     *
     * <p>These are handed to the template rather than hardcoded in CSS because the failure they
     * cover is a RUNTIME one: a Cloudinary URL that 404s, a hotel row whose image was never
     * uploaded. The template's {@code onerror} swaps to these, so one dead image costs a
     * placeholder instead of a blank frame in a document already sent to a customer.
     */
    private final String fallbackCoverImageUrl;
    private final String fallbackLogoUrl;
    private final String fallbackHotelImageUrl;
    private final String fallbackVehicleImageUrl;
    private final String fallbackSightseeingImageUrl;
    private final String fallbackAgentImageUrl;

    // ── Sections ──────────────────────────────────────────────────────────────
    private final CompanySection company;
    private final AgentSection agent;
    private final List<SnapshotItem> snapshotItems;
    private final List<ItineraryPage> itineraryPages;
    private final List<HotelPage> hotelPages;
    private final TransportSection transport;
    private final List<SightseeingItem> sightseeingItems;
    /** Up to three gallery images for the transport/sightseeing spread. */
    private final List<String> sightseeingImages;
    private final List<String> inclusions;
    private final List<String> exclusions;
    private final PricingSection pricing;
    private final List<PaymentScheduleItem> paymentSchedule;
    private final List<CancellationRow> cancellationRows;
    /** Already chunked into page-sized blocks — see the mapper's paginator. */
    private final List<List<String>> terms;

    /**
     * Payment schedule, cancellation policy and terms, PACKED onto as few A4 sheets as they fit.
     *
     * <p>This is what the template renders; the three fields above are the raw source, kept because
     * they are the documented model contract and a different template may want them separately.
     *
     * <p><b>Why packing rather than one section per sheet.</b> The first cut gave each of the three
     * its own fixed-height page, which is correct and looks wrong: a quotation with three
     * cancellation lines and five terms produced two almost-empty A4 sheets. Packing merges short
     * blocks onto one sheet and only spills to another when the content genuinely does not fit —
     * so a long terms list still gets the pages it needs and a short one costs nothing.
     */
    private final List<PolicyPage> policyPages;

    // ── Nested view models ────────────────────────────────────────────────────

    /** Tenant (or white-labelled sub-agent) identity on the cover and every footer. */
    @Getter
    @Builder
    public static class CompanySection {
        private final String name;
        private final String tagline;
        private final String logoUrl;
        private final String phone;
        private final String email;
        private final String website;
        private final String address;
        private final String gstin;
        /** e.g. {@code 12 Years of Experience}; null when the company never set operatingSince. */
        private final String experienceLabel;
        /** e.g. {@code 1240 Google Reviews}; null when unset. */
        private final String reviewsLabel;
    }

    /** The human the customer talks to. Every field optional — an agent may have no photo. */
    @Getter
    @Builder
    public static class AgentSection {
        private final String name;
        private final String designation;
        private final String phone;
        private final String email;
        private final String photoUrl;
        private final String signatureUrl;
    }

    /** One tile in the journey-snapshot grid: an icon key, a label and a finished value. */
    @Getter
    @Builder
    public static class SnapshotItem {
        private final String iconKey;
        private final String label;
        private final String value;
    }

    /** One A4 itinerary page holding at most a handful of whole days — never a split day. */
    @Getter
    @Builder
    public static class ItineraryPage {
        private final int pageNumber;
        private final List<ItineraryDay> days;
    }

    @Getter
    @Builder
    public static class ItineraryDay {
        /** e.g. {@code Day 3}. */
        private final String dayLabel;
        private final String date;
        private final String title;
        /**
         * The description as the agent structured it — one entry per bullet or paragraph.
         *
         * <p>A list rather than a String because the builder's rich-text editor is where these are
         * written, and flattening {@code <ul><li>…</li></ul>} into one paragraph turned a six-point
         * day into a run-on sentence. The template draws the markers; these are just the lines.
         */
        private final List<String> descriptionLines;
        /** e.g. {@code Breakfast, Dinner}; null when no meals are attached. */
        private final String meals;
        private final String transfer;
        private final String imageUrl;
    }

    /** One A4 hotel page holding at most three hotel cards. */
    @Getter
    @Builder
    public static class HotelPage {
        private final int pageNumber;
        private final List<HotelSection> hotels;
        /**
         * Section-level accommodation notes, one entry per bullet. Carried on the page rather than
         * on each hotel because the quotation stores them once for the whole section; the mapper
         * attaches them to the FIRST page so they are not repeated on every sheet.
         */
        private final List<String> noteLines;
    }

    @Getter
    @Builder
    public static class HotelSection {
        private final String name;
        private final String city;
        /** {@code ★★★★☆} — precomputed, so the template never loops to draw stars. */
        private final String starLabel;
        private final String checkIn;
        private final String checkOut;
        private final String nightsLabel;
        private final String roomType;
        private final String mealPlan;
        private final String roomsLabel;
        private final String refundableLabel;
        private final String imageUrl;
    }

    @Getter
    @Builder
    public static class TransportSection {
        private final String summary;
        private final List<DisplayValueItem> vehicles;
        /** Vehicle notes, one entry per bullet — same reasoning as {@link ItineraryDay}. */
        private final List<String> noteLines;
    }

    @Getter
    @Builder
    public static class SightseeingItem {
        private final String title;
        private final String dayLabel;
        /** One entry per bullet — same reasoning as {@link ItineraryDay}. */
        private final List<String> descriptionLines;
        private final String imageUrl;
    }

    /**
     * The money page. Every row is a finished {@code ₹…} string.
     *
     * <p>{@link #rows} carries only the section lines that actually appear; the adjustment lines
     * below are separate fields because each must be able to print a genuine <b>zero</b>. A 0%
     * discount is information — it tells the customer nothing was knocked off — and collapsing it
     * into "hide when falsy" is how ₹0 GST silently disappears from a document that legally needs
     * to show it.
     */
    @Getter
    @Builder
    public static class PricingSection {
        private final List<PricingRow> rows;
        private final String subtotal;
        private final String discountLabel;
        private final String discountAmount;
        private final String markup;
        private final String taxLabel;
        private final String taxAmount;
        private final String grandTotal;
        private final String perAdult;
        /** e.g. {@code Pending} — the human label, never the enum name. */
        private final String statusLabel;
        /** The machine value, e.g. {@code PENDING}, for CSS class selection only. */
        private final String statusCode;
    }

    @Getter
    @Builder
    public static class PricingRow {
        private final String label;
        private final String detail;
        private final String amount;
    }

    /** A generic label/value line for the transport and terms blocks. */
    @Getter
    @Builder
    public static class DisplayValueItem {
        private final String label;
        private final String value;
    }

    @Getter
    @Builder
    public static class PaymentScheduleItem {
        private final String milestone;
        private final String dueLabel;
        private final String amount;
    }

    @Getter
    @Builder
    public static class CancellationRow {
        private final String window;
        private final String charge;
    }

    /** One A4 sheet of closing policy content, holding whichever blocks fitted on it. */
    @Getter
    @Builder
    public static class PolicyPage {
        private final List<PolicyBlock> blocks;
    }

    /**
     * A titled list of policy lines — or the part of one that fitted on this sheet.
     *
     * <p>{@link #continued} is set on the second and later parts of a block that had to be split,
     * so the template can print "Terms &amp; Conditions (continued)". Without it a reader meets the
     * same heading twice and reasonably assumes the document repeated itself.
     */
    @Getter
    @Builder
    public static class PolicyBlock {
        private final String title;
        private final List<String> items;
        private final boolean continued;
    }
}
