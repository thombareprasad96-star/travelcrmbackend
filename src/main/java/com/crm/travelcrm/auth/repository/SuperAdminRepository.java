package com.crm.travelcrm.auth.repository;

import com.crm.travelcrm.common.entity.SuperAdmin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SuperAdminRepository extends JpaRepository<SuperAdmin, Long> {
    Optional<SuperAdmin> findByEmail(String email);

    /** H1 — a soft-deleted platform account must never authenticate. Use this on every auth path. */
    Optional<SuperAdmin> findByEmailAndDeletedAtIsNull(String email);

    Optional<SuperAdmin> findByIdAndDeletedAtIsNull(Long id);

    Optional<SuperAdmin> findByPublicIdAndDeletedAtIsNull(UUID publicId);

    long countByDeletedAtIsNull();

    boolean existsByEmail(String email);

    boolean existsByEmailAndDeletedAtIsNull(String email);

    List<SuperAdmin> findAllByDeletedAtIsNull();

    List<SuperAdmin> findAllByDeletedAtIsNullOrderByCreatedAtAsc();

    /**
     * Every platform account that should receive notifications. Mirrors the auth path's acceptance
     * rules — a disabled or soft-deleted account cannot log in, so notifying it would write rows no
     * one will ever read.
     */
    List<SuperAdmin> findAllByEnabledTrueAndDeletedAtIsNull();
}
