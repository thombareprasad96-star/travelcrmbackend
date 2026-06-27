package com.crm.travelcrm.ai.service;

import com.crm.travelcrm.ai.dto.ChatMessageDto;
import com.crm.travelcrm.ai.dto.ChatSessionDto;
import com.crm.travelcrm.ai.entity.ChatMessage;
import com.crm.travelcrm.ai.entity.ChatRole;
import com.crm.travelcrm.ai.entity.ChatSession;
import com.crm.travelcrm.ai.mapper.ChatMessageMapper;
import com.crm.travelcrm.ai.mapper.ChatSessionMapper;
import com.crm.travelcrm.ai.repository.ChatMessageRepository;
import com.crm.travelcrm.ai.repository.ChatSessionRepository;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Session + message persistence for Disha. Every read/write is tenant- AND owner-scoped: sessions are
 * resolved by (publicId, tenantId, userId), so a foreign publicId is simply "not found" (404) — never
 * another user's data.
 */
@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final AiCurrentUser currentUser;

    @Transactional
    public ChatSessionDto createSession(String title) {
        ChatSession session = ChatSession.builder()
                .tenantId(currentUser.requireTenantId())
                .userId(currentUser.requireUserId())
                .title(StringUtils.hasText(title) ? title.trim() : "New chat")
                .lastMessageAt(LocalDateTime.now())
                .build();
        return sessionMapper.toDto(sessionRepository.save(session));
    }

    @Transactional(readOnly = true)
    public List<ChatSessionDto> listSessions() {
        return sessionRepository.findByTenantIdAndUserIdAndDeletedAtIsNullOrderByLastMessageAtDesc(
                        currentUser.requireTenantId(), currentUser.requireUserId())
                .stream().map(sessionMapper::toDto).toList();
    }

    @Transactional(readOnly = true)
    public ChatSession requireOwnedSession(UUID sessionPublicId) {
        return sessionRepository.findByPublicIdAndTenantIdAndUserIdAndDeletedAtIsNull(
                        sessionPublicId, currentUser.requireTenantId(), currentUser.requireUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Chat session not found"));
    }

    @Transactional(readOnly = true)
    public List<ChatMessageDto> getMessages(UUID sessionPublicId) {
        ChatSession session = requireOwnedSession(sessionPublicId);
        return messageRepository.findBySessionIdAndDeletedAtIsNullOrderByCreatedAtAsc(session.getId())
                .stream().map(messageMapper::toDto).toList();
    }

    /** Last {@code limit} turns, oldest first — the bounded context window for the LLM. */
    @Transactional(readOnly = true)
    public List<ChatMessage> recentMessages(Long sessionId, int limit) {
        List<ChatMessage> desc = messageRepository.findBySessionIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                sessionId, PageRequest.of(0, Math.max(1, limit)));
        Collections.reverse(desc);
        return desc;
    }

    @Transactional
    public ChatMessage appendMessage(ChatSession session, ChatRole role, String content, String toolName) {
        ChatMessage saved = messageRepository.save(ChatMessage.builder()
                .tenantId(session.getTenantId())
                .sessionId(session.getId())
                .role(role)
                .content(content)
                .toolName(toolName)
                .build());
        session.setLastMessageAt(LocalDateTime.now());
        sessionRepository.save(session);
        return saved;
    }
}