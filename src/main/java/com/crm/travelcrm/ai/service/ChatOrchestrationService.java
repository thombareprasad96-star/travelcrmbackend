package com.crm.travelcrm.ai.service;

import com.crm.travelcrm.ai.config.DishaProperties;
import com.crm.travelcrm.ai.entity.AiCallStatus;
import com.crm.travelcrm.ai.entity.ChatMessage;
import com.crm.travelcrm.ai.entity.ChatRole;
import com.crm.travelcrm.ai.entity.ChatSession;
import com.crm.travelcrm.ai.tool.BookingTools;
import com.crm.travelcrm.ai.tool.DashboardTools;
import com.crm.travelcrm.ai.tool.LeadTools;
import com.crm.travelcrm.ai.tool.QuotationTools;
import com.crm.travelcrm.ai.tool.ReminderTools;
import com.crm.travelcrm.common.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * The brain of Disha. It builds the bounded context (system prompt + recent turns), runs the Spring AI
 * tool-calling loop against Ollama, persists both turns, audits the outcome and streams the answer over
 * SSE.
 *
 * <p><b>Security on the worker thread:</b> the chat runs on {@code dishaTaskExecutor} (off the servlet
 * thread) so it can stream. SecurityContext + {@link TenantContext} are plain ThreadLocals that do NOT
 * inherit onto pool threads, so they are captured on the request thread in {@link #submit} and
 * re-established here, then always cleared in {@code finally}. This is what keeps every tool call
 * scoped to the right user + tenant.
 *
 * <p><b>Streaming model:</b> we use the blocking {@code call()} (not {@code stream()}) so the tool
 * loop executes inline with the ThreadLocals intact, then emit the final answer to the client in
 * chunks. True token streaming would require reactor context propagation and is a later enhancement.
 */
@Service
@Slf4j
public class ChatOrchestrationService {

    private static final int CHUNK_SIZE = 160;

    private final ChatClient chatClient;
    private final ChatSessionService chatSessionService;
    private final SystemPromptProvider systemPromptProvider;
    private final AiAuditService audit;
    private final DishaProperties properties;
    private final Executor dishaTaskExecutor;

    private final LeadTools leadTools;
    private final BookingTools bookingTools;
    private final QuotationTools quotationTools;
    private final ReminderTools reminderTools;
    private final DashboardTools dashboardTools;

    public ChatOrchestrationService(ChatClient.Builder chatClientBuilder,
                                    ChatSessionService chatSessionService,
                                    SystemPromptProvider systemPromptProvider,
                                    AiAuditService audit,
                                    DishaProperties properties,
                                    Executor dishaTaskExecutor,
                                    LeadTools leadTools,
                                    BookingTools bookingTools,
                                    QuotationTools quotationTools,
                                    ReminderTools reminderTools,
                                    DashboardTools dashboardTools) {
        this.chatClient = chatClientBuilder.build();
        this.chatSessionService = chatSessionService;
        this.systemPromptProvider = systemPromptProvider;
        this.audit = audit;
        this.properties = properties;
        this.dishaTaskExecutor = dishaTaskExecutor;
        this.leadTools = leadTools;
        this.bookingTools = bookingTools;
        this.quotationTools = quotationTools;
        this.reminderTools = reminderTools;
        this.dashboardTools = dashboardTools;
    }

    /** Called on the servlet (request) thread: capture identity, then process on the worker pool. */
    public void submit(UUID sessionPublicId, String userMessage, SseEmitter emitter) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long tenantId = TenantContext.getTenantId();
        dishaTaskExecutor.execute(() -> process(sessionPublicId, userMessage, auth, tenantId, emitter));
    }

    private void process(UUID sessionPublicId, String userMessage, Authentication auth,
                         Long tenantId, SseEmitter emitter) {
        long start = System.currentTimeMillis();
        Long sessionPk = null;
        try {
            if (auth == null || tenantId == null) {
                throw new IllegalStateException("Missing security context for chat");
            }
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            SecurityContextHolder.setContext(ctx);
            TenantContext.setTenantId(tenantId);

            ChatSession session = chatSessionService.requireOwnedSession(sessionPublicId);
            sessionPk = session.getId();
            audit.bindSession(sessionPk);

            // Build history from prior turns BEFORE persisting the new user turn (avoids duplication).
            List<Message> history = buildHistory(sessionPk);
            chatSessionService.appendMessage(session, ChatRole.USER, userMessage, null);

            String answer = chatClient.prompt()
                    .system(systemPromptProvider.systemPrompt())
                    .messages(history)
                    .user(userMessage)
                    .tools(leadTools, bookingTools, quotationTools, reminderTools, dashboardTools)
                    .call()
                    .content();
            if (answer == null) answer = "";

            chatSessionService.appendMessage(session, ChatRole.ASSISTANT, answer, null);

            emitChunks(emitter, answer);
            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
            emitter.complete();

            audit.recordTurn(sessionPk, userMessage, AiCallStatus.SUCCESS,
                    "reply length=" + answer.length(), System.currentTimeMillis() - start);

        } catch (Exception ex) {
            log.error("Disha chat failed for session {}", sessionPublicId, ex);
            audit.recordTurn(sessionPk, userMessage, AiCallStatus.ERROR, ex.getMessage(),
                    System.currentTimeMillis() - start);
            try {
                emitter.send(SseEmitter.event().name("error").data(friendlyError(ex)));
                emitter.complete();
            } catch (IOException ignore) {
                emitter.completeWithError(ex);
            }
        } finally {
            audit.clearSession();
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private List<Message> buildHistory(Long sessionId) {
        List<Message> history = new ArrayList<>();
        for (ChatMessage m : chatSessionService.recentMessages(sessionId, properties.getContextMaxTurns())) {
            if (m.getContent() == null || m.getContent().isBlank()) continue;
            switch (m.getRole()) {
                case USER -> history.add(new UserMessage(m.getContent()));
                case ASSISTANT -> history.add(new AssistantMessage(m.getContent()));
                default -> { /* SYSTEM/TOOL turns are not replayed */ }
            }
        }
        return history;
    }

    private void emitChunks(SseEmitter emitter, String text) throws IOException {
        for (int i = 0; i < text.length(); i += CHUNK_SIZE) {
            String part = text.substring(i, Math.min(text.length(), i + CHUNK_SIZE));
            emitter.send(SseEmitter.event().name("token").data(part));
        }
    }

    private String friendlyError(Exception ex) {
        String type = ex.getClass().getName().toLowerCase();
        String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
        if (type.contains("resourceaccess") || msg.contains("connection refused")
                || msg.contains("connect") || msg.contains("timed out")) {
            return "Disha's AI service is unavailable. Please ensure Ollama is running "
                    + "(http://localhost:11434) and the model is pulled.";
        }
        return "Sorry, I couldn't complete that request right now.";
    }
}