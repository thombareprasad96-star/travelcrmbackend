package com.crm.travelcrm.report.dashboard.service;

import com.crm.travelcrm.booking.assignment.BookingAssigneeView;
import com.crm.travelcrm.booking.assignment.BookingAssigneeViewFactory;
import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.enums.BookingStatus;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.enums.LeadSource;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.lead.enums.LeadType;
import com.crm.travelcrm.lead.repository.LeadRepository;
import com.crm.travelcrm.reminder.entity.Reminder;
import com.crm.travelcrm.reminder.entity.ReminderStatus;
import com.crm.travelcrm.reminder.repository.ReminderRepository;
import com.crm.travelcrm.report.dashboard.dto.DashboardAnalyticsResponse;
import com.crm.travelcrm.report.support.ReportDateRange;
import com.crm.travelcrm.report.support.ReportPeriod;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class DashboardAnalyticsService {

    private static final String[] SOURCE_COLORS = {
            "#f472b6", "#60a5fa", "#fbbf24", "#34d399", "#a78bfa", "#fb923c"
    };
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.ENGLISH);
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final LeadRepository leadRepository;
    private final BookingRepository bookingRepository;
    private final ReminderRepository reminderRepository;
    private final BookingAssigneeViewFactory assigneeViewFactory;

    @Transactional(readOnly = true)
    public DashboardAnalyticsResponse getAnalytics(String period, String from, String to) {
        Long tenantId = requireTenant();
        String[] range = ReportPeriod.resolve(period, from, to);
        LocalDateTime[] dateTimes = ReportDateRange.resolve(range[0], range[1]);
        LocalDateTime fromDateTime = dateTimes[0];
        LocalDateTime toDateTime = dateTimes[1];
        if (fromDateTime.isAfter(toDateTime)) {
            throw new IllegalArgumentException("from date cannot be after to date");
        }

        LocalDate fromDate = fromDateTime.toLocalDate();
        LocalDate toDate = toDateTime.toLocalDate();
        List<Lead> leads = leadRepository.findDashboardLeads(tenantId, fromDateTime, toDateTime);
        List<Booking> bookings = bookingRepository.findDashboardBookings(tenantId, fromDate, toDate);
        List<Booking> activeBookings = bookings.stream()
                .filter(this::isActiveBooking)
                .toList();

        long totalLeads = leads.size();
        long convertedLeads = leads.stream()
                .filter(lead -> lead.getLeadStage() == LeadStage.CONVERTED)
                .count();
        long hotLeads = leads.stream()
                .filter(lead -> lead.getLeadType() == LeadType.VIP)
                .count();
        long wins = activeBookings.stream()
                .filter(this::isWonBooking)
                .count();

        BigDecimal revenue = sum(activeBookings, Booking::getCustomerAmount);
        BigDecimal profit = sum(activeBookings, Booking::getNetProfit);
        BigDecimal refunds = sum(
                bookings.stream().filter(booking -> booking.getStatus() == BookingStatus.REFUNDED).toList(),
                Booking::getTotalPayable);
        List<PerformerAgg> performers = performers(leads, activeBookings);

        return DashboardAnalyticsResponse.builder()
                .totalLeads(totalLeads)
                .convertedLeads(convertedLeads)
                .conversionRate(percent(convertedLeads, totalLeads, 2))
                .revenue(revenue)
                .profit(profit)
                .netMargin(percent(profit, revenue, 1))
                .refunds(refunds)
                .winRate(percent(wins, activeBookings.size(), 2))
                .hotLeads(hotLeads)
                .leadSources(leadSources(leads))
                .topDestinations(topDestinations(activeBookings))
                .revenueTimeline(revenueTimeline(activeBookings))
                .topPerformersConv(topPerformersByConversion(performers))
                .topPerformersProfit(topPerformersByProfit(performers))
                .priorityFollowups(priorityFollowups(tenantId))
                .nearTravelDates(nearTravelDates(tenantId))
                .build();
    }

    private List<DashboardAnalyticsResponse.LeadSourceSlice> leadSources(List<Lead> leads) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Lead lead : leads) {
            String source = sourceName(lead.getLeadSource());
            counts.merge(source, 1L, Long::sum);
        }

        List<Map.Entry<String, Long>> sorted = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(7)
                .toList();

        List<DashboardAnalyticsResponse.LeadSourceSlice> result = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<String, Long> entry = sorted.get(i);
            result.add(DashboardAnalyticsResponse.LeadSourceSlice.builder()
                    .name(entry.getKey())
                    .value(entry.getValue())
                    .color(i < SOURCE_COLORS.length ? SOURCE_COLORS[i] : "#94a3b8")
                    .build());
        }
        return result;
    }

    private List<DashboardAnalyticsResponse.TopDestination> topDestinations(List<Booking> bookings) {
        Map<String, DestinationAgg> map = new LinkedHashMap<>();
        for (Booking booking : bookings) {
            DestinationAgg agg = map.computeIfAbsent(textOr(booking.getDestinationSnapshot(), "Other"),
                    ignored -> new DestinationAgg());
            agg.bookings++;
            agg.revenue = agg.revenue.add(money(booking.getCustomerAmount()));
        }

        return map.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().bookings, left.getValue().bookings))
                .limit(5)
                .map(entry -> DashboardAnalyticsResponse.TopDestination.builder()
                        .name(entry.getKey())
                        .bookings(entry.getValue().bookings)
                        .revenue(entry.getValue().revenue)
                        .build())
                .toList();
    }

    private List<DashboardAnalyticsResponse.RevenueTimelinePoint> revenueTimeline(List<Booking> bookings) {
        Map<String, TimelineAgg> map = new LinkedHashMap<>();
        for (Booking booking : bookings) {
            LocalDate bookingDate = booking.getBookingDate();
            if (bookingDate == null) {
                continue;
            }
            String key = bookingDate.format(MONTH_FORMAT);
            TimelineAgg agg = map.computeIfAbsent(key, ignored -> new TimelineAgg());
            agg.bookings++;
            agg.revenue = agg.revenue.add(money(booking.getCustomerAmount()));
        }

        LocalDate now = LocalDate.now().withDayOfMonth(1);
        List<DashboardAnalyticsResponse.RevenueTimelinePoint> result = new ArrayList<>();
        for (int i = 5; i >= 0; i--) {
            String key = now.minusMonths(i).format(MONTH_FORMAT);
            TimelineAgg agg = map.getOrDefault(key, new TimelineAgg());
            result.add(DashboardAnalyticsResponse.RevenueTimelinePoint.builder()
                    .month(key)
                    .revenue(agg.revenue)
                    .bookings(agg.bookings)
                    .build());
        }
        return result;
    }

    private List<DashboardAnalyticsResponse.PerformerConversion> topPerformersByConversion(
            List<PerformerAgg> performers) {
        List<PerformerAgg> sorted = performers.stream()
                .sorted((left, right) -> Long.compare(right.conversions, left.conversions))
                .limit(5)
                .toList();

        List<DashboardAnalyticsResponse.PerformerConversion> result = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            PerformerAgg performer = sorted.get(i);
            result.add(DashboardAnalyticsResponse.PerformerConversion.builder()
                    .rank(i + 1)
                    .name(performer.name)
                    .leads(performer.leads)
                    .conversions(performer.conversions)
                    .revenue(performer.revenue)
                    .profit(performer.profit)
                    .rate(percent(performer.conversions, performer.leads, 2))
                    .tier(tier(i))
                    .build());
        }
        return result;
    }

    private List<DashboardAnalyticsResponse.PerformerProfit> topPerformersByProfit(
            List<PerformerAgg> performers) {
        List<PerformerAgg> sorted = performers.stream()
                .sorted((left, right) -> right.profit.compareTo(left.profit))
                .limit(5)
                .toList();

        List<DashboardAnalyticsResponse.PerformerProfit> result = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            PerformerAgg performer = sorted.get(i);
            result.add(DashboardAnalyticsResponse.PerformerProfit.builder()
                    .rank(i + 1)
                    .name(performer.name)
                    .leads(performer.leads)
                    .conversions(performer.conversions)
                    .revenue(performer.revenue)
                    .profit(performer.profit)
                    .margin(percent(performer.profit, performer.revenue, 1))
                    .converted(performer.conversions)
                    .tier(tier(i))
                    .build());
        }
        return result;
    }

    private List<PerformerAgg> performers(List<Lead> leads, List<Booking> bookings) {
        Map<String, PerformerAgg> map = new LinkedHashMap<>();
        for (Lead lead : leads) {
            String name = leadAssigneeName(lead);
            PerformerAgg agg = map.computeIfAbsent(name, PerformerAgg::new);
            agg.leads++;
            if (lead.getLeadStage() == LeadStage.CONVERTED) {
                agg.conversions++;
            }
        }

        BookingAssigneeView assignees = bookings.isEmpty()
                ? BookingAssigneeView.empty()
                : assigneeViewFactory.of(bookings);
        for (Booking booking : bookings) {
            String name = textOr(assignees.nameOf(booking.getAssignedUserId()), "Unassigned");
            PerformerAgg agg = map.computeIfAbsent(name, PerformerAgg::new);
            agg.revenue = agg.revenue.add(money(booking.getCustomerAmount()));
            agg.profit = agg.profit.add(money(booking.getNetProfit()));
        }
        return new ArrayList<>(map.values());
    }

    private List<DashboardAnalyticsResponse.PriorityFollowup> priorityFollowups(Long tenantId) {
        LocalDate today = LocalDate.now();
        Instant endOfToday = today.atTime(LocalTime.MAX).atZone(ZONE).toInstant();
        Instant startOfToday = today.atStartOfDay(ZONE).toInstant();
        List<Reminder> reminders = reminderRepository
                .findTop5ByTenantIdAndStatusInAndDueDateLessThanEqualAndDeletedAtIsNullOrderByDueDateAsc(
                        tenantId, List.of(ReminderStatus.Active, ReminderStatus.OVERDUE), endOfToday);

        return reminders.stream()
                .map(reminder -> DashboardAnalyticsResponse.PriorityFollowup.builder()
                        .name(textOr(reminder.getLeadName(), textOr(reminder.getTitle(), "Lead")))
                        .phone(textOr(reminder.getPhone(), ""))
                        .note(textOr(reminder.getDescription(), textOr(reminder.getNotes(), "Follow-up required")))
                        .dueDate(formatDateTime(reminder.getDueDate()))
                        .urgency(isOverdue(reminder, startOfToday) ? "Overdue" : "Today")
                        .build())
                .toList();
    }

    private List<DashboardAnalyticsResponse.NearTravelDate> nearTravelDates(Long tenantId) {
        LocalDate today = LocalDate.now();
        List<Lead> leads = leadRepository.findUpcomingTravelLeads(tenantId, today, today.plusDays(10));
        return leads.stream()
                .filter(lead -> lead.getLeadStage() != LeadStage.CONVERTED && lead.getLeadStage() != LeadStage.LOST)
                .limit(5)
                .map(lead -> DashboardAnalyticsResponse.NearTravelDate.builder()
                        .name(textOr(lead.getCustomerName(), "Lead"))
                        .phone(textOr(lead.getPhone(), ""))
                        .travelDate(lead.getTravelDate() == null ? "" : lead.getTravelDate().format(DATE_FORMAT))
                        .stage(stageName(lead.getLeadStage()))
                        .daysLeft(lead.getTravelDate() == null
                                ? 0
                                : ChronoUnit.DAYS.between(today, lead.getTravelDate()))
                        .build())
                .toList();
    }

    private boolean isActiveBooking(Booking booking) {
        return booking.getStatus() != BookingStatus.CANCELLED
                && booking.getStatus() != BookingStatus.REFUNDED;
    }

    private boolean isWonBooking(Booking booking) {
        return booking.getStatus() == BookingStatus.CONFIRMED
                || booking.getStatus() == BookingStatus.COMPLETED;
    }

    private String leadAssigneeName(Lead lead) {
        if (lead.getAssignedUser() == null) {
            return "Unassigned";
        }
        return textOr(lead.getAssignedUser().getName(), "Unassigned");
    }

    private String sourceName(LeadSource source) {
        return source == null ? "Other" : source.getDisplayName();
    }

    private String stageName(LeadStage stage) {
        return stage == null ? "" : stage.getDisplayName();
    }

    private String tier(int index) {
        return switch (index) {
            case 0 -> "gold";
            case 1 -> "silver";
            case 2 -> "bronze";
            default -> null;
        };
    }

    private boolean isOverdue(Reminder reminder, Instant startOfToday) {
        return reminder.getStatus() == ReminderStatus.OVERDUE
                || (reminder.getDueDate() != null && reminder.getDueDate().isBefore(startOfToday));
    }

    private String formatDateTime(Instant instant) {
        return instant == null ? "" : DATE_TIME_FORMAT.format(instant.atZone(ZONE));
    }

    private BigDecimal sum(List<Booking> bookings, MoneyGetter getter) {
        return bookings.stream()
                .map(getter::get)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private double percent(long numerator, long denominator, int scale) {
        if (denominator <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), scale, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private double percent(BigDecimal numerator, BigDecimal denominator, int scale) {
        if (denominator == null || denominator.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        return money(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(denominator, scale, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private Long requireTenant() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("TenantContext is empty - cannot read dashboard analytics.");
        }
        return tenantId;
    }

    @FunctionalInterface
    private interface MoneyGetter {
        BigDecimal get(Booking booking);
    }

    private static final class DestinationAgg {
        private long bookings;
        private BigDecimal revenue = BigDecimal.ZERO;
    }

    private static final class TimelineAgg {
        private long bookings;
        private BigDecimal revenue = BigDecimal.ZERO;
    }

    private static final class PerformerAgg {
        private final String name;
        private long leads;
        private long conversions;
        private BigDecimal revenue = BigDecimal.ZERO;
        private BigDecimal profit = BigDecimal.ZERO;

        private PerformerAgg(String name) {
            this.name = name;
        }
    }
}
