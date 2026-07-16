package com.crm.travelcrm.marketing.service;

import com.crm.travelcrm.auth.api.CurrentUserProvider;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.marketing.dto.*;
import com.crm.travelcrm.marketing.entity.DripEnrollment;
import com.crm.travelcrm.marketing.entity.DripSequence;
import com.crm.travelcrm.marketing.entity.DripStep;
import com.crm.travelcrm.marketing.entity.Segment;
import com.crm.travelcrm.marketing.enums.DripAudienceType;
import com.crm.travelcrm.marketing.enums.DripStatus;
import com.crm.travelcrm.marketing.enums.EnrollmentStatus;
import com.crm.travelcrm.marketing.repository.DripEnrollmentRepository;
import com.crm.travelcrm.marketing.repository.DripSequenceRepository;
import com.crm.travelcrm.marketing.repository.DripStepRepository;
import com.crm.travelcrm.marketing.repository.SegmentRepository;
import com.crm.travelcrm.permission.service.SubAgentScope;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DripServiceImpl implements DripService {

    private static final java.util.Set<DripStatus> EDITABLE = java.util.Set.of(DripStatus.DRAFT, DripStatus.PAUSED);

    private final DripSequenceRepository sequenceRepository;
    private final DripStepRepository stepRepository;
    private final DripEnrollmentRepository enrollmentRepository;
    private final SegmentRepository segmentRepository;
    private final DripEnrollmentSync enrollmentSync;
    private final SubAgentScope subAgentScope;
    private final CurrentUserProvider currentUser;

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<DripSequenceResponse> list(int page, int size, String sortBy, String sortDir, String search, DripStatus status) {
        Long tenantId = requireTenantId();
        Sort sort = "asc".equalsIgnoreCase(sortDir) ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        Page<DripSequence> p = sequenceRepository.findAll(listSpec(tenantId, search, status), PageRequest.of(page, size, sort));
        List<DripSequenceResponse> content = p.getContent().stream().map(s -> toResponse(s, tenantId)).toList();
        return PagedApiResponse.of("Sequences fetched successfully", content, PaginationMeta.from(p, sortBy, sortDir));
    }

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<DripSequenceResponse> get(UUID publicId) {
        Long tenantId = requireTenantId();
        return ApiResponse.success("Sequence fetched successfully", toResponse(requireVisible(publicId), tenantId));
    }

    @Override
    @Transactional
    public ApiResponse<DripSequenceResponse> create(CreateDripSequenceRequest request) {
        Long tenantId = requireTenantId();
        Segment seg = resolveSegment(request.audienceType(), request.segmentPublicId(), tenantId);
        DripSequence seq = DripSequence.builder()
                .name(request.name().trim())
                .description(trimOrNull(request.description()))
                .status(DripStatus.DRAFT)
                .audienceType(request.audienceType())
                .segmentId(seg != null ? seg.getId() : null)
                .segmentPublicId(seg != null ? seg.getPublicId() : null)
                .segmentNameSnapshot(seg != null ? seg.getName() : null)
                .build();
        DripSequence saved = sequenceRepository.save(seq);
        saveSteps(saved.getId(), request.steps());
        return ApiResponse.success("Sequence created successfully", toResponse(saved, tenantId), 201);
    }

    @Override
    @Transactional
    public ApiResponse<DripSequenceResponse> update(UUID publicId, UpdateDripSequenceRequest request) {
        Long tenantId = requireTenantId();
        DripSequence seq = requireVisible(publicId);
        if (!EDITABLE.contains(seq.getStatus())) {
            throw new BusinessException("An active sequence must be paused before editing.", HttpStatus.CONFLICT);
        }
        Segment seg = resolveSegment(request.audienceType(), request.segmentPublicId(), tenantId);
        seq.setName(request.name().trim());
        seq.setDescription(trimOrNull(request.description()));
        seq.setAudienceType(request.audienceType());
        seq.setSegmentId(seg != null ? seg.getId() : null);
        seq.setSegmentPublicId(seg != null ? seg.getPublicId() : null);
        seq.setSegmentNameSnapshot(seg != null ? seg.getName() : null);
        sequenceRepository.save(seq);
        stepRepository.deleteBySequenceIdAndTenantId(seq.getId(), tenantId);
        saveSteps(seq.getId(), request.steps());
        return ApiResponse.success("Sequence updated successfully", toResponse(seq, tenantId));
    }

    @Override
    @Transactional
    public ApiResponse<Void> delete(UUID publicId) {
        DripSequence seq = requireVisible(publicId);
        seq.softDelete(currentUser.currentUsernameOrSystem());
        sequenceRepository.save(seq);
        return ApiResponse.success("Sequence deleted successfully");
    }

    @Override
    @Transactional
    public ApiResponse<DripSequenceResponse> activate(UUID publicId) {
        Long tenantId = requireTenantId();
        DripSequence seq = requireVisible(publicId);
        if (seq.getStatus() == DripStatus.ACTIVE) {
            throw new BusinessException("Sequence is already active.", HttpStatus.CONFLICT);
        }
        if (stepRepository.findBySequenceIdAndTenantIdOrderByStepOrderAsc(seq.getId(), tenantId).isEmpty()) {
            throw new BusinessException("Add at least one step before activating.");
        }
        seq.setStatus(DripStatus.ACTIVE);
        sequenceRepository.save(seq);
        enrollmentSync.enroll(seq, tenantId);   // enroll current segment members (idempotent)
        return ApiResponse.success("Sequence activated", toResponse(seq, tenantId));
    }

    @Override
    @Transactional
    public ApiResponse<DripSequenceResponse> pause(UUID publicId) {
        Long tenantId = requireTenantId();
        DripSequence seq = requireVisible(publicId);
        if (seq.getStatus() != DripStatus.ACTIVE) {
            throw new BusinessException("Only an active sequence can be paused.", HttpStatus.CONFLICT);
        }
        seq.setStatus(DripStatus.PAUSED);
        sequenceRepository.save(seq);
        return ApiResponse.success("Sequence paused", toResponse(seq, tenantId));
    }

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<DripEnrollmentResponse> enrollments(UUID publicId, int page, int size, EnrollmentStatus status) {
        Long tenantId = requireTenantId();
        DripSequence seq = requireVisible(publicId);
        Pageable pageable = PageRequest.of(page, size, Sort.by("enrolledAt").descending());
        Page<DripEnrollment> p = (status == null)
                ? enrollmentRepository.findBySequenceIdAndTenantId(seq.getId(), tenantId, pageable)
                : enrollmentRepository.findBySequenceIdAndTenantIdAndStatus(seq.getId(), tenantId, status, pageable);
        List<DripEnrollmentResponse> content = p.getContent().stream()
                .map(e -> new DripEnrollmentResponse(e.getPublicId(), e.getCustomerNameSnapshot(), e.getStatus(),
                        e.getCurrentStepOrder(), e.getNextRunAt(), e.getEnrolledAt(), e.getCompletedAt()))
                .toList();
        return PagedApiResponse.of("Enrollments fetched successfully", content, PaginationMeta.from(p));
    }

    // ── internals ────────────────────────────────────────────────────────────────

    private void saveSteps(Long sequenceId, List<DripStepDTO> steps) {
        int order = 1;
        for (DripStepDTO dto : steps) {
            stepRepository.save(DripStep.builder()
                    .sequenceId(sequenceId)
                    .stepOrder(order++)
                    .name(trimOrNull(dto.name()))
                    .delayDays(Math.max(0, dto.delayDays()))
                    .channel(dto.channel())
                    .subject(trimOrNull(dto.subject()))
                    .body(dto.body().trim())
                    .templateName(trimOrNull(dto.templateName()))
                    .build());
        }
    }

    private DripSequenceResponse toResponse(DripSequence seq, Long tenantId) {
        List<DripStepDTO> steps = stepRepository.findBySequenceIdAndTenantIdOrderByStepOrderAsc(seq.getId(), tenantId).stream()
                .map(s -> new DripStepDTO(s.getPublicId(), s.getStepOrder(), s.getName(), s.getDelayDays(),
                        s.getChannel(), s.getSubject(), s.getBody(), s.getTemplateName()))
                .toList();
        long enrolled = enrollmentRepository.countBySequenceIdAndTenantId(seq.getId(), tenantId);
        long active = enrollmentRepository.countBySequenceIdAndTenantIdAndStatus(seq.getId(), tenantId, EnrollmentStatus.ACTIVE);
        long completed = enrollmentRepository.countBySequenceIdAndTenantIdAndStatus(seq.getId(), tenantId, EnrollmentStatus.COMPLETED);
        return new DripSequenceResponse(
                seq.getPublicId(), seq.getName(), seq.getDescription(), seq.getStatus(), seq.getAudienceType(),
                seq.getSegmentPublicId(), seq.getSegmentNameSnapshot(), steps, enrolled, active, completed,
                seq.getCreatedBy(), seq.getCreatedAt(), seq.getUpdatedAt());
    }

    private Segment resolveSegment(DripAudienceType audienceType, UUID segmentPublicId, Long tenantId) {
        if (audienceType != DripAudienceType.SEGMENT) return null;
        if (segmentPublicId == null) {
            throw new BusinessException("Choose a segment to enroll from.");
        }
        Segment seg = segmentRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(segmentPublicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Segment not found: " + segmentPublicId));
        subAgentScope.assertVisible(seg, segmentPublicId);
        return seg;
    }

    private Specification<DripSequence> listSpec(Long tenantId, String search, DripStatus status) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("tenantId"), tenantId));
            ps.add(cb.isNull(root.get("deletedAt")));
            Long owner = subAgentScope.ownerFilter();
            if (owner != null) ps.add(cb.equal(root.get("ownerUserId"), owner));
            if (StringUtils.hasText(search)) ps.add(cb.like(cb.lower(root.get("name")), "%" + search.toLowerCase() + "%"));
            if (status != null) ps.add(cb.equal(root.get("status"), status));
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private DripSequence requireVisible(UUID publicId) {
        DripSequence seq = sequenceRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, requireTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Sequence not found: " + publicId));
        subAgentScope.assertVisible(seq, publicId);
        return seq;
    }

    private Long requireTenantId() {
        Long t = TenantContext.getTenantId();
        if (t == null) throw new IllegalStateException("TenantContext is empty — no tenant in scope.");
        return t;
    }

    private String trimOrNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}