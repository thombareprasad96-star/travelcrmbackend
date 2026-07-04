package com.crm.travelcrm.settings.service;

import com.crm.travelcrm.settings.crypto.AesSecretCipher;
import com.crm.travelcrm.settings.entity.TenantSettings;
import com.crm.travelcrm.settings.repository.TenantSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Properties;

/**
 * Resolves the {@link JavaMailSender} to use for a tenant. If the tenant has saved its own SMTP
 * settings (Settings → Email), a sender is built from them so mail goes out from the tenant's own
 * address. Otherwise it falls back to the application-wide {@code spring.mail.*} sender so tenants
 * that haven't configured email still work.
 *
 * <p>Use this anywhere real mail is sent (quotation email, etc.) instead of injecting the global
 * {@code JavaMailSender} directly — that global bean sends from the single {@code spring.mail}
 * account regardless of tenant.
 */
@Component
@RequiredArgsConstructor
public class TenantMailSenderFactory {

    private final TenantSettingsRepository repo;
    private final AesSecretCipher cipher;
    private final JavaMailSender defaultMailSender;   // the global spring.mail.* bean (fallback)

    @Value("${spring.mail.username:no-reply@travelcrm.local}")
    private String defaultFrom;

    /** Sender + the From address/name to stamp on the message. */
    public record ResolvedMail(JavaMailSender sender, String from, String fromName) {}

    @Transactional(readOnly = true)
    public ResolvedMail resolve(Long tenantId) {
        TenantSettings ts = tenantId == null ? null : repo.findByTenantId(tenantId).orElse(null);

        boolean tenantConfigured = ts != null
                && StringUtils.hasText(ts.getSmtpHost())
                && StringUtils.hasText(ts.getSmtpPasswordEnc());

        if (!tenantConfigured) {
            // No per-tenant SMTP — fall back to the application-wide sender.
            return new ResolvedMail(defaultMailSender, defaultFrom, null);
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
        } else if ("TLS".equalsIgnoreCase(ts.getEncryption())) {
            props.put("mail.smtp.starttls.enable", "true");
        }

        String from = StringUtils.hasText(ts.getEmailFromAddress())
                ? ts.getEmailFromAddress() : ts.getSmtpUsername();
        return new ResolvedMail(sender, from, ts.getEmailFromName());
    }
}