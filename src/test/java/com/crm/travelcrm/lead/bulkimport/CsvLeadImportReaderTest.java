package com.crm.travelcrm.lead.bulkimport;

import com.crm.travelcrm.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parsing rules for the CSV path. These are the cases that decide whether a real agency's export is
 * importable at all — every one of them was a plausible silent failure before it was pinned down.
 */
class CsvLeadImportReaderTest {

    private final CsvLeadImportReader reader = new CsvLeadImportReader();

    private MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "leads.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    // ── Header handling ──────────────────────────────────────────────────────

    @Test
    @DisplayName("headers match regardless of case, spacing and underscores")
    void headersAreMatchedLeniently() {
        LeadImportSheet sheet = reader.read(csv("""
                customer_name,PHONE ,Lead Source,leadtype
                Ravi Sharma,+919876543210,Website,Fresh
                """));

        assertThat(sheet.presentColumns()).contains(
                LeadImportColumn.CUSTOMER_NAME, LeadImportColumn.PHONE,
                LeadImportColumn.LEAD_SOURCE, LeadImportColumn.LEAD_TYPE);
        assertThat(sheet.rows()).hasSize(1);
        assertThat(sheet.rows().get(0).get(LeadImportColumn.CUSTOMER_NAME)).isEqualTo("Ravi Sharma");
    }

    @Test
    @DisplayName("a UTF-8 BOM does not swallow the first column — Excel's 'CSV UTF-8' export writes one")
    void bomIsStripped() {
        LeadImportSheet sheet = reader.read(csv("﻿Customer Name,Phone,Lead Source,Lead Type\n"
                + "Ravi Sharma,+919876543210,Website,Fresh\n"));

        assertThat(sheet.presentColumns()).contains(LeadImportColumn.CUSTOMER_NAME);
        assertThat(sheet.rows().get(0).get(LeadImportColumn.CUSTOMER_NAME)).isEqualTo("Ravi Sharma");
    }

    @Test
    @DisplayName("an unrecognised header is reported, not silently dropped")
    void unknownHeadersAreReported() {
        LeadImportSheet sheet = reader.read(csv("""
                Customer Name,Mobile No,Lead Source,Lead Type
                Ravi Sharma,+919876543210,Website,Fresh
                """));

        assertThat(sheet.ignoredHeaders()).containsExactly("Mobile No");
        assertThat(sheet.presentColumns()).doesNotContain(LeadImportColumn.PHONE);
    }

    @Test
    @DisplayName("a repeated column is reported rather than letting the last one silently win")
    void duplicateColumnIsReported() {
        LeadImportSheet sheet = reader.read(csv("""
                Customer Name,Phone,Phone,Lead Source,Lead Type
                Ravi,+919876543210,+919999999999,Website,Fresh
                """));

        assertThat(sheet.ignoredHeaders()).containsExactly("Phone (duplicate column)");
        assertThat(sheet.rows().get(0).get(LeadImportColumn.PHONE)).isEqualTo("+919876543210");
    }

    // ── Row handling ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("trailing blank lines are not rows — a spreadsheet export is full of them")
    void blankRowsAreDropped() {
        LeadImportSheet sheet = reader.read(csv("""
                Customer Name,Phone,Lead Source,Lead Type
                Ravi Sharma,+919876543210,Website,Fresh
                ,,,
                ,,,
                """));

        assertThat(sheet.rows()).hasSize(1);
    }

    @Test
    @DisplayName("the reported row number is the line the user sees in their file")
    void rowNumberMatchesTheUsersFile() {
        LeadImportSheet sheet = reader.read(csv("""
                Customer Name,Phone,Lead Source,Lead Type
                Ravi,+919876543210,Website,Fresh
                Priya,+919812345678,Referral,Hot
                """));

        assertThat(sheet.rows()).extracting(LeadImportSheet.LeadImportRow::rowNumber)
                .containsExactly(2, 3);
    }

    @Test
    @DisplayName("a quoted value containing a comma stays one field")
    void quotedCommasSurvive() {
        LeadImportSheet sheet = reader.read(csv("""
                Customer Name,Phone,Lead Source,Lead Type,Notes
                Ravi,+919876543210,Website,Fresh,"Wants Goa, then Kerala"
                """));

        assertThat(sheet.rows().get(0).get(LeadImportColumn.NOTES))
                .isEqualTo("Wants Goa, then Kerala");
    }

    @Test
    @DisplayName("an empty file fails with a readable message instead of an index error")
    void emptyFileIsRejected() {
        assertThatThrownBy(() -> reader.read(csv("")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no header row");
    }

    // ── Format selection ─────────────────────────────────────────────────────

    @Test
    @DisplayName("the filename extension decides the reader, not the browser's content type")
    void supportsCsvByExtension() {
        assertThat(reader.supports("leads.csv", "application/vnd.ms-excel")).isTrue();
        assertThat(reader.supports("leads.CSV", null)).isTrue();
        assertThat(reader.supports("leads.xlsx", "text/csv")).isFalse();
    }
}
