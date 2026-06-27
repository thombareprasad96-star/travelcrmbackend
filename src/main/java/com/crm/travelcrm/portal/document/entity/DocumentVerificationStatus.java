package com.crm.travelcrm.portal.document.entity;

/** Staff verification state of an uploaded document. New uploads start PENDING. */
public enum DocumentVerificationStatus {
    PENDING,
    VERIFIED,
    REJECTED
}
