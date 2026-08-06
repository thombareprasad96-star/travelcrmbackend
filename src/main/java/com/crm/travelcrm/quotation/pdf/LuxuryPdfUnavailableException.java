package com.crm.travelcrm.quotation.pdf;

import com.crm.travelcrm.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * The LUXURY design cannot be produced in this environment — the feature is switched off
 * ({@code pdf.luxury.enabled=false}) or Chromium could not be launched.
 *
 * <p>Extends {@link BusinessException} so the existing {@code GlobalExceptionHandler} renders it in
 * the project's normal envelope with no handler change. 503 rather than 500: the request was
 * perfectly valid and the same request will succeed once the renderer is available.
 *
 * <p><b>The caller is always told.</b> Quietly falling back to CLASSIC would hand an agent a
 * document in a design they did not choose, which they would then send to a customer without ever
 * learning that Luxury is broken.
 */
public class LuxuryPdfUnavailableException extends BusinessException {

    public LuxuryPdfUnavailableException(String message) {
        super(message, HttpStatus.SERVICE_UNAVAILABLE);
    }
}
