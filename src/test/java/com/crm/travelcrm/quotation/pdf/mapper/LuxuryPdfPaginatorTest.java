package com.crm.travelcrm.quotation.pdf.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chunking rules that stop a fixed-height A4 page from silently clipping its content.
 *
 * <p>The failure this guards against leaves no trace: Chromium overflows a fixed-height box and
 * crops it at the sheet edge, so the PDF looks finished and days 9 and 10 are simply absent. Every
 * assertion here is really the same assertion — nothing is ever dropped.
 */
class LuxuryPdfPaginatorTest {

    private final LuxuryPdfPaginator paginator = new LuxuryPdfPaginator();

    private static List<String> items(int n) {
        return IntStream.rangeClosed(1, n).mapToObj(i -> "item " + i).toList();
    }

    @Test
    @DisplayName("short itinerary days pack four to a page")
    void shortDaysFitFourPerPage() {
        var pages = paginator.itineraryPages(items(9), s -> s);
        assertThat(pages).hasSize(3);
        assertThat(pages.get(0)).hasSize(4);
        assertThat(pages.get(2)).hasSize(1);
    }

    /**
     * Long prose drops the whole itinerary to three per page — not just the wordy page.
     * A grid that changes density halfway through the document reads as a layout fault.
     */
    @Test
    @DisplayName("one long description re-paginates the entire itinerary")
    void longDescriptionsDropToThreePerPage() {
        String longOne = "x".repeat(LuxuryPdfPaginator.LONG_DESCRIPTION_CHARS + 1);
        List<String> days = List.of("short", "short", "short", longOne, "short", "short");

        var pages = paginator.itineraryPages(days, s -> s);
        assertThat(pages).hasSize(2);
        assertThat(pages.get(0)).hasSize(3);
        assertThat(pages.get(1)).hasSize(3);
    }

    @Test
    @DisplayName("hotels cap at three per page")
    void hotelsChunkByThree() {
        var pages = paginator.hotelPages(items(7));
        assertThat(pages).hasSize(3);
        assertThat(pages.get(0)).hasSize(3);
        assertThat(pages.get(2)).hasSize(1);
    }

    /**
     * The whole point: a long list becomes more pages, never fewer items. Terms are legal text and
     * a clipped clause is the worst thing this design could do.
     */
    @Test
    @DisplayName("no item is ever dropped, however long the list")
    void everyItemSurvivesChunking() {
        List<String> terms = items(97);
        var pages = paginator.termsPages(terms);
        assertThat(pages.stream().mapToInt(List::size).sum()).isEqualTo(97);
        assertThat(pages.stream().flatMap(List::stream).toList()).containsExactlyElementsOf(terms);
    }

    /** Empty in, empty out — NOT one empty page, which would print a blank sheet. */
    @Test
    @DisplayName("empty input produces no pages at all")
    void emptyInputProducesNoPages() {
        assertThat(paginator.itineraryPages(List.<String>of(), s -> s)).isEmpty();
        assertThat(paginator.hotelPages(null)).isEmpty();
        assertThat(paginator.termsPages(List.<String>of())).isEmpty();
    }

    @Test
    @DisplayName("the gallery takes at most three images and tolerates fewer")
    void galleryCapsAtThree() {
        assertThat(paginator.sightseeingGallery(items(8))).hasSize(3);
        assertThat(paginator.sightseeingGallery(items(2))).hasSize(2);
        assertThat(paginator.sightseeingGallery(null)).isEmpty();
    }
}
