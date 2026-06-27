package com.crm.travelcrm.portal.security;

import com.crm.travelcrm.portal.auth.entity.TravelerAccount;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

/**
 * Mints and validates <b>traveler</b> JWTs. Two hard separations from the staff {@link
 * com.crm.travelcrm.auth.security.JwtUtil} make the realms non-interchangeable:
 *
 * <ol>
 *   <li><b>Distinct signing key</b> ({@code portal.jwt.secret}, never {@code jwt.secret}). A staff
 *       token therefore fails signature verification here, and a traveler token fails it on the staff
 *       side — neither can be replayed against the other realm even before claim checks.</li>
 *   <li><b>Audience/type claim</b> {@code typ=TRAVELER} + {@code aud=portal}, asserted on every
 *       validate, so a token that somehow shared a key still couldn't cross.</li>
 * </ol>
 *
 * The subject is the {@code TravelerAccount.publicId}; the filter reloads the account each request
 * so a disabled/soft-deleted traveler is rejected immediately (server-side revocation).
 */
@Component
public class PortalJwtUtil {

    public static final String TYPE_CLAIM = "typ";
    public static final String TYPE_TRAVELER = "TRAVELER";
    public static final String AUDIENCE = "portal";
    public static final String TENANT_ID = "tenantId";

    @Value("${portal.jwt.secret}")
    private String secretKey;

    @Value("${portal.jwt.expiry-ms}")
    private long expiryMs;

    public String generateToken(TravelerAccount account) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(account.getPublicId().toString())
                .audience().add(AUDIENCE).and()
                .claim(TYPE_CLAIM, TYPE_TRAVELER)
                .claim(TENANT_ID, account.getTenantId())
                .issuedAt(new Date(now))
                .expiration(new Date(now + expiryMs))
                .signWith(getKey())
                .compact();
    }

    public long getExpiryMs() {
        return expiryMs;
    }

    /** True only for a well-formed, correctly-signed, non-expired <b>traveler</b> token. */
    public boolean isTravelerToken(String token) {
        try {
            Claims claims = getClaims(token);
            return TYPE_TRAVELER.equals(claims.get(TYPE_CLAIM, String.class))
                    && claims.getAudience() != null
                    && claims.getAudience().contains(AUDIENCE);
        } catch (Exception e) {
            return false;
        }
    }

    public java.util.UUID extractAccountPublicId(String token) {
        return java.util.UUID.fromString(getClaims(token).getSubject());
    }

    public Long extractTenantId(String token) {
        Object value = getClaims(token).get(TENANT_ID);
        if (value == null)              return null;
        if (value instanceof Integer i) return i.longValue();
        if (value instanceof Long l)    return l;
        return Long.parseLong(value.toString());
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
