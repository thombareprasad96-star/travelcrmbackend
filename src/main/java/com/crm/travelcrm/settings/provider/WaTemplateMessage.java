package com.crm.travelcrm.settings.provider;

import java.util.List;

/**
 * One outbound WhatsApp template message, provider-agnostic. Carries everything a {@link WhatsAppSender}
 * needs to deliver a single templated message; the tenant credentials come separately (the sender
 * reads them from {@code TenantSettings}).
 *
 * @param toPhoneE164  destination in E.164, e.g. {@code "+919099097103"}
 * @param templateName the pre-approved template to render
 * @param languageCode template language, e.g. {@code "en"}
 * @param bodyValues   ordered body substitutions ({{1}}, {{2}}, …)
 * @param mediaUrl     optional header media URL (image/pdf); {@code null} when the template has no header
 */
public record WaTemplateMessage(
        String toPhoneE164,
        String templateName,
        String languageCode,
        List<String> bodyValues,
        String mediaUrl) {
}
