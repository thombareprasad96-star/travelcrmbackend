package com.crm.travelcrm.marketing.service;

import com.crm.travelcrm.customer.entity.Customer;
import com.crm.travelcrm.marketing.entity.DripEnrollment;
import com.crm.travelcrm.marketing.entity.DripSequence;
import com.crm.travelcrm.marketing.entity.DripStep;
import com.crm.travelcrm.marketing.entity.Segment;
import com.crm.travelcrm.marketing.enums.DripAudienceType;
import com.crm.travelcrm.marketing.enums.EnrollmentStatus;
import com.crm.travelcrm.marketing.repository.DripEnrollmentRepository;
import com.crm.travelcrm.marketing.repository.DripStepRepository;
import com.crm.travelcrm.marketing.repository.SegmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Enrolls a segment's current members into a sequence, skipping anyone already enrolled (any
 * status) — idempotent, so it can be called on activate AND repeatedly by the runner to pick up
 * newly-matching customers. Runs in the caller's transaction (plain component, no {@code @Transactional}).
 */
@Component
@RequiredArgsConstructor
public class DripEnrollmentSync {

    private final DripEnrollmentRepository enrollmentRepository;
    private final DripStepRepository stepRepository;
    private final SegmentRepository segmentRepository;
    private final SegmentAudienceResolver audienceResolver;

    /** @return number of customers newly enrolled. */
    public int enroll(DripSequence seq, Long tenantId) {
        if (seq.getAudienceType() != DripAudienceType.SEGMENT || seq.getSegmentId() == null) return 0;

        Segment segment = segmentRepository.findByIdAndTenantIdAndDeletedAtIsNull(seq.getSegmentId(), tenantId).orElse(null);
        if (segment == null) return 0;

        List<DripStep> steps = stepRepository.findBySequenceIdAndTenantIdOrderByStepOrderAsc(seq.getId(), tenantId);
        if (steps.isEmpty()) return 0;
        DripStep first = steps.get(0);

        List<Customer> members = audienceResolver.list(audienceResolver.forSegment(tenantId, segment));
        LocalDateTime now = LocalDateTime.now();
        int enrolled = 0;
        for (Customer c : members) {
            if (enrollmentRepository.existsBySequenceIdAndTenantIdAndCustomerId(seq.getId(), tenantId, c.getId())) continue;
            enrollmentRepository.save(DripEnrollment.builder()
                    .sequenceId(seq.getId())
                    .customerId(c.getId())
                    .customerNameSnapshot(c.getName())
                    .status(EnrollmentStatus.ACTIVE)
                    .currentStepOrder(first.getStepOrder())
                    .nextRunAt(now.plusDays(first.getDelayDays()))
                    .enrolledAt(now)
                    .build());
            enrolled++;
        }
        return enrolled;
    }
}