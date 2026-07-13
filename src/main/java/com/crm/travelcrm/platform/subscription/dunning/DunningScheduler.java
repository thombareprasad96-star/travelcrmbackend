package com.crm.travelcrm.platform.subscription.dunning;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Daily invoice-dunning sweep. Runs shortly after the date-based {@code SubscriptionExpiryScheduler}
 * (they target the same EXPIRED terminal from orthogonal triggers: end-date lapse vs. non-payment).
 * Also invocable on demand via {@code POST /api/super-admin/subscriptions/run-dunning}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DunningScheduler {

    private final DunningService dunningService;

    @Scheduled(cron = "${app.subscription.dunning-cron:0 15 3 * * *}")
    public void scheduledRun() {
        int changes = dunningService.runDunning(LocalDate.now());
        if (changes > 0) {
            log.info("[Dunning] {} tenant status transition(s)", changes);
        }
    }
}