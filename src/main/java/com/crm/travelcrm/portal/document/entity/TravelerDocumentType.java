package com.crm.travelcrm.portal.document.entity;

/** Kind of traveler-uploaded document. PASSPORT/VISA are expiry-tracked PII. */
public enum TravelerDocumentType {
    PASSPORT,
    VISA,
    PHOTO,
    OTHER
}
