package com.crm.travelcrm.auth.service;

import com.crm.travelcrm.common.entity.SuperAdmin;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class SuperAdminLoginNotificationService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JavaMailSender mailSender;

    @Value("${app.super-admin.login-alerts.enabled:true}")
    private boolean enabled;

    @Value("${app.super-admin.login-alerts.from-email:vetotechit@gmail.com}")
    private String fromEmail;

    @Value("${app.super-admin.login-alerts.from-name:Veto Tech IT}")
    private String fromName;

    public boolean isNewDeviceOrIp(SuperAdmin admin, String ipAddress, String userAgent) {
        String hash = userAgentHash(userAgent);
        return admin.getLastLoginAt() == null
                || !Objects.equals(admin.getLastLoginIp(), ipAddress)
                || !Objects.equals(admin.getLastLoginUserAgentHash(), hash);
    }

    public void notifyLogin(SuperAdmin admin, String ipAddress, String userAgent,
                            boolean newDeviceOrIp, LocalDateTime loginAt) {
        if (!enabled || admin == null || !StringUtils.hasText(admin.getEmail())) {
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            if (StringUtils.hasText(fromEmail) && StringUtils.hasText(fromName)) {
                helper.setFrom(fromEmail, fromName);
            } else if (StringUtils.hasText(fromEmail)) {
                helper.setFrom(fromEmail);
            }
            helper.setTo(admin.getEmail());
            helper.setSubject(newDeviceOrIp
                    ? "[TravelCRM] New SuperAdmin device/IP login"
                    : "[TravelCRM] SuperAdmin login");
            helper.setText("""
                    A SuperAdmin login succeeded for your TravelCRM platform account.

                    Email: %s
                    Time: %s
                    IP: %s
                    Device/IP status: %s
                    User-Agent: %s

                    If this was not you, rotate the password and investigate the platform audit log immediately.
                    """.formatted(
                    admin.getEmail(),
                    loginAt != null ? loginAt.format(FORMATTER) : "unknown",
                    StringUtils.hasText(ipAddress) ? ipAddress : "unknown",
                    newDeviceOrIp ? "NEW OR CHANGED" : "known",
                    StringUtils.hasText(userAgent) ? userAgent : "unknown"));
            mailSender.send(message);
        } catch (Exception ex) {
            log.warn("SuperAdmin login notification email failed for {}: {}",
                    admin.getEmail(), ex.getMessage());
        }
    }

    public String userAgentHash(String userAgent) {
        if (!StringUtils.hasText(userAgent)) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(userAgent.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash user-agent", ex);
        }
    }
}
