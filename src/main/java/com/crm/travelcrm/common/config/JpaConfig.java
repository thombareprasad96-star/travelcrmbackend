package com.crm.travelcrm.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

// order = 0 makes the TX advisor the outermost AOP wrapper, so it opens the session
// before TenantFilterAspect (@Order(1), inner) enables the Hibernate tenant filter.
@Configuration
@EnableTransactionManagement(order = 0, proxyTargetClass = true)
public class JpaConfig {
}




