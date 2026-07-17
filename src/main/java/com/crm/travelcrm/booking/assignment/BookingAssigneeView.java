package com.crm.travelcrm.booking.assignment;

import com.crm.travelcrm.auth.entity.User;

import java.util.Map;
import java.util.UUID;

/**
 * An immutable snapshot of "internal user id → who that is", built ONCE per response and handed to
 * {@code BookingMapper} as a {@code @Context} so it can render a booking's assignee without a
 * repository of its own.
 *
 * <p><b>Why this exists rather than a {@code @ManyToOne User} on {@link
 * com.crm.travelcrm.booking.entity.Booking}.</b> {@code getAll} maps a whole PAGE of bookings to
 * full responses; a lazy association there is a per-row query. This resolves every assignee on the
 * page in one batched read (see {@link BookingAssigneeViewFactory}), so the cost is O(1) queries
 * regardless of page size — and Booking keeps its existing convention of plain Long logical FKs.
 *
 * <p>Unknown ids resolve to {@code null} rather than throwing: a booking can outlive the user it was
 * assigned to (hard-deleted, or moved tenant), and a detail page must still render.
 */
public final class BookingAssigneeView {

    private static final BookingAssigneeView EMPTY = new BookingAssigneeView(Map.of());

    private final Map<Long, User> byId;

    BookingAssigneeView(Map<Long, User> byId) {
        this.byId = Map.copyOf(byId);
    }

    /** A view that knows nobody — every lookup yields null. */
    public static BookingAssigneeView empty() {
        return EMPTY;
    }

    /** The assignee's publicId (never the internal Long), or null if unassigned/unknown. */
    public UUID publicIdOf(Long userId) {
        User u = user(userId);
        return u == null ? null : u.getPublicId();
    }

    /** The assignee's display name, or null if unassigned/unknown. */
    public String nameOf(Long userId) {
        User u = user(userId);
        return u == null ? null : u.getName();
    }

    private User user(Long userId) {
        return userId == null ? null : byId.get(userId);
    }
}
