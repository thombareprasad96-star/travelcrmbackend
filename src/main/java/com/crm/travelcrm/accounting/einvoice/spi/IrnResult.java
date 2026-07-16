package com.crm.travelcrm.accounting.einvoice.spi;

import java.time.LocalDateTime;

/**
 * Outcome of an e-invoice (IRN) registration attempt with the government IRP. Returned by an
 * {@link EInvoiceProvider}; the stub returns {@link #unavailable()} so nothing breaks until a real
 * NIC/GSP provider bean is wired.
 */
public record IrnResult(
        Status status,
        String irn,
        String ackNo,
        LocalDateTime ackDate,
        String signedQrData,
        String message) {

    public enum Status {
        /** No e-invoicing provider is configured (default). */
        UNAVAILABLE,
        /** IRN successfully registered. */
        GENERATED,
        /** The IRP rejected the request. */
        FAILED
    }

    public static IrnResult unavailable() {
        return new IrnResult(Status.UNAVAILABLE, null, null, null, null,
                "e-invoicing is not configured for this tenant.");
    }

    public static IrnResult generated(String irn, String ackNo, LocalDateTime ackDate, String signedQr) {
        return new IrnResult(Status.GENERATED, irn, ackNo, ackDate, signedQr, "IRN generated.");
    }

    public static IrnResult failed(String message) {
        return new IrnResult(Status.FAILED, null, null, null, null, message);
    }
}