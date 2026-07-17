package com.crm.travelcrm.auth.repository;

import com.crm.travelcrm.common.entity.SuperAdmin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SuperAdminRepository extends JpaRepository<SuperAdmin, Long> {
    Optional<SuperAdmin> findByEmail(String email);

    /** H1 — a soft-deleted platform account must never authenticate. Use this on every auth path. */
    Optional<SuperAdmin> findByEmailAndDeletedAtIsNull(String email);

    boolean existsByEmail(String email);

    /**
     * Every platform account that should receive notifications. Mirrors the auth path's acceptance
     * rules — a disabled or soft-deleted account cannot log in, so notifying it would write rows no
     * one will ever read.
     */
    List<SuperAdmin> findAllByEnabledTrueAndDeletedAtIsNull();
}