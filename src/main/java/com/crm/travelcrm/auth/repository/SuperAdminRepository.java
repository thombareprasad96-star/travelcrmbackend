package com.crm.travelcrm.auth.repository;

import com.crm.travelcrm.common.entity.SuperAdmin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SuperAdminRepository extends JpaRepository<SuperAdmin, Long> {
    Optional<SuperAdmin> findByEmail(String email);

    /** H1 — a soft-deleted platform account must never authenticate. Use this on every auth path. */
    Optional<SuperAdmin> findByEmailAndDeletedAtIsNull(String email);

    boolean existsByEmail(String email);
}