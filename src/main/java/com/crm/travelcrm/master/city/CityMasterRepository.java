package com.crm.travelcrm.master.city;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CityMasterRepository extends JpaRepository<CityMasterEntity, Long> {

    @Query("SELECT c FROM CityMasterEntity c WHERE c.tenantId IS NULL OR c.tenantId = :tenantId")
    Page<CityMasterEntity> findAllVisibleTo(@Param("tenantId") Long tenantId, Pageable pageable);
}