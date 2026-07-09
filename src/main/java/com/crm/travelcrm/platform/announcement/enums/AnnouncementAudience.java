package com.crm.travelcrm.platform.announcement.enums;

/** Which tenants an announcement targets (by lifecycle status). */
public enum AnnouncementAudience {
    /** All operational tenants — ACTIVE + TRIAL. */
    ALL,
    ACTIVE,
    TRIAL
}