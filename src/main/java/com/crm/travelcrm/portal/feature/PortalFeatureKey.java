package com.crm.travelcrm.portal.feature;

/**
 * Teaser ("Coming Soon") features a traveler can register interest in. Stored as a STRING enum so
 * the set can grow without a data migration. Adding a value here + a config row on the frontend is
 * all it takes to expose a new "Notify me" feature.
 */
public enum PortalFeatureKey {
    PAY_ONLINE,
    LIVE_TRACKING,
    TRIP_MEMORIES,
    REFER_EARN,
    OFFLINE_MODE
}
