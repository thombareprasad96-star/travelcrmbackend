package com.crm.travelcrm.lead.repository;

import com.crm.travelcrm.lead.entity.Lead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LeadRepository extends JpaRepository<Lead, Long> {

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);

    Optional<Lead> findByEmail(String email);

    Optional<Lead> findByPhone(String phone);
}