package com.crm.travelcrm.quotation.analytics;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.common.staffip.StaffIpService;
import com.crm.travelcrm.quotation.entity.Quotation;
import com.crm.travelcrm.quotation.repository.QuotationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class WeblinkAnalyticsService {

    private final QuotationWeblinkViewRepository viewRepository;
    private final QuotationRepository quotationRepository;
    private final StaffIpService staffIpService;

    /**
     * Record one public weblink view — async + best-effort. Resolves the quotation to get its
     * tenantId (TenantContext is empty on the public path), classifies the IP HOME/EXTERNAL, and
     * upserts the per-IP aggregate. Never propagates an error to the (public) caller.
     */
    @Async
    @Transactional
    public void recordView(UUID quotationPublicId, String ipAddress, String userAgent) {
        try {
            if (quotationPublicId == null || ipAddress == null || ipAddress.isBlank()) return;

            Quotation q = quotationRepository.findByPublicIdAndDeletedAtIsNull(quotationPublicId).orElse(null);
            if (q == null) return;
            Long tenantId = q.getTenantId();

            String ua = (userAgent != null && userAgent.length() > 400)
                    ? userAgent.substring(0, 400) : userAgent;
            Instant now = Instant.now();

            Optional<QuotationWeblinkView> existing = viewRepository
                    .findByTenantIdAndQuotationPublicIdAndIpAddress(tenantId, quotationPublicId, ipAddress);

            if (existing.isPresent()) {
                QuotationWeblinkView row = existing.get();
                row.setViewCount(row.getViewCount() + 1);
                row.setLastViewedAt(now);
                if (ua != null) row.setUserAgent(ua);
                viewRepository.save(row);
            } else {
                ViewerType type = staffIpService.isHomeIp(tenantId, ipAddress)
                        ? ViewerType.HOME : ViewerType.EXTERNAL;
                viewRepository.save(QuotationWeblinkView.builder()
                        .quotationId(q.getId())
                        .quotationPublicId(quotationPublicId)
                        .tenantId(tenantId)
                        .ipAddress(ipAddress)
                        .viewerType(type)
                        .viewCount(1)
                        .firstViewedAt(now)
                        .lastViewedAt(now)
                        .userAgent(ua)
                        .build());
            }
        } catch (Exception e) {
            // Best-effort: a view-logging failure must never affect the public response.
            log.warn("Failed to record weblink view for quotation {}: {}", quotationPublicId, e.getMessage());
        }
    }

    /** Authed analytics read — explicitly tenant-scoped (this entity isn't Hibernate-filtered). */
    @Transactional(readOnly = true)
    public WeblinkAnalyticsDto getAnalytics(UUID quotationPublicId) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("TenantContext is empty. JwtAuthFilter must run for this endpoint.");
        }

        // Existence + ownership check (404 if not this tenant's quotation).
        quotationRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(quotationPublicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Quotation not found: " + quotationPublicId));

        List<QuotationWeblinkView> rows = viewRepository
                .findAllByQuotationPublicIdAndTenantIdOrderByViewCountDescLastViewedAtDesc(quotationPublicId, tenantId);

        long total    = rows.stream().mapToLong(QuotationWeblinkView::getViewCount).sum();
        long external = rows.stream().filter(r -> r.getViewerType() == ViewerType.EXTERNAL)
                .mapToLong(QuotationWeblinkView::getViewCount).sum();
        long home     = rows.stream().filter(r -> r.getViewerType() == ViewerType.HOME)
                .mapToLong(QuotationWeblinkView::getViewCount).sum();

        return WeblinkAnalyticsDto.builder()
                .summary(WeblinkAnalyticsDto.Summary.builder()
                        .totalViews(total)
                        .externalViews(external)
                        .homeIpViews(home)
                        .uniqueIps(rows.size())
                        .build())
                .rows(rows.stream()
                        .map(r -> WeblinkAnalyticsDto.Row.builder()
                                .ipAddress(r.getIpAddress())
                                .type(r.getViewerType())
                                .views(r.getViewCount())
                                .firstViewedAt(r.getFirstViewedAt())
                                .lastViewedAt(r.getLastViewedAt())
                                .build())
                        .toList())
                .build();
    }
}