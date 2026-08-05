package com.crm.travelcrm.communication.repository;

import com.crm.travelcrm.communication.entity.CommConversationMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Membership + per-member read state for internal conversations. */
public interface CommConversationMemberRepository extends JpaRepository<CommConversationMember, Long> {

    List<CommConversationMember> findByTenantIdAndConversationIdAndDeletedAtIsNull(
            Long tenantId, Long conversationId);

    /** The caller's own membership — also the authorisation check for an internal thread. */
    Optional<CommConversationMember> findByTenantIdAndConversationIdAndUserIdAndDeletedAtIsNull(
            Long tenantId, Long conversationId, Long userId);

    List<CommConversationMember> findByTenantIdAndUserIdAndDeletedAtIsNull(Long tenantId, Long userId);

    /** Personal pins, most recent first. Pinning is per member, never a property of the channel. */
    List<CommConversationMember> findByTenantIdAndUserIdAndPinnedAtIsNotNullAndDeletedAtIsNullOrderByPinnedAtDesc(
            Long tenantId, Long userId);

    boolean existsByTenantIdAndConversationIdAndUserIdAndDeletedAtIsNull(
            Long tenantId, Long conversationId, Long userId);
}
