package com.crm.travelcrm.customer.util;

import com.crm.travelcrm.customer.entity.Customer;
import com.crm.travelcrm.customer.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

/**
 * Generates the per-tenant human-friendly customer code ({@code CUS10001}, …).
 *
 * <p>The sequence is derived from the tenant's highest existing id, so each tenant
 * gets its own monotonically increasing run. This mirrors {@code VendorCodeGenerator}.
 * Uniqueness is additionally guaranteed by the {@code uk_customer_tenant_code}
 * constraint, which is the real safety net under concurrency.</p>
 */
@Component
@RequiredArgsConstructor
public class CustomerCodeGenerator {

    private static final Logger log = LogManager.getLogger(CustomerCodeGenerator.class);

    private static final String PREFIX       = "CUS";
    private static final long   STARTING_SEQ = 10001L;

    private final CustomerRepository customerRepository;

    public String generate(Long tenantId) {
        long nextSeq = customerRepository.findTopByTenantIdOrderByIdDesc(tenantId)
                .map(Customer::getCustomerCode)
                .map(this::parseSequence)
                .map(seq -> seq + 1)
                .orElse(STARTING_SEQ);

        String code = PREFIX + nextSeq;
        log.debug("Generated customer code: {} for tenantId: {}", code, tenantId);
        return code;
    }

    private long parseSequence(String code) {
        try {
            return Long.parseLong(code.substring(PREFIX.length()));
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            log.warn("Could not parse customer code '{}', falling back to starting sequence", code);
            return STARTING_SEQ - 1; // +1 in caller → STARTING_SEQ
        }
    }
}