package com.crm.travelcrm.notificationsetting.repository;

import com.crm.travelcrm.notificationsetting.entity.NotificationSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NotificationSettingRepository extends JpaRepository<NotificationSetting, Long> {
    Optional<NotificationSetting> findByTenantId(Long tenantId);
}