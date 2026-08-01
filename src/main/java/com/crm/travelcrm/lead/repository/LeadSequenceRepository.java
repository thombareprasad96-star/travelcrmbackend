package com.crm.travelcrm.lead.repository;

import com.crm.travelcrm.lead.entity.LeadSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LeadSequenceRepository extends JpaRepository<LeadSequence, Long> {

    /**
     * Fetch the tenant's counter row under a {@code SELECT ... FOR UPDATE} pessimistic
     * write lock, so two concurrent lead creations for the same tenant are serialized and can
     * never hand out the same reference number. Must be called inside an active transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<LeadSequence> findByTenantId(Long tenantId);
}
