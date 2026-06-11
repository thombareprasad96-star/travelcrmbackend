package com.crm.travelcrm.notification.api;

import java.util.Map;

/**
 * Renders a notification message from a template string and a payload map.
 * Supports variable substitution with {@code ${key}} placeholders.
 *
 * <p>Implementations can use Thymeleaf, Freemarker, or simple string replacement.
 * Swap implementations via Spring bean override without touching callers (D principle).
 */
public interface TemplateRenderer {

    /**
     * @param template raw template string, e.g. {@code "Hello ${name}, your lead is ready."}
     * @param payload  key-value pairs to substitute
     * @return rendered string
     */
    String render(String template, Map<String, Object> payload);
}