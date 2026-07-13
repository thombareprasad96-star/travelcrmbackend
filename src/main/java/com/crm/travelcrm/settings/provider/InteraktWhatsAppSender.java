package com.crm.travelcrm.settings.provider;

import com.crm.travelcrm.settings.config.WhatsAppProperties;
import com.crm.travelcrm.settings.crypto.AesSecretCipher;
import com.crm.travelcrm.settings.entity.TenantSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real {@link WhatsAppSender} backed by the Interakt WhatsApp Business API. Active by default
 * ({@code app.whatsapp.provider=interakt}) and marked {@code @Primary}, so it transparently replaces
 * {@link LoggingWhatsAppSender} everywhere a sender is injected.
 *
 * <p>Each tenant's own API key is decrypted per-call from {@code TenantSettings} (never logged), and
 * the message goes out against the tenant's approved template. A non-2xx response is turned into a
 * {@link RuntimeException} carrying Interakt's error body so the caller can record FAILED and surface
 * a message.
 *
 * @see <a href="https://www.interakt.shop/resource-center/send-whatsapp-messages-using-api/">Interakt send-message API</a>
 */
@Component
@Primary
@ConditionalOnProperty(name = "app.whatsapp.provider", havingValue = "interakt", matchIfMissing = true)
@Slf4j
public class InteraktWhatsAppSender implements WhatsAppSender {

    private final AesSecretCipher cipher;
    private final WhatsAppProperties props;
    private final RestClient client;

    public InteraktWhatsAppSender(AesSecretCipher cipher, WhatsAppProperties props) {
        this.cipher = cipher;
        this.props = props;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(props.getInterakt().getConnectTimeoutMs());
        rf.setReadTimeout(props.getInterakt().getReadTimeoutMs());
        this.client = RestClient.builder().requestFactory(rf).build();
    }

    @Override
    public void send(TenantSettings ts, WaTemplateMessage message) {
        String apiKey = cipher.decrypt(ts.getWhatsAppApiKeyEnc());
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("WhatsApp API key is not set for this tenant");
        }
        if (!StringUtils.hasText(message.templateName())) {
            throw new IllegalStateException("No WhatsApp template name to send");
        }

        String[] phone = splitCountryCode(message.toPhoneE164());

        Map<String, Object> template = new LinkedHashMap<>();
        template.put("name", message.templateName());
        template.put("languageCode", StringUtils.hasText(message.languageCode())
                ? message.languageCode() : props.getDefaultLanguage());
        template.put("bodyValues", message.bodyValues() != null ? message.bodyValues() : List.of());
        if (StringUtils.hasText(message.mediaUrl())) {
            template.put("mediaUrl", message.mediaUrl());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("countryCode", phone[0]);
        payload.put("phoneNumber", phone[1]);
        payload.put("type", "Template");
        payload.put("template", template);

        try {
            ResponseEntity<String> resp = client.post()
                    .uri(props.getInterakt().getBaseUrl())
                    // Interakt's API key is already the ready-to-use Basic credential — do not re-encode.
                    .header(HttpHeaders.AUTHORIZATION, "Basic " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toEntity(String.class);
            log.info("[WhatsApp][Interakt] sent template '{}' to {}{} — HTTP {}",
                    message.templateName(), phone[0], phone[1], resp.getStatusCode().value());
        } catch (RestClientResponseException ex) {
            // 4xx/5xx from Interakt — include the response body (never the API key) for diagnosis.
            throw new RuntimeException("Interakt API error " + ex.getStatusCode().value()
                    + ": " + ex.getResponseBodyAsString(), ex);
        }
    }

    /**
     * Split an E.164 number into Interakt's {@code countryCode} + national {@code phoneNumber}. Numbers
     * are normalised upstream to {@code <defaultCountryCode><national>}, so we strip the configured
     * country code; a number with a different country code falls back to keeping its national part.
     */
    private String[] splitCountryCode(String e164) {
        String cc = props.getDefaultCountryCode();
        if (e164 != null && e164.startsWith(cc)) {
            return new String[]{cc, e164.substring(cc.length())};
        }
        String national = e164 == null ? "" : e164.replaceFirst("^\\+", "");
        return new String[]{cc, national};
    }
}
