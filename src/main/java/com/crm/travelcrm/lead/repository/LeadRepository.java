package com.crm.travelcrm.lead.repository;

import com.crm.travelcrm.lead.entity.Lead;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeadRepository extends JpaRepository<Lead, Long> {

    // ── List ─────────────────────────────────────────────────────────────────
    Page<Lead> findAllByTenantIdAndDeletedAtIsNull(
            Long tenantId, Pageable pageable);

    // ── Single fetch ─────────────────────────────────────────────────────────
    Optional<Lead> findByPublicIdAndTenantIdAndDeletedAtIsNull(
            UUID publicId, Long tenantId);

    // ── Search ───────────────────────────────────────────────────────────────
    Optional<Lead> findByEmailAndTenantIdAndDeletedAtIsNull(
            String email, Long tenantId);

    Optional<Lead> findByPhoneAndTenantIdAndDeletedAtIsNull(
            String phone, Long tenantId);

    // ── Duplicate checks ─────────────────────────────────────────────────────
    boolean existsByEmailAndTenantIdAndDeletedAtIsNull(
            String email, Long tenantId);

    boolean existsByPhoneAndTenantIdAndDeletedAtIsNull(
            String phone, Long tenantId);

    // ── Duplicate check excluding self (for update) ──────────────────────────
    boolean existsByEmailAndTenantIdAndDeletedAtIsNullAndPublicIdNot(
            String email, Long tenantId, UUID publicId);

    boolean existsByPhoneAndTenantIdAndDeletedAtIsNullAndPublicIdNot(
            String phone, Long tenantId, UUID publicId);
}