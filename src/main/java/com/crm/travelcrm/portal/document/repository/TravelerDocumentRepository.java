package com.crm.travelcrm.portal.document.repository;

import com.crm.travelcrm.portal.document.dto.ExpiringDocumentView;
import com.crm.travelcrm.portal.document.dto.TravelerDocumentView;
import com.crm.travelcrm.portal.document.entity.TravelerDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TravelerDocumentRepository extends JpaRepository<TravelerDocument, Long> {

    /** List a traveler's own documents — projection, so the {@code content} blob is NOT loaded. */
    List<TravelerDocumentView> findAllByCustomerIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long customerId);

    /** Full entity (incl. bytes) for download/delete — scoped to the owning customer (404 if foreign). */
    Optional<TravelerDocument> findByPublicIdAndCustomerIdAndDeletedAtIsNull(UUID publicId, Long customerId);

    // ── Expiry-reminder job (per tenant) — projection, no blob ────────────────────
    List<ExpiringDocumentView>
        findByTenantIdAndDeletedAtIsNullAndExpiryDateIsNotNullAndExpiryDateLessThanEqual(
            Long tenantId, LocalDate maxExpiry);

    /** Record that a reminder fired at {@code threshold} days — idempotency marker (no blob touched). */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TravelerDocument d SET d.lastReminderDayThreshold = :threshold WHERE d.id = :id")
    void markReminded(@Param("id") Long id, @Param("threshold") Integer threshold);
}
