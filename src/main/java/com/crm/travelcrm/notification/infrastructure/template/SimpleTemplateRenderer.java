package com.crm.travelcrm.notification.infrastructure.template;

import com.crm.travelcrm.notification.api.TemplateRenderer;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Minimal {@code ${key}} variable substitution renderer.
 * Replace with a Thymeleaf or Freemarker implementation by declaring a higher-priority
 * bean — callers depend on the {@link TemplateRenderer} abstraction only (D principle).
 */
@Component
public class SimpleTemplateRenderer implements TemplateRenderer {

    @Override
    public String render(String template, Map<String, Object> payload) {
        if (template == null) return "";
        if (payload == null || payload.isEmpty()) return template;
        String result = template;
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return result;
    }
}