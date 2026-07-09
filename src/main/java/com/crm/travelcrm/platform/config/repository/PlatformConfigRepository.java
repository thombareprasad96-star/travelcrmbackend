package com.crm.travelcrm.platform.config.repository;

import com.crm.travelcrm.platform.config.entity.PlatformConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlatformConfigRepository extends JpaRepository<PlatformConfig, Long> {

    Optional<PlatformConfig> findByKey(String key);

    List<PlatformConfig> findAllByDeletedAtIsNullOrderByKeyAsc();
}