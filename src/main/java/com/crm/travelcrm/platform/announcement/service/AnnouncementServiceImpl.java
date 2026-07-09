package com.crm.travelcrm.platform.announcement.service;

import com.crm.travelcrm.auth.enums.Role;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.common.context.PlatformActor;
import com.crm.travelcrm.common.context.PlatformContext;
import com.crm.travelcrm.notification.api.NotifyEvent;
import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;
import com.crm.travelcrm.platform.announcement.dto.AnnouncementResponse;
import com.crm.travelcrm.platform.announcement.dto.SendAnnouncementRequest;
import com.crm.travelcrm.platform.announcement.entity.Announcement;
import com.crm.travelcrm.platform.announcement.enums.AnnouncementAudience;
import com.crm.travelcrm.platform.announcement.enums.AnnouncementRecipientScope;
import com.crm.travelcrm.platform.announcement.repository.AnnouncementRepository;
import com.crm.travelcrm.platform.audit.PlatformAuditRecorder;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.enums.TenantStatus;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AnnouncementServiceImpl implements AnnouncementService {

    private final AnnouncementRepository announcementRepository;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final PlatformAuditRecorder platformAuditRecorder;

    @Override
    @Transactional
    public AnnouncementResponse send(SendAnnouncementRequest request) {
        AnnouncementAudience audience = request.getAudience() != null
                ? request.getAudience() : AnnouncementAudience.ALL;
        AnnouncementRecipientScope scope = request.getRecipientScope() != null
                ? request.getRecipientScope() : AnnouncementRecipientScope.ADMINS;

        int tenantCount = 0;
        int recipientCount = 0;
        for (Tenant tenant : targetTenants(audience)) {
            List<Long> recipients = (scope == AnnouncementRecipientScope.ADMINS)
                    ? userRepository.findActiveUserIdsByRole(tenant.getId(), Role.TENANT_ADMIN)
                    : userRepository.findActiveUserIds(tenant.getId());
            if (recipients.isEmpty()) {
                continue;
            }
            // NotifyEventListener scopes off event.tenantId (not ambient context), so a SuperAdmin
            // with no TenantContext can fan out to any tenant's users. Delivery = in-app + SSE bell.
            eventPublisher.publishEvent(NotifyEvent.builder()
                    .type("PLATFORM_ANNOUNCEMENT")
                    .tenantId(tenant.getId())
                    .recipientUserIds(recipients)
                    .title(request.getTitle())
                    .message(request.getMessage())
                    .channels(Set.of(DeliveryChannel.IN_APP))
                    .build());
            tenantCount++;
            recipientCount += recipients.size();
        }

        Announcement saved = announcementRepository.save(Announcement.builder()
                .title(request.getTitle())
                .message(request.getMessage())
                .audience(audience)
                .recipientScope(scope)
                .tenantCount(tenantCount)
                .recipientCount(recipientCount)
                .sentByEmail(currentActorEmail())
                .build());

        platformAuditRecorder.safeRecord(PlatformAuditAction.ANNOUNCEMENT_SEND, true,
                null, null, "ANNOUNCEMENT", saved.getPublicId(),
                "Announcement '" + request.getTitle() + "' → " + tenantCount + " tenants / "
                        + recipientCount + " users (" + audience + ", " + scope + ")");

        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AnnouncementResponse> history(Pageable pageable) {
        return announcementRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc(pageable)
                .map(this::toResponse);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Live tenants matching the audience (ALL = operational: ACTIVE + TRIAL). */
    private List<Tenant> targetTenants(AnnouncementAudience audience) {
        List<Tenant> live = tenantRepository.findByDeletedAtIsNull();
        return switch (audience) {
            case ACTIVE -> live.stream().filter(t -> t.getStatus() == TenantStatus.ACTIVE).toList();
            case TRIAL  -> live.stream().filter(t -> t.getStatus() == TenantStatus.TRIAL).toList();
            case ALL    -> live.stream()
                    .filter(t -> t.getStatus() == TenantStatus.ACTIVE || t.getStatus() == TenantStatus.TRIAL)
                    .toList();
        };
    }

    private String currentActorEmail() {
        PlatformActor actor = PlatformContext.getActor();
        return actor != null ? actor.email() : "system";
    }

    private AnnouncementResponse toResponse(Announcement a) {
        return new AnnouncementResponse(a.getPublicId(), a.getTitle(), a.getMessage(),
                a.getAudience(), a.getRecipientScope(), a.getTenantCount(), a.getRecipientCount(),
                a.getSentByEmail(), a.getCreatedAt());
    }
}