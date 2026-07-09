package com.crm.travelcrm.platform.announcement.enums;

/** Which users inside each targeted tenant receive the announcement. */
public enum AnnouncementRecipientScope {
    /** Tenant admins only (account owners). */
    ADMINS,
    /** Every active user of the tenant. */
    ALL_USERS
}