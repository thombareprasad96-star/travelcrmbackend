package com.crm.travelcrm.fleet.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.fleet.entity.FleetComplianceDocument;
import com.crm.travelcrm.fleet.entity.FleetDocumentAlert;
import com.crm.travelcrm.fleet.entity.FleetDriver;
import com.crm.travelcrm.fleet.entity.FleetVehicle;
import com.crm.travelcrm.fleet.enums.FleetDocumentCategory;
import com.crm.travelcrm.fleet.enums.FleetDriverStatus;
import com.crm.travelcrm.fleet.enums.FleetRefType;
import com.crm.travelcrm.fleet.repository.FleetComplianceDocumentRepository;
import com.crm.travelcrm.fleet.repository.FleetDocumentAlertRepository;
import com.crm.travelcrm.fleet.repository.FleetDriverRepository;
import com.crm.travelcrm.fleet.repository.FleetVehicleRepository;
import com.crm.travelcrm.notification.api.NotifyEvent;
import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Per-tenant document-expiry scan (vehicle insurance/RC/permit/PUC + active drivers'
 * licences). Same threshold semantics as the portal's DocumentExpiryReminderService: only
 * the most urgent crossed-and-unfired threshold fires, so a long gap never floods a backlog.
 * The saved {@link FleetDocumentAlert} row is both the idempotency marker and the alert
 * history; delivery is a {@link NotifyEvent} to the tenant's admins/managers.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FleetExpiryScanService {

    private final FleetVehicleRepository vehicleRepository;
    private final FleetDriverRepository driverRepository;
    private final FleetComplianceDocumentRepository documentRepository;
    private final FleetDocumentAlertRepository alertRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public int scanCurrentTenant(Long tenantId, List<Integer> thresholdsDesc, LocalDate today) {
        int maxThreshold = thresholdsDesc.get(0);
        LocalDate limit = today.plusDays(maxThreshold);

        List<Long> recipientIds = userRepository
                .findByTenantIdAndRoleInAndIsActiveTrue(tenantId, List.of("TENANT_ADMIN", "MANAGER"))
                .stream().map(User::getId).toList();

        // ONE sweep, over fleet_compliance_documents.
        //
        // This used to walk the four date columns on the vehicle plus the licence date on the driver.
        // Those columns still exist, but every one of them was migrated into a document row, so
        // scanning documents covers everything the old loop covered AND the fourteen categories it
        // could not see at all. Running both loops would double-alert on insurance, RC, permit and
        // PUC; running only the old one means a Green Card, a fitness certificate or a PSV badge
        // lapses in silence — which is the failure this table exists to prevent.
        int fired = 0;
        for (FleetComplianceDocument doc : documentRepository.findExpiringBy(tenantId, limit)) {
            boolean isVehicle = doc.getVehicle() != null;
            fired += process(tenantId, thresholdsDesc, today, limit, recipientIds,
                    isVehicle ? FleetRefType.VEHICLE : FleetRefType.DRIVER,
                    isVehicle ? doc.getVehicle().getId() : doc.getDriver().getId(),
                    isVehicle ? doc.getVehicle().getPublicId() : doc.getDriver().getPublicId(),
                    isVehicle ? "Vehicle " + doc.getVehicle().getVehicleNumber()
                              : "Driver " + doc.getDriver().getName(),
                    doc.getCategory(), doc.getValidUntil(), doc.getId());
        }
        return fired;
    }

    private int process(Long tenantId, List<Integer> thresholdsDesc, LocalDate today, LocalDate limit,
                        List<Long> recipientIds, FleetRefType refType, Long refId, UUID refPublicId,
                        String refLabel, FleetDocumentCategory docType, LocalDate expiryDate,
                        Long documentId) {
        if (expiryDate == null || expiryDate.isAfter(limit)) return 0;

        long daysLeft = ChronoUnit.DAYS.between(today, expiryDate);
        Integer lastFired = alertRepository
                .findFirstByTenantIdAndRefTypeAndRefIdAndDocTypeAndExpiryDateOrderByThresholdDaysAsc(
                        tenantId, refType, refId, docType, expiryDate)
                .map(FleetDocumentAlert::getThresholdDays)
                .orElse(null);
        Integer threshold = pickThreshold(thresholdsDesc, daysLeft, lastFired);
        if (threshold == null) return 0;

        // The alert row is saved even when nobody can be notified — it is the idempotency
        // marker (prevents daily re-fires) and the /alerts history.
        alertRepository.save(FleetDocumentAlert.builder()
                .tenantId(tenantId)
                .refType(refType).refId(refId).refPublicId(refPublicId).refLabel(refLabel)
                .docType(docType).documentId(documentId).expiryDate(expiryDate)
                .daysLeft(daysLeft).thresholdDays(threshold)
                .build());

        if (!recipientIds.isEmpty()) {
            String when = daysLeft < 0
                    ? "expired " + Math.abs(daysLeft) + " day(s) ago (on " + expiryDate + ")"
                    : daysLeft == 0
                            ? "expires TODAY (" + expiryDate + ")"
                            : "expires on " + expiryDate + " (in " + daysLeft + " day(s))";
            eventPublisher.publishEvent(NotifyEvent.builder()
                    .type("FLEET_DOCUMENT_EXPIRY")
                    .tenantId(tenantId)
                    .recipientUserIds(recipientIds)
                    .title(refLabel + " — " + docType.label() + (daysLeft < 0 ? " expired" : " expiring"))
                    .message(refLabel + ": " + docType.label() + " " + when + ".")
                    .referenceType("FLEET_" + refType.name())
                    .referencePublicId(refPublicId)
                    .channels(Set.of(DeliveryChannel.IN_APP))
                    .build());
        }

        log.info("[FLEET-DOC-EXPIRY] {} {} {} | daysLeft={} | threshold={} | tenant={}",
                refType, refLabel, docType, daysLeft, threshold, tenantId);
        return 1;
    }

    /**
     * The smallest (most urgent) threshold that expiry has crossed and that hasn't fired yet —
     * identical semantics to the portal's DocumentExpiryReminderService#pickThreshold.
     */
    private Integer pickThreshold(List<Integer> thresholdsDesc, long daysUntil, Integer lastFired) {
        Integer chosen = null;
        for (int t : thresholdsDesc) {
            if (daysUntil <= t && (lastFired == null || t < lastFired)) {
                chosen = t;
            }
        }
        return chosen;
    }
}