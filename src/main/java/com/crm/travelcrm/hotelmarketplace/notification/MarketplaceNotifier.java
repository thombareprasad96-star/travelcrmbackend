package com.crm.travelcrm.hotelmarketplace.notification;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.hotelmarketplace.booking.entity.PlatformHotelBooking;
import com.crm.travelcrm.notification.api.NotifyEvent;
import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;
import com.crm.travelcrm.platform.notification.api.PlatformNotificationType;
import com.crm.travelcrm.platform.notification.api.PlatformNotifyEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Every notification the marketplace sends — to the tenant that placed the order, and to the
 * SuperAdmin queue that has to act on it. One place, so the two audiences can be reasoned about
 * side by side and the money rule below can actually be enforced by reading a single file.
 *
 * <h2>Publish AFTER any {@code TenantScope} block closes, never inside one</h2>
 * {@code @EventListener} is synchronous, so {@code publishEvent} runs the notification listener on
 * <em>this</em> thread before returning. That listener mutates {@code TenantContext} for the
 * duration of the fan-out. It now saves and restores rather than clearing, so a mid-scope publish is
 * no longer catastrophic — but the scope's tenant would still be swapped out underneath work in
 * progress, and one channel throwing inside a tenant-scoped transaction is a rollback of that
 * transaction. Publish last, when the durable state is already committed.
 *
 * <h2>The money rule</h2>
 * A tenant-facing message may name {@code tenantPayable} (and the revised/refund figures derived
 * from it) and <b>nothing else</b>. {@code supplierTotal} and {@code platformEarning} are the
 * platform's own economics; a notification is the easiest place in the system to leak them, because
 * it is prose rather than a mapped DTO and no DTO test covers it. Nothing here reads either field —
 * keep it that way.
 *
 * <h2>No {@code actorUserId}, ever</h2>
 * The actor on almost every marketplace transition is a SuperAdmin, whose id lives in a different
 * table and therefore a different number space from tenant {@code User} ids. Setting it would make
 * the in-app channel's ACTOR RULE drop the notification for whichever tenant user happens to share
 * that number — silently, and only for that one user.
 *
 * <h2>Failure is swallowed</h2>
 * A booking that is confirmed with a supplier must not be un-confirmed because a notification row
 * could not be written. Each publish is guarded and logged; the durable state is the platform row.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketplaceNotifier {

    /** The fallback audience when the submitter is unknown — the same pair the portal reminders use. */
    private static final List<String> FALLBACK_ROLES = List.of("TENANT_ADMIN", "MANAGER");

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_TIME =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH);

    private final ApplicationEventPublisher eventPublisher;
    private final UserRepository userRepository;

    // ── Tenant-facing ───────────────────────────────────────────────────────────────────────────

    /** The SuperAdmin approved it: the room is held and the payable is now owed. */
    public void bookingConfirmed(PlatformHotelBooking b) {
        publishToTenant(b, MarketplaceNotificationType.HOTEL_BOOKING_CONFIRMED, () -> {
            String payable = money(b.getCurrency(), b.getTenantPayable());
            return tenantEvent(b, MarketplaceNotificationType.HOTEL_BOOKING_CONFIRMED,
                    "Hotel confirmed — " + b.getHotelNameSnapshot(),
                    stay(b) + " is confirmed."
                            + (payable == null ? "" : " Payable to the platform: " + payable + ".")
                            + (blank(b.getSupplierConfirmationNumber()) ? ""
                                    : " Hotel reference " + b.getSupplierConfirmationNumber() + ".")
                            + ref(b));
        });
    }

    /**
     * Declined. The message names both remedies on purpose — the CRM booking is deliberately left
     * standing (§8 decision 2), which is only useful if the tenant knows they may re-use it.
     */
    public void bookingRejected(PlatformHotelBooking b) {
        publishToTenant(b, MarketplaceNotificationType.HOTEL_BOOKING_REJECTED, () ->
                tenantEvent(b, MarketplaceNotificationType.HOTEL_BOOKING_REJECTED,
                        "Hotel request declined — " + b.getHotelNameSnapshot(),
                        stay(b) + " could not be confirmed."
                                + (blank(b.getRejectionReason()) ? ""
                                        : " Reason: " + b.getRejectionReason().trim())
                                + " Nothing has been charged. Choose another hotel for this trip, or"
                                + " delete the booking if it is no longer needed."
                                + ref(b)));
    }

    /**
     * The price moved and the tenant owes an answer. Both figures are named because "the price has
     * changed, please review" sends the tenant hunting for a number the notification already had.
     */
    public void revisionRequired(PlatformHotelBooking b) {
        publishToTenant(b, MarketplaceNotificationType.HOTEL_BOOKING_REVISION_REQUIRED, () -> {
            String from = money(b.getCurrency(), firstNonNull(
                    b.getRevisionPreviousPayable(), b.getTenantPayable(), b.getQuotedTenantPayable()));
            String to = money(b.getCurrency(), b.getRevisedTenantPayable());
            String movement = (from != null && to != null)
                    ? " The payable moves from " + from + " to " + to + "."
                    : (to != null ? " The revised payable is " + to + "." : "");
            return tenantEvent(b, MarketplaceNotificationType.HOTEL_BOOKING_REVISION_REQUIRED,
                    "Price changed — your approval is needed",
                    stay(b) + " is available at a different price." + movement
                            + " Nothing is booked until you accept."
                            + (b.getRevisionExpiresAt() == null ? ""
                                    : " This offer lapses on " + DAY_TIME.format(b.getRevisionExpiresAt()) + ".")
                            + ref(b));
        });
    }

    /** The offer lapsed unanswered. Says plainly that nothing was booked and nothing was charged. */
    public void revisionExpired(PlatformHotelBooking b) {
        publishToTenant(b, MarketplaceNotificationType.HOTEL_BOOKING_REVISION_EXPIRED, () -> {
            String offered = money(b.getCurrency(), b.getRevisedTenantPayable());
            return tenantEvent(b, MarketplaceNotificationType.HOTEL_BOOKING_REVISION_EXPIRED,
                    "Revised price expired — " + b.getHotelNameSnapshot(),
                    "The revised price" + (offered == null ? "" : " of " + offered) + " for " + stay(b)
                            + " was not accepted in time and has lapsed. Nothing was booked and you"
                            + " have not been charged. Submit a fresh request if you still want the room."
                            + ref(b));
        });
    }

    /** Settled cancellation. The charge and the refund are the only two numbers that matter here. */
    public void bookingCancelled(PlatformHotelBooking b) {
        publishToTenant(b, MarketplaceNotificationType.HOTEL_BOOKING_CANCELLED, () -> {
            BigDecimal charge = b.getCancellationCharge();
            String chargeText = (charge == null || charge.signum() <= 0)
                    ? " There is no cancellation charge."
                    : " Cancellation charge: " + money(b.getCurrency(), charge) + ".";
            String refund = money(b.getCurrency(), b.getTenantRefundAmount());
            return tenantEvent(b, MarketplaceNotificationType.HOTEL_BOOKING_CANCELLED,
                    "Hotel booking cancelled — " + b.getHotelNameSnapshot(),
                    stay(b) + " is cancelled." + chargeText
                            + (refund == null ? "" : " Due back to you: " + refund + ".")
                            + (blank(b.getCancellationReason()) ? ""
                                    : " Reason: " + b.getCancellationReason().trim())
                            + ref(b));
        });
    }

    /** The guest-facing document exists. Deliberately separate from confirmation — it can be revoked. */
    public void voucherIssued(PlatformHotelBooking b) {
        publishToTenant(b, MarketplaceNotificationType.HOTEL_BOOKING_VOUCHER_ISSUED, () ->
                tenantEvent(b, MarketplaceNotificationType.HOTEL_BOOKING_VOUCHER_ISSUED,
                        "Voucher ready — " + b.getHotelNameSnapshot(),
                        "Voucher " + (blank(b.getVoucherNumber()) ? "" : b.getVoucherNumber() + " ")
                                + "for " + stay(b) + " is ready to download and share with your guest."
                                + ref(b)));
    }

    /**
     * Confirmed with the supplier, but not yet visible on the CRM booking. The point of telling the
     * tenant at all is to stop a second booking of a room they already hold.
     *
     * <p>Goes to the submitter <b>and</b> the tenant's admins: this is a data-integrity alert, not
     * an update on someone's order. The stored {@code crmSyncError} is deliberately not repeated —
     * it is an internal stack-level string, and it is already in the log next to the booking code.
     */
    public void crmSyncFailed(PlatformHotelBooking b) {
        log.warn("Marketplace booking {} confirmed but CRM projection failed ({} attempt(s)): {}",
                b.getBookingCode(), b.getCrmSyncAttempts(), b.getCrmSyncError());
        publishToTenant(b, MarketplaceNotificationType.HOTEL_BOOKING_SYNC_FAILED, () ->
                NotifyEvent.builder()
                        .type(MarketplaceNotificationType.HOTEL_BOOKING_SYNC_FAILED)
                        .tenantId(b.getTenantId())
                        .recipientUserIds(submitterAndAdmins(b))
                        .title("Hotel booking not linked yet — " + b.getHotelNameSnapshot())
                        .message(stay(b) + " is confirmed with the hotel, but has not appeared on the"
                                + " CRM booking yet. The room is held — please do not book it again."
                                + " We are retrying automatically." + ref(b))
                        .referenceType(MarketplaceNotificationType.REF_HOTEL_BOOKING)
                        .referencePublicId(b.getPublicId())
                        .channels(Set.of(DeliveryChannel.IN_APP))
                        .build());
    }

    // ── SuperAdmin-facing (the other realm — different event, different inbox) ──────────────────

    /** A new request landed in the approval queue. Nothing moves until a human works it. */
    public void superAdminRequestSubmitted(PlatformHotelBooking b) {
        publishToPlatform(b, PlatformNotificationType.MARKETPLACE_BOOKING_REQUESTED, () -> {
            String quoted = money(b.getCurrency(), b.getQuotedTenantPayable());
            return platformEvent(b, PlatformNotificationType.MARKETPLACE_BOOKING_REQUESTED,
                    "Hotel booking request — " + tenantLabel(b),
                    tenantLabel(b) + " requested " + stay(b) + " for " + b.getLeadGuestName() + "."
                            + (quoted == null ? "" : " Quoted to the tenant at " + quoted + ".")
                            + ref(b));
        });
    }

    /** A tenant wants out of a room the platform has already committed to a supplier. */
    public void superAdminCancelRequested(PlatformHotelBooking b) {
        publishToPlatform(b, PlatformNotificationType.MARKETPLACE_CANCEL_REQUESTED, () ->
                platformEvent(b, PlatformNotificationType.MARKETPLACE_CANCEL_REQUESTED,
                        "Cancellation requested — " + tenantLabel(b),
                        tenantLabel(b) + " asked to cancel " + stay(b) + "."
                                + (blank(b.getCancelRequestReason()) ? ""
                                        : " Reason: " + b.getCancelRequestReason().trim())
                                + " Confirm the charge with the hotel before settling." + ref(b)));
    }

    // ── Event construction ──────────────────────────────────────────────────────────────────────

    private NotifyEvent tenantEvent(PlatformHotelBooking b, String type, String title, String message) {
        return NotifyEvent.builder()
                .type(type)
                .tenantId(b.getTenantId())
                .recipientUserIds(submitterOrAdmins(b))
                .title(title)
                .message(message)
                .referenceType(MarketplaceNotificationType.REF_HOTEL_BOOKING)
                .referencePublicId(b.getPublicId())
                .channels(Set.of(DeliveryChannel.IN_APP))
                .build();
    }

    private PlatformNotifyEvent platformEvent(PlatformHotelBooking b, String type,
                                              String title, String message) {
        return PlatformNotifyEvent.builder()
                .type(type)
                .title(title)
                .message(message)
                .referenceType(PlatformNotificationType.REF_MARKETPLACE_BOOKING)
                .referencePublicId(b.getPublicId())
                .tenantId(b.getTenantId())
                .tenantName(b.getTenantName())
                .build();
    }

    // ── Audience ────────────────────────────────────────────────────────────────────────────────

    /**
     * The person who placed the order, falling back to the tenant's admins and managers when the
     * submitter is unknown — a request raised by an automation, or by a user since deactivated.
     *
     * <p>An empty list is not a failure mode: {@code InAppNotificationChannel} fans out to the
     * tenant's admins itself when no recipient is named, so the worst case is the same audience.
     */
    private List<Long> submitterOrAdmins(PlatformHotelBooking b) {
        return b.getRequestedByUserId() != null
                ? List.of(b.getRequestedByUserId())
                : adminIds(b.getTenantId());
    }

    private List<Long> submitterAndAdmins(PlatformHotelBooking b) {
        Set<Long> ids = new LinkedHashSet<>();
        if (b.getRequestedByUserId() != null) {
            ids.add(b.getRequestedByUserId());
        }
        ids.addAll(adminIds(b.getTenantId()));
        return List.copyOf(ids);
    }

    private List<Long> adminIds(Long tenantId) {
        return userRepository.findByTenantIdAndRoleInAndIsActiveTrue(tenantId, FALLBACK_ROLES)
                .stream().map(User::getId).toList();
    }

    // ── Publishing ──────────────────────────────────────────────────────────────────────────────

    /**
     * The audience lookup is inside the guard on purpose — it is a database call, and a notifier
     * that can throw is a notifier that can roll back the transition it was describing.
     */
    private void publishToTenant(PlatformHotelBooking b, String type, Supplier<NotifyEvent> event) {
        try {
            eventPublisher.publishEvent(event.get());
        } catch (RuntimeException e) {
            log.error("Tenant notification {} failed for marketplace booking {}: {}",
                    type, b.getBookingCode(), e.getMessage(), e);
        }
    }

    private void publishToPlatform(PlatformHotelBooking b, String type,
                                   Supplier<PlatformNotifyEvent> event) {
        try {
            eventPublisher.publishEvent(event.get());
        } catch (RuntimeException e) {
            log.error("Platform notification {} failed for marketplace booking {}: {}",
                    type, b.getBookingCode(), e.getMessage(), e);
        }
    }

    // ── Prose helpers ───────────────────────────────────────────────────────────────────────────

    /** "Taj Palace, Jaipur (12 Aug 2026 – 15 Aug 2026, 3 nights, 2 rooms)" */
    private String stay(PlatformHotelBooking b) {
        StringBuilder sb = new StringBuilder(nullSafe(b.getHotelNameSnapshot(), "The hotel"));
        if (!blank(b.getCityNameSnapshot())) {
            sb.append(", ").append(b.getCityNameSnapshot());
        }
        sb.append(" (").append(day(b.getCheckIn())).append(" – ").append(day(b.getCheckOut()));
        if (b.getNights() != null && b.getNights() > 0) {
            sb.append(", ").append(plural(b.getNights(), "night"));
        }
        if (b.getRooms() != null && b.getRooms() > 0) {
            sb.append(", ").append(plural(b.getRooms(), "room"));
        }
        return sb.append(")").toString();
    }

    /** Trailing " Request MKB-000123." — the code the tenant quotes when they call about it. */
    private String ref(PlatformHotelBooking b) {
        return blank(b.getBookingCode()) ? "" : " Request " + b.getBookingCode() + ".";
    }

    private String tenantLabel(PlatformHotelBooking b) {
        if (!blank(b.getTenantName())) return b.getTenantName();
        if (!blank(b.getTenantCode())) return b.getTenantCode();
        return "A tenant";
    }

    private static String money(String currency, BigDecimal amount) {
        if (amount == null) return null;
        return (blank(currency) ? "INR" : currency) + " "
                + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String day(LocalDate d) {
        return d == null ? "date TBC" : DAY.format(d);
    }

    private static String plural(int n, String unit) {
        return n + " " + unit + (n == 1 ? "" : "s");
    }

    private static BigDecimal firstNonNull(BigDecimal... candidates) {
        for (BigDecimal c : candidates) {
            if (c != null) return c;
        }
        return null;
    }

    private static String nullSafe(String value, String fallback) {
        return blank(value) ? fallback : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
