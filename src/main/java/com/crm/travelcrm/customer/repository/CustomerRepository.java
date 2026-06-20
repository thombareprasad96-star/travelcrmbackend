package com.crm.travelcrm.customer.repository;

import com.crm.travelcrm.customer.entity.Customer;
import com.crm.travelcrm.customer.enums.CustomerStatus;
import com.crm.travelcrm.customer.enums.CustomerType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-aware data access for {@link Customer}.
 *
 * <p>Every finder is explicitly scoped by {@code tenantId} and excludes
 * soft-deleted rows ({@code deletedAt IS NULL}) so isolation never depends on the
 * Hibernate filter being enabled on a given session. {@link JpaSpecificationExecutor}
 * backs the dynamic {@code /filter} endpoint.</p>
 */
@Repository
public interface CustomerRepository
        extends JpaRepository<Customer, Long>, JpaSpecificationExecutor<Customer> {

    // ── Single fetch ───────────────────────────────────────────────────────────

    Optional<Customer> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    // Tenant-scoped lookup by internal id — used to validate cross-aggregate references
    // (e.g. Booking.customerId) without bypassing tenant isolation via bare findById.
    Optional<Customer> findByIdAndTenantIdAndDeletedAtIsNull(Long id, Long tenantId);

    Optional<Customer> findByPhoneAndTenantIdAndDeletedAtIsNull(String phone, Long tenantId);

    // ── Listing ────────────────────────────────────────────────────────────────

    Page<Customer> findAllByTenantIdAndDeletedAtIsNull(Long tenantId, Pageable pageable);

    List<Customer> findAllByTenantIdAndDeletedAtIsNull(Long tenantId);

    /** Case-insensitive name search (used by {@code /search-name}). */
    List<Customer> findByTenantIdAndDeletedAtIsNullAndNameContainingIgnoreCase(
            Long tenantId, String name);

    // ── Duplicate guards (phone is the per-tenant natural key) ─────────────────

    boolean existsByPhoneAndTenantIdAndDeletedAtIsNull(String phone, Long tenantId);

    boolean existsByPhoneAndTenantIdAndDeletedAtIsNullAndPublicIdNot(
            String phone, Long tenantId, UUID publicId);

    // ── Code generation support ────────────────────────────────────────────────

    Optional<Customer> findTopByTenantIdOrderByIdDesc(Long tenantId);

    // ── Stats counters (aggregated in the database) ────────────────────────────

    long countByTenantIdAndDeletedAtIsNull(Long tenantId);

    long countByTenantIdAndDeletedAtIsNullAndStatus(Long tenantId, CustomerStatus status);

    long countByTenantIdAndDeletedAtIsNullAndType(Long tenantId, CustomerType type);
}