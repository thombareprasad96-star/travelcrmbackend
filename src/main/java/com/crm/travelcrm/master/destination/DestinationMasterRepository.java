package com.crm.travelcrm.master.destination;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DestinationMasterRepository extends JpaRepository<DestinationMasterEntity, Long> {

    // Global destinations (tenantId IS NULL) plus the caller's own — never other tenants'.
    @Query("SELECT d " +
            "FROM DestinationMasterEntity d " +
            "WHERE d.tenantId IS NULL OR d.tenantId = :tenantId")
    Page<DestinationMasterEntity> findAllVisibleTo(@Param("tenantId") Long tenantId, Pageable pageable);

    @Query("""
        SELECT d
        FROM DestinationMasterEntity d
        WHERE d.tenantId IS NULL OR d.tenantId = :tenantId
        ORDER BY d.name ASC
    """)
    List<DestinationMasterEntity> findAllVisibleTo(
            @Param("tenantId") Long tenantId);
}