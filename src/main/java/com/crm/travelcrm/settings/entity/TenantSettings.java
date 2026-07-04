package com.crm.travelcrm.settings.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * One row per tenant holding the tenant's integration secrets/config: SMTP (email) and WhatsApp.
 * Extends {@link BaseTenantEntity} so {@code tenantId} is auto-stamped and the Hibernate tenant
 * filter scopes every read. Provisioned lazily on the first save (see the config services).
 *
 * <p>Secrets ({@code smtpPasswordEnc}, {@code whatsAppApiKeyEnc}) are stored AES-encrypted — never
 * plaintext, and never returned to the client.
 */
@Entity
@Table(name = "tenant_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class TenantSettings extends BaseTenantEntity {

    // ── Email / SMTP ─────────────────────────────────────────────────────────
    @Column(name = "smtp_host")
    private String smtpHost;

    @Column(name = "smtp_port")
    private Integer smtpPort;

    @Column(name = "encryption")
    private String encryption;                 // TLS | SSL | None

    @Column(name = "smtp_username")
    private String smtpUsername;

    @Column(name = "smtp_password_enc", columnDefinition = "TEXT")
    private String smtpPasswordEnc;            // AES ciphertext

    @Column(name = "email_from_address")
    private String emailFromAddress;

    @Column(name = "email_from_name")
    private String emailFromName;

    @Column(name = "email_last_tested_at")
    private LocalDateTime emailLastTestedAt;

    // ── WhatsApp ─────────────────────────────────────────────────────────────
    @Column(name = "whatsapp_api_key_enc", columnDefinition = "TEXT")
    private String whatsAppApiKeyEnc;         // AES ciphertext

    @Column(name = "whatsapp_phone")
    private String whatsAppPhone;

    @Column(name = "wa_template_name")
    private String waTemplateName;

    @Column(name = "wa_template_language")
    private String waTemplateLanguage;        // default "en"

    @Column(name = "wa_header_image_url", columnDefinition = "TEXT")
    private String waHeaderImageUrl;

    @Column(name = "wa_last_tested_at")
    private LocalDateTime waLastTestedAt;
}