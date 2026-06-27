package com.crm.travelcrm.activity.entity;

/**
 * The kind of action an {@link ActivityLog} row records.
 *
 * <p>Names match the values the frontend Activity Reports page filters by
 * ({@code action} query param): Login | Logout | Create | Update | Delete |
 * Settings | Export | View. Stored as {@code STRING} so the column reads as the
 * literal label.
 */
public enum ActivityAction {
    Login,
    Logout,
    Create,
    Update,
    Delete,
    Settings,
    Export,
    View
}