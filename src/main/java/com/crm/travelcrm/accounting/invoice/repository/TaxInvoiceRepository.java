package com.crm.travelcrm.accounting.invoice.repository;

import com.crm.travelcrm.accounting.invoice.entity.TaxInvoice;
import com.crm.travelcrm.accounting.invoice.enums.InvoiceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TaxInvoiceRepository extends JpaRepository<TaxInvoice, Long> {

    Optional<TaxInvoice> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    Page<TaxInvoice> findByTenantIdAndDeletedAtIsNullOrderByIdDesc(Long tenantId, Pageable pageable);

    List<TaxInvoice> findByTenantIdAndBookingIdAndDeletedAtIsNullOrderByIdDesc(Long tenantId, Long bookingId);

    /** Guards single-active-invoice-per-booking: an ISSUED invoice already exists for the booking. */
    boolean existsByTenantIdAndBookingIdAndStatusAndDeletedAtIsNull(
            Long tenantId, Long bookingId, InvoiceStatus status);

    List<TaxInvoice> findByTenantIdAndInvoiceDateBetweenAndDeletedAtIsNull(
            Long tenantId, LocalDate from, LocalDate to);
}