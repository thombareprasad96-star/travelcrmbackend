package com.crm.travelcrm.auth.api;

/**
 * Public, read-only access to the currently authenticated principal.
 *
 * <p>This is the supported way for other modules to learn "who is acting" without
 * importing the {@code User} entity or touching {@code SecurityContextHolder}
 * directly. Keeping the resolution rules (principal type, SuperAdmin handling) in
 * one place means a change to how identity is carried only touches auth.
 */
public interface CurrentUserProvider {

    /**
     * Internal id of the authenticated tenant user, or {@code null} when there is no
     * tenant user in context (unauthenticated, or a SuperAdmin whose principal is a
     * different entity type). Use this where "no actor" is a valid state — e.g. as the
     * actor on a notification event.
     */
    Long currentUserIdOrNull();

    /**
     * Authenticated principal's username (email), or {@code "system"} when there is no
     * authentication in context. Suitable for audit fields (createdBy/deletedBy).
     */
    String currentUsernameOrSystem();
}