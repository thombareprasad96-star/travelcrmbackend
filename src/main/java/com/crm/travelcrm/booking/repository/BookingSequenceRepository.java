package com.crm.travelcrm.booking.repository;

import com.crm.travelcrm.booking.entity.BookingSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BookingSequenceRepository extends JpaRepository<BookingSequence, Long> {

    /**
     * Fetch the tenant's counter row under a {@code SELECT ... FOR UPDATE} pessimistic
     * write lock, so two concurrent conversions for the same tenant are serialized and can
     * never hand out the same reference number. Must be called inside an active transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<BookingSequence> findByTenantId(Long tenantId);

    /**
     * Unlocked existence check for {@code BookingSequenceProvisioner}. Deliberately NOT the locked
     * finder above: the provisioner runs in its own short {@code REQUIRES_NEW} transaction, and
     * taking a row lock there would hold it on a second connection while the caller's transaction
     * waits — the lock belongs to the caller's unit, not this probe.
     */
    boolean existsByTenantId(Long tenantId);
}