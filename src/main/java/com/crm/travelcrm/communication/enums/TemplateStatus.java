package com.crm.travelcrm.communication.enums;

/**
 * Lifecycle of a message template.
 *
 * <p>Note this is OUR state, not the provider's. A WhatsApp template must additionally be approved
 * by Meta before it can be sent; that is tracked separately by
 * {@code comm_template.providerTemplateName} being present, because approval happens in the
 * provider's console and we cannot observe it from here.
 */
public enum TemplateStatus {

    DRAFT,

    /** Selectable in a composer. */
    ACTIVE,

    /** Hidden from composers, kept so old messages still resolve their template reference. */
    ARCHIVED
}
