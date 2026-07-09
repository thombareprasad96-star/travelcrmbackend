package com.crm.travelcrm.platform.analytics.service;

import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.platform.analytics.dto.AnalyticsOverviewResponse;
import com.crm.travelcrm.platform.analytics.dto.LabelValue;
import com.crm.travelcrm.platform.analytics.dto.MonthAmount;
import com.crm.travelcrm.platform.billing.entity.BillingRecord;
import com.crm.travelcrm.platform.billing.enums.BillingStatus;
import com.crm.travelcrm.platform.billing.repository.BillingRecordRepository;
import com.crm.travelcrm.platform.subscription.entity.Plan;
import com.crm.travelcrm.platform.subscription.repository.PlanRepository;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.enums.TenantPlan;
import com.crm.travelcrm.tenent.enums.TenantStatus;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AnalyticsServiceImpl implements AnalyticsService {

    private static final int MONTHS = 12;
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH);

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PlanRepository planRepository;
    private final BillingRecordRepository billingRepository;

    @Override
    @Transactional(readOnly = true)
    public AnalyticsOverviewResponse overview() {
        // Live tenants aggregated in-memory (platform scale — a bounded set).
        List<Tenant> tenants = tenantRepository.findByDeletedAtIsNull();
        List<Plan> plans = planRepository.findAllByOrderByMonthlyPriceAsc();

        Map<TenantPlan, BigDecimal> planPrice = plans.stream().collect(Collectors.toMap(
                Plan::getCode,
                p -> p.getMonthlyPrice() != null ? p.getMonthlyPrice() : BigDecimal.ZERO,
                (a, b) -> a));

        Map<TenantStatus, Long> byStatus = tenants.stream()
                .collect(Collectors.groupingBy(Tenant::getStatus, Collectors.counting()));
        Map<TenantPlan, Long> byPlan = tenants.stream()
                .collect(Collectors.groupingBy(Tenant::getPlan, Collectors.counting()));

        long active = byStatus.getOrDefault(TenantStatus.ACTIVE, 0L);
        long trial = byStatus.getOrDefault(TenantStatus.TRIAL, 0L);

        // MRR = recurring price of operational (ACTIVE + TRIAL) tenants.
        BigDecimal mrr = tenants.stream()
                .filter(t -> t.getStatus() == TenantStatus.ACTIVE || t.getStatus() == TenantStatus.TRIAL)
                .map(t -> planPrice.getOrDefault(t.getPlan(), BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal outstanding = billingRepository.sumAmountByStatus(BillingStatus.UNPAID);

        // Revenue series (last 12 months) from PAID invoices, keyed by issue month.
        YearMonth current = YearMonth.now();
        YearMonth start = current.minusMonths(MONTHS - 1L);
        List<BillingRecord> paid = billingRepository
                .findByStatusAndDeletedAtIsNullAndIssueDateGreaterThanEqual(BillingStatus.PAID, start.atDay(1));
        Map<YearMonth, BigDecimal> revByMonth = paid.stream()
                .filter(b -> b.getIssueDate() != null)
                .collect(Collectors.groupingBy(
                        b -> YearMonth.from(b.getIssueDate()),
                        Collectors.reducing(BigDecimal.ZERO,
                                b -> b.getAmount() != null ? b.getAmount() : BigDecimal.ZERO,
                                BigDecimal::add)));

        Map<YearMonth, Long> newByMonth = tenants.stream()
                .filter(t -> t.getCreatedAt() != null)
                .collect(Collectors.groupingBy(t -> YearMonth.from(t.getCreatedAt()), Collectors.counting()));

        List<LabelValue> newTenantsByMonth = new ArrayList<>(MONTHS);
        List<MonthAmount> revenueByMonth = new ArrayList<>(MONTHS);
        for (int i = 0; i < MONTHS; i++) {
            YearMonth ym = start.plusMonths(i);
            String label = ym.format(MONTH_FMT);
            newTenantsByMonth.add(new LabelValue(label, newByMonth.getOrDefault(ym, 0L)));
            revenueByMonth.add(new MonthAmount(label, revByMonth.getOrDefault(ym, BigDecimal.ZERO)));
        }

        List<LabelValue> tenantsByStatus = List.of(
                new LabelValue("ACTIVE", active),
                new LabelValue("TRIAL", trial),
                new LabelValue("SUSPENDED", byStatus.getOrDefault(TenantStatus.SUSPENDED, 0L)),
                new LabelValue("EXPIRED", byStatus.getOrDefault(TenantStatus.EXPIRED, 0L)));

        List<LabelValue> tenantsByPlan = plans.stream()
                .map(p -> new LabelValue(p.getDisplayName(), byPlan.getOrDefault(p.getCode(), 0L)))
                .toList();

        return AnalyticsOverviewResponse.builder()
                .totalTenants(tenants.size())
                .activeTenants(active)
                .trialTenants(trial)
                .suspendedTenants(byStatus.getOrDefault(TenantStatus.SUSPENDED, 0L))
                .expiredTenants(byStatus.getOrDefault(TenantStatus.EXPIRED, 0L))
                .totalUsers(userRepository.countByDeletedAtIsNullAndTenantIdIsNotNull())
                .mrr(mrr)
                .outstanding(outstanding != null ? outstanding : BigDecimal.ZERO)
                .collectedThisMonth(revByMonth.getOrDefault(current, BigDecimal.ZERO))
                .currency(plans.isEmpty() ? "INR" : plans.get(0).getCurrency())
                .tenantsByStatus(tenantsByStatus)
                .tenantsByPlan(tenantsByPlan)
                .newTenantsByMonth(newTenantsByMonth)
                .revenueByMonth(revenueByMonth)
                .build();
    }
}