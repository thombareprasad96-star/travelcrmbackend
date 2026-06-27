package com.crm.travelcrm.ai.service;

import com.crm.travelcrm.ai.entity.AiAuditLog;
import com.crm.travelcrm.ai.entity.AiCallStatus;
import com.crm.travelcrm.ai.repository.AiAuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * Audits every Disha interaction and tool call: prompt + tool name + params + outcome + userId +
 * tenantId. Writes are best-effort (a logging failure must never break a chat). A per-thread
 * "current session" binding lets tool-call audits link back to the owning session without threading
 * the id through every tool signature.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiAuditService {

    private static final int MAX_OUTCOME = 2000;
    private static final ThreadLocal<Long> CURRENT_SESSION = new ThreadLocal<>();

    private final AiAuditLogRepository repository;
    private final AiCurrentUser currentUser;
    private final ObjectMapper objectMapper;

    public void bindSession(Long sessionId) { CURRENT_SESSION.set(sessionId); }
    public void clearSession() { CURRENT_SESSION.remove(); }

    /**
     * Run a tool delegate, timing it and persisting an audit row for the outcome. The original
     * result/exception is always returned/rethrown unchanged — auditing is transparent to callers.
     */
    public <T> T recordToolCall(String toolName, Object params, Supplier<T> action) {
        long start = System.currentTimeMillis();
        try {
            T result = action.get();
            persist(toolName, params, AiCallStatus.SUCCESS, summarize(result),
                    System.currentTimeMillis() - start, CURRENT_SESSION.get(), null);
            return result;
        } catch (AccessDeniedException ex) {
            persist(toolName, params, AiCallStatus.DENIED, ex.getMessage(),
                    System.currentTimeMillis() - start, CURRENT_SESSION.get(), null);
            throw ex;
        } catch (RuntimeException ex) {
            persist(toolName, params, AiCallStatus.ERROR, ex.getMessage(),
                    System.currentTimeMillis() - start, CURRENT_SESSION.get(), null);
            throw ex;
        }
    }

    /** Chat-turn level audit (the prompt + final outcome of the whole orchestration). */
    public void recordTurn(Long sessionId, String prompt, AiCallStatus status, String outcome, long latencyMs) {
        persist(null, null, status, outcome, latencyMs, sessionId, prompt);
    }

    private void persist(String toolName, Object params, AiCallStatus status, String outcome,
                         long latencyMs, Long sessionId, String prompt) {
        try {
            repository.save(AiAuditLog.builder()
                    .tenantId(safeTenant())
                    .userId(safeUser())
                    .sessionId(sessionId)
                    .prompt(truncate(prompt))
                    .toolName(toolName)
                    .toolParams(toJson(params))
                    .outcome(truncate(outcome))
                    .status(status)
                    .latencyMs(latencyMs)
                    .build());
        } catch (Exception ex) {
            log.warn("AI audit write failed (tool={}, status={}): {}", toolName, status, ex.getMessage());
        }
    }

    private Long safeTenant() {
        try { return currentUser.requireTenantId(); } catch (RuntimeException e) { return null; }
    }

    private Long safeUser() {
        try { return currentUser.requireUserId(); } catch (RuntimeException e) { return null; }
    }

    private String toJson(Object params) {
        if (params == null) return null;
        try { return truncate(objectMapper.writeValueAsString(params)); }
        catch (Exception e) { return String.valueOf(params); }
    }

    private String summarize(Object result) {
        if (result == null) return "null";
        try { return truncate(objectMapper.writeValueAsString(result)); }
        catch (Exception e) { return truncate(result.toString()); }
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() <= MAX_OUTCOME ? s : s.substring(0, MAX_OUTCOME) + "…";
    }
}