package com.crm.travelcrm.calendar.service;

import com.crm.travelcrm.auth.api.CurrentUserProvider;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.enums.BookingStatus;
import com.crm.travelcrm.calendar.dto.CalendarEvent;
import com.crm.travelcrm.calendar.dto.CalendarSummaryDto;
import com.crm.travelcrm.calendar.dto.ServiceItemCalendarRow;
import com.crm.travelcrm.calendar.dto.SumCount;
import com.crm.travelcrm.calendar.enums.CalendarSource;
import com.crm.travelcrm.calendar.repository.CalendarBookingQueryRepository;
import com.crm.travelcrm.calendar.repository.CalendarLeadQueryRepository;
import com.crm.travelcrm.calendar.repository.CalendarReminderQueryRepository;
import com.crm.travelcrm.calendar.repository.CalendarServiceItemQueryRepository;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.permission.service.SubAgentScope;
import com.crm.travelcrm.reminder.entity.Reminder;
import com.crm.travelcrm.reminder.entity.ReminderStatus;
import com.crm.travelcrm.task.entity.Task;
import com.crm.travelcrm.task.repository.TaskRepository;
import com.crm.travelcrm.task.specification.TaskSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class CalendarServiceImpl implements CalendarService {

    private final TaskRepository taskRepository;
    private final CalendarReminderQueryRepository reminderQueryRepository;
    private final CalendarBookingQueryRepository bookingQueryRepository;
    private final CalendarServiceItemQueryRepository serviceItemQueryRepository;
    private final CalendarLeadQueryRepository leadQueryRepository;
    private final SubAgentScope subAgentScope;
    private final CurrentUserProvider currentUserProvider;
    private final UserRepository userRepository;

    /** Statuses that count as a real trip on the calendar (a booking that still travels). */
    private static final List<BookingStatus> TRIP_STATUSES =
            List.of(BookingStatus.CONFIRMED, BookingStatus.PENDING, BookingStatus.COMPLETED);
    /** Statuses that carry no live balance / trip (excluded from revenue + payment-due). */
    private static final List<BookingStatus> DEAD_STATUSES =
            List.of(BookingStatus.CANCELLED, BookingStatus.REFUNDED);

    private static final Comparator<CalendarEvent> BY_START =
            Comparator.comparing(CalendarEvent::getStart,
                    Comparator.nullsLast(Comparator.naturalOrder()));

    // ── Feed ─────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<CalendarEvent> getEvents(Instant from, Instant to, String categories,
                                         UUID assignee, boolean mine) {
        Long tenantId = requireTenantId();
        if (from == null || to == null) {
            LocalDate monthStart = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
            from = monthStart.atStartOfDay(ZoneOffset.UTC).toInstant();
            to = monthStart.plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        }

        Long ownerFilter = subAgentScope.ownerFilter();       // sub-agent's own id, else null
        boolean subAgent = ownerFilter != null;
        Set<String> authorities = currentAuthorities();
        Set<CalendarSource> sourceFilter = parseSources(categories);

        Long assigneeUserId = mine ? requireCurrentUserId() : resolveAssignee(assignee, tenantId);

        // Reminders need REMINDER_READ; booking-derived events need BOOKING_READ, are hidden from
        // sub-agents entirely, and are dropped when the feed is narrowed to one person (they have no
        // assignee dimension).
        boolean includeReminders = authorities.contains("REMINDER_READ");
        boolean includeBookingDerived =
                !subAgent && assigneeUserId == null && authorities.contains("BOOKING_READ");

        return assemble(tenantId, from, to, ownerFilter, assigneeUserId, sourceFilter,
                includeReminders, includeBookingDerived);
    }

    // ── Summary ──────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public CalendarSummaryDto getSummary(LocalDate date) {
        Long tenantId = requireTenantId();
        LocalDate refDate = date != null ? date : LocalDate.now(ZoneOffset.UTC);

        Instant dayStart = refDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dayEnd = refDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        // Lead.createdAt is a @CreatedDate LocalDateTime stamped in the JVM's default zone (no UTC
        // DateTimeProvider is configured), so map the UTC day window into that same wall-clock zone
        // before comparing — otherwise a non-UTC server miscounts new-leads-today near midnight.
        LocalDateTime leadFrom = LocalDateTime.ofInstant(dayStart, ZoneId.systemDefault());
        LocalDateTime leadTo = LocalDateTime.ofInstant(dayEnd.minusNanos(1), ZoneId.systemDefault());
        LocalDate monthStart = refDate.withDayOfMonth(1);
        LocalDate monthEndExcl = monthStart.plusMonths(1);

        // Tenant-wide events for the reference day (all sources) — drives the right-rail lists.
        List<CalendarEvent> today = assemble(tenantId, dayStart, dayEnd, null, null, null, true, true);

        SumCount outstanding = bookingQueryRepository.sumOutstanding(tenantId, DEAD_STATUSES);
        SumCount overdue = bookingQueryRepository.sumOverdueBefore(tenantId, DEAD_STATUSES, refDate);
        SumCount dueToday = bookingQueryRepository.sumDueOn(tenantId, DEAD_STATUSES, refDate);

        return CalendarSummaryDto.builder()
                .date(refDate)
                .todaysFollowups(countActiveFollowups(today))
                .activeTrips(bookingQueryRepository
                        .countByTenantIdAndStatusAndTravelDateGreaterThanEqualAndDeletedAtIsNull(
                                tenantId, BookingStatus.CONFIRMED, refDate))
                .paymentsDueAmount(nz(outstanding.amount()))
                .paymentsDueCount(nz(outstanding.count()))
                .flightsToday(countSource(today, CalendarSource.FLIGHT))
                .hotelCheckinsToday(countSource(today, CalendarSource.HOTEL_CHECKIN))
                .visaToday(countSource(today, CalendarSource.VISA))
                .newLeadsToday(leadQueryRepository
                        .countByTenantIdAndCreatedAtBetweenAndDeletedAtIsNull(tenantId, leadFrom, leadTo))
                .revenueThisMonth(nz(bookingQueryRepository
                        .sumRevenueBetween(tenantId, DEAD_STATUSES, monthStart, monthEndExcl)))
                .todaysSchedule(today)
                .paymentDueSummary(CalendarSummaryDto.PaymentDueSummary.builder()
                        .totalOverdue(nz(overdue.amount()))
                        .overdueCount(nz(overdue.count()))
                        .dueToday(nz(dueToday.amount()))
                        .dueTodayCount(nz(dueToday.count()))
                        .build())
                .tripsStartingToday(filterSource(today, CalendarSource.TRIP))
                .flightDepartures(filterSource(today, CalendarSource.FLIGHT))
                .hotelCheckins(filterSource(today, CalendarSource.HOTEL_CHECKIN))
                .visaAppointments(filterSource(today, CalendarSource.VISA))
                .build();
    }

    // ── Assembly ─────────────────────────────────────────────────────────────────────────────

    private List<CalendarEvent> assemble(Long tenantId, Instant from, Instant to,
                                         Long ownerFilter, Long assigneeUserId,
                                         Set<CalendarSource> filter,
                                         boolean includeReminders, boolean includeBookingDerived) {
        // The window is half-open [from, to). Callers pass `to` as a start-of-day/period boundary
        // (e.g. tomorrow 00:00, or the 1st of next month), so derive an INCLUSIVE upper bound one
        // instant earlier: without this, the day-granular booking/service-item BETWEEN queries (and
        // the Instant BETWEEN on tasks/reminders) bleed the next day/month into the results.
        Instant toInclusive = to.minusNanos(1);
        LocalDate fromDate = from.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate toDate = toInclusive.atZone(ZoneOffset.UTC).toLocalDate();

        List<CalendarEvent> events = new ArrayList<>();

        if (includeSource(filter, CalendarSource.TASK)) {
            events.addAll(taskEvents(tenantId, from, toInclusive, ownerFilter, assigneeUserId));
        }
        if (includeReminders && includeSource(filter, CalendarSource.REMINDER)) {
            events.addAll(reminderEvents(tenantId, from, toInclusive, ownerFilter, assigneeUserId));
        }
        if (includeBookingDerived) {
            boolean trip = includeSource(filter, CalendarSource.TRIP);
            boolean pay = includeSource(filter, CalendarSource.PAYMENT_DUE);
            if (trip || pay) {
                events.addAll(bookingEvents(tenantId, fromDate, toDate, trip, pay));
            }
            events.addAll(serviceItemEvents(tenantId, fromDate, toDate, filter));
        }

        events.sort(BY_START);
        return events;
    }

    private List<CalendarEvent> taskEvents(Long tenantId, Instant from, Instant to,
                                           Long ownerFilter, Long assigneeUserId) {
        Specification<Task> spec = TaskSpecification.build(
                tenantId, null, null, null, assigneeUserId, from, to);
        if (ownerFilter != null) {
            spec = spec.and(TaskSpecification.ownedBy(ownerFilter));
        }
        return taskRepository.findAll(spec).stream().map(this::toTaskEvent).toList();
    }

    private List<CalendarEvent> reminderEvents(Long tenantId, Instant from, Instant to,
                                               Long ownerFilter, Long assigneeUserId) {
        List<Reminder> reminders = reminderQueryRepository
                .findByTenantIdAndDueDateBetweenAndDeletedAtIsNull(tenantId, from, to);
        return reminders.stream()
                .filter(r -> ownerFilter == null || ownerFilter.equals(r.getOwnerUserId()))
                .filter(r -> assigneeUserId == null || assigneeUserId.equals(r.getAssignToUserId()))
                .map(this::toReminderEvent)
                .toList();
    }

    private List<CalendarEvent> bookingEvents(Long tenantId, LocalDate fromDate, LocalDate toDate,
                                              boolean includeTrip, boolean includePayment) {
        List<Booking> bookings = bookingQueryRepository
                .findByTenantIdAndStatusInAndTravelDateBetweenAndDeletedAtIsNull(
                        tenantId, TRIP_STATUSES, fromDate, toDate);
        List<CalendarEvent> events = new ArrayList<>();
        for (Booking b : bookings) {
            if (includeTrip) {
                events.add(toTripEvent(b));
            }
            if (includePayment) {
                BigDecimal pending = pendingAmount(b);
                if (pending.signum() > 0) {
                    events.add(toPaymentDueEvent(b, pending));
                }
            }
        }
        return events;
    }

    private List<CalendarEvent> serviceItemEvents(Long tenantId, LocalDate fromDate, LocalDate toDate,
                                                  Set<CalendarSource> filter) {
        List<String> types = new ArrayList<>();
        if (includeSource(filter, CalendarSource.FLIGHT)) types.add("flight");
        if (includeSource(filter, CalendarSource.HOTEL_CHECKIN)) types.add("hotel");
        if (includeSource(filter, CalendarSource.VISA)) types.add("visa");
        if (types.isEmpty()) return List.of();

        return serviceItemQueryRepository
                .findServiceEvents(tenantId, fromDate, toDate, types)
                .stream().map(this::toServiceEvent).toList();
    }

    // ── Event mappers ────────────────────────────────────────────────────────────────────────

    private CalendarEvent toTaskEvent(Task t) {
        return CalendarEvent.builder()
                .id("TASK:" + t.getPublicId())
                .source(CalendarSource.TASK)
                .category(t.getCategory() != null ? t.getCategory().label() : "Task")
                .title(t.getTitle())
                .subtitle(t.getLocation() != null ? t.getLocation() : t.getLeadName())
                .start(t.calendarInstant())
                .end(t.getEndAt())
                .allDay(t.isAllDay())
                .priority(t.getPriority() != null ? t.getPriority().name() : null)
                .status(t.getStatus() != null ? t.getStatus().name() : null)
                .referenceType("TASK")
                .referencePublicId(t.getPublicId())
                .assigneeName(t.getAssignToName())
                .editable(true)
                .build();
    }

    private CalendarEvent toReminderEvent(Reminder r) {
        return CalendarEvent.builder()
                .id("REMINDER:" + r.getPublicId())
                .source(CalendarSource.REMINDER)
                .category(CalendarSource.REMINDER.label())
                .title(r.getTitle())
                .subtitle(r.getLeadName())
                .start(r.getDueDate())
                .allDay(false)
                .priority(r.getPriority() != null ? r.getPriority().name() : null)
                .status(r.getStatus() != null ? r.getStatus().name() : null)
                .referenceType("REMINDER")
                .referencePublicId(r.getPublicId())
                .assigneeName(r.getAssignToName())
                .editable(true)
                .build();
    }

    private CalendarEvent toTripEvent(Booking b) {
        return CalendarEvent.builder()
                .id("TRIP:" + b.getPublicId())
                .source(CalendarSource.TRIP)
                .category(CalendarSource.TRIP.label())
                .title(tripTitle(b))
                .subtitle(b.getCustomerNameSnapshot())
                .start(dayInstant(b.getTravelDate()))
                .allDay(true)
                .status(b.getStatus() != null ? b.getStatus().name() : null)
                .referenceType("BOOKING")
                .referencePublicId(b.getPublicId())
                .editable(false)
                .build();
    }

    private CalendarEvent toPaymentDueEvent(Booking b, BigDecimal pending) {
        return CalendarEvent.builder()
                .id("PAYMENT_DUE:" + b.getPublicId())
                .source(CalendarSource.PAYMENT_DUE)
                .category(CalendarSource.PAYMENT_DUE.label())
                .title("Payment Due")
                .subtitle(b.getCustomerNameSnapshot())
                .start(dayInstant(b.getTravelDate()))
                .allDay(true)
                .amount(pending)
                .status(b.getPaymentStatus() != null ? b.getPaymentStatus().name() : null)
                .referenceType("BOOKING")
                .referencePublicId(b.getPublicId())
                .editable(false)
                .build();
    }

    private CalendarEvent toServiceEvent(ServiceItemCalendarRow row) {
        String type = row.serviceType() == null ? "" : row.serviceType().toLowerCase();
        CalendarSource source = switch (type) {
            case "flight" -> CalendarSource.FLIGHT;
            case "hotel" -> CalendarSource.HOTEL_CHECKIN;
            default -> CalendarSource.VISA;
        };
        return CalendarEvent.builder()
                .id(source.name() + ":" + row.serviceItemPublicId())
                .source(source)
                .category(source.label())
                .title(row.title() != null ? row.title() : source.label())
                .subtitle(joinNonBlank(row.customerName(), row.destination()))
                .start(dayInstant(row.serviceDate()))
                .end(row.endDate() != null ? dayInstant(row.endDate()) : null)
                .allDay(true)
                .status(row.status() != null ? row.status().name() : null)
                .referenceType("BOOKING")
                .referencePublicId(row.bookingPublicId())
                .editable(false)
                .build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────

    private static long countActiveFollowups(List<CalendarEvent> events) {
        return events.stream()
                .filter(e -> e.getSource() == CalendarSource.REMINDER)
                .filter(e -> !ReminderStatus.Completed.name().equals(e.getStatus())
                        && !ReminderStatus.Dismissed.name().equals(e.getStatus()))
                .count();
    }

    private static long countSource(List<CalendarEvent> events, CalendarSource source) {
        return events.stream().filter(e -> e.getSource() == source).count();
    }

    private static List<CalendarEvent> filterSource(List<CalendarEvent> events, CalendarSource source) {
        return events.stream().filter(e -> e.getSource() == source).toList();
    }

    private static String tripTitle(Booking b) {
        String dest = b.getDestinationSnapshot();
        if (dest != null && !dest.isBlank()) return dest;
        return b.getBookingCode() != null ? "Booking " + b.getBookingCode() : "Trip";
    }

    /**
     * Delegates to {@code Booking.getPendingAmount()} rather than re-deriving
     * {@code totalPayable − paidAmount} here. This was a fourth private copy of that formula, and
     * copies drift: the entity's version is status-aware (a cancelled booking carries no live
     * balance) and floored at zero, and this one was neither. It survived only because
     * {@code TRIP_STATUSES} happens to exclude cancelled bookings upstream — a coincidence, not a
     * guarantee.
     */
    private static BigDecimal pendingAmount(Booking b) {
        return b.getPendingAmount();
    }

    private static Instant dayInstant(LocalDate date) {
        return date == null ? null : date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static String joinNonBlank(String a, String b) {
        boolean ha = a != null && !a.isBlank();
        boolean hb = b != null && !b.isBlank();
        if (ha && hb) return a + " · " + b;
        if (ha) return a;
        return hb ? b : null;
    }

    private static boolean includeSource(Set<CalendarSource> filter, CalendarSource source) {
        return filter == null || filter.contains(source);
    }

    private Set<CalendarSource> parseSources(String csv) {
        if (csv == null || csv.isBlank()) return null;
        Set<CalendarSource> out = java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(this::parseSource)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(() -> java.util.EnumSet.noneOf(CalendarSource.class)));
        return out;   // empty set ⇒ caller filtered everything out (no events)
    }

    private CalendarSource parseSource(String token) {
        try {
            return CalendarSource.valueOf(token.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Long resolveAssignee(UUID assigneePublicId, Long tenantId) {
        if (assigneePublicId == null) return null;
        return userRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(assigneePublicId, tenantId)
                .map(User::getId)
                .orElse(-1L);   // unknown user ⇒ a sentinel that matches nothing (empty feed)
    }

    private Set<String> currentAuthorities() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) return Set.of();
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("TenantContext is empty — no tenant bound to this request");
        }
        return tenantId;
    }

    private Long requireCurrentUserId() {
        Long id = currentUserProvider.currentUserIdOrNull();
        if (id == null) {
            throw new IllegalStateException("The calendar is available to tenant users only");
        }
        return id;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static long nz(Long v) {
        return v != null ? v : 0L;
    }
}