package com.crm.travelcrm.portal.feature;

import com.crm.travelcrm.portal.feature.dto.FeatureInterestSummaryDto;
import com.crm.travelcrm.portal.feature.dto.NotifyResponse;
import com.crm.travelcrm.portal.feature.entity.PortalFeatureInterest;
import com.crm.travelcrm.portal.feature.repository.PortalFeatureInterestRepository;
import com.crm.travelcrm.portal.security.CurrentTraveler;
import com.crm.travelcrm.portal.security.TravelerPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Interest tracking for "Coming Soon" features. Traveler-facing methods scope by the authenticated
 * {@link TravelerPrincipal}'s {@code customerId}; the staff summary scopes by an explicit tenantId
 * (called from the staff chain, where {@code CurrentTraveler} is absent).
 */
@Service
@RequiredArgsConstructor
public class PortalFeatureService {

    private final PortalFeatureInterestRepository repository;

    /** Idempotent: re-tapping (or a concurrent insert) returns {@code alreadyRegistered=true}. */
    @Transactional
    public NotifyResponse register(PortalFeatureKey key) {
        TravelerPrincipal me = CurrentTraveler.require();
        if (repository.existsByTenantIdAndCustomerIdAndFeatureKey(me.tenantId(), me.customerId(), key)) {
            return new NotifyResponse(key, true);
        }
        try {
            repository.save(PortalFeatureInterest.builder()
                    .customerId(me.customerId())
                    .customerPublicId(me.customerPublicId())
                    .featureKey(key)
                    .build());
            return new NotifyResponse(key, false);
        } catch (DataIntegrityViolationException concurrentInsert) {
            // Two taps raced past the exists-check — the unique constraint makes it idempotent.
            return new NotifyResponse(key, true);
        }
    }

    @Transactional(readOnly = true)
    public List<PortalFeatureKey> myInterests() {
        TravelerPrincipal me = CurrentTraveler.require();
        return repository.findByTenantIdAndCustomerId(me.tenantId(), me.customerId())
                .stream()
                .map(PortalFeatureInterest::getFeatureKey)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FeatureInterestSummaryDto> summary(Long tenantId) {
        return repository.summarize(tenantId);
    }
}