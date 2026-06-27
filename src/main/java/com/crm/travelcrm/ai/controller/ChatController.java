package com.crm.travelcrm.ai.controller;

import com.crm.travelcrm.ai.dto.ChatMessageDto;
import com.crm.travelcrm.ai.dto.ChatSessionDto;
import com.crm.travelcrm.ai.dto.CreateSessionRequest;
import com.crm.travelcrm.ai.dto.SendMessageRequest;
import com.crm.travelcrm.ai.service.ChatOrchestrationService;
import com.crm.travelcrm.ai.service.ChatSessionService;
import com.crm.travelcrm.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

/**
 * Disha chat API. Thin controller — all logic lives in the services. Every endpoint runs as the
 * authenticated tenant user (JWT → SecurityContext + TenantContext), and sessions are owner-scoped,
 * so users only ever touch their own conversations.
 */
@RestController
@RequestMapping("/ai/chat")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ChatController {

    /** Two-minute window for a single answer (Ollama can be slow on first token). */
    private static final long SSE_TIMEOUT_MS = 120_000L;

    private final ChatSessionService chatSessionService;
    private final ChatOrchestrationService orchestrationService;

    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<ChatSessionDto>> createSession(
            @Valid @RequestBody(required = false) CreateSessionRequest request) {
        String title = request == null ? null : request.getTitle();
        ChatSessionDto dto = chatSessionService.createSession(title);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Session created", dto, HttpStatus.CREATED.value()));
    }

    @GetMapping("/sessions")
    public ApiResponse<List<ChatSessionDto>> listSessions() {
        return ApiResponse.success("Sessions fetched", chatSessionService.listSessions());
    }

    @GetMapping("/sessions/{sessionPublicId}/messages")
    public ApiResponse<List<ChatMessageDto>> getMessages(@PathVariable UUID sessionPublicId) {
        return ApiResponse.success("Messages fetched", chatSessionService.getMessages(sessionPublicId));
    }

    /**
     * Send a message and stream the assistant reply over SSE. Events: {@code token} (incremental text),
     * {@code done} (completion), {@code error} (failure). Identity is captured synchronously before the
     * emitter is returned, then the chat runs on a worker thread.
     */
    @PostMapping(value = "/sessions/{sessionPublicId}/messages",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(@PathVariable UUID sessionPublicId,
                                  @Valid @RequestBody SendMessageRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        orchestrationService.submit(sessionPublicId, request.getMessage(), emitter);
        return emitter;
    }
}