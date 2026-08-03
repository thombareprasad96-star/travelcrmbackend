package com.crm.travelcrm.hotelmarketplace.catalog.repository;

import com.crm.travelcrm.hotelmarketplace.catalog.entity.PlatformHotel;
import com.crm.travelcrm.hotelmarketplace.catalog.enums.PlatformHotelStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Catalog reads.
 *
 * <p><b>These rows carry no {@code tenant_id}</b>, so neither the Hibernate {@code tenantFilter} nor
 * {@code TenantIsolationArchTest} (which is scoped to {@code BaseTenantEntity} repositories) covers
 * anything here. Nothing is scoped for you. The two audiences are therefore served by two different
 * methods and must not share one:</p>
 * <ul>
 *   <li>{@link #findByPublicIdAndDeletedAtIsNull} — SuperAdmin only, sees every status.</li>
 *   <li>{@link #findSellableByPublicId} / {@link #searchSellable} — tenant-facing, ACTIVE only.</li>
 * </ul>
 * Calling the admin finder from a tenant path leaks DRAFT and SUSPENDED hotels.
 */
public interface PlatformHotelRepository extends JpaRepository<PlatformHotel, Long> {

    // ── SuperAdmin ──────────────────────────────────────────────────────────

    Optional<PlatformHotel> findByPublicIdAndDeletedAtIsNull(UUID publicId);

    /**
     * <b>{@code q} must arrive already lower-cased and wrapped in {@code %}</b> — see the note on
     * {@link #searchSellable} for why the normalisation cannot live inside the query.
     */
    @Query("""
           SELECT h FROM PlatformHotel h
           WHERE h.deletedAt IS NULL
             AND (:status IS NULL OR h.status = :status)
             AND (:q IS NULL OR LOWER(h.name) LIKE :q
                             OR LOWER(h.cityName) LIKE :q)
           """)
    Page<PlatformHotel> searchForAdmin(@Param("status") PlatformHotelStatus status,
                                       @Param("q") String q,
                                       Pageable pageable);

    /**
     * Possible duplicates of a hotel about to be created: same normalised name, city and country.
     * Reported to the SuperAdmin as a warning — NOT enforced as a unique constraint, because two
     * genuinely different properties can share a name in one city and a hard rule would block the
     * second one from ever being onboarded (design doc §5.1).
     */
    @Query("""
           SELECT h FROM PlatformHotel h
           WHERE h.deletedAt IS NULL
             AND LOWER(h.name) = LOWER(:name)
             AND LOWER(h.cityName) = LOWER(:cityName)
             AND (:countryCode IS NULL OR h.countryCode = :countryCode)
           """)
    List<PlatformHotel> findPossibleDuplicates(@Param("name") String name,
                                               @Param("cityName") String cityName,
                                               @Param("countryCode") String countryCode);

    // ── Tenant-facing (ACTIVE only) ─────────────────────────────────────────

    @Query("""
           SELECT h FROM PlatformHotel h
           WHERE h.deletedAt IS NULL
             AND h.status = com.crm.travelcrm.hotelmarketplace.catalog.enums.PlatformHotelStatus.ACTIVE
             AND h.publicId = :publicId
           """)
    Optional<PlatformHotel> findSellableByPublicId(@Param("publicId") UUID publicId);

    /**
     * Tenant catalog search. ACTIVE only — the status predicate is inside the query rather than left
     * to a caller, so a new call site cannot forget it and expose DRAFT rows.
     *
     * <p><b>{@code q} must arrive already lower-cased and wrapped in {@code %}, and {@code city}
     * already lower-cased.</b> That normalisation is the caller's job on purpose, and moving it back
     * into the query re-introduces a 500 on every unfiltered search:</p>
     *
     * <p>An optional parameter is used twice — once as {@code :p IS NULL}, once in the predicate.
     * Hibernate resolves a parameter's type across ALL its occurrences, and {@code IS NULL} pins
     * nothing, so the type can only come from the other one. A direct comparison against an entity
     * path supplies it ({@code h.countryCode = :countryCode} ⇒ VARCHAR); a parameter WRAPPED in a
     * function ({@code LOWER(:city)}, {@code CONCAT('%', :q, '%')}) does not — it binds as untyped
     * {@code JAVA_OBJECT}, PostgreSQL resolves the unknown to {@code bytea}, and the statement dies
     * with {@code function lower(bytea) does not exist} the moment the parameter is null.</p>
     *
     * <p>So every optional parameter here is compared DIRECTLY to a path expression, never passed
     * through a function. {@code LOWER()} stays on the column side, where it costs nothing extra —
     * the column was already being lower-cased per row.</p>
     */
    @Query("""
           SELECT h FROM PlatformHotel h
           WHERE h.deletedAt IS NULL
             AND h.status = com.crm.travelcrm.hotelmarketplace.catalog.enums.PlatformHotelStatus.ACTIVE
             AND (:q IS NULL OR LOWER(h.name) LIKE :q)
             AND (:city IS NULL OR LOWER(h.cityName) = :city)
             AND (:countryCode IS NULL OR h.countryCode = :countryCode)
             AND (:minStars IS NULL OR h.stars >= :minStars)
           """)
    Page<PlatformHotel> searchSellable(@Param("q") String q,
                                       @Param("city") String city,
                                       @Param("countryCode") String countryCode,
                                       @Param("minStars") Integer minStars,
                                       Pageable pageable);
}
