package com.crm.travelcrm.quotation.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PdfFormatTest {

    private final PdfFormat fmt = new PdfFormat();

    @Test
    void dateFormatsLocalDate() {
        assertThat(fmt.date(LocalDate.of(2026, 8, 15))).isEqualTo("15 Aug 2026");
    }

    @Test
    void dateFormatsIsoString() {
        assertThat(fmt.date("2026-08-15")).isEqualTo("15 Aug 2026");
    }

    @Test
    void dateReturnsDashForBlankString() {
        assertThat(fmt.date("")).isEqualTo("-");
        assertThat(fmt.date("   ")).isEqualTo("-");
    }

    @Test
    void dateLeavesNonIsoStringReadable() {
        assertThat(fmt.date("15 Aug")).isEqualTo("15 Aug");
    }
}
