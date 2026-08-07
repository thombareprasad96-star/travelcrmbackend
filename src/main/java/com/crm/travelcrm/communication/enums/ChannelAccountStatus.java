package com.crm.travelcrm.communication.enums;

/**
 * Connection state of one channel on the Channels &amp; Integrations screen.
 *
 * <p>For WhatsApp and SMTP this is DERIVED from {@code tenant_settings} rather than stored — those
 * credentials already live there and duplicating a secret to render a status badge is how the two
 * copies drift. Only IMAP, SMS and Voice persist their own row and their own encrypted secret.
 */
public enum ChannelAccountStatus {

    /** Never set up. */
    NOT_CONFIGURED,

    /** Credentials present and the last check succeeded. */
    CONNECTED,

    /** Configured, then deliberately switched off by the tenant. */
    DISCONNECTED,

    /** Credentials present but the last check failed. {@code lastError} carries why. */
    ERROR
}
