package com.crm.travelcrm.platform.payment.repository;

import com.crm.travelcrm.platform.payment.entity.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Idempotency ledger for inbound gateway webhooks. */
@Repository
public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

    boolean existsByEventId(String eventId);
}