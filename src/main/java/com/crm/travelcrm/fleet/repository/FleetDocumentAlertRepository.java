package com.crm.travelcrm.fleet.repository;

import com.crm.travelcrm.fleet.entity.FleetDocumentAlert;
import com.crm.travelcrm.fleet.enums.FleetDocumentCategory;
import com.crm.travelcrm.fleet.enums.FleetRefType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface FleetDocumentAlertRepository extends JpaRepository<FleetDocumentAlert, Long> {

    /**
     * Smallest threshold already fired for this (ref, doc, expiryDate) — the scan only fires
     * thresholds strictly below it, so each threshold alerts at most once per expiry date.
     */
    Optional<FleetDocumentAlert> findFirstByTenantIdAndRefTypeAndRefIdAndDocTypeAndExpiryDateOrderByThresholdDaysAsc(
            Long tenantId, FleetRefType refType, Long refId, FleetDocumentCategory docType, LocalDate expiryDate);

    Page<FleetDocumentAlert> findByTenantIdAndDeletedAtIsNull(Long tenantId, Pageable pageable);
}