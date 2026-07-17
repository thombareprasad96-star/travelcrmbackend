package com.crm.travelcrm.booking.assignment;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.booking.entity.Booking;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds a {@link BookingAssigneeView} for one booking or a whole page, in a single batched query.
 *
 * <p>The read is tenant-scoped explicitly ({@code findByIdInAndTenantId}) rather than relying on the
 * Hibernate filter: {@code User} carries {@code tenant_id} on {@code BaseEntity}, not
 * {@code BaseTenantEntity}, so {@code tenantFilter} does not apply to it. An id from another tenant
 * therefore returns nothing and renders a blank name — never a foreign user's.
 *
 * <p><b>The tenant comes from the BOOKINGS, never from {@code TenantContext}.</b> This is not
 * stylistic. Both create paths publish a notification before rendering their response, and
 * {@code NotifyEventListener} is a synchronous {@code @EventListener} that calls
 * {@code TenantContext.clear()} in a {@code finally} — on the <i>publisher's own thread</i>
 * ({@code NotifyEventListener:38}). So by the time a response is mapped, the ThreadLocal is
 * already empty, and a factory that read it would resolve nothing and silently render a blank
 * assignee on every freshly-created booking. That is exactly the bug this comment replaces: caught
 * by an end-to-end conversion, invisible to unit tests, and invisible again to any reader who
 * assumes the ThreadLocal survives the method it was set in. The booking is a
 * {@code BaseTenantEntity} and carries the authoritative tenant itself; use that.
 */
@Component
@RequiredArgsConstructor
public class BookingAssigneeViewFactory {

    private final UserRepository userRepository;

    /** View covering a single booking's assignee. */
    @Transactional(readOnly = true)
    public BookingAssigneeView of(Booking booking) {
        return booking == null ? BookingAssigneeView.empty() : of(Set.of(booking));
    }

    /** View covering every distinct assignee across {@code bookings} — one query, page-size agnostic. */
    @Transactional(readOnly = true)
    public BookingAssigneeView of(Collection<Booking> bookings) {
        Set<Long> ids = bookings.stream()
                .map(Booking::getAssignedUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return BookingAssigneeView.empty();
        }

        Map<Long, User> byId = userRepository.findByIdInAndTenantId(ids, tenantOf(bookings)).stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));
        return new BookingAssigneeView(byId);
    }

    /**
     * The tenant these bookings belong to, taken from the rows themselves.
     *
     * <p>Every path that renders a list has already scoped it to one tenant, so a mixed set is an
     * isolation failure upstream, not something to paper over with a "pick the first" — it throws.
     */
    private Long tenantOf(Collection<Booking> bookings) {
        Set<Long> tenants = bookings.stream()
                .map(Booking::getTenantId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (tenants.size() != 1) {
            throw new IllegalStateException(
                    "Refusing to resolve booking assignees across " + tenants.size()
                            + " tenants — every rendered set must be scoped to exactly one.");
        }
        return tenants.iterator().next();
    }
}
