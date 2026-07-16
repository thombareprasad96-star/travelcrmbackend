package com.crm.travelcrm.marketing.repository;

import com.crm.travelcrm.marketing.entity.DripEnrollment;
import com.crm.travelcrm.marketing.enums.EnrollmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DripEnrollmentRepository extends JpaRepository<DripEnrollment, Long> {

    Page<DripEnrollment> findBySequenceIdAndTenantId(Long sequenceId, Long tenantId, Pageable pageable);

    Page<DripEnrollment> findBySequenceIdAndTenantIdAndStatus(Long sequenceId, Long tenantId, EnrollmentStatus status, Pageable pageable);

    // Idempotent enroll: a customer enrolled once (any status) is never re-enrolled.
    boolean existsBySequenceIdAndTenantIdAndCustomerId(Long sequenceId, Long tenantId, Long customerId);

    // Runner: due active enrollments across the tenant (bounded via Pageable).
    List<DripEnrollment> findByTenantIdAndStatusAndNextRunAtLessThanEqualOrderByNextRunAtAsc(
            Long tenantId, EnrollmentStatus status, LocalDateTime now, Pageable pageable);

    long countBySequenceIdAndTenantId(Long sequenceId, Long tenantId);

    long countBySequenceIdAndTenantIdAndStatus(Long sequenceId, Long tenantId, EnrollmentStatus status);
}