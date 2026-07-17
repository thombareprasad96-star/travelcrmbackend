package com.crm.travelcrm.leadsource.dto;

import java.util.List;

/**
 * One channel in the Integrations grid, with a summary of the tenant's connections to it.
 *
 * <p><b>No styling fields, structurally.</b> Tailwind scans SOURCE FILES for literal class strings, so
 * a server-sent {@code "bg-orange-100"} is purged from the bundle and renders unstyled. The frontend
 * holds a {@code code → {icon, colour}} map; the wire carries meaning only.
 *
 * @param code            {@code LeadSourceChannel.name()} — the stable frontend key
 * @param slug            the permanent URL segment
 * @param displayName     tenant-facing name
 * @param description     one line on what connecting this does
 * @param setupHint       shown in the setup drawer
 * @param webhookUrlShown false for channels that post to one app-wide URL and so have no per-connection URL
 * @param credentialFields inputs the setup form renders. Values are WRITE-ONLY — never returned.
 * @param configFields    non-secret settings; their values ARE returned
 * @param connectionCount how many connections the tenant has to this channel
 * @param worstStatus     the least healthy status across them — a grid card must not show CONNECTED
 *                        while one of its connections is broken
 * @param totalLeadCount  leads received across all of them
 * @param lastLeadReceivedAt newest across all of them; null ⇒ nothing has ever arrived
 */
public record ChannelCatalogDto(
        String code,
        String slug,
        String displayName,
        String description,
        String setupHint,
        boolean webhookUrlShown,
        List<FieldDto> credentialFields,
        List<FieldDto> configFields,
        int connectionCount,
        String worstStatus,
        long totalLeadCount,
        String lastLeadReceivedAt
) {
    /** One form input the setup drawer renders. */
    public record FieldDto(String key, String label, boolean required, String helpText) {}
}
