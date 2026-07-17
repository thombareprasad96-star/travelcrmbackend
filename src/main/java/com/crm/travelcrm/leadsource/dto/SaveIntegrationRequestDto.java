package com.crm.travelcrm.leadsource.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * Create or update a connection.
 *
 * <p><b>No tenantId and no channel-on-update</b>: the tenant comes from the JWT, and a connection's
 * channel is immutable — changing it would silently repoint a live URL at a different parser.
 */
@Data
public class SaveIntegrationRequestDto {

    /**
     * {@code LeadSourceChannel.name()} or its slug — both accepted, since the frontend holds the code
     * while the URL holds the slug. Ignored on update.
     */
    @NotBlank(message = "Channel is required")
    private String channel;

    @NotBlank(message = "Give this connection a name")
    @Size(max = 120, message = "Name must not exceed 120 characters")
    private String label;

    private Boolean enabled;

    /** FB page id / IVR DID / site key. Only meaningful for PROVIDER_ACCOUNT channels. */
    @Size(max = 128)
    private String externalAccountId;

    /**
     * Secret values, keyed as the channel's catalog declares.
     *
     * <p><b>Write-only and MERGE-on-update</b>: a key omitted here keeps its stored value, so an edit
     * form that never received the secret cannot blank it by saving. Send an empty string to clear one
     * deliberately.
     */
    private Map<String, String> credentials;

    /** Non-secret settings. Replaces the stored blob wholesale. */
    private Map<String, String> config;
}
