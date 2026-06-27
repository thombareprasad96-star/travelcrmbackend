package com.crm.travelcrm.ai.repository;

import com.crm.travelcrm.ai.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sessions are tenant- AND owner-scoped: every finder carries {@code tenantId} + {@code userId} so a
 * caller can only ever resolve their own sessions (a foreign publicId returns empty ⇒ 404).
 */
public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    Optional<ChatSession> findByPublicIdAndTenantIdAndUserIdAndDeletedAtIsNull(
            UUID publicId, Long tenantId, Long userId);

    List<ChatSession> findByTenantIdAndUserIdAndDeletedAtIsNullOrderByLastMessageAtDesc(
            Long tenantId, Long userId);
}