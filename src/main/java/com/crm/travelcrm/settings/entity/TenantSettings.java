package com.crm.travelcrm.settings.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
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

    // ── Overdue-task alerting (All Tasks screen) ─────────────────────────────
    // IN_APP is always on and is not represented here. These two are OFF by default and must be
    // switched on per tenant.
    //
    // Why a switch exists at all: there is no per-USER notification opt-out anywhere in this
    // product yet (comm_notification_prefs is designed but read by no code), so without a
    // tenant-level flag an agent would receive WhatsApp messages about their own overdue work with
    // no way to stop them. Defaulting to false means turning this on is a deliberate act by an
    // agency that wants it.

    // @Builder.Default is required: this class is @SuperBuilder, which silently DISCARDS a plain
    // field initializer (see the build warnings on Testimonial/Addon, which have that bug).
    @Column(name = "task_overdue_alert_whatsapp", nullable = false)
    @Builder.Default
    private boolean taskOverdueAlertWhatsApp = false;

    @Column(name = "task_overdue_alert_email", nullable = false)
    @Builder.Default
    private boolean taskOverdueAlertEmail = false;
}