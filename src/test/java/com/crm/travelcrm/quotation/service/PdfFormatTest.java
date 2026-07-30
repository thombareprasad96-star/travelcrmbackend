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
    void dateReturnsDashForNullLocalDate() {
        assertThat(fmt.date(null)).isEqualTo("-");
    }
}
