package com.crm.travelcrm.leadsource.gateway;

import com.crm.travelcrm.leadsource.entity.LeadSourceIntegration;
import com.crm.travelcrm.leadsource.repository.LeadSourceIntegrationRepository;
import com.crm.travelcrm.leadsource.service.IngestTokenService;
import com.crm.travelcrm.leadsource.spi.IntegrationCredentials;
import com.crm.travelcrm.settings.crypto.AesSecretCipher;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Turns an ingest token (or a provider account key) into a {@link ResolvedIntegration}.
 *
 * <p><b>THIS IS THE ONLY CLASS IN THE MODULE PERMITTED TO RUN WITHOUT A {@code TenantContext}, and it
 * must stay that way.</b> The chicken-and-egg is unavoidable: the tenant is precisely what this
 * resolves. It is safe because:
 * <ul>
 *   <li>it runs OUTSIDE any transaction, so {@code TenantFilterAspect} never fires and there is no
 *       fail-open to exploit;</li>
 *   <li>the gateway explicitly CLEARED every inherited context before calling it, so a caller cannot
 *       smuggle in a tenant via their own JWT;</li>
 *   <li>the token is 256 bits of {@code SecureRandom} and the lookup returns exactly one row — "the
 *       wrong tenant" is not reachable by guessing.</li>
 * </ul>
 *
 * <p><b>Never add a tenant-scoped read to this class.</b> It would run unfiltered.
 */
@Service
@RequiredArgsConstructor
public class LeadIngestTokenResolver {

    private static final Logger log = LogManager.getLogger(LeadIngestTokenResolver.class);

    private final LeadSourceIntegrationRepository repository;
    private final IngestTokenService tokenService;
    private final AesSecretCipher cipher;
    private final ObjectMapper objectMapper;

    /**
     * Resolve a presented token.
     *
     * <p>Returns empty for: a malformed token (rejected before any DB access), an unknown token, or a
     * previous-token match whose overlap window has closed. The caller must treat every empty exactly
     * the same way — a 401 with an identical body — so the endpoint never reveals whether a token is
     * real.
     */
    public Optional<ResolvedIntegration> resolveByToken(String presentedToken) {
        // Step 2 of the recipe: format check first, so garbage costs a string compare, not a query.
        if (!tokenService.looksWellFormed(presentedToken)) {
            return Optional.empty();
        }

        String hash = tokenService.hash(presentedToken);

        Optional<LeadSourceIntegration> current = repository.findByIngestTokenHashAndDeletedAtIsNull(hash);
        if (current.isPresent()) {
            return current.map(row -> toResolved(row, false));
        }

        // Overlapping rotation. The tenant must physically go and paste the new URL into the
        // provider's console; an instant cutover drops every lead in that human-sized gap — silently,
        // because the provider just gets a 401 and stops.
        Optional<LeadSourceIntegration> previous =
                repository.findByIngestTokenHashPreviousAndDeletedAtIsNull(hash);
        if (previous.isEmpty()) {
            return Optional.empty();
        }

        LeadSourceIntegration row = previous.get();
        LocalDateTime revokeAt = row.getTokenPreviousRevokeAt();
        if (revokeAt == null || revokeAt.isBefore(LocalDateTime.now())) {
            // The window is closed (or was never opened). The old token is now simply invalid.
            log.warn("Ingest delivery presented a ROTATED-OUT token for integration {} (tenant {}, "
                            + "channel {}). The overlap window closed at {}.",
                    row.getId(), row.getTenantId(), row.getChannel(), revokeAt);
            return Optional.empty();
        }

        log.info("Ingest delivery authenticated with the PREVIOUS token for integration {} "
                        + "(tenant {}, channel {}); the provider has not been repointed yet. "
                        + "Overlap window closes at {}.",
                row.getId(), row.getTenantId(), row.getChannel(), revokeAt);
        return Optional.of(toResolved(row, true));
    }

    /**
     * PROVIDER_ACCOUNT resolution — <b>only ever called after the platform HMAC has proven the body's
     * authenticity</b>. The body supplies an account KEY, never a tenantId (owner decision 2), and that
     * key is only a lookup into a row an authenticated tenant created.
     */
    public Optional<ResolvedIntegration> resolveByAccount(String channelSlug, String accountKey) {
        if (accountKey == null || accountKey.isBlank()) {
            return Optional.empty();
        }
        return repository.findByChannelAndExternalAccountIdAndDeletedAtIsNull(channelSlug, accountKey)
                .map(row -> toResolved(row, false));
    }

    private ResolvedIntegration toResolved(LeadSourceIntegration row, boolean usedPreviousToken) {
        return new ResolvedIntegration(
                row.getId(),
                row.getTenantId(),
                row.getChannel(),
                row.getLabel(),
                row.isEnabled(),
                row.getStatus(),
                decryptCredentials(row),
                usedPreviousToken);
    }

    /**
     * Decrypt the credential bag into an immutable view.
     *
     * <p>A failure here is deliberately NOT fatal to the delivery: it yields empty credentials, and the
     * adapter's own verification then fails closed. Throwing would turn a mis-keyed row into a 500 and
     * a provider retry storm.
     */
    private IntegrationCredentials decryptCredentials(LeadSourceIntegration row) {
        String enc = row.getCredentialsEnc();
        if (enc == null || enc.isBlank()) {
            return IntegrationCredentials.empty();
        }
        try {
            String json = cipher.decrypt(enc);
            Map<String, String> values = objectMapper.readValue(json, new TypeReference<>() {});
            return new IntegrationCredentials(values);
        } catch (Exception e) {
            // Never log the ciphertext or the exception's message verbatim — a decrypt failure can
            // echo key material in some providers.
            log.error("Could not decrypt credentials for integration {} (tenant {}, channel {}): {}. "
                            + "Treating as no credentials; the channel's verification will fail closed.",
                    row.getId(), row.getTenantId(), row.getChannel(), e.getClass().getSimpleName());
            return IntegrationCredentials.empty();
        }
    }
}
