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

    // ── Existing-customer matching (lead auto-prefill) ─────────────────────────
    // Both finders answer "does this tenant already know this person?" and back BOTH the
    // /customers/lookup probe and the authoritative link written at lead-create time.
    //
    // `findFirst...OrderByIdAsc` rather than a plain unique lookup on purpose: the uniqueness of
    // (tenant_id, phone) is a PARTIAL index over live rows on the RAW phone, so two rows can
    // legitimately share a canonical phone_normalized while differing in format. Oldest-first makes
    // the choice deterministic and picks the original record rather than the later duplicate.

    /**
     * Match on the E.164 shadow key, so "+919812345678", "9812345678" and "09812345678" all find
     * the same customer. Callers must skip a null canonical value — {@code PhoneCanonicalizer}
     * returns null when it cannot canonicalise without guessing, and matching null to null would
     * make every unparseable phone match every other one.
     */
    Optional<Customer> findFirstByPhoneNormalizedAndTenantIdAndDeletedAtIsNullOrderByIdAsc(
            String phoneNormalized, Long tenantId);

    /** Email is stored lower-cased on write; IgnoreCase guards rows that predate that. */
    Optional<Customer> findFirstByEmailIgnoreCaseAndTenantIdAndDeletedAtIsNullOrderByIdAsc(
            String email, Long tenantId);

    // ── Listing ────────────────────────────────────────────────────────────────

    Page<Customer> findAllByTenantIdAndDeletedAtIsNull(Long tenantId, Pageable pageable);

    List<Customer> findAllByTenantIdAndDeletedAtIsNull(Long tenantId);

    // Bulk load for a set of ids within the tenant (used by Marketing dispatch to resolve
    // merge tags for a batch of campaign recipients without N per-row lookups).
    List<Customer> findByIdInAndTenantIdAndDeletedAtIsNull(java.util.Collection<Long> ids, Long tenantId);

    /** Case-insensitive name search (used by {@code /search-name}). */
    List<Customer> findByTenantIdAndDeletedAtIsNullAndNameContainingIgnoreCase(
            Long tenantId, String name);

    // ── Duplicate guards (phone is the per-tenant natural key) ─────────────────

    boolean existsByPhoneAndTenantIdAndDeletedAtIsNull(String phone, Long tenantId);

    boolean existsByPhoneAndTenantIdAndDeletedAtIsNullAndPublicIdNot(
            String phone, Long tenantId, UUID publicId);

    // ── Trashed-only duplicate lookup (create-time "restore available" detection) ──
    // Phone is the per-tenant natural key. An active duplicate errors normally; a match that
    // lives ONLY in Trash returns the record so the API can offer Restore instead. Intercepts
    // before insert so the DB unique constraint (uk_customer_tenant_phone) never throws raw.
    Optional<Customer> findFirstByPhoneAndTenantIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(
            String phone, Long tenantId);

    // ── Traveler-portal login resolution (DELIBERATELY cross-tenant) ──────────────
    // Used only by TravelerAuthService at OTP request/verify, BEFORE any tenant is known — the
    // identity (and thus the tenant) is resolved FROM the phone/email. The matched customer's
    // tenantId then scopes everything after. Never use these on authenticated, tenant-scoped paths.
    Optional<Customer> findFirstByPhoneAndDeletedAtIsNullOrderByIdAsc(String phone);

    Optional<Customer> findFirstByEmailAndDeletedAtIsNullOrderByIdAsc(String email);

    // ── Code generation support ────────────────────────────────────────────────

    Optional<Customer> findTopByTenantIdOrderByIdDesc(Long tenantId);

    // ── Stats counters (aggregated in the database) ────────────────────────────

    long countByTenantIdAndDeletedAtIsNull(Long tenantId);

    long countByTenantIdAndDeletedAtIsNullAndStatus(Long tenantId, CustomerStatus status);

    long countByTenantIdAndDeletedAtIsNullAndType(Long tenantId, CustomerType type);
}