package com.crm.travelcrm.booking.cancellation.repository;

import com.crm.travelcrm.booking.cancellation.entity.DocumentSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DocumentSequenceRepository extends JpaRepository<DocumentSequence, Long> {

    /** Locked read (SELECT … FOR UPDATE) for the atomic increment. Row must already exist. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<DocumentSequence> findByTenantIdAndSeriesCode(Long tenantId, String seriesCode);

    boolean existsByTenantIdAndSeriesCode(Long tenantId, String seriesCode);
}
