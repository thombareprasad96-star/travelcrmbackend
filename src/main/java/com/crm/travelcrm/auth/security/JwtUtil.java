package com.crm.travelcrm.auth.security;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.entity.SuperAdmin;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secretKey;

    @Value("${jwt.expiry-ms}")
    private long expiryMs;

    @Value("${jwt.super-admin-expiry-ms:${jwt.expiry-ms}}")
    private long superAdminExpiryMs;

    @Value("${impersonation.jwt.expiry-ms:1800000}")
    private long impersonationExpiryMs;

    public String generateToken(SuperAdmin superAdmin) {
        return Jwts.builder()
                .subject(superAdmin.getEmail())
                .claim(JwtClaims.ROLE, JwtClaims.ROLE_SUPER_ADMIN)
                .claim(JwtClaims.TOKEN_TYPE, JwtClaims.TYPE_PLATFORM)
                .claim(JwtClaims.TOKEN_VERSION, superAdmin.getTokenVersionOrZero())
                // SuperAdmin has no tenant — intentionally omitted
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + superAdminExpiryMs))
                .signWith(getKey())
                .compact();
    }

    public String generateToken(User user) {
        return Jwts.builder()
                .subject(user.getEmail())
                .claim(JwtClaims.ROLE, user.getRole().name())
                .claim(JwtClaims.TENANT_ID, user.getTenantId())   // ← ADDED
                .claim(JwtClaims.TOKEN_VERSION, user.getTokenVersionOrZero())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiryMs))
                .signWith(getKey())
                .compact();
    }

    /**
     * Time-boxed impersonation token: a normal tenant-user token (so the staff app accepts it and
     * runs AS that user) marked with {@code typ=IMPERSONATION} plus the acting SuperAdmin's id/email,
     * and a short TTL ({@code impersonation.jwt.expiry-ms}). Signed with the SAME key as user tokens
     * — a separate key would defeat the purpose (the token must authenticate as the target user).
     */
    public String generateImpersonationToken(User user, Long impersonatorId, String impersonatorEmail) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(user.getEmail())
                .claim(JwtClaims.ROLE, user.getRole().name())
                .claim(JwtClaims.TENANT_ID, user.getTenantId())
                .claim(JwtClaims.TOKEN_VERSION, user.getTokenVersionOrZero())
                .claim(JwtClaims.TOKEN_TYPE, JwtClaims.TYPE_IMPERSONATION)
                .claim(JwtClaims.IMPERSONATOR_ID, impersonatorId)
                .claim(JwtClaims.IMPERSONATOR_EMAIL, impersonatorEmail)
                .issuedAt(new Date(now))
                .expiration(new Date(now + impersonationExpiryMs))
                .signWith(getKey())
                .compact();
    }

    public long getImpersonationExpiryMs() {
        return impersonationExpiryMs;
    }

    public String extractEmail(String token) {
        return getClaims(token).getSubject();
    }

    public String extractRole(String token) {
        return getClaims(token).get(JwtClaims.ROLE, String.class);
    }

    // ← NEW METHOD
    public Long extractTenantId(String token) {
        Object value = getClaims(token).get(JwtClaims.TENANT_ID);
        if (value == null)              return null;
        if (value instanceof Integer i) return i.longValue();
        if (value instanceof Long l)    return l;
        return Long.parseLong(value.toString());
    }

    public String extractTokenType(String token) {
        return getClaims(token).get(JwtClaims.TOKEN_TYPE, String.class);
    }

    /** Token version ('tv') claim, or null for tokens minted before token-versioning existed. */
    public Integer extractTokenVersion(String token) {
        Object value = getClaims(token).get(JwtClaims.TOKEN_VERSION);
        if (value == null)              return null;
        if (value instanceof Integer i) return i;
        if (value instanceof Long l)    return l.intValue();
        return Integer.parseInt(value.toString());
    }

    public Long extractImpersonatorId(String token) {
        Object value = getClaims(token).get(JwtClaims.IMPERSONATOR_ID);
        if (value == null)              return null;
        if (value instanceof Integer i) return i.longValue();
        if (value instanceof Long l)    return l;
        return Long.parseLong(value.toString());
    }

    public String extractImpersonatorEmail(String token) {
        return getClaims(token).get(JwtClaims.IMPERSONATOR_EMAIL, String.class);
    }

    public boolean isTokenValid(String token) {
        try {
            getClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(getKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secretKey));
    }
}
