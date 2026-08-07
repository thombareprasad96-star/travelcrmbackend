package com.crm.travelcrm.communication.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.common.entity.Ownable;
import com.crm.travelcrm.communication.enums.CommChannel;
import com.crm.travelcrm.communication.enums.TemplateStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * A reusable message template — the tenant's single template library.
 *
 * <p><b>Nothing like this existed before.</b> Marketing stores {@code subject}/{@code body} inline
 * on each campaign, drip step and automation trigger, and its {@code templateName} is a bare string
 * naming a provider-approved WhatsApp template with no row behind it. This table is the shared
 * library; marketing adopts it in a later pass.
 *
 * <p>WhatsApp quick replies are simply templates with {@code category = "QUICK_REPLY"} — no second
 * table, so a quick reply is editable, searchable and reportable like any other.
 *
 * <h2>{@link #arity} exists because a mismatch is otherwise silent</h2>
 * WhatsApp {@code bodyValues} are POSITIONAL substitutions for {@code {{1}}, {{2}}, …}, not a
 * message body. The two existing callers already disagree about what that means:
 * {@code MessageDispatcher} passes one whole composed message as a single value, while
 * {@code QuotationServiceImpl} passes three ordered parameters. Nothing in the codebase records how
 * many a given provider template expects, so getting it wrong is a provider-side rejection logged as
 * FAILED with no explanation. {@link #arity} records it.
 */
@Entity
@Table(name = "comm_templates", indexes = {
        @Index(name = "idx_ctpl_tenant",   columnList = "tenant_id"),
        @Index(name = "idx_ctpl_channel",  columnList = "channel"),
        @Index(name = "idx_ctpl_status",   columnList = "status"),
        @Index(name = "idx_ctpl_category", columnList = "category"),
        @Index(name = "idx_ctpl_owner",    columnList = "owner_user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CommTemplate extends BaseTenantEntity implements Ownable {

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private CommChannel channel;

    /**
     * Grouping label — {@code QUICK_REPLY}, {@code GREETING}, {@code FOLLOW_UP}, {@code PAYMENT}, …
     *
     * <p>A free String rather than an enum, for the same reason as {@code CommCall.outcome}: it is a
     * display/filter label over a vocabulary each agency extends to suit itself, and an enum column
     * would need a CHECK-constraint refresh and a migration for every new one.
     */
    @Column(name = "category", length = 40)
    private String category;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /** Email only. */
    @Column(name = "subject", length = 255)
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    /**
     * The provider-side approved template name (WhatsApp).
     *
     * <p>Null for email/SMS and for a WhatsApp template still awaiting Meta approval. Its presence
     * is the only signal available that this template can actually be sent — approval happens in the
     * provider's console and cannot be observed from here.
     */
    @Column(name = "provider_template_name", length = 120)
    private String providerTemplateName;

    @Column(name = "language_code", length = 10)
    private String languageCode;

    /** Number of positional {@code {{n}}} parameters the provider template expects. */
    @Column(name = "arity", nullable = false)
    @Builder.Default
    private short arity = 0;

    /** Ordered, comma-separated names for the {@code {{n}}} slots. Labels the composer's inputs. */
    @Column(name = "variables", columnDefinition = "TEXT")
    private String variables;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    @Builder.Default
    private TemplateStatus status = TemplateStatus.DRAFT;

    @Column(name = "usage_count", nullable = false)
    @Builder.Default
    private int usageCount = 0;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    /** True when this can be handed to a transport right now. */
    public boolean isSendable() {
        return status == TemplateStatus.ACTIVE
                && (channel != CommChannel.WHATSAPP || providerTemplateName != null);
    }
}
