package com.crm.travelcrm.common.context;

/**
 * The acting platform SuperAdmin for the current request thread, carried by
 * {@link PlatformContext}. Holds the internal {@code Long} id (for the audit logical FK)
 * and the email snapshot (for the audit actor label). Never exposed externally.
 */
public record PlatformActor(Long superAdminId, String email) {
}