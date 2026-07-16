package com.crm.travelcrm.accounting.invoice.repository;

import com.crm.travelcrm.accounting.invoice.entity.TaxInvoiceLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaxInvoiceLineRepository extends JpaRepository<TaxInvoiceLine, Long> {

    List<TaxInvoiceLine> findByInvoiceIdAndTenantIdOrderByLineNoAsc(Long invoiceId, Long tenantId);
}