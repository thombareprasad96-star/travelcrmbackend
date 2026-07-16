package com.crm.travelcrm.marketing.service;

import com.crm.travelcrm.customer.entity.Customer;
import com.crm.travelcrm.customer.repository.CustomerRepository;
import com.crm.travelcrm.marketing.entity.DripEnrollment;
import com.crm.travelcrm.marketing.entity.DripSequence;
import com.crm.travelcrm.marketing.entity.DripStep;
import com.crm.travelcrm.marketing.enums.DripAudienceType;
import com.crm.travelcrm.marketing.enums.DripStatus;
import com.crm.travelcrm.marketing.enums.EnrollmentStatus;
import com.crm.travelcrm.marketing.repository.DripEnrollmentRepository;
import com.crm.travelcrm.marketing.repository.DripSequenceRepository;
import com.crm.travelcrm.marketing.repository.DripStepRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Executes drip sequences: enrolls new segment members and advances due enrollments a step at a
 * time. Exposes small transactional units the {@code DripRunnerScheduler} orchestrates cross-bean.
 * Only ACTIVE (non-deleted) sequences fire — enrollments of a paused sequence wait and resume.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DripRunnerService {

    private final DripSequenceRepository sequenceRepository;
    private final DripStepRepository stepRepository;
    private final DripEnrollmentRepository enrollmentRepository;
    private final CustomerRepository customerRepository;
    private final MessageDispatcher dispatcher;
    private final DripEnrollmentSync enrollmentSync;

    @Value("${app.marketing.batch-size:300}")
    private int batchSize;

    @Transactional(readOnly = true)
    public List<Long> activeSegmentSequenceIds(Long tenantId) {
        return sequenceRepository.findByTenantIdAndStatusAndDeletedAtIsNull(tenantId, DripStatus.ACTIVE).stream()
                .filter(s -> s.getAudienceType() == DripAudienceType.SEGMENT)
                .map(DripSequence::getId)
                .toList();
    }

    @Transactional
    public void enrollForSequence(Long sequenceId, Long tenantId) {
        DripSequence seq = sequenceRepository.findByIdAndTenantIdAndDeletedAtIsNull(sequenceId, tenantId).orElse(null);
        if (seq == null || seq.getStatus() != DripStatus.ACTIVE) return;
        enrollmentSync.enroll(seq, tenantId);
    }

    @Transactional
    public int runDueEnrollments(Long tenantId) {
        List<DripEnrollment> due = enrollmentRepository.findByTenantIdAndStatusAndNextRunAtLessThanEqualOrderByNextRunAtAsc(
                tenantId, EnrollmentStatus.ACTIVE, LocalDateTime.now(), PageRequest.of(0, batchSize));
        if (due.isEmpty()) return 0;

        Set<Long> activeSeqIds = sequenceRepository.findByTenantIdAndStatusAndDeletedAtIsNull(tenantId, DripStatus.ACTIVE)
                .stream().map(DripSequence::getId).collect(Collectors.toSet());

        int processed = 0;
        for (DripEnrollment e : due) {
            if (!activeSeqIds.contains(e.getSequenceId())) continue;   // paused/deleted sequence — resume later
            try {
                processEnrollment(e, tenantId);
                processed++;
            } catch (Exception ex) {
                log.error("Drip enrollment {} failed: {}", e.getId(), ex.getMessage(), ex);
            }
        }
        return processed;
    }

    private void processEnrollment(DripEnrollment e, Long tenantId) {
        List<DripStep> steps = stepRepository.findBySequenceIdAndTenantIdOrderByStepOrderAsc(e.getSequenceId(), tenantId);
        DripStep current = steps.stream().filter(s -> s.getStepOrder() == e.getCurrentStepOrder()).findFirst().orElse(null);
        if (current == null) {
            complete(e);
            return;
        }
        Customer cust = customerRepository.findByIdAndTenantIdAndDeletedAtIsNull(e.getCustomerId(), tenantId).orElse(null);
        if (cust != null) {
            dispatcher.deliver(tenantId, current.getChannel(), cust, current.getSubject(), current.getBody(), current.getTemplateName());
        }
        Optional<DripStep> next = steps.stream()
                .filter(s -> s.getStepOrder() > e.getCurrentStepOrder())
                .min(Comparator.comparingInt(DripStep::getStepOrder));
        if (next.isPresent()) {
            e.setCurrentStepOrder(next.get().getStepOrder());
            e.setNextRunAt(LocalDateTime.now().plusDays(next.get().getDelayDays()));
            enrollmentRepository.save(e);
        } else {
            complete(e);
        }
    }

    private void complete(DripEnrollment e) {
        e.setStatus(EnrollmentStatus.COMPLETED);
        e.setCompletedAt(LocalDateTime.now());
        e.setNextRunAt(null);
        enrollmentRepository.save(e);
    }
}