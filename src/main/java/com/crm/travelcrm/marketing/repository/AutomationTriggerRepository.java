package com.crm.travelcrm.marketing.repository;

import com.crm.travelcrm.marketing.entity.AutomationTrigger;
import com.crm.travelcrm.marketing.enums.AutomationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AutomationTriggerRepository extends JpaRepository<AutomationTrigger, Long> {

    Optional<AutomationTrigger> findByTenantIdAndTriggerType(Long tenantId, AutomationType triggerType);

    List<AutomationTrigger> findByTenantId(Long tenantId);

    List<AutomationTrigger> findByTenantIdAndEnabledTrue(Long tenantId);
}