package com.crm.travelcrm.lead.assignment.repository;

import com.crm.travelcrm.lead.assignment.entity.LeadAssignmentPointer;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LeadAssignmentPointerRepository extends JpaRepository<LeadAssignmentPointer, Long> {

    /**
     * Fetch the tenant's cursor row under a {@code SELECT ... FOR UPDATE} pessimistic write lock, so
     * two concurrent lead creations for the same tenant are serialized and can never read-then-advance
     * the cursor to the same value (lost update). MUST be called inside an active transaction (the
     * lead-creation transaction), which holds the lock through commit.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<LeadAssignmentPointer> findByTenantId(Long tenantId);

    /**
     * Non-locking read for the read-only recommendation "peek" (the create form pre-fill), which must
     * never take a write lock or advance the cursor.
     */
    Optional<LeadAssignmentPointer> findFirstByTenantId(Long tenantId);

    /** Existence check used by the provisioner to create the cursor row before the lock is taken. */
    boolean existsByTenantId(Long tenantId);
}