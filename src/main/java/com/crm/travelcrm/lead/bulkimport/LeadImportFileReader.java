package com.crm.travelcrm.lead.bulkimport;

import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses one upload into a {@link LeadImportSheet}. One implementation per file format; add a format
 * by adding a bean, and {@link LeadImportFileReaderResolver} picks it up with nothing else changing.
 *
 * <p>Implementations must be <b>read-only and side-effect free</b> — they are called by the preview
 * endpoint, which is contractually allowed to write nothing.
 */
public interface LeadImportFileReader {

    /**
     * Hard ceiling on rows read from a single file. Reached by reading {@code MAX_ROWS + 1} so the
     * service can tell "exactly at the limit" from "over it" and say so, rather than silently
     * truncating a 5,000-row file to 2,000 and reporting success.
     *
     * <p>This is a memory bound, not a policy: every row is materialised before validation, and an
     * uncapped .xlsx is a trivial way to exhaust a small VPS heap.
     */
    int MAX_ROWS = 2000;

    /** Whether this reader handles the given upload, judged on filename extension then content type. */
    boolean supports(String filename, String contentType);

    /** @throws com.crm.travelcrm.common.exception.BusinessException when the file cannot be parsed */
    LeadImportSheet read(MultipartFile file);

    /**
     * Map the header row's cells to known columns. Shared by every reader so CSV and Excel cannot
     * drift on what counts as a recognised header.
     *
     * @return index → column for the cells that matched; unmatched cells are appended to
     *         {@code ignoredHeadersOut}
     */
    static Map<Integer, LeadImportColumn> mapHeaders(List<String> headerCells,
                                                     List<String> ignoredHeadersOut) {
        Map<Integer, LeadImportColumn> byIndex = new LinkedHashMap<>();
        List<String> seen = new ArrayList<>();

        for (int i = 0; i < headerCells.size(); i++) {
            final int index = i;
            final String raw = headerCells.get(i);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            LeadImportColumn.fromHeader(raw).ifPresentOrElse(column -> {
                // A duplicated column would make the last one silently win. Report the repeat as
                // ignored instead, so the user sees why only one of their two "Phone" columns landed.
                if (seen.contains(column.name())) {
                    ignoredHeadersOut.add(raw.trim() + " (duplicate column)");
                } else {
                    seen.add(column.name());
                    byIndex.put(index, column);
                }
            }, () -> ignoredHeadersOut.add(raw.trim()));
        }
        return byIndex;
    }

    /** Normalised extension of the upload, without the dot; empty when there is none. */
    static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
