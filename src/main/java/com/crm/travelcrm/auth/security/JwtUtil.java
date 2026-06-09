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

    public String generateToken(SuperAdmin superAdmin) {
        return Jwts.builder()
                .subject(superAdmin.getEmail())
                .claim("role", "SUPER_ADMIN")
                // SuperAdmin has no tenant — intentionally omitted
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiryMs))
                .signWith(getKey())
                .compact();
    }

    public String generateToken(User user) {
        return Jwts.builder()
                .subject(user.getEmail())
                .claim("role", user.getRole().name())
                .claim("tenantId", user.getTenantId())   // ← ADDED
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiryMs))
                .signWith(getKey())
                .compact();
    }

    public String extractEmail(String token) {
        return getClaims(token).getSubject();
    }

    public String extractRole(String token) {
        return getClaims(token).get("role", String.class);
    }

    // ← NEW METHOD
    public Long extractTenantId(String token) {
        Object value = getClaims(token).get("tenantId");
        if (value == null)              return null;
        if (value instanceof Integer i) return i.longValue();
        if (value instanceof Long l)    return l;
        return Long.parseLong(value.toString());
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