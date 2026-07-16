package com.crm.travelcrm.accounting.export.spi;

/**
 * The result of a ledger export — the file bytes plus its name and content type, or an
 * {@code available=false} marker with a reason when the requested format isn't supported by the
 * active provider.
 */
public record LedgerExport(
        boolean available,
        String filename,
        String contentType,
        byte[] content,
        String message) {

    public static LedgerExport of(String filename, String contentType, byte[] content) {
        return new LedgerExport(true, filename, contentType, content, "OK");
    }

    public static LedgerExport unsupported(String message) {
        return new LedgerExport(false, null, null, null, message);
    }
}