package com.crm.travelcrm.settings.service;

import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.settings.crypto.AesSecretCipher;
import com.crm.travelcrm.settings.entity.TenantSettings;
import com.crm.travelcrm.settings.repository.TenantSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Properties;

/**
 * Resolves tenant-owned SMTP only. Tenant/customer email must never fall back to the platform CRM
 * mailbox used for SuperAdmin security alerts.
 */
@Component
@RequiredArgsConstructor
public class TenantMailSenderFactory {

    private final TenantSettingsRepository repo;
    private final AesSecretCipher cipher;

    /** Sender + the From address/name to stamp on the message. */
    public record ResolvedMail(JavaMailSender sender, String from, String fromName) {}

    @Transactional(readOnly = true)
    public ResolvedMail resolve(Long tenantId) {
        if (tenantId == null) {
            throw new BusinessException("Tenant context is required to send tenant email.",
                    HttpStatus.FORBIDDEN);
        }

        TenantSettings ts = repo.findByTenantId(tenantId).orElse(null);
        boolean tenantConfigured = ts != null
                && StringUtils.hasText(ts.getSmtpHost())
                && StringUtils.hasText(ts.getSmtpUsername())
                && StringUtils.hasText(ts.getSmtpPasswordEnc());

        if (!tenantConfigured) {
            throw new BusinessException("Tenant SMTP is not configured. Configure Settings > Email first.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(ts.getSmtpHost());
        sender.setPort(ts.getSmtpPort() != null ? ts.getSmtpPort() : 587);
        sender.setUsername(ts.getSmtpUsername());
        sender.setPassword(cipher.decrypt(ts.getSmtpPasswordEnc()));

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        if ("SSL".equalsIgnoreCase(ts.getEncryption())) {
            props.put("mail.smtp.ssl.enable", "true");
        } else {
            props.put("mail.smtp.starttls.enable", "true");
        }

        String from = StringUtils.hasText(ts.getEmailFromAddress())
                ? ts.getEmailFromAddress()
                : ts.getSmtpUsername();
        return new ResolvedMail(sender, from, ts.getEmailFromName());
    }
}
