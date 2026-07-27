package com.crm.travelcrm.auth.repository;

import com.crm.travelcrm.common.entity.SuperAdminInvite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SuperAdminInviteRepository extends JpaRepository<SuperAdminInvite, Long> {

    Optional<SuperAdminInvite> findByTokenHashAndDeletedAtIsNull(String tokenHash);

    List<SuperAdminInvite> findAllByInvitedEmailAndConsumedAtIsNullAndDeletedAtIsNull(String invitedEmail);

    List<SuperAdminInvite> findAllByDeletedAtIsNullOrderByCreatedAtDesc();
}
