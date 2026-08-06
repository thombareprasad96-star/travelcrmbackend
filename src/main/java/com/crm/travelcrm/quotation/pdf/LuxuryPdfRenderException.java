package com.crm.travelcrm.quotation.pdf;

import com.crm.travelcrm.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * Chromium was available but this particular render failed — navigation error, the readiness flag
 * never turned true, or the produced byte array was empty/not a PDF.
 *
 * <p>The message reaching the client is deliberately generic. {@code GlobalExceptionHandler} echoes
 * a {@link BusinessException}'s message verbatim, and the real cause here is renderer internals
 * (URLs, tokens, Chromium stack text) that must not be shipped to a browser. The cause is logged.
 */
public class LuxuryPdfRenderException extends BusinessException {

    public LuxuryPdfRenderException(String message) {
        super(message, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
