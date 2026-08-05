package com.crm.travelcrm.lead.bulkimport;

import com.crm.travelcrm.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Excel reader for {@code .xlsx} and legacy {@code .xls} — {@code WorkbookFactory} picks the right
 * implementation from the stream's magic bytes, so both formats share this one path.
 *
 * <p>Only the <b>first sheet</b> is read. A multi-sheet workbook almost always means one data sheet
 * plus notes/lookups, and silently concatenating them would import a tab of dropdown values as leads.
 *
 * <p>{@link #stringValue} is the load-bearing part. Two Excel behaviours would otherwise corrupt
 * every import:
 * <ul>
 *   <li>A phone typed as a number is stored as a double, and the obvious {@code getNumericCellValue}
 *       renders {@code 919876543210} as {@code "9.1987654321E11"} — which fails the phone pattern on
 *       every single row. Numerics are rendered through {@link BigDecimal#toPlainString()} instead.</li>
 *   <li>A date cell is also a double (days since 1900). Date-formatted cells are converted to
 *       {@code yyyy-MM-dd}, the one format {@link LeadImportRowMapper} parses.</li>
 * </ul>
 */
@Slf4j
@Component
public class ExcelLeadImportReader implements LeadImportFileReader {

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private final DataFormatter formatter = new DataFormatter();

    @Override
    public boolean supports(String filename, String contentType) {
        String ext = LeadImportFileReader.extensionOf(filename);
        if (ext.equals("xlsx") || ext.equals("xls")) {
            return true;
        }
        return ext.isEmpty() && contentType != null
                && (contentType.equals("application/vnd.ms-excel")
                || contentType.equals(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    @Override
    public LeadImportSheet read(MultipartFile file) {
        try (InputStream in = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(in)) {

            if (workbook.getNumberOfSheets() == 0) {
                throw new BusinessException("This workbook has no sheets.", HttpStatus.BAD_REQUEST);
            }
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                throw new BusinessException("The first sheet is empty — it has no header row.",
                        HttpStatus.BAD_REQUEST);
            }

            List<String> ignoredHeaders = new ArrayList<>();
            Map<Integer, LeadImportColumn> byIndex =
                    LeadImportFileReader.mapHeaders(headerCells(headerRow), ignoredHeaders);

            List<LeadImportSheet.LeadImportRow> rows = new ArrayList<>();
            for (int r = headerRow.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                if (rows.size() > MAX_ROWS) {
                    break;   // service reports the overflow; see MAX_ROWS
                }
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                Map<LeadImportColumn, String> values = new LinkedHashMap<>();
                byIndex.forEach((index, column) ->
                        values.put(column, stringValue(row.getCell(index))));

                // getRowNum() is 0-based; +1 is the row number shown in Excel's gutter.
                LeadImportSheet.LeadImportRow parsed =
                        new LeadImportSheet.LeadImportRow(row.getRowNum() + 1, values);
                if (!parsed.isBlank()) {
                    rows.add(parsed);
                }
            }

            Set<LeadImportColumn> present = new LinkedHashSet<>(byIndex.values());
            return new LeadImportSheet(rows, present, ignoredHeaders);

        } catch (BusinessException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            log.warn("Excel lead import could not be parsed", e);
            throw new BusinessException(
                    "This Excel file could not be read. Make sure it is a valid .xlsx or .xls file, "
                            + "or save it as CSV and try again.",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private List<String> headerCells(Row headerRow) {
        List<String> cells = new ArrayList<>();
        for (int i = 0; i < headerRow.getLastCellNum(); i++) {
            cells.add(stringValue(headerRow.getCell(i)));
        }
        return cells;
    }

    /** Render a cell the way the user sees it, without Excel's numeric artefacts. See class javadoc. */
    private String stringValue(Cell cell) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType()
                : cell.getCellType();

        return switch (type) {
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    LocalDate date = cell.getLocalDateTimeCellValue().toLocalDate();
                    yield ISO_DATE.format(date);
                }
                // Plain string, never scientific notation; trailing ".0" removed so a whole number
                // stays a whole number ("2", not "2.0" — which would fail Integer parsing).
                yield BigDecimal.valueOf(cell.getNumericCellValue())
                        .stripTrailingZeros()
                        .toPlainString();
            }
            case BLANK, ERROR, _NONE -> null;
            default -> formatter.formatCellValue(cell);
        };
    }
}
