package com.crm.travelcrm.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.transaction.annotation.EnableTransactionManagement;

// order = 0 makes the TX advisor the outermost AOP wrapper, so it opens the session
// before TenantFilterAspect (@Order(1), inner) enables the Hibernate tenant filter.
//
// @EnableJpaAuditing lives here rather than on AuditingConfig (which declares the AuditorAware bean
// it references). Keeping the annotation off the class that defines its own referenced bean is the
// tidier arrangement, and this class stays bean-free to preserve it.
//
// NOTE: this separation was TRIED as a fix for the Flyway startup cycle described in
// application.properties and does NOT fix it — verified by experiment. The cycle reproduces with
// FLYWAY_ENABLED=true regardless of where these two annotations live, and regardless of
// JPA_DDL_AUTO. Do not re-attempt this avenue.
@Configuration
@EnableTransactionManagement(order = 0, proxyTargetClass = true)
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaConfig {
}




