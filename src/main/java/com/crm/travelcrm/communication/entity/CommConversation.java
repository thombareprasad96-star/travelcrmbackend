package com.crm.travelcrm.communication.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.common.entity.Ownable;
import com.crm.travelcrm.communication.enums.CommChannel;
import com.crm.travelcrm.communication.enums.ConversationKind;
import com.crm.travelcrm.communication.enums.ConversationPriority;
import com.crm.travelcrm.communication.enums.ConversationStatus;
import com.crm.travelcrm.communication.enums.MessageDirection;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One thread — the row the inbox lists and the conversation view opens.
 *
 * <p>Tenant-scoped via {@link BaseTenantEntity}. Implements {@link Ownable} so the sub-agent row
 * scope ({@code SubAgentScope}) confines a partner to its own threads; the broader OWN/TEAM/ALL
 * axis that implements "assigned vs all conversations" reads {@link #assignedUserId} through
 * {@code ScopeResolver}.
 *
 * <p><b>{@link #ownerUserId} must be set explicitly by the service, never left to
 * {@code OwnershipEntityListener}.</b> That listener only stamps when the authenticated principal is
 * a tenant {@code User}, and the commonest way a conversation is born is an inbound webhook — a
 * thread with a null owner is invisible to a sub-agent forever.
 *
 * <h2>Two clocks, and why both are stored</h2>
 * {@link #lastMessageAt} orders the inbox. {@link #lastInboundAt} is a different thing entirely: it
 * is when the CONTACT last wrote, and it is what opens and closes the WhatsApp 24-hour customer
 * service window. Outside that window Meta permits approved templates only, so
 * {@link #isSessionWindowOpen(Duration)} is what the send endpoint checks before accepting free
 * text. Deriving it from the newest message would mean loading messages to answer a question the
 * composer asks on every keystroke.
 *
 * <h2>Unread is deliberately modelled twice</h2>
 * {@link #unreadCount} here is correct for {@link ConversationKind#CUSTOMER} threads, which have one
 * external party and one agent who owes the reply. {@link ConversationKind#INTERNAL} threads ignore
 * it and read {@code CommConversationMember.lastReadAt} instead, because a group chat genuinely has
 * N different unread counts. Forcing per-user read state onto customer threads would add a row per
 * user per conversation for no product gain.
 */
@Entity
@Table(name = "comm_conversations", indexes = {
        @Index(name = "idx_conv_tenant",   columnList = "tenant_id"),
        @Index(name = "idx_conv_last_msg", columnList = "last_message_at"),
        @Index(name = "idx_conv_assignee", columnList = "assigned_user_id"),
        @Index(name = "idx_conv_owner",    columnList = "owner_user_id"),
        @Index(name = "idx_conv_status",   columnList = "status"),
        @Index(name = "idx_conv_channel",  columnList = "channel"),
        @Index(name = "idx_conv_contact",  columnList = "contact_identity_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CommConversation extends BaseTenantEntity implements Ownable {

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 10)
    @Builder.Default
    private ConversationKind kind = ConversationKind.CUSTOMER;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private CommChannel channel;

    /** {@code comm_contact_identities.id}. Null exactly when {@link #kind} is INTERNAL. */
    @Column(name = "contact_identity_id")
    private Long contactIdentityId;

    /** {@code comm_channel_accounts.id} the thread arrived on / goes out through. */
    @Column(name = "channel_account_id")
    private Long channelAccountId;

    /** Email thread subject, or an internal channel's name. Null for WhatsApp/SMS. */
    @Column(name = "subject", length = 255)
    private String subject;

    // ── Contact snapshot: denormalised so the list view never joins ────────────────────────────

    @Column(name = "contact_name", length = 150)
    private String contactName;

    /** Canonical address, mirrored from the contact identity for display and search. */
    @Column(name = "contact_value", length = 190)
    private String contactValue;

    // ── Ownership + assignment ────────────────────────────────────────────────────────────────

    /** Row-level owner. Set explicitly in the service — see the class javadoc. */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    /** Who owes the reply. The field {@code ScopeResolver} scopes "assigned vs all" on. */
    @Column(name = "assigned_user_id")
    private Long assignedUserId;

    @Column(name = "assigned_user_public_id")
    private UUID assignedUserPublicId;

    @Column(name = "assigned_user_name", length = 150)
    private String assignedUserName;

    // ── State ─────────────────────────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    @Builder.Default
    private ConversationStatus status = ConversationStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 10)
    @Builder.Default
    private ConversationPriority priority = ConversationPriority.NORMAL;

    /** Only meaningful while {@link #status} is SNOOZED. */
    @Column(name = "snoozed_until")
    private LocalDateTime snoozedUntil;

    @Column(name = "pinned", nullable = false)
    @Builder.Default
    private boolean pinned = false;

    // ── Clocks. See the class javadoc — these are NOT interchangeable. ─────────────────────────

    /** Newest message of any direction. The inbox sort key. */
    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    /** Newest INBOUND message. Opens/closes the WhatsApp session window. */
    @Column(name = "last_inbound_at")
    private LocalDateTime lastInboundAt;

    /** Newest OUTBOUND message — "have we replied yet?" without loading the thread. */
    @Column(name = "last_outbound_at")
    private LocalDateTime lastOutboundAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_direction", length = 10)
    private MessageDirection lastDirection;

    /** Trimmed body of the newest message, for the list row. */
    @Column(name = "last_message_preview", length = 300)
    private String lastMessagePreview;

    @Column(name = "unread_count", nullable = false)
    @Builder.Default
    private int unreadCount = 0;

    @Column(name = "message_count", nullable = false)
    @Builder.Default
    private int messageCount = 0;

    // ── CRM links. All nullable, all logical FKs, filled in as they are discovered. ────────────

    @Column(name = "lead_id")
    private Long leadId;

    @Column(name = "lead_public_id")
    private UUID leadPublicId;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "customer_public_id")
    private UUID customerPublicId;

    /**
     * Carried as a UUID on THIS table rather than as a column on {@code bookings}.
     *
     * <p>{@code Booking} is {@code @Audited}: every column added there needs its twin in
     * {@code bookings_aud} or every write to every booking fails. PART 14 restructured the whole
     * marketplace link for the same reason.
     */
    @Column(name = "booking_public_id")
    private UUID bookingPublicId;

    @Column(name = "quotation_public_id")
    private UUID quotationPublicId;

    // ── Derived ───────────────────────────────────────────────────────────────────────────────

    /**
     * Whether a free-text (non-template) reply is permitted right now.
     *
     * <p>True only inside {@code window} of the contact's last inbound message. Enforced on the
     * server in the send endpoint — a UI-only gate means a stale tab sends into a closed window and
     * the failure surfaces as a provider rejection seconds later, recorded as FAILED.
     */
    public boolean isSessionWindowOpen(Duration window) {
        return lastInboundAt != null
                && lastInboundAt.isAfter(LocalDateTime.now().minus(window));
    }

    /** True when the contact wrote last — i.e. this thread is waiting on us. */
    public boolean isAwaitingReply() {
        return status != ConversationStatus.CLOSED && lastDirection == MessageDirection.INBOUND;
    }
}
