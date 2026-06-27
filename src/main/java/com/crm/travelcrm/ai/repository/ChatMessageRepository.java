package com.crm.travelcrm.ai.repository;

import com.crm.travelcrm.ai.entity.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** Full transcript for a session, oldest first (the session is already owner-checked). */
    List<ChatMessage> findBySessionIdAndDeletedAtIsNullOrderByCreatedAtAsc(Long sessionId);

    /** Most recent turns first — used to build the bounded context window. */
    List<ChatMessage> findBySessionIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long sessionId, Pageable pageable);
}