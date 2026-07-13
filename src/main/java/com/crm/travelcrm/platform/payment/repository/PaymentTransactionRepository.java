package com.crm.travelcrm.platform.payment.repository;

import com.crm.travelcrm.platform.payment.entity.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Platform-level SaaS payment attempts (no tenant scoping — scoped explicitly by {@code tenantId}). */
@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    Optional<PaymentTransaction> findByGatewayOrderId(String gatewayOrderId);

    Optional<PaymentTransaction> findByPublicId(UUID publicId);

    /** A tenant's payment history, newest first. */
    List<PaymentTransaction> findByTenantIdOrderByCreatedAtDesc(Long tenantId);
}