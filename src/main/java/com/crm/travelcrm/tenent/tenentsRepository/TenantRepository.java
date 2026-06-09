package com.crm.travelcrm.tenent.tenentsRepository;

import com.crm.travelcrm.tenent.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, Long> {

    boolean existsByEmail(String email);
    boolean existsByOrganizationCode(String organizationCode);
    boolean existsByEmailAndIdNot(String email, Long id);
}