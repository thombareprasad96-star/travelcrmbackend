package com.crm.travelcrm.marketing.service;

import com.crm.travelcrm.auth.api.CurrentUserProvider;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.customer.entity.Customer;
import com.crm.travelcrm.marketing.dto.*;
import com.crm.travelcrm.marketing.entity.Segment;
import com.crm.travelcrm.marketing.repository.SegmentRepository;
import com.crm.travelcrm.permission.service.SubAgentScope;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SegmentServiceImpl implements SegmentService {

    private final SegmentRepository segmentRepository;
    private final SegmentAudienceResolver audienceResolver;
    private final SegmentConditionCodec conditionCodec;
    private final MarketingFieldCatalog fieldCatalog;
    private final SubAgentScope subAgentScope;
    private final CurrentUserProvider currentUser;

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<SegmentResponse> list(int page, int size, String sortBy, String sortDir, String search) {
        Long tenantId = requireTenantId();
        Sort sort = "asc".equalsIgnoreCase(sortDir) ? Sort.by(sortBy).ascending() : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);

        Page<Segment> segPage = segmentRepository.findAll(listSpec(tenantId, search), pageable);
        List<SegmentResponse> content = segPage.getContent().stream()
                .map(s -> toResponse(s, audienceResolver.count(audienceResolver.forSegment(tenantId, s))))
                .toList();
        return PagedApiResponse.of("Segments fetched successfully", content, PaginationMeta.from(segPage, sortBy, sortDir));
    }

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<SegmentResponse> get(UUID publicId) {
        Long tenantId = requireTenantId();
        Segment s = requireVisible(publicId);
        long count = audienceResolver.count(audienceResolver.forSegment(tenantId, s));
        return ApiResponse.success("Segment fetched successfully", toResponse(s, count));
    }

    @Override
    @Transactional
    public ApiResponse<SegmentResponse> create(CreateSegmentRequest request) {
        Long tenantId = requireTenantId();
        Segment s = Segment.builder()
                .name(request.name().trim())
                .description(trimOrNull(request.description()))
                .matchType(request.matchType())
                .conditionsJson(conditionCodec.toJson(request.conditions()))
                .build();
        Segment saved = segmentRepository.save(s);   // tenantId + ownerUserId stamped by listeners
        long count = audienceResolver.count(audienceResolver.forSegment(tenantId, saved));
        return ApiResponse.success("Segment created successfully", toResponse(saved, count), 201);
    }

    @Override
    @Transactional
    public ApiResponse<SegmentResponse> update(UUID publicId, UpdateSegmentRequest request) {
        Long tenantId = requireTenantId();
        Segment s = requireVisible(publicId);
        s.setName(request.name().trim());
        s.setDescription(trimOrNull(request.description()));
        s.setMatchType(request.matchType());
        s.setConditionsJson(conditionCodec.toJson(request.conditions()));
        Segment saved = segmentRepository.save(s);
        long count = audienceResolver.count(audienceResolver.forSegment(tenantId, saved));
        return ApiResponse.success("Segment updated successfully", toResponse(saved, count));
    }

    @Override
    @Transactional
    public ApiResponse<Void> delete(UUID publicId) {
        Segment s = requireVisible(publicId);
        s.softDelete(currentUser.currentUsernameOrSystem());
        segmentRepository.save(s);
        return ApiResponse.success("Segment deleted successfully");
    }

    @Override
    @Transactional(readOnly = true)
    public ApiResponse<SegmentPreviewResponse> preview(SegmentPreviewRequest request) {
        Long tenantId = requireTenantId();
        Specification<Customer> spec = audienceResolver.forRules(tenantId, request.matchType(), request.conditions());
        Page<Customer> p = audienceResolver.page(spec, PageRequest.of(0, 25));
        List<SegmentSampleDTO> sample = p.getContent().stream()
                .map(c -> new SegmentSampleDTO(c.getPublicId(), c.getName(), c.getEmail(), c.getPhone(), c.getCity(),
                        c.getTier() != null ? c.getTier().name() : null))
                .toList();
        return ApiResponse.success("Preview computed", new SegmentPreviewResponse(p.getTotalElements(), sample));
    }

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<SegmentMemberResponse> members(UUID publicId, int page, int size) {
        Long tenantId = requireTenantId();
        Segment s = requireVisible(publicId);
        Page<Customer> p = audienceResolver.page(audienceResolver.forSegment(tenantId, s), PageRequest.of(page, size));
        List<SegmentMemberResponse> content = p.getContent().stream()
                .map(c -> new SegmentMemberResponse(c.getPublicId(), c.getName(), c.getEmail(), c.getPhone(),
                        c.getCity(), c.getState(),
                        c.getTier() != null ? c.getTier().name() : null,
                        c.getType() != null ? c.getType().name() : null,
                        c.getStatus() != null ? c.getStatus().name() : null))
                .toList();
        return PagedApiResponse.of("Members fetched successfully", content, PaginationMeta.from(p));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SegmentFieldDef> fields() {
        return fieldCatalog.fields();
    }

    // ── internals ────────────────────────────────────────────────────────────────

    private SegmentResponse toResponse(Segment s, long memberCount) {
        return new SegmentResponse(
                s.getPublicId(), s.getName(), s.getDescription(), s.getMatchType(),
                conditionCodec.fromJson(s.getConditionsJson()), memberCount,
                s.getCreatedBy(), s.getCreatedAt(), s.getUpdatedAt());
    }

    private Specification<Segment> listSpec(Long tenantId, String search) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("tenantId"), tenantId));
            ps.add(cb.isNull(root.get("deletedAt")));
            Long owner = subAgentScope.ownerFilter();
            if (owner != null) ps.add(cb.equal(root.get("ownerUserId"), owner));
            if (StringUtils.hasText(search)) {
                ps.add(cb.like(cb.lower(root.get("name")), "%" + search.toLowerCase() + "%"));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private Segment requireVisible(UUID publicId) {
        Segment s = segmentRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, requireTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Segment not found: " + publicId));
        subAgentScope.assertVisible(s, publicId);
        return s;
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