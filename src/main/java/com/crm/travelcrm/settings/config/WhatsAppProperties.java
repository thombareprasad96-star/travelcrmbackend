package com.crm.travelcrm.settings.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound from {@code app.whatsapp.*}. Tunables for the outbound WhatsApp integration: which provider
 * is active, phone/language defaults, the Interakt HTTP endpoint + timeouts, and the approved
 * template name to use per business flow.
 *
 * <p>Per-tenant secrets (API key, phone, the tenant's own default template) live on
 * {@code TenantSettings} — these properties are the application-wide wiring only.
 */
@Component
@ConfigurationProperties(prefix = "app.whatsapp")
public class WhatsAppProperties {

    /**
     * Active provider. {@code "interakt"} wires the real {@code InteraktWhatsAppSender}; anything
     * else leaves the logging stub in place. Defaults to interakt.
     */
    private String provider = "interakt";

    /** Country code prepended to bare (national) phone numbers, e.g. {@code "+91"}. */
    private String defaultCountryCode = "+91";

    /** Template language used when a tenant hasn't set one. */
    private String defaultLanguage = "en";

    private final Interakt interakt = new Interakt();
    private final Templates templates = new Templates();

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getDefaultCountryCode() { return defaultCountryCode; }
    public void setDefaultCountryCode(String defaultCountryCode) { this.defaultCountryCode = defaultCountryCode; }

    public String getDefaultLanguage() { return defaultLanguage; }
    public void setDefaultLanguage(String defaultLanguage) { this.defaultLanguage = defaultLanguage; }

    public Interakt getInterakt() { return interakt; }
    public Templates getTemplates() { return templates; }

    /** Interakt "send template" endpoint + HTTP timeouts. */
    public static class Interakt {
        private String baseUrl = "https://api.interakt.ai/v1/public/message/";
        private int connectTimeoutMs = 5000;
        private int readTimeoutMs = 10000;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }

        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    }

    /**
     * Approved WhatsApp template names per flow. Leave blank to fall back to the tenant's own default
     * template (Settings → WhatsApp). Interakt requires every template be pre-approved on the account.
     */
    public static class Templates {
        private String otp;
        private String quotation;
        private String reminder;

        public String getOtp() { return otp; }
        public void setOtp(String otp) { this.otp = otp; }

        public String getQuotation() { return quotation; }
        public void setQuotation(String quotation) { this.quotation = quotation; }

        public String getReminder() { return reminder; }
        public void setReminder(String reminder) { this.reminder = reminder; }
    }
}
