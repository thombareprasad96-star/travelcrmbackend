package com.crm.travelcrm.lead.bulkimport;

import com.crm.travelcrm.common.exception.BusinessException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Builds the downloadable import template: the exact header row {@link LeadImportColumn} declares,
 * plus one filled example row so the expected date format, phone format and {@code ;}-separated
 * services are visible rather than documented somewhere the user will not read.
 *
 * <p>Emitted as CSV even though the importer also accepts Excel — every spreadsheet tool opens a CSV,
 * and generating one has no dependency cost. Writing .xlsx here would mean shipping POI's writer path
 * for a file the user immediately edits and usually saves back as their own format anyway.
 *
 * <p>A UTF-8 BOM is prepended so Excel on Windows opens the file as UTF-8 instead of the system code
 * page — without it a template opened, edited and re-saved by a user turns non-ASCII names into
 * mojibake before it ever reaches the parser.
 */
@Component
public class LeadImportTemplateWriter {

    private static final String UTF8_BOM = "﻿";

    public byte[] buildCsvTemplate() {
        List<LeadImportColumn> columns = Arrays.asList(LeadImportColumn.values());

        StringWriter out = new StringWriter();
        out.write(UTF8_BOM);

        try (CSVPrinter printer = new CSVPrinter(out, CSVFormat.DEFAULT)) {
            printer.printRecord(columns.stream().map(LeadImportColumn::getHeader).toList());
            printer.printRecord(columns.stream().map(LeadImportColumn::getExample).toList());
        } catch (IOException e) {
            // A StringWriter cannot actually fail; surface it rather than returning a broken file.
            throw new BusinessException("Could not generate the import template.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
}
