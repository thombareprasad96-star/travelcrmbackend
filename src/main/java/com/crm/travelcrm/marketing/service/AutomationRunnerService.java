package com.crm.travelcrm.marketing.service;

import com.crm.travelcrm.customer.entity.Customer;
import com.crm.travelcrm.customer.repository.CustomerRepository;
import com.crm.travelcrm.marketing.entity.AutomationTrigger;
import com.crm.travelcrm.marketing.enums.AutomationType;
import com.crm.travelcrm.marketing.repository.AutomationTriggerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Fires enabled birthday/anniversary triggers. Idempotent per day via {@code lastRunOn}: a trigger
 * runs once, on the first tick at/after its {@code sendTime}. {@code daysBefore} shifts the target
 * date, so "3 days before" matches customers whose occasion is 3 days out.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutomationRunnerService {

    private final AutomationTriggerRepository triggerRepository;
    private final CustomerRepository customerRepository;
    private final MessageDispatcher dispatcher;

    @Transactional
    public void runForTenant(Long tenantId) {
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        for (AutomationTrigger t : triggerRepository.findByTenantIdAndEnabledTrue(tenantId)) {
            if (today.equals(t.getLastRunOn())) continue;          // already fired today
            if (t.getSendTime() != null && now.isBefore(t.getSendTime())) continue;   // not yet time
            int sent = fire(t, tenantId, today);
            t.setLastRunOn(today);
            t.setTotalSent(t.getTotalSent() + sent);
            triggerRepository.save(t);
            log.info("Automation {} fired for tenant {} — {} sent", t.getTriggerType(), tenantId, sent);
        }
    }

    private int fire(AutomationTrigger t, Long tenantId, LocalDate today) {
        LocalDate target = today.plusDays(Math.max(0, t.getDaysBefore()));   // occasion is daysBefore ahead
        List<Customer> customers = customerRepository.findAllByTenantIdAndDeletedAtIsNull(tenantId);
        int sent = 0;
        for (Customer c : customers) {
            LocalDate occasion = t.getTriggerType() == AutomationType.BIRTHDAY ? c.getBirthday() : c.getAnniversary();
            if (occasion == null) continue;
            if (occasion.getMonthValue() == target.getMonthValue() && occasion.getDayOfMonth() == target.getDayOfMonth()) {
                try {
                    if (dispatcher.deliver(tenantId, t.getChannel(), c, t.getSubject(), t.getBody(), t.getTemplateName()).sent()) {
                        sent++;
                    }
                } catch (Exception ex) {
                    log.error("Automation {} send to customer {} failed: {}", t.getTriggerType(), c.getId(), ex.getMessage());
                }
            }
        }
        return sent;
    }
}