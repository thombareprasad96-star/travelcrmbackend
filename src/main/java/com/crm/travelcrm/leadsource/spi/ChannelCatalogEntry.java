package com.crm.travelcrm.leadsource.spi;

import lombok.Builder;

import java.util.List;

/**
 * What an adapter declares about itself for the Integrations UI and for save-time validation.
 *
 * <p><b>No styling fields, and that is structural rather than a preference.</b> Tailwind scans SOURCE
 * FILES for literal class strings, so a server-sent {@code "bg-orange-100"} is purged from the bundle
 * and renders unstyled. Icons are React components. The frontend holds a {@code code → {icon, colour}}
 * presentation map; the wire carries meaning only.
 *
 * @param displayName    tenant-facing channel name, e.g. "JustDial"
 * @param description    one line explaining what connecting this does
 * @param credentialFields the credential inputs the setup form renders. Also the save-time contract —
 *                       see {@link CredentialField#required()}.
 * @param configFields   non-secret settings (allowed origins, a default assignee…). Stored in
 *                       {@code config_json}, which is FE-returnable wholesale.
 * @param setupHint      free text shown in the setup drawer, e.g. where to paste the webhook URL
 * @param webhookUrlShown whether this channel's setup shows a per-connection webhook URL. False for
 *                       {@link TenantResolution#PROVIDER_ACCOUNT} channels, which post to one app-wide
 *                       URL and would otherwise show a URL that does not exist.
 */
@Builder
public record ChannelCatalogEntry(
        String displayName,
        String description,
        List<CredentialField> credentialFields,
        List<ConfigField> configFields,
        String setupHint,
        boolean webhookUrlShown
) {

    public ChannelCatalogEntry {
        credentialFields = credentialFields == null ? List.of() : List.copyOf(credentialFields);
        configFields = configFields == null ? List.of() : List.copyOf(configFields);
    }

    /** The keys that must be present in the credential bag for this channel to work. */
    public List<String> requiredCredentialKeys() {
        return credentialFields.stream().filter(CredentialField::required).map(CredentialField::key).toList();
    }

    /**
     * One secret input.
     *
     * <p><b>One declaration, two jobs.</b> It renders the form field AND drives the save-time
     * fail-fast — which is the credential bag's only mitigation: the bag is an encrypted blob, so the
     * database cannot validate it and a typo'd key would otherwise surface at ingest (a lost lead),
     * not at save (a form error). Declaring the field twice would let the two drift, which is worse
     * than not checking at all.
     *
     * @param key         the key in the credential bag — what the adapter reads back
     * @param label       form label
     * @param required    whether save is rejected without it
     * @param helpText    optional hint under the field
     */
    public record CredentialField(String key, String label, boolean required, String helpText) {}

    /** One non-secret setting. Same shape, but its value is returnable to the frontend. */
    public record ConfigField(String key, String label, boolean required, String helpText) {}
}
