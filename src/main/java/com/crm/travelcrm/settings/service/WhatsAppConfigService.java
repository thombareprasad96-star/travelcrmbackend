package com.crm.travelcrm.settings.service;

import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.settings.crypto.AesSecretCipher;
import com.crm.travelcrm.settings.dto.SaveConfigResponse;
import com.crm.travelcrm.settings.dto.TestWhatsAppRequest;
import com.crm.travelcrm.settings.dto.TestWhatsAppResponse;
import com.crm.travelcrm.settings.dto.WhatsAppConfigDTO;
import com.crm.travelcrm.settings.dto.WhatsAppConfigRequest;
import com.crm.travelcrm.settings.dto.WhatsAppStatsDTO;
import com.crm.travelcrm.settings.entity.TenantSettings;
import com.crm.travelcrm.settings.repository.TenantSettingsRepository;
import com.crm.travelcrm.settings.repository.WaMessageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Per-tenant WhatsApp configuration, test-send, and integration stats. The API key is stored
 * AES-encrypted and never returned. Delivery goes through the swappable {@link WhatsAppSender} SPI
 * (logging stub by default); every attempt is recorded in {@code whatsapp_logs} for the stats.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WhatsAppConfigService {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATETIME_FMT =
            DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm", Locale.ENGLISH);

    private final TenantSettingsRepository repo;
    private final WaMessageLogRepository waLogRepo;
    private final AesSecretCipher cipher;
    private final WhatsAppMessagingService messaging;

    @Transactional(readOnly = true)
    public WhatsAppConfigDTO getConfig(Long tenantId) {
        return toDto(repo.findByTenantId(tenantId).orElse(null));
    }

    @Transactional
    public SaveConfigResponse save(WhatsAppConfigRequest req, Long tenantId) {
        TenantSettings ts = loadOrCreate(tenantId);
        // Only overwrite the stored API key when the user actually changed it.
        if (req.isApiKeyChanged() && StringUtils.hasText(req.getApiKey())) {
            ts.setWhatsAppApiKeyEnc(cipher.encrypt(req.getApiKey()));
        }
        ts.setWaTemplateName(req.getTemplateName().trim());
        ts.setWaTemplateLanguage(req.getTemplateLanguage().trim());
        ts.setWaHeaderImageUrl(StringUtils.hasText(req.getHeaderImageUrl())
                ? req.getHeaderImageUrl().trim() : null);
        repo.save(ts);
        return new SaveConfigResponse(true, "WhatsApp configuration saved successfully",
                LocalDateTime.now().format(DATETIME_FMT));
    }

    @Transactional
    public TestWhatsAppResponse sendTest(TestWhatsAppRequest req, Long tenantId) {
        if (!messaging.isConfigured(tenantId)) {
            throw new BusinessException("WhatsApp is not configured. Save configuration first.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        // Delivery + the whatsapp_logs audit row are handled by the messaging facade.
        WhatsAppMessagingService.Result result =
                messaging.sendWithTenantTemplate(tenantId, req.getPhoneNumber(), List.of("Test User"));
        if (result.success()) {
            repo.findByTenantId(tenantId).ifPresent(ts -> {
                ts.setWaLastTestedAt(LocalDateTime.now());
                repo.save(ts);
            });
            return new TestWhatsAppResponse(true, "Test message sent successfully",
                    null, LocalDateTime.now().format(DATETIME_FMT));
        }
        log.warn("Test WhatsApp failed for tenant {}: {}", tenantId, result.errorMessage());
        return new TestWhatsAppResponse(false,
                "Failed to send test message. Check your API key and template name.",
                result.errorMessage(), null);
    }

    @Transactional(readOnly = true)
    public WhatsAppStatsDTO getStats(Long tenantId) {
        TenantSettings ts = repo.findByTenantId(tenantId).orElse(null);
        boolean configured = ts != null && StringUtils.hasText(ts.getWhatsAppApiKeyEnc());
        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        long sent = configured
                ? waLogRepo.countByTenantIdAndStatusAndSentAtAfter(tenantId, "SENT", monthStart) : 0;
        long total = configured
                ? waLogRepo.countByTenantIdAndSentAtAfter(tenantId, monthStart) : 0;
        String rate = total > 0 ? Math.round(sent * 100.0 / total) + "%" : "—";
        String lastTested = ts != null && ts.getWaLastTestedAt() != null
                ? ts.getWaLastTestedAt().format(DATE_FMT) : null;
        return new WhatsAppStatsDTO(sent, rate, lastTested,
                configured ? "Active" : "Inactive",
                configured ? "Connected" : "Not configured");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private TenantSettings loadOrCreate(Long tenantId) {
        return repo.findByTenantId(tenantId)
                .orElseGet(() -> repo.save(TenantSettings.builder().build()));
    }

    private WhatsAppConfigDTO toDto(TenantSettings ts) {
        boolean configured = ts != null && StringUtils.hasText(ts.getWhatsAppApiKeyEnc());
        return WhatsAppConfigDTO.builder()
                .configured(configured)
                .apiKeySet(configured)
                .templateName(ts != null ? ts.getWaTemplateName() : null)
                .templateLanguage(ts != null && StringUtils.hasText(ts.getWaTemplateLanguage())
                        ? ts.getWaTemplateLanguage() : "en")
                .headerImageUrl(ts != null ? ts.getWaHeaderImageUrl() : null)
                .whatsAppPhone(ts != null ? ts.getWhatsAppPhone() : null)
                .lastTestedAt(ts != null && ts.getWaLastTestedAt() != null
                        ? ts.getWaLastTestedAt().format(DATE_FMT) : null)
                .lastSavedAt(ts != null && ts.getUpdatedAt() != null
                        ? ts.getUpdatedAt().format(DATE_FMT) : null)
                .build();
    }
}