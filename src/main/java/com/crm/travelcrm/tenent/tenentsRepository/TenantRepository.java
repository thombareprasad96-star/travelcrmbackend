package com.crm.travelcrm.tenent.tenentsRepository;

import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.enums.TenantStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, Long> {

    boolean existsByEmail(String email);
    boolean existsByOrganizationCode(String organizationCode);
    boolean existsByEmailAndIdNot(String email, Long id);

    /** Codes are stored uppercased and are globally unique — the dev seeder resolves its own
     *  demo tenant by code so it can never attach to a real customer's tenant. */
    Optional<Tenant> findByOrganizationCode(String organizationCode);

    /**
     * Pessimistic-write lock on the tenant row — the serialization point for seat-mutating operations
     * (sub-agent provisioning + seat-license open) so a concurrent pair cannot both pass a check-then-act
     * seat-cap / duplicate check. Must be called inside a transaction; the lock is held to commit.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Tenant t WHERE t.id = :id")
    Optional<Tenant> findByIdForUpdate(@Param("id") Long id);

    // External lookups key on publicId (UUID) — the internal Long PK is never exposed.
    Optional<Tenant> findByPublicId(UUID publicId);
    Optional<Tenant> findByPublicIdAndDeletedAtIsNull(UUID publicId);

    /**
     * Paged tenant search for the console. Lists live (deleted=false) or trashed (deleted=true)
     * tenants, optionally filtered by {@code status} and a free-text query over name / code / email.
     * Tenant is platform-level (no tenant @Filter), so soft-deleted rows are handled explicitly here.
     */
    @Query("""
            SELECT t FROM Tenant t
            WHERE ((:deleted = true  AND t.deletedAt IS NOT NULL)
                OR (:deleted = false AND t.deletedAt IS NULL))
              AND (:status IS NULL OR t.status = :status)
              AND (:q IS NULL OR :q = ''
                OR LOWER(t.organizationName) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(t.organizationCode) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(t.email)            LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<Tenant> search(@Param("q") String q,
                        @Param("status") TenantStatus status,
                        @Param("deleted") boolean deleted,
                        Pageable pageable);

    /** Live tenants in the given statuses whose subscription end date has passed — for auto-expiry. */
    List<Tenant> findByStatusInAndSubscriptionEndDateBeforeAndDeletedAtIsNull(
            Collection<TenantStatus> statuses, LocalDate date);

    /** All live (non-deleted) tenants — analytics aggregates these in-memory (platform scale). */
    List<Tenant> findByDeletedAtIsNull();

    /**
     * The tenant's explicitly-enabled module keys (Feature Flags). Empty ⇒ the tenant has no
     * override and falls back to its plan defaults. Direct element-collection join so the module
     * access filter can read them without loading the whole Tenant or its lazy collection.
     */
    @Query("SELECT m FROM Tenant t JOIN t.enabledModules m WHERE t.id = :id")
    List<String> findEnabledModules(@Param("id") Long id);
}