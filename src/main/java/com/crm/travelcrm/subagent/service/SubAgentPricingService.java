package com.crm.travelcrm.subagent.service;

import com.crm.travelcrm.subagent.entity.SubAgentProfile;
import com.crm.travelcrm.subagent.enums.MarkupType;
import com.crm.travelcrm.subagent.enums.SubAgentStatus;
import com.crm.travelcrm.subagent.repository.SubAgentProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Resolves the B2B franchise markup <em>amount</em> (in INR) to layer onto a quotation, given the
 * owning user. This is the single source of truth for turning a sub-agent's configured markup
 * (PERCENT or FIXED, from {@link SubAgentProfile}) into a concrete rupee figure that
 * {@code QuotationMapper.computeTotals} folds in pre-tax, exactly like the staff markup.
 *
 * <p>Returns {@link BigDecimal#ZERO} for every owner that is not an <b>active</b> sub-agent (no
 * profile, suspended, or {@code null} owner) — so staff-owned quotations are completely unaffected.
 * Lookups are tenant-agnostic (userId is globally unique) so the resolver also works on the public
 * quotation share-link path, which carries no {@code TenantContext}.</p>
 */
@Service
@RequiredArgsConstructor
public class SubAgentPricingService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final SubAgentProfileRepository profileRepository;

    /**
     * The markup amount (INR, scale 2) to add onto a quotation owned by {@code ownerUserId} whose
     * component sum is {@code subtotal}. PERCENT markup is applied to the subtotal; FIXED is used as-is.
     * Never negative. ZERO when the owner is not an active sub-agent.
     */
    @Transactional(readOnly = true)
    public BigDecimal resolveMarkupAmount(Long ownerUserId, BigDecimal subtotal) {
        if (ownerUserId == null) {
            return BigDecimal.ZERO;
        }
        return profileRepository.findByUserIdAndDeletedAtIsNull(ownerUserId)
                .filter(p -> p.getStatus() == SubAgentStatus.ACTIVE)
                .map(p -> amountFor(p, subtotal))
                .orElse(BigDecimal.ZERO);
    }

    private BigDecimal amountFor(SubAgentProfile p, BigDecimal subtotal) {
        BigDecimal value = p.getMarkupValue() != null ? p.getMarkupValue() : BigDecimal.ZERO;
        BigDecimal base = subtotal != null ? subtotal : BigDecimal.ZERO;
        BigDecimal amount = p.getMarkupType() == MarkupType.PERCENT
                ? base.multiply(value).divide(HUNDRED, 2, RoundingMode.HALF_UP)
                : value;
        return amount.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }
}
