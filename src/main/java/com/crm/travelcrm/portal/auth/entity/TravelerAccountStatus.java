package com.crm.travelcrm.portal.auth.entity;

/** Lifecycle of a traveler's portal login (separate from the staff user lifecycle). */
public enum TravelerAccountStatus {
    /** Can request OTPs and log in. */
    ACTIVE,
    /** Provisioned but blocked from logging in (staff can disable a traveler). */
    DISABLED
}
