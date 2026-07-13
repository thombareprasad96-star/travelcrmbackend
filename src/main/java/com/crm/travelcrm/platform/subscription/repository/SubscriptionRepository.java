package com.crm.travelcrm.platform.subscription.repository;

import com.crm.travelcrm.platform.subscription.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Platform-level recurring subscriptions (scaffold; no tenant scoping — scoped by {@code tenantId}). */
@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    Optional<Subscription> findByGatewaySubscriptionId(String gatewaySubscriptionId);
}