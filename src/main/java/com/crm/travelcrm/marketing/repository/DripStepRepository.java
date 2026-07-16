package com.crm.travelcrm.marketing.repository;

import com.crm.travelcrm.marketing.entity.DripStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface DripStepRepository extends JpaRepository<DripStep, Long> {

    List<DripStep> findBySequenceIdAndTenantIdOrderByStepOrderAsc(Long sequenceId, Long tenantId);

    Optional<DripStep> findBySequenceIdAndTenantIdAndStepOrder(Long sequenceId, Long tenantId, int stepOrder);

    // Steps are child rows fully owned by the sequence — replaced wholesale on update.
    @Transactional
    void deleteBySequenceIdAndTenantId(Long sequenceId, Long tenantId);
}