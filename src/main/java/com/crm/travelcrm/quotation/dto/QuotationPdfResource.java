package com.crm.travelcrm.quotation.dto;

/**
 * Result of resolving a quotation's PDF: either a remote {@code url} (the stored
 * Cloudinary asset) to redirect to, or freshly rendered {@code content} bytes.
 * Exactly one is populated.
 */
public record QuotationPdfResource(String url, byte[] content) {

    public static QuotationPdfResource remote(String url) {
        return new QuotationPdfResource(url, null);
    }

    public static QuotationPdfResource inline(byte[] content) {
        return new QuotationPdfResource(null, content);
    }

    public boolean isRemote() {
        return url != null && !url.isBlank();
    }
}