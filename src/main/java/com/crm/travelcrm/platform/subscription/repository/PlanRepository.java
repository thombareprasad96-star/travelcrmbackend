package com.crm.travelcrm.platform.subscription.repository;

import com.crm.travelcrm.platform.subscription.entity.Plan;
import com.crm.travelcrm.tenent.enums.TenantPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Platform-level plan catalogue (no tenant scoping). */
@Repository
public interface PlanRepository extends JpaRepository<Plan, Long> {

    Optional<Plan> findByCode(TenantPlan code);

    Optional<Plan> findByPublicId(UUID publicId);

    List<Plan> findAllByOrderByMonthlyPriceAsc();
}