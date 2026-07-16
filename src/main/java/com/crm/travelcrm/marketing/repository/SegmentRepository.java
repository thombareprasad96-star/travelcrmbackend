package com.crm.travelcrm.marketing.repository;

import com.crm.travelcrm.marketing.entity.Segment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SegmentRepository extends JpaRepository<Segment, Long>, JpaSpecificationExecutor<Segment> {

    Optional<Segment> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    Optional<Segment> findByIdAndTenantIdAndDeletedAtIsNull(Long id, Long tenantId);
}