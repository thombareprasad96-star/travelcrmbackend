package com.crm.travelcrm.common.config;

import com.crm.travelcrm.auth.entity.User;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Supplies the {@code createdBy}/{@code updatedBy} value for every audited entity.
 *
 * <p>{@code @EnableJpaAuditing} lives on {@link JpaConfig}, not here — see the note there.
 */
@Configuration
public class AuditingConfig {

    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> {
            Authentication auth =
                    SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()
                    || "anonymousUser".equals(auth.getPrincipal())) {
                return Optional.of("system");
            }
            // For tenant staff this is the LOGIN username, not the email. Deliberate: email is no
            // longer unique, so an office sharing info@agency.com would write three
            // indistinguishable audit rows. Read explicitly off the principal rather than relying on
            // auth.getName(), so the intent survives any future change to User.getUsername().
            if (auth.getPrincipal() instanceof User user && user.getUsername() != null) {
                return Optional.of(user.getUsername());
            }
            // SuperAdmin (email) and TravelerPrincipal ("traveler:<uuid>") keep their own getName().
            return Optional.of(auth.getName());
        };
    }
}
