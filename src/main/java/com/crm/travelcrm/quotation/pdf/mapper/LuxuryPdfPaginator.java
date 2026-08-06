package com.crm.travelcrm.quotation.pdf.mapper;

import com.crm.travelcrm.quotation.pdf.dto.LuxuryQuotationPdfDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides, in Java, how many items go on each fixed-height A4 page of the LUXURY document.
 *
 * <p><b>Why pagination cannot be left to the browser here.</b> The Luxury template is not a flowing
 * document — it is a deck of {@code .page} elements each pinned to exactly one A4 sheet, which is
 * what lets every page carry its own footer, page number and background artwork. Chromium will
 * happily overflow content out of a fixed-height box and clip it at the sheet edge, silently: the
 * PDF looks finished, and the customer never sees days 9 and 10 of their itinerary. Chunking here
 * means the overflow becomes an extra page instead of missing text.
 *
 * <p><b>Why the itinerary limit is content-sensitive.</b> Four short days fit; four days with
 * paragraph-length descriptions do not. Rather than shrink the font — which produces a technically
 * complete but unreadable document — the limit drops to three when the prose is long. A single day
 * is never split across two pages: half a day's description continuing under a new heading reads as
 * a formatting fault, not as a page break.
 */
@Component
public class LuxuryPdfPaginator {

    /** Days per itinerary page when descriptions are short enough to sit side by side. */
    static final int ITINERARY_DAYS_PER_PAGE = 4;

    /** Days per itinerary page once the prose is long — see {@link #LONG_DESCRIPTION_CHARS}. */
    static final int ITINERARY_DAYS_PER_PAGE_LONG = 3;

    /**
     * The point at which a day's description stops being a caption and starts being a paragraph.
     * Measured against the template's day card at its designed font size; roughly three printed
     * lines. Deliberately generous — dropping to three days per page costs one extra sheet, while
     * getting it wrong the other way costs clipped text.
     */
    static final int LONG_DESCRIPTION_CHARS = 320;

    /** Hotel cards per page. Three full-bleed cards is what the designed grid holds. */
    static final int HOTELS_PER_PAGE = 3;

    /** Gallery images on the transport/sightseeing spread. */
    static final int SIGHTSEEING_IMAGES = 3;

    /** Terms lines per page. Conservative: terms are legal text and clipping one is not an option. */
    static final int TERMS_PER_PAGE = 18;

    /**
     * Splits itinerary days into page-sized groups, choosing the per-page count from the longest
     * description in the whole list rather than per page.
     *
     * <p>Per-page would be cleverer and worse: it would put four days on page one and three on page
     * two purely because a later day happened to be wordier, producing a document whose grid
     * visibly changes density halfway through. One decision for the whole itinerary keeps it even.
     */
    public <T> List<List<T>> itineraryPages(List<T> days, java.util.function.Function<T, String> description) {
        if (days == null || days.isEmpty()) return List.of();
        boolean longProse = days.stream()
                .map(description)
                .anyMatch(d -> d != null && d.length() > LONG_DESCRIPTION_CHARS);
        return chunk(days, longProse ? ITINERARY_DAYS_PER_PAGE_LONG : ITINERARY_DAYS_PER_PAGE);
    }

    public <T> List<List<T>> hotelPages(List<T> hotels) {
        return chunk(hotels, HOTELS_PER_PAGE);
    }

    public <T> List<List<T>> termsPages(List<T> terms) {
        return chunk(terms, TERMS_PER_PAGE);
    }

    // ── Closing policy pages ──────────────────────────────────────────────────

    /**
     * Vertical space on one A4 policy sheet, in "line units".
     *
     * <p>Derived from the template, not guessed: 297mm minus the page's 18mm/22mm padding leaves
     * 257mm; the page eyebrow costs about 20mm; a 9.5pt line at 1.7 line-height is about 5.7mm.
     * That gives ~41 lines, and the budget is set below it because a wrapped line and a list marker
     * both cost a little more than the arithmetic says. Under-filling costs whitespace;
     * over-filling costs clipped text, and only one of those is recoverable.
     */
    static final int PAGE_BUDGET_UNITS = 32;

    /** A block's heading plus the gap under it, in the same units. */
    static final int BLOCK_HEADING_UNITS = 3;

