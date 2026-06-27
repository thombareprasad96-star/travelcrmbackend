package com.crm.travelcrm.portal.auth.repository;

import com.crm.travelcrm.portal.auth.entity.TravelerAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TravelerAccountRepository extends JpaRepository<TravelerAccount, Long> {

    /** Provisioning / login lookup — one account per (tenant, customer). */
    Optional<TravelerAccount> findByTenantIdAndCustomerIdAndDeletedAtIsNull(Long tenantId, Long customerId);

    /** Loaded by the portal auth filter from the token's account publicId + tenantId. */
    Optional<TravelerAccount> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);
}
