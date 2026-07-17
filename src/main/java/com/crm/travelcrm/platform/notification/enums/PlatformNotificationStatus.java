package com.crm.travelcrm.platform.notification.enums;

/**
 * Read state of a platform notification.
 *
 * <p>Deliberately NOT the tenant module's {@code NotificationStatus}. The two realms are
 * independent by design; sharing the enum would be the first thread of a coupling that ends with
 * one module's change breaking the other's feed. The duplicate <i>is</i> the boundary.
 */
public enum PlatformNotificationStatus {
    UNREAD,
    READ
}