package com.crm.travelcrm.accounting.tds.repository;

import com.crm.travelcrm.accounting.tds.entity.VendorPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VendorPaymentRepository extends JpaRepository<VendorPayment, Long> {

    List<VendorPayment> findByVendorBillIdAndTenantIdAndDeletedAtIsNullOrderByIdAsc(
            Long vendorBillId, Long tenantId);

    Optional<VendorPayment> findByTenantIdAndVendorBillIdAndIdempotencyKeyAndDeletedAtIsNull(
            Long tenantId, Long vendorBillId, String idempotencyKey);
}