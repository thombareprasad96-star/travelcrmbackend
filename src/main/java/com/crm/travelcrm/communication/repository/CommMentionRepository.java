package com.crm.travelcrm.communication.repository;

import com.crm.travelcrm.communication.entity.CommMention;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@code @mentions} — the Mentions tab and its unread badge. */
public interface CommMentionRepository extends JpaRepository<CommMention, Long> {

    Optional<CommMention> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    Page<CommMention> findByTenantIdAndMentionedUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            Long tenantId, Long mentionedUserId, Pageable pageable);

    long countByTenantIdAndMentionedUserIdAndReadAtIsNullAndDeletedAtIsNull(
            Long tenantId, Long mentionedUserId);

    List<CommMention> findByTenantIdAndMessageIdAndDeletedAtIsNull(Long tenantId, Long messageId);

    /** Idempotency: mentioning someone twice in one note must notify them once. */
    boolean existsByTenantIdAndMessageIdAndMentionedUserIdAndDeletedAtIsNull(
            Long tenantId, Long messageId, Long mentionedUserId);
}
