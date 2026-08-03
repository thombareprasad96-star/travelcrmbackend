package com.crm.travelcrm.hotelmarketplace.booking.service;

import com.crm.travelcrm.booking.api.CrmBookingLinkPort;
import com.crm.travelcrm.booking.enums.ServiceItemStatus;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.hotelmarketplace.booking.dto.SubmitMarketplaceBookingRequest;
import com.crm.travelcrm.hotelmarketplace.booking.entity.PlatformHotelBooking;
import com.crm.travelcrm.hotelmarketplace.booking.enums.CrmSyncState;
import com.crm.travelcrm.hotelmarketplace.booking.enums.MarketplaceBookingStatus;
import com.crm.travelcrm.hotelmarketplace.booking.repository.PlatformHotelBookingRepository;
import com.crm.travelcrm.hotelmarketplace.catalog.entity.PlatformHotel;
import com.crm.travelcrm.hotelmarketplace.catalog.entity.PlatformHotelMealPlan;
import com.crm.travelcrm.hotelmarketplace.catalog.entity.PlatformHotelRoom;
import com.crm.travelcrm.hotelmarketplace.catalog.repository.PlatformHotelMealPlanRepository;
import com.crm.travelcrm.hotelmarketplace.catalog.repository.PlatformHotelRepository;
import com.crm.travelcrm.hotelmarketplace.catalog.repository.PlatformHotelRoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * The one transaction of a submit: resolve or mint the CRM booking, project the hotel line and its
 * payable, and record the platform request — all or nothing.
 *
 * <p><b>{@code @Transactional} on the method, never the class.</b> {@code TenantFilterAspect} matches
 * {@code @annotation(Transactional)}, so a class-level annotation would run this with the tenant
 * filter disabled.</p>
 *
 * <p><b>Why the CRM booking is resolved HERE, on the tenant's own thread, and not at approval.</b>
 * Three things would break on the SuperAdmin thread, all for the same reason — the principal there is
 * not a tenant {@code User}:</p>
 * <ul>
 *   <li>{@code OwnershipEntityListener} would leave {@code owner_user_id} null, and
 *       {@code SubAgentScope.assertVisible} 404s on a null owner — a sub-agent would be locked out of
 *       the booking it just ordered.</li>
 *   <li>{@code BookingAssigneeResolver} would have no current user to default the assignee to.</li>
 *   <li>The monthly booking quota would 403 an order the platform had already approved, stranding it.
 *       Raised here, the same 403 reaches the tenant, who can upgrade or link an existing booking.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketplaceBookingWriter {

    private final PlatformHotelBookingRepository bookingRepository;
    private final PlatformHotelRepository hotelRepository;
    private final PlatformHotelRoomRepository roomRepository;
    private final PlatformHotelMealPlanRepository mealPlanRepository;
    private final CrmBookingLinkPort crmLink;

    @Transactional
    public PlatformHotelBooking persistRequest(SubmitMarketplaceBookingRequest req,
                                               Long tenantId,
                                               Long requestedByUserId) {
        PlatformHotel hotel = hotelRepository.findSellableByPublicId(req.getHotelPublicId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Hotel not available: " + req.getHotelPublicId()));

        if (!req.getCheckOut().isAfter(req.getCheckIn())) {
            throw new BusinessException("Check-out must be after check-in.", HttpStatus.BAD_REQUEST);
        }
        int nights = (int) ChronoUnit.DAYS.between(req.getCheckIn(), req.getCheckOut());

        PlatformHotelRoom room = resolveRoom(hotel, req.getRoomPublicId());
        PlatformHotelMealPlan mealPlan = resolveMealPlan(hotel, req.getMealPlanPublicId());
        assertOccupancy(room, req);

        // ── The CRM booking: link, or create ──
        CrmBookingLinkPort.CrmBookingRef crmBooking = req.getCrmBookingPublicId() != null
                ? crmLink.requireLinkable(req.getCrmBookingPublicId(), tenantId)
                : createCrmBooking(req, tenantId);

        // ── The platform request row ──
        PlatformHotelBooking row = PlatformHotelBooking.builder()
                .tenantId(tenantId)
                .bookingCode(nextBookingCode())
                .idempotencyKey(req.getIdempotencyKey())
                .platformHotelId(hotel.getId())
                .platformHotelPublicId(hotel.getPublicId())
                .platformRoomPublicId(room == null ? null : room.getPublicId())
                .platformMealPlanPublicId(mealPlan == null ? null : mealPlan.getPublicId())
                // Snapshots: the catalog may be edited or unpublished after this, and what was asked
                // for must not move underneath the request.
                .hotelNameSnapshot(hotel.getName())
                .cityNameSnapshot(hotel.getCityName())
                .countryCodeSnapshot(hotel.getCountryCode())
                .addressSnapshot(hotel.getAddress())
                .roomNameSnapshot(room == null ? null : room.getName())
                .mealPlanSnapshot(mealPlan == null ? null : mealPlan.getName())
                .checkIn(req.getCheckIn())
                .checkOut(req.getCheckOut())
                .nights(nights)
                .rooms(req.getRooms() == null ? 1 : req.getRooms())
                .adults(req.getAdults() == null ? 1 : req.getAdults())
                .children(req.getChildren() == null ? 0 : req.getChildren())
                .leadGuestName(req.getLeadGuestName())
                .leadGuestPhone(req.getLeadGuestPhone())
                .leadGuestEmail(req.getLeadGuestEmail())
                .specialRequests(req.getSpecialRequests())
                .status(MarketplaceBookingStatus.REQUESTED)
                .requestedByUserId(requestedByUserId)
                .tenantCustomerSellingAmount(req.getTenantCustomerSellingAmount())
                .crmBookingPublicId(crmBooking.bookingPublicId())
                .crmSyncState(CrmSyncState.PENDING)
                .build();

        // Persist FIRST so Hibernate's @UuidGenerator assigns publicId. Pre-setting it here would be
        // fighting the generator — it assigns on insert regardless, so a hand-picked value could be
        // silently replaced and the CRM rows would end up keyed to a UUID nothing else references.
        PlatformHotelBooking saved = bookingRepository.save(row);

        // ── Project the hotel line as PENDING ──
        // No price on the line and no payable amount yet: nothing has been agreed with the supplier,
        // and writing a number here would put an invented cost into the booking's economics.
        CrmBookingLinkPort.CrmMarketplaceProjection projection = crmLink.projectHotel(
                new CrmBookingLinkPort.MarketplaceHotelProjectionCommand(
                        tenantId,
                        crmBooking.bookingPublicId(),
                        saved.getPublicId(),
                        hotel.getName(),
                        hotel.getName(),
                        hotel.getCityName(),
                        room == null ? null : room.getName(),
                        mealPlan == null ? null : mealPlan.getName(),
                        req.getCheckIn(),
                        req.getCheckOut(),
                        req.getRooms(),
                        ServiceItemStatus.PENDING,
                        null,
                        null,                       // payable unknown until the SuperAdmin confirms
                        null,
                        "Awaiting platform confirmation."));

        saved.setCrmServiceItemPublicId(projection.serviceItemPublicId());
        saved.setCrmExpensePublicId(projection.expensePublicId());
        saved = bookingRepository.save(saved);

        log.info("Marketplace request {} raised by tenant {} against hotel {} (CRM booking {})",
                saved.getBookingCode(), tenantId, hotel.getName(), crmBooking.bookingCode());
        return saved;
    }

    // ── internals ───────────────────────────────────────────────────────────

    private CrmBookingLinkPort.CrmBookingRef createCrmBooking(SubmitMarketplaceBookingRequest req, Long tenantId) {
        if (req.getCustomer() == null || req.getTenantCustomerSellingAmount() == null) {
            throw new BusinessException(
                    "Either attach an existing booking, or provide the customer and your selling price "
                            + "so one can be created.", HttpStatus.BAD_REQUEST);
        }
        String destination = (req.getDestination() == null || req.getDestination().isBlank())
                ? "Hotel booking"
                : req.getDestination().trim();

        return crmLink.createForMarketplace(new CrmBookingLinkPort.MarketplaceBookingSeed(
                tenantId,
                null,                        // the marketplace publicId is minted by the caller
                req.getCustomer(),
                destination,
                LocalDate.now(),
                req.getCheckIn(),            // travel date = check-in
                req.getTenantCustomerSellingAmount(),
                // Nothing is agreed with the supplier yet, so there is no payable. create() requires a
                // strictly positive vendorCost, and the adapter hands the field straight over to the
                // projection — so this placeholder never survives the same transaction.
                req.getTenantCustomerSellingAmount(),
                Boolean.FALSE,
                req.getLeadPublicId(),
                null));
    }

    private PlatformHotelRoom resolveRoom(PlatformHotel hotel, UUID roomPublicId) {
        if (roomPublicId == null) return null;
        return roomRepository.findByPublicIdAndHotelIdAndDeletedAtIsNull(roomPublicId, hotel.getId())
                .filter(PlatformHotelRoom::isActive)
                .orElseThrow(() -> new BusinessException(
                        "That room is no longer offered by this hotel.", HttpStatus.CONFLICT));
    }

    private PlatformHotelMealPlan resolveMealPlan(PlatformHotel hotel, UUID mealPlanPublicId) {
        if (mealPlanPublicId == null) return null;
        return mealPlanRepository.findByPublicIdAndHotelIdAndDeletedAtIsNull(mealPlanPublicId, hotel.getId())
                .filter(PlatformHotelMealPlan::isActive)
                .orElseThrow(() -> new BusinessException(
                        "That meal plan is no longer offered by this hotel.", HttpStatus.CONFLICT));
    }

    /** Occupancy is checked at request time so an impossible party is refused before a human sees it. */
    private void assertOccupancy(PlatformHotelRoom room, SubmitMarketplaceBookingRequest req) {
        if (room == null || room.getMaxOccupancy() == null) return;
        int rooms = req.getRooms() == null ? 1 : req.getRooms();
        int guests = (req.getAdults() == null ? 1 : req.getAdults())
                + (req.getChildren() == null ? 0 : req.getChildren());
        int capacity = room.getMaxOccupancy() * rooms;
        if (guests > capacity) {
            throw new BusinessException(
                    guests + " guests exceed the capacity of " + rooms + " × " + room.getName()
                            + " (" + capacity + ").", HttpStatus.BAD_REQUEST);
        }
    }

    /** Collision is caught by the unique index; the random suffix makes it vanishingly unlikely. */
    private static String nextBookingCode() {
        return "MKT-" + LocalDate.now().getYear() + "-"
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
