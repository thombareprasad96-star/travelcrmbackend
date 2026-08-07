package com.crm.travelcrm.communication.repository;

import com.crm.travelcrm.communication.entity.CommChannelAccount;
import com.crm.travelcrm.communication.enums.ChannelAccountStatus;
import com.crm.travelcrm.communication.enums.CommChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Connected channels.
 *
 * <p>{@link #findByIngestTokenAndDeletedAtIsNull} is the ONLY method here that is not tenant-scoped,
 * and deliberately so: it is the webhook's tenant RESOLVER, called before any tenant is known, in
 * exactly the same shape as {@code LeadIngestTokenResolver}. It matches on the SHA-256 hash, never a
 * clear token, and every write that follows must be wrapped in {@code TenantScope.call(...)} with
 * the tenant it returns.
 */
public interface CommChannelAccountRepository extends JpaRepository<CommChannelAccount, Long> {

    Optional<CommChannelAccount> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    List<CommChannelAccount> findByTenantIdAndDeletedAtIsNull(Long tenantId);

    Optional<CommChannelAccount> findFirstByTenantIdAndChannelAndDeletedAtIsNullOrderByIdAsc(
            Long tenantId, CommChannel channel);

    List<CommChannelAccount> findByTenantIdAndChannelAndStatusAndDeletedAtIsNull(
            Long tenantId, CommChannel channel, ChannelAccountStatus status);

    /**
     * Resolve a tenant from an inbound webhook's opaque path token. NOT tenant-scoped by design.
     *
     * @param ingestTokenHash SHA-256 hex of the token, never the token itself
     */
    Optional<CommChannelAccount> findByIngestTokenAndDeletedAtIsNull(String ingestTokenHash);

    /** Accounts a polling reader should visit — used by the Phase 4 IMAP scheduler. */
    List<CommChannelAccount> findByChannelAndStatusAndDeletedAtIsNull(
            CommChannel channel, ChannelAccountStatus status);
}
