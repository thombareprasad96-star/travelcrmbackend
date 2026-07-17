package com.crm.travelcrm.leadsource.spi;

/**
 * A pointer to a lead that exists at the provider but was not in the webhook body — Meta's
 * {@code leadgen_id} is the canonical case.
 *
 * <p>Persisted BEFORE the provider is ACKed, so a crash between ACK and fetch is recoverable.
 *
 * @param externalId  the provider's id for the lead — what the fetch is keyed on
 * @param accountKey  the provider account it belongs to (Meta: the page id), when the fetch needs it
 *                    to select a credential
 * @param receivedAt  epoch millis the webhook arrived, from the delivery, not from the clock at fetch
 *                    time — a fetch may run minutes later and some providers expire leads
 */
public record FetchHandle(String externalId, String accountKey, long receivedAt) {

    public FetchHandle {
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("FetchHandle requires an externalId");
        }
    }
}
