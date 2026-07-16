package com.crm.travelcrm.common.entity;

/**
 * Marks a tenant entity as carrying a row-level OWNER ({@code owner_user_id}) — the tenant user /
 * sub-agent who owns the record. This is the second isolation dimension: {@code tenant_id} scopes
 * to a tenant, {@code owner_user_id} scopes to a user WITHIN that tenant (the row-level half of
 * permission enforcement — see {@code ScopeResolver}).
 *
 * <p>Stamped on create by {@link com.crm.travelcrm.common.listener.OwnershipEntityListener} from
 * the authenticated principal, and read by the per-module access guards + list filters (Phase 2).
 * Deliberately distinct from {@code BaseEntity.createdBy}, which is an audit email String — not a
 * queryable numeric owner id and unusable for {@code owner_user_id IN (:visibleIds)} scoping.</p>
 *
 * <p>{@code Lead} is NOT {@code Ownable}: its owner dimension is the existing {@code assignedUser}
 * FK, which {@code LeadAccessGuard} already scopes on.</p>
 */
public interface Ownable {

    Long getOwnerUserId();

    void setOwnerUserId(Long ownerUserId);
}
