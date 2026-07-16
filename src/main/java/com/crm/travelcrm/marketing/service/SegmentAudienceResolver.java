package com.crm.travelcrm.marketing.service;

import com.crm.travelcrm.customer.entity.Customer;
import com.crm.travelcrm.customer.repository.CustomerRepository;
import com.crm.travelcrm.marketing.dto.SegmentConditionDTO;
import com.crm.travelcrm.marketing.entity.Segment;
import com.crm.travelcrm.marketing.enums.SegmentMatchType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves the concrete customer audience for a segment (or "all customers"), reusing the
 * {@link SegmentEvaluator}. Shared by segments (member count / preview / members), campaigns
 * (recipient materialisation) and drips (enrollment sync) so audience logic lives in one place.
 */
@Component
@RequiredArgsConstructor
public class SegmentAudienceResolver {

    private final SegmentEvaluator evaluator;
    private final SegmentConditionCodec codec;
    private final CustomerRepository customerRepository;

    public Specification<Customer> forSegment(Long tenantId, Segment segment) {
        return evaluator.toSpecification(tenantId, segment.getMatchType(), codec.fromJson(segment.getConditionsJson()));
    }

    /** Build a spec straight from ad-hoc rules (segment preview — no persisted segment yet). */
    public Specification<Customer> forRules(Long tenantId, SegmentMatchType matchType, List<SegmentConditionDTO> conditions) {
        return evaluator.toSpecification(tenantId, matchType, conditions);
    }

    public Specification<Customer> forAllCustomers(Long tenantId) {
        return evaluator.toSpecification(tenantId, SegmentMatchType.ALL, List.of());
    }

    public long count(Specification<Customer> spec) {
        return customerRepository.count(spec);
    }

    public List<Customer> list(Specification<Customer> spec) {
        return customerRepository.findAll(spec);
    }

    public Page<Customer> page(Specification<Customer> spec, Pageable pageable) {
        return customerRepository.findAll(spec, pageable);
    }
}