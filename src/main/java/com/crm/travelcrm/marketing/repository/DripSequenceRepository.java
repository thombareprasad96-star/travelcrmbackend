package com.crm.travelcrm.marketing.repository;

import com.crm.travelcrm.marketing.entity.DripSequence;
import com.crm.travelcrm.marketing.enums.DripStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DripSequenceRepository extends JpaRepository<DripSequence, Long>, JpaSpecificationExecutor<DripSequence> {

    Optional<DripSequence> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    Optional<DripSequence> findByIdAndTenantIdAndDeletedAtIsNull(Long id, Long tenantId);

    // Scheduler: ACTIVE sequences (for enrollment sync + due-step processing).
    List<DripSequence> findByTenantIdAndStatusAndDeletedAtIsNull(Long tenantId, DripStatus status);
}