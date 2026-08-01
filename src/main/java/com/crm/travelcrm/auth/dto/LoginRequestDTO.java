package com.crm.travelcrm.auth.dto;

import lombok.Data;

@Data
public class LoginRequestDTO {

    /**
     * Tenant staff login identifier. Preferred field.
     *
     * <p>{@link #email} is kept as a wire-compatible alias, NOT as a second credential: the staff
     * login form posts {@code {email, password}} and lives in a separate repository, so accepting
     * only {@code username} would have locked every user out at the moment of deploy. Whichever
     * field arrives is resolved against the {@code username} column and nothing else — an actual
     * email address in either field authenticates no one.
     */
    private String username;

    /**
     * SuperAdmin login identifier (that realm is still email-keyed and is read directly from here),
     * and the legacy alias for {@link #username} on the tenant staff endpoint.
     */
    private String email;

    private String password;

    /**
     * The tenant staff login identifier, taking {@code username} when present and falling back to
     * the legacy {@code email} field. Used only by {@code AuthServiceImpl.userLogin}; the SuperAdmin
     * path reads {@link #getEmail()} directly so the two realms can never resolve each other.
     */
    public String getLoginIdentifier() {
        return (username != null && !username.isBlank()) ? username : email;
    }
}
