package com.crm.travelcrm.platform.usage.alert;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UsageAlertMarkerRepository extends JpaRepository<UsageAlertMarker, Long> {

    boolean existsByTenantIdAndMetricAndPeriodAndLevel(
            Long tenantId, String metric, String period, String level);
}