package com.crm.travelcrm.leadsource.dto;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * One connection, as the tenant sees it.
 *
 * <p><b>There is no credentials field, and there never will be.</b> The secret bag is write-only: it is
 * stored in its own encrypted column precisely so that "never return it" is a property of the schema
 * rather than of someone remembering to strip it. Returning it — even masked — would make this DTO a
 * blacklist, and the rule in this codebase is whitelist-never-blacklist.
 *
 * @param publicId       the external id. The internal Long never crosses this boundary.
 * @param ingestUrl      the full URL to paste into the provider's console. Present ONLY in the response
 *                       to a create or a rotate, because it embeds the token — which we cannot show
 *                       again, since only its hash is stored.
 * @param tokenPrefix    the token's first few plaintext characters, for masked display and support
 *                       correlation ("is the URL you pasted the one we issued?"). Never authenticates.
 * @param config         the NON-secret settings blob, returnable wholesale
 * @param credentialKeysSet which secret keys are populated — the form can show "configured" without the
 *                       value ever leaving the server
 */
public record IntegrationResponseDto(
        UUID publicId,
        String channel,
        String channelCode,
        String label,
        boolean enabled,
        String status,
        String resolutionMode,
        String externalAccountId,
        String ingestUrl,
        String tokenPrefix,
        LocalDateTime tokenRotatedAt,
        LocalDateTime tokenPreviousRevokeAt,
        LocalDateTime tokenLastUsedAt,
        LocalDateTime credentialsExpireAt,
        long leadCount,
        LocalDateTime lastLeadReceivedAt,
        Map<String, String> config,
        java.util.List<String> credentialKeysSet,
        LocalDateTime createdAt
) {}
