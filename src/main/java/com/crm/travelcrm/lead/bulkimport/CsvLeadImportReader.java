package com.crm.travelcrm.lead.bulkimport;

import com.crm.travelcrm.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CSV reader — the format the downloadable template uses, and the one every spreadsheet tool can
 * produce with Save As.
 *
 * <p>Reads as UTF-8 and strips a leading BOM by hand: Excel's "CSV UTF-8" export writes one, and
 * without this the first header becomes {@code "﻿Customer Name"}, matches nothing, and the whole
 * file reports "missing required column: Customer Name" — a confusing failure for the single most
 * likely way an Indian travel agency produces this file.
 */
@Slf4j
@Component
public class CsvLeadImportReader implements LeadImportFileReader {

    private static final char BOM = '﻿';

    @Override
    public boolean supports(String filename, String contentType) {
        String ext = LeadImportFileReader.extensionOf(filename);
        if (ext.equals("csv")) {
            return true;
        }
        // Only trust the content type when the name carries no usable extension — browsers report
        // CSV inconsistently (text/csv, application/vnd.ms-excel, text/plain), so the name wins.
        return ext.isEmpty() && contentType != null
                && (contentType.equals("text/csv") || contentType.equals("text/plain"));
    }

    @Override
    public LeadImportSheet read(MultipartFile file) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setIgnoreSurroundingSpaces(true)
                     .setIgnoreEmptyLines(true)
                     .build()
                     .parse(reader)) {

            List<String> ignoredHeaders = new ArrayList<>();
            Map<Integer, LeadImportColumn> byIndex = null;
            List<LeadImportSheet.LeadImportRow> rows = new ArrayList<>();

            for (CSVRecord record : parser) {
                if (byIndex == null) {
                    byIndex = LeadImportFileReader.mapHeaders(headerCells(record), ignoredHeaders);
                    continue;
                }
                if (rows.size() > MAX_ROWS) {
                    break;   // service reports the overflow; see MAX_ROWS
                }
                Map<LeadImportColumn, String> values = new LinkedHashMap<>();
                byIndex.forEach((index, column) -> {
                    if (index < record.size()) {
                        values.put(column, record.get(index));
                    }
                });
                // record.getRecordNumber() is 1-based and counts the header, which is exactly the
                // line number the user sees in their editor.
                LeadImportSheet.LeadImportRow row =
                        new LeadImportSheet.LeadImportRow((int) record.getRecordNumber(), values);
                if (!row.isBlank()) {
                    rows.add(row);
                }
            }

            if (byIndex == null) {
                throw new BusinessException("The file is empty — it has no header row.",
                        HttpStatus.BAD_REQUEST);
            }
            Set<LeadImportColumn> present = new LinkedHashSet<>(byIndex.values());
            return new LeadImportSheet(rows, present, ignoredHeaders);

        } catch (IOException e) {
            log.warn("CSV lead import could not be parsed", e);
            throw new BusinessException(
                    "This CSV file could not be read. Re-save it as CSV (UTF-8) and try again.",
                    HttpStatus.BAD_REQUEST);
        }
    }

    /** First record's cells, BOM stripped from the very first one. */
    private List<String> headerCells(CSVRecord header) {
        List<String> cells = new ArrayList<>(header.size());
        for (int i = 0; i < header.size(); i++) {
            String value = header.get(i);
            if (i == 0 && value != null && !value.isEmpty() && value.charAt(0) == BOM) {
                value = value.substring(1);
            }
            cells.add(value);
        }
        return cells;
    }
}