    /** Characters that fit on one rendered line at the policy list's font size and column width. */
    static final int CHARS_PER_LINE = 95;

    /**
     * Packs the closing blocks — payment schedule, cancellation policy, terms — onto as few sheets
     * as they fit, splitting a block across sheets only when it genuinely does not fit on one.
     *
     * <p>Greedy first-fit in the given order, because these blocks have a reading order: payment
     * terms before cancellation before the general conditions. A bin-packing algorithm would fill
     * pages more tightly by reordering them, and produce a document whose sections appear in an
     * order nobody chose.
     *
     * <p>Empty blocks are dropped rather than printed as a heading with nothing under it.
     */
    public List<LuxuryQuotationPdfDto.PolicyPage> packPolicyPages(
            List<LuxuryQuotationPdfDto.PolicyBlock> blocks) {

        List<LuxuryQuotationPdfDto.PolicyPage> pages = new ArrayList<>();
        List<LuxuryQuotationPdfDto.PolicyBlock> current = new ArrayList<>();
        int used = 0;

        for (LuxuryQuotationPdfDto.PolicyBlock block : blocks) {
            if (block == null || block.getItems() == null || block.getItems().isEmpty()) continue;

            List<String> remaining = block.getItems();
            boolean continued = false;

            while (!remaining.isEmpty()) {
                int free = PAGE_BUDGET_UNITS - used;

                // Not even the heading and one line fit here — start a fresh sheet. The
                // `!current.isEmpty()` guard stops an over-long single line from looping forever on
                // a page that is already empty; in that case it is placed anyway and allowed to be
                // the one thing on its sheet.
                if (free < BLOCK_HEADING_UNITS + itemUnits(remaining.get(0)) && !current.isEmpty()) {
                    pages.add(page(current));
                    current = new ArrayList<>();
                    used = 0;
                    free = PAGE_BUDGET_UNITS;
                }

                int budget = free - BLOCK_HEADING_UNITS;
                List<String> take = new ArrayList<>();
                int cost = 0;
                for (String item : remaining) {
                    int units = itemUnits(item);
                    if (!take.isEmpty() && cost + units > budget) break;
                    take.add(item);
                    cost += units;
                }

                current.add(LuxuryQuotationPdfDto.PolicyBlock.builder()
                        .title(block.getTitle())
                        .items(List.copyOf(take))
                        .continued(continued)
                        .build());
                used += BLOCK_HEADING_UNITS + cost;

                remaining = remaining.subList(take.size(), remaining.size());
                continued = true;

                // Anything left over starts the next sheet — this block filled this one.
                if (!remaining.isEmpty()) {
                    pages.add(page(current));
                    current = new ArrayList<>();
                    used = 0;
                }
            }
        }

        if (!current.isEmpty()) pages.add(page(current));
        return pages;
    }

    private static LuxuryQuotationPdfDto.PolicyPage page(List<LuxuryQuotationPdfDto.PolicyBlock> blocks) {
        return LuxuryQuotationPdfDto.PolicyPage.builder().blocks(List.copyOf(blocks)).build();
    }

    /** How many rendered lines one policy line costs — a long clause wraps and must be paid for. */
    private static int itemUnits(String text) {
        int length = text == null ? 0 : text.length();
        return Math.max(1, (int) Math.ceil(length / (double) CHARS_PER_LINE));
    }

    /** First {@value #SIGHTSEEING_IMAGES} images, or fewer. Never null. */
    public <T> List<T> sightseeingGallery(List<T> images) {
        if (images == null || images.isEmpty()) return List.of();
        return List.copyOf(images.subList(0, Math.min(SIGHTSEEING_IMAGES, images.size())));
    }

    /**
     * Fixed-size grouping. Returns an empty list (not a list containing an empty list) for no
     * input, so {@code th:each} over the result renders nothing rather than one blank page.
     */
    public <T> List<List<T>> chunk(List<T> items, int size) {
        if (items == null || items.isEmpty()) return List.of();
        if (size < 1) throw new IllegalArgumentException("page size must be >= 1");
        List<List<T>> pages = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            pages.add(List.copyOf(items.subList(i, Math.min(i + size, items.size()))));
        }
        return pages;
    }
}
