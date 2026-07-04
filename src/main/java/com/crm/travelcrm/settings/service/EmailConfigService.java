package com.crm.travelcrm.settings.service;

import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.settings.crypto.AesSecretCipher;
import com.crm.travelcrm.settings.dto.EmailConfigDTO;
import com.crm.travelcrm.settings.dto.EmailConfigRequest;
import com.crm.travelcrm.settings.dto.EmailStatsDTO;
import com.crm.travelcrm.settings.dto.SaveConfigResponse;
import com.crm.travelcrm.settings.dto.TestEmailRequest;
import com.crm.travelcrm.settings.dto.TestEmailResponse;
import com.crm.travelcrm.settings.entity.TenantSettings;
import com.crm.travelcrm.settings.repository.TenantSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Properties;

/**
 * Per-tenant SMTP configuration + test-send. One {@link TenantSettings} row per tenant (lazily
 * created on first save). The SMTP password is stored AES-encrypted and never returned to the UI.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailConfigService {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATETIME_FMT =
            DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm", Locale.ENGLISH);

    private final TenantSettingsRepository repo;
    private final AesSecretCipher cipher;
    private final EmailAuditService emailAudit;

    @Transactional(readOnly = true)
    public EmailConfigDTO getConfig(Long tenantId) {
        return toDto(repo.findByTenantId(tenantId).orElse(null));
    }

    /** Send stats for the settings hub — "Sent Today" + delivery rate. */
    @Transactional(readOnly = true)
    public EmailStatsDTO getStats(Long tenantId) {
        return emailAudit.stats(tenantId);
    }

    @Transactional
    public SaveConfigResponse save(EmailConfigRequest req, Long tenantId) {
        TenantSettings ts = loadOrCreate(tenantId);
        ts.setSmtpHost(req.getSmtpHost().trim());
        ts.setSmtpPort(req.getPortNumber() > 0 ? req.getPortNumber() : 587);
        ts.setEncryption(StringUtils.hasText(req.getEncryption()) ? req.getEncryption() : "TLS");
        ts.setSmtpUsername(req.getUsername().trim());
        // Only overwrite the stored password when the user actually changed it.
        if (req.isPasswordChanged() && StringUtils.hasText(req.getPassword())) {
            ts.setSmtpPasswordEnc(cipher.encrypt(req.getPassword()));
        }
        ts.setEmailFromAddress(req.getFromEmail().trim());
        ts.setEmailFromName(req.getFromName().trim());
        repo.save(ts);
        return new SaveConfigResponse(true, "Email configuration saved successfully",
                LocalDateTime.now().format(DATETIME_FMT));
    }

    @Transactional
    public TestEmailResponse sendTest(TestEmailRequest req, Long tenantId) {
        TenantSettings ts = repo.findByTenantId(tenantId).orElse(null);
        if (ts == null || !StringUtils.hasText(ts.getSmtpHost())
                || !StringUtils.hasText(ts.getSmtpPasswordEnc())) {
            throw new BusinessException("SMTP is not configured. Save configuration first.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String recipient = req.getRecipientEmail().trim();
        try {
            JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
            mailSender.setHost(ts.getSmtpHost());
            mailSender.setPort(ts.getSmtpPort() != null ? ts.getSmtpPort() : 587);
            mailSender.setUsername(ts.getSmtpUsername());
            mailSender.setPassword(cipher.decrypt(ts.getSmtpPasswordEnc()));
            Properties props = mailSender.getJavaMailProperties();
            props.put("mail.transport.protocol", "smtp");
            props.put("mail.smtp.auth", "true");
            if ("SSL".equalsIgnoreCase(ts.getEncryption())) {
                props.put("mail.smtp.ssl.enable", "true");
            } else if ("TLS".equalsIgnoreCase(ts.getEncryption())) {
                props.put("mail.smtp.starttls.enable", "true");
            }

            String fromAddress = StringUtils.hasText(ts.getEmailFromAddress())
                    ? ts.getEmailFromAddress() : ts.getSmtpUsername();
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromAddress);
            msg.setTo(recipient);
            msg.setSubject("Test Email"
                    + (StringUtils.hasText(ts.getEmailFromName()) ? " from " + ts.getEmailFromName() : ""));
            msg.setText("This is a test email from your Travel CRM.\n\n"
                    + "If you received this message, your SMTP configuration is working correctly.");
            mailSender.send(msg);

            ts.setEmailLastTestedAt(LocalDateTime.now());
            repo.save(ts);
            emailAudit.record(recipient, "Test Email", true, null);
            return new TestEmailResponse(true, "Test email sent successfully to " + recipient,
                    null, LocalDateTime.now().format(DATETIME_FMT));
        } catch (MailException ex) {
            // Structured error — a delivery failure is a normal 200 response, not a 500.
            log.warn("Test email failed for tenant {}: {}", tenantId, ex.getMessage());
            emailAudit.record(recipient, "Test Email", false, ex.getMessage());
            return new TestEmailResponse(false,
                    "Failed to send test email. Check host, port, credentials and encryption.",
                    ex.getMessage(), null);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private TenantSettings loadOrCreate(Long tenantId) {
        // tenantId auto-stamped by TenantEntityListener on persist; no need to set it here.
        return repo.findByTenantId(tenantId)
                .orElseGet(() -> repo.save(TenantSettings.builder().build()));
    }

    private EmailConfigDTO toDto(TenantSettings ts) {
        boolean configured = ts != null && StringUtils.hasText(ts.getSmtpHost());
        int port = ts != null && ts.getSmtpPort() != null ? ts.getSmtpPort() : 587;
        String enc = ts != null && StringUtils.hasText(ts.getEncryption()) ? ts.getEncryption() : "TLS";
        return EmailConfigDTO.builder()
                .configured(configured)
                .smtpHost(ts != null ? ts.getSmtpHost() : null)
                .smtpPort(formatPort(port, enc))
                .portNumber(port)
                .encryption(enc)
                .username(ts != null ? ts.getSmtpUsername() : null)
                .passwordSet(ts != null && StringUtils.hasText(ts.getSmtpPasswordEnc()))
                .fromEmail(ts != null ? ts.getEmailFromAddress() : null)
                .fromName(ts != null ? ts.getEmailFromName() : null)
                .lastTestedAt(ts != null && ts.getEmailLastTestedAt() != null
                        ? ts.getEmailLastTestedAt().format(DATE_FMT) : null)
                .lastSavedAt(ts != null && ts.getUpdatedAt() != null
                        ? ts.getUpdatedAt().format(DATE_FMT) : null)
                .build();
    }

    private String formatPort(int port, String encryption) {
        return switch (port) {
            case 465 -> "465 (SSL)";
            case 587 -> "587 (TLS)";
            case 25 -> "25 (SMTP)";
            case 2525 -> "2525 (Alt)";
            default -> port + (StringUtils.hasText(encryption) ? " (" + encryption + ")" : "");
        };
    }
}