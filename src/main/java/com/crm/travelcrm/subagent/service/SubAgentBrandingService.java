package com.crm.travelcrm.subagent.service;

import com.crm.travelcrm.subagent.enums.SubAgentStatus;
import com.crm.travelcrm.subagent.repository.SubAgentProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Resolves the white-label {@link SubAgentBranding} to apply to a document owned by {@code ownerUserId}.
 * Returns branding only for an <b>active</b> sub-agent (never for staff-owned documents); the PDF
 * services then override the tenant Company branding field-by-field with the non-blank values.
 *
 * <p>Lookups are tenant-agnostic (userId is globally unique) so this also works on the public
 * quotation share-link path, which carries no {@code TenantContext}.</p>
 */
@Service
@RequiredArgsConstructor
public class SubAgentBrandingService {

    private final SubAgentProfileRepository profileRepository;

    @Transactional(readOnly = true)
    public Optional<SubAgentBranding> resolve(Long ownerUserId) {
        if (ownerUserId == null) {
            return Optional.empty();
        }
        return profileRepository.findByUserIdAndDeletedAtIsNull(ownerUserId)
                .filter(p -> p.getStatus() == SubAgentStatus.ACTIVE)
                .map(p -> new SubAgentBranding(
                        p.getBrandName(), p.getLogoUrl(),
                        p.getContactPhone(), p.getContactEmail(), p.getBrandColor()));
    }
}
