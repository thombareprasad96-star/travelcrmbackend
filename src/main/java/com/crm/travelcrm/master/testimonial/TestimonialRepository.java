package com.crm.travelcrm.master.testimonial;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TestimonialRepository extends JpaRepository<Testimonial, Long> {

    // Tenant-scoped finders only — findById(Long) bypasses the Hibernate tenant filter.
    Optional<Testimonial> findByIdAndTenantId(Long id, Long tenantId);

    Page<Testimonial> findByTenantId(Long tenantId, Pageable pageable);
}