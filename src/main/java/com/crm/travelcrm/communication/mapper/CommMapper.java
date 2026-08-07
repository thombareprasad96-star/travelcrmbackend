package com.crm.travelcrm.communication.mapper;

import com.crm.travelcrm.communication.config.CommunicationProperties;
import com.crm.travelcrm.communication.dto.CommAttachmentDto;
import com.crm.travelcrm.communication.dto.CommConversationDto;
import com.crm.travelcrm.communication.dto.CommMessageDto;
import com.crm.travelcrm.communication.dto.CommSearchHitDto;
import com.crm.travelcrm.communication.entity.CommContactIdentity;
import com.crm.travelcrm.communication.entity.CommConversation;
import com.crm.travelcrm.communication.entity.CommMessage;
import com.crm.travelcrm.communication.enums.CommChannel;
import com.crm.travelcrm.communication.repository.CommAttachmentView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Entity → DTO conversion for the Communication Center.
 *
 * <p><b>Hand-written rather than MapStruct, deliberately.</b> Nearly every field that matters here
 * is either computed ({@code freeTextAllowed} depends on a configured window and the clock;
 * {@code awaitingReply} on the last direction), whitelisted (the internal {@code Long} ids,
 * {@code providerMessageId}, {@code senderUserId} and {@code ownerUserId} must never leave the
 * server), or joined from a sibling row (attachments). A generated mapper would need an
 * {@code @AfterMapping} for all of it and an {@code ignore = true} for each excluded field — at
 * which point the generated half carries no meaning and every future field is opt-OUT of exposure
 * instead of opt-in. This is the same reasoning the traveler portal's hand-written mappers use, and
 * for the same reason: these DTOs cross a trust boundary.
 */
@Component
@RequiredArgsConstructor
public class CommMapper {

    private final CommunicationProperties properties;

    // ── Conversation ─────────────────────────────────────────────────────────────────────────

    public CommConversationDto toDto(CommConversation c, CommContactIdentity contact) {
        return CommConversationDto.builder()
                .publicId(c.getPublicId())
                .kind(c.getKind())
                .channel(c.getChannel())
                .subject(c.getSubject())
                .contactName(c.getContactName())
                .contactValue(c.getContactValue())
                .contactPublicId(contact != null ? contact.getPublicId() : null)
                .status(c.getStatus())
                .priority(c.getPriority())
                .pinned(c.isPinned())
                .snoozedUntil(c.getSnoozedUntil())
                .unreadCount(c.getUnreadCount())
                .messageCount(c.getMessageCount())
                .lastMessageAt(c.getLastMessageAt())
                .lastInboundAt(c.getLastInboundAt())
                .lastDirection(c.getLastDirection())
                .lastMessagePreview(c.getLastMessagePreview())
                .awaitingReply(c.isAwaitingReply())
                .freeTextAllowed(freeTextAllowed(c))
                .assignedUserPublicId(c.getAssignedUserPublicId())
                .assignedUserName(c.getAssignedUserName())
                .leadPublicId(c.getLeadPublicId())
                .customerPublicId(c.getCustomerPublicId())
                .bookingPublicId(c.getBookingPublicId())
                .quotationPublicId(c.getQuotationPublicId())
                .build();
    }

    public List<CommConversationDto> toConversationDtos(List<CommConversation> conversations,
                                                        Map<Long, CommContactIdentity> contactsById) {
        return conversations.stream()
                .map(c -> toDto(c, contactsById.get(c.getContactIdentityId())))
                .toList();
    }

    /**
     * Whether the composer may offer a free-text box right now.
     *
     * <p>Only WhatsApp is ever restricted: Meta permits free-form replies only inside the 24-hour
     * customer service window opened by the contact's last inbound message, and outside it accepts
     * approved templates only. Email, SMS and internal threads have no such rule.
     *
     * <p>This is advisory for the UI. The send endpoint applies the SAME check server-side — a
     * client that ignores this field gets a 422, not a silent provider rejection recorded as FAILED
     * three seconds later.
     */
    private boolean freeTextAllowed(CommConversation c) {
        if (c.getChannel() != CommChannel.WHATSAPP) {
            return true;
        }
        return c.isSessionWindowOpen(properties.getWhatsappSessionWindow());
    }

    // ── Message ──────────────────────────────────────────────────────────────────────────────

    public CommMessageDto toDto(CommMessage m,
                                UUID conversationPublicId,
                                List<CommAttachmentView> attachments,
                                UUID callPublicId) {
        return CommMessageDto.builder()
                .publicId(m.getPublicId())
                .conversationPublicId(conversationPublicId)
                .channel(m.getChannel())
                .direction(m.getDirection())
                .visibility(m.getVisibility())
                .bodyText(m.getBodyText())
                .bodyHtml(m.getBodyHtml())
                .status(m.getStatus())
                .statusAt(m.getStatusAt())
                .errorMessage(m.getErrorMessage())
                .senderName(m.getSenderName())
                .occurredAt(m.getOccurredAt())
                .attachmentCount(m.getAttachmentCount())
                .attachments(attachments == null ? List.of() : attachments.stream().map(this::toDto).toList())
                .note(m.isNote())
                .callPublicId(callPublicId)
                .build();
    }

    public CommAttachmentDto toDto(CommAttachmentView a) {
        return CommAttachmentDto.builder()
                .publicId(a.getPublicId())
                .fileName(a.getFileName())
                .contentType(a.getContentType())
                .sizeBytes(a.getSizeBytes())
                .build();
    }

    // ── Search ───────────────────────────────────────────────────────────────────────────────

    public CommSearchHitDto toSearchHit(CommMessage m, CommConversation c) {
        return CommSearchHitDto.builder()
                .messagePublicId(m.getPublicId())
                .conversationPublicId(c != null ? c.getPublicId() : null)
                .channel(m.getChannel())
                .direction(m.getDirection())
                .contactName(c != null ? c.getContactName() : null)
                .contactValue(c != null ? c.getContactValue() : null)
                .snippet(m.preview(properties.getPreviewLength()))
                .occurredAt(m.getOccurredAt())
                .build();
    }
}
