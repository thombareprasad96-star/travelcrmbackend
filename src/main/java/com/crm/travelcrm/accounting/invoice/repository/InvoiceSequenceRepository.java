package com.crm.travelcrm.accounting.invoice.repository;

import com.crm.travelcrm.accounting.invoice.entity.InvoiceSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface InvoiceSequenceRepository extends JpaRepository<InvoiceSequence, Long> {

    /** Locked read (SELECT … FOR UPDATE) for the atomic increment. Row must already exist. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<InvoiceSequence> findByTenantIdAndSeriesCodeAndFinancialYear(
            Long tenantId, String seriesCode, String financialYear);

    boolean existsByTenantIdAndSeriesCodeAndFinancialYear(
            Long tenantId, String seriesCode, String financialYear);
}