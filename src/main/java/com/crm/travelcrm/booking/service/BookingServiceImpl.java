package com.crm.travelcrm.booking.service;

import com.crm.travelcrm.booking.dto.request.BookingFinancialPreviewRequest;
import com.crm.travelcrm.booking.dto.request.CancelBookingRequestDTO;
import com.crm.travelcrm.booking.dto.request.CreateBookingRequestDTO;
import com.crm.travelcrm.booking.dto.request.LeadConversionRequestDTO;
import com.crm.travelcrm.booking.dto.request.PaymentUpdateRequestDTO;
import com.crm.travelcrm.booking.dto.request.StatusUpdateRequestDTO;
import com.crm.travelcrm.booking.dto.request.TripSnapshotRequest;
import com.crm.travelcrm.booking.dto.request.UpdateBookingRequestDTO;
import com.crm.travelcrm.booking.assignment.BookingAssigneeResolver;
import com.crm.travelcrm.booking.assignment.BookingAssigneeView;
import com.crm.travelcrm.booking.assignment.BookingAssigneeViewFactory;
import com.crm.travelcrm.booking.api.BookingCancelledEvent;
import com.crm.travelcrm.booking.enums.CancelAction;
import com.crm.travelcrm.booking.dto.response.BookingAssigneeDto;
import com.crm.travelcrm.booking.dto.response.BookingFinancialPreviewResponse;
import com.crm.travelcrm.booking.dto.response.BookingPageSummaryResponseDTO;
import com.crm.travelcrm.booking.dto.response.BookingResponseDTO;
import com.crm.travelcrm.booking.dto.response.BookingStatsResponseDTO;
import com.crm.travelcrm.booking.dto.response.TripSnapshotResponse;
import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.entity.BookingPayment;
import com.crm.travelcrm.booking.entity.BookingTripLeg;
import com.crm.travelcrm.booking.entity.BookingTripSnapshot;
import com.crm.travelcrm.booking.enums.BookingStatus;
import com.crm.travelcrm.booking.enums.PaymentStatus;
import com.crm.travelcrm.booking.exception.BookingNotFoundException;
import com.crm.travelcrm.booking.mapper.BookingMapper;
import com.crm.travelcrm.booking.repository.BookingExpenseRepository;
import com.crm.travelcrm.booking.repository.BookingPaymentRepository;
import com.crm.travelcrm.accounting.settings.service.AccountingSettingsService;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.booking.cancellation.dto.CancellationQuote;
import com.crm.travelcrm.booking.cancellation.entity.BookingCancellation;
import com.crm.travelcrm.booking.cancellation.entity.CancellationPolicy;
import com.crm.travelcrm.booking.cancellation.enums.RefundStatus;
import com.crm.travelcrm.booking.cancellation.repository.BookingCancellationRepository;
import com.crm.travelcrm.booking.cancellation.service.CancellationCalculator;
import com.crm.travelcrm.booking.cancellation.service.CancellationDocumentService;
import com.crm.travelcrm.booking.cancellation.service.CancellationPolicyResolver;
import com.crm.travelcrm.booking.specification.BookingSpecification;
import com.crm.travelcrm.booking.util.BookingCodeGenerator;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.event.LeadSoftDeletedEvent;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.common.util.PhoneNormalizer;
import com.crm.travelcrm.customer.entity.Customer;
import com.crm.travelcrm.customer.repository.CustomerRepository;
import com.crm.travelcrm.customer.service.CustomerMatcher;
import com.crm.travelcrm.customer.service.CustomerService;
import com.crm.travelcrm.customer.util.CustomerCodeGenerator;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.entity.LeadItinerary;
import com.crm.travelcrm.lead.enums.DepartureMode;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.lead.repository.LeadRepository;
import com.crm.travelcrm.lead.service.LeadAccessGuard;
import com.crm.travelcrm.permission.service.SubAgentScope;
import com.crm.travelcrm.subagent.service.SubAgentCommissionService;
import com.crm.travelcrm.notification.api.NotifyEvent;
import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;
import com.crm.travelcrm.notification.domain.enums.NotificationType;
import com.crm.travelcrm.quotation.entity.Quotation;
import com.crm.travelcrm.quotation.repository.QuotationRepository;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BookingServiceImpl implements BookingService {

    private static final Logger log = LogManager.getLogger(BookingServiceImpl.class);
    private static final Set<BookingStatus> TERMINAL_STATUSES = Set.of(
            BookingStatus.COMPLETED,
            BookingStatus.CANCELLED,
            BookingStatus.REFUNDED
    );

    // Booking tax rates are now PER-TENANT, on AccountingSettings, resolved through
    // BookingTaxCalculator — see recomputeTotals. They used to be the two flat properties
    // app.booking.gst-rate / app.booking.tcs-rate applied to every booking of every tenant, which
    // over-collected TCS from every domestic customer. Those properties survive only as the seed
    // values for a tenant's first settings row (AccountingSettingsService.loadOrCreate), so an
    // environment that had tuned them keeps its behaviour.

    private final BookingRepository        bookingRepository;
    private final BookingPaymentRepository paymentRepository;
    /** Only for the marketplace-payable term of the server-owned vendorCost — see update(). */
    private final BookingExpenseRepository expenseRepository;
    private final BookingMapper        bookingMapper;
    private final BookingCodeGenerator bookingCodeGenerator;
    private final ApplicationEventPublisher eventPublisher;
    private final CustomerRepository   customerRepository;
    private final CustomerCodeGenerator customerCodeGenerator;
    // Owns resolve-or-create for the booking form's nested `customer` block. Re-implementing those
    // duplicate/trashed-restore rules here is what used to 500 against uq_customers_phone_tenant.
    private final CustomerService      customerService;
    private final CustomerMatcher      customerMatcher;
    private final LeadRepository       leadRepository;
    private final LeadAccessGuard      leadAccessGuard;
    private final QuotationRepository  quotationRepository;
    private final TenantRepository     tenantRepository;
    private final CancellationPolicyResolver policyResolver;
    private final CancellationCalculator cancellationCalculator;
    private final BookingCancellationRepository cancellationRepository;
    private final com.crm.travelcrm.vendor.repository.VendorRepository vendorRepository;
    private final CancellationDocumentService cancellationDocumentService;
    private final SubAgentScope subAgentScope;
    private final SubAgentCommissionService commissionService;
    private final BookingProfitService profitService;
    private final BookingTaxCalculator bookingTaxCalculator;
    private final AccountingSettingsService accountingSettings;
    private final BookingAssigneeResolver assigneeResolver;
    private final BookingAssigneeViewFactory assigneeViewFactory;

    // ── Create ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public BookingResponseDTO create(CreateBookingRequestDTO request) {
        Long tenantId = requireTenantId();

        Booking booking = bookingMapper.toEntity(request);
        booking.setTenantId(tenantId);
        booking.setBookingCode(bookingCodeGenerator.generate(tenantId));
        booking.setStatus(BookingStatus.PENDING);

        // The create form may omit bookingDate (for example an older/mobile client). The column is
        // NOT NULL and the DTO contract promises "defaults to today", so materialise that default
        // on the entity rather than only using it transiently inside the quota check below.
        if (booking.getBookingDate() == null) {
            booking.setBookingDate(LocalDate.now());
        }

        // BookingMapper deliberately ignores element collections. Copy the selected inclusions
        // here, just as update() and lead conversion do, otherwise a successful create silently
        // drops every service chip selected on the page.
        booking.setServices(request.getServices() == null
                ? new ArrayList<>()
                : new ArrayList<>(request.getServices()));

        // Monthly booking-quota gate (hard 403 once the plan cap for the target month is reached).
        enforceBookingQuota(tenantId, booking.getBookingDate());

        // Resolve the source lead FIRST — a new customer created below is stamped with its
        // provenance, which is what lets the cancel-cleanup reclaim only the customers this flow
        // created and never one a user typed in by hand.
        Lead sourceLead = resolveSourceLead(request.getLeadPublicId(), tenantId);

        // Resolve the customer: reuse by publicId (optionally syncing corrections) or create a new
        // profile. Delegated to the customer module so the duplicate-phone and trashed-restore rules
        // live in ONE place — re-implementing them here is what used to 500 against
        // uq_customers_phone_tenant when a booking raced the phone search.
        Customer customer = customerService.resolveOrCreate(
                request.getCustomer(), sourceLead != null ? sourceLead.getId() : null);

        booking.setCustomerId(customer.getId());
        booking.setCustomerPublicId(customer.getPublicId());
        // Read from the RESOLVED customer, never from the request. Conversion used to take this from
        // the request while direct create took it from the row, so the two paths could disagree about
        // the same customer's name on the same day.
        booking.setCustomerNameSnapshot(customer.getName());

        // Destination is sent as a free-text name (no id); snapshot it so the NOT NULL holds.
        booking.setDestinationSnapshot(request.getDestination());

        // Who services this booking: the explicit choice, else the current user. No lead to inherit
        // from on this path.
        booking.setAssignedUserId(assigneeResolver.resolveForCreate(request.getAssignedUserId(), tenantId));

        if (sourceLead != null) {
            booking.setLeadId(sourceLead.getId());
            booking.setSourceLeadPublicId(sourceLead.getPublicId());
        }

        // Traveller / departure / itinerary detail. Attached before save so the cascade persists it
        // in the same transaction as the booking it belongs to.
        booking.attachTripSnapshot(buildTripSnapshot(request.getTripSnapshot()));

        // Who the vendorCost is owed to. Resolved before the financials so a bad vendor id fails the
        // request before any money field is written.
        applyVendorLink(booking, request.getVendorPublicId());

        BigDecimal initialPaid = request.getPaidAmount() != null
                ? request.getPaidAmount() : BigDecimal.ZERO;
        calculateAndApplyFinancials(booking, request.getCustomerAmount(),
                request.getVendorCost(), initialPaid);

        // Pin the governing cancellation policy NOW (immune to later policy edits). A direct booking
        // has no source quotation, so the tenant company default (as-of the booking date) applies.
        pinCancellationPolicy(booking, null, tenantId);

        Booking saved = bookingRepository.save(booking);
        if (sourceLead != null) {
            sourceLead.setLeadStage(LeadStage.CONVERTED);
            sourceLead.setConvertedAt(LocalDateTime.now());
            sourceLead.setConvertedBookingPublicId(saved.getPublicId());
            backLinkCustomerToLead(sourceLead, customer);
            leadRepository.save(sourceLead);
        }
        // An initial payment entered on the create form must appear in the ledger too, or the invoice
        // would show "Paid ₹X" over an empty Payments-Received table (paidAmount vs ledger divergence).
        if (initialPaid.signum() > 0) {
            recordPaymentLedgerRow(saved, initialPaid, "Opening balance",
                    saved.getBookingDate(), null, "Initial payment recorded at booking creation");
            // Broker commission accrues on payment received — an upfront payment counts too.
            commissionService.syncForBooking(saved);
        }
        log.info("Booking {} created for customer {} | tenantId: {}",
                saved.getBookingCode(), customer.getCustomerCode(), tenantId);
        publishBookingEvent(NotificationType.BOOKING_CREATED, saved,
                "New Booking: " + saved.getBookingCode(),
                "A new booking " + saved.getBookingCode() + " was created");
        return toResponse(saved);
    }

    /**
     * Resolve the optional source lead by publicId, tenant-scoped.
     *
     * <p>Returns null when no lead was supplied — a booking taken directly, with no prior enquiry,
     * is entirely normal. A publicId that IS supplied but does not resolve is a 404: silently
     * ignoring it would drop the lead-to-booking traceability the reports depend on, with nothing
     * anywhere to say it happened.
     */
    private Lead resolveSourceLead(UUID leadPublicId, Long tenantId) {
        if (leadPublicId == null) return null;
        return leadRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(leadPublicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Lead not found: " + leadPublicId));
    }

    /**
     * Build the trip snapshot from the request block, or null when none was sent.
     *
     * <p>The departure section is copied through {@code mode} rather than field-by-field: the form
     * sends the flight and train preferred times under one shared {@code preferredTime} key, and it
     * keeps the values of whichever groups the clerk filled in before switching mode. Copying
     * everything would produce a train journey carrying an airport code — data that reads as real to
     * anything that does not check the mode first.
     */
    private BookingTripSnapshot buildTripSnapshot(TripSnapshotRequest request) {
        if (request == null) return null;

        BookingTripSnapshot snapshot = BookingTripSnapshot.builder()
                .packageType(request.getPackageType())
                .notes(request.getNotes())
                .build();

        TripSnapshotRequest.Departure departure = request.getDeparture();
        if (departure != null) {
            snapshot.setDepartCountry(departure.getCountry());
            snapshot.setDepartCity(departure.getCity());

            DepartureMode mode = departure.getMode();
            snapshot.setDepartureMode(mode);
            if (mode == DepartureMode.FLIGHT) {
                snapshot.setDepartureAirport(departure.getAirport());
                snapshot.setAirportCode(departure.getAirportCode() == null
                        ? null : departure.getAirportCode().trim().toUpperCase());
                snapshot.setPreferredFlightTime(departure.getPreferredTime());
            } else if (mode == DepartureMode.TRAIN) {
                snapshot.setRailwayStation(departure.getRailwayStation());
                snapshot.setTrainClass(departure.getTrainClass());
                snapshot.setPreferredTrainTime(departure.getPreferredTime());
            } else if (mode == DepartureMode.CAR) {
                snapshot.setPickupAddress(departure.getPickupAddress());
                snapshot.setPickupDateTime(departure.getPickupDateTime());
                snapshot.setVehiclePreference(departure.getVehiclePreference());
            }
        }

        TripSnapshotRequest.Travellers travellers = request.getTravellers();
        if (travellers != null) {
            snapshot.setRooms(travellers.getRooms());
            snapshot.setMale(travellers.getMale());
            snapshot.setFemale(travellers.getFemale());
            // Prefer the sent total, else derive it from the split. Stored either way so a later
            // edit to male/female cannot rewrite the headcount this booking was priced against.
            snapshot.setTotalAdults(travellers.getTotalAdults() != null
                    ? travellers.getTotalAdults()
                    : zero(travellers.getMale()) + zero(travellers.getFemale()));
            snapshot.setChildren(travellers.getChildren());
            snapshot.setInfants(travellers.getInfants());
            snapshot.setExtraBeds(travellers.getExtraBeds());
        }

        TripSnapshotRequest.SpecialAssistance assistance = request.getSpecialAssistance();
        if (assistance != null && Boolean.TRUE.equals(assistance.getRequired())) {
            snapshot.setSpecialAssistanceRequired(true);
            snapshot.setAssistancePassengerCount(assistance.getPassengerCount());
            snapshot.setSpecialAssistanceNotes(assistance.getNotes());
            if (assistance.getTypes() != null) {
                snapshot.getSpecialAssistanceTypes().addAll(assistance.getTypes());
            }
        } else {
            // Unticked means unticked: never carry the detail of an assistance request that was
            // turned off, or the voucher prints a wheelchair nobody asked for.
            snapshot.setSpecialAssistanceRequired(false);
            snapshot.setAssistancePassengerCount(0);
        }

        if (request.getItinerary() != null) {
            int day = 1;
            for (TripSnapshotRequest.TripLeg leg : request.getItinerary()) {
                snapshot.addLeg(BookingTripLeg.builder()
                        .destination(leg.getDestination())
                        .city(leg.getCity())
                        .nights(leg.getNights())
                        // Renumber from position rather than trusting the sent dayNumber: rows can be
                        // added and removed on the form, and a stale index would order the printed
                        // itinerary wrongly.
                        .dayNumber(day++)
                        .build());
            }
        }

        return snapshot;
    }

    /**
     * Update the owned trip snapshot in place. Replacing a one-to-one orphan with a new row can
     * insert before Hibernate deletes the old row and violate the unique booking_id constraint;
     * mutating the existing aggregate avoids that ordering hazard while preserving orphan cleanup
     * for itinerary rows.
     */
    private void applyTripSnapshotUpdate(Booking booking, TripSnapshotRequest request) {
        if (request == null) return;

        BookingTripSnapshot incoming = buildTripSnapshot(request);
        BookingTripSnapshot target = booking.getTripSnapshot();
        if (target == null) {
            booking.attachTripSnapshot(incoming);
            return;
        }

        target.setPackageType(incoming.getPackageType());
        target.setDepartCountry(incoming.getDepartCountry());
        target.setDepartCity(incoming.getDepartCity());
        target.setDepartureMode(incoming.getDepartureMode());
        target.setDepartureAirport(incoming.getDepartureAirport());
        target.setAirportCode(incoming.getAirportCode());
        target.setPreferredFlightTime(incoming.getPreferredFlightTime());
        target.setRailwayStation(incoming.getRailwayStation());
        target.setTrainClass(incoming.getTrainClass());
        target.setPreferredTrainTime(incoming.getPreferredTrainTime());
        target.setPickupAddress(incoming.getPickupAddress());
        target.setPickupDateTime(incoming.getPickupDateTime());
        target.setVehiclePreference(incoming.getVehiclePreference());
        target.setRooms(incoming.getRooms());
        target.setMale(incoming.getMale());
        target.setFemale(incoming.getFemale());
        target.setTotalAdults(incoming.getTotalAdults());
        target.setChildren(incoming.getChildren());
        target.setInfants(incoming.getInfants());
        target.setExtraBeds(incoming.getExtraBeds());
        target.setSpecialAssistanceRequired(incoming.getSpecialAssistanceRequired());
        target.setAssistancePassengerCount(incoming.getAssistancePassengerCount());
        target.setSpecialAssistanceNotes(incoming.getSpecialAssistanceNotes());
        target.setNotes(incoming.getNotes());

        target.getSpecialAssistanceTypes().clear();
        target.getSpecialAssistanceTypes().addAll(incoming.getSpecialAssistanceTypes());
        target.getItinerary().clear();
        incoming.getItinerary().forEach(target::addLeg);
    }

    private static int zero(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * Point a lead at the customer its booking resolved to.
     *
     * <p>Mutates only — the caller saves, because the conversion path already writes the lead a
     * line later and a save in here would make that two round-trips for one row.
     *
     * <p>Set unconditionally rather than fill-if-empty: this is not a prefill guess, it is the
     * customer the booking was actually raised for. If an older match pointed somewhere else the
     * phone has since changed, and the booking is the more recent truth.
     */
    private void backLinkCustomerToLead(Lead lead, Customer customer) {
        if (lead == null || customer == null) return;
        lead.setCustomerId(customer.getId());
        lead.setCustomerPublicId(customer.getPublicId());
        lead.setCustomerLinkedAt(LocalDateTime.now());
    }

    /**
     * Build the booking's trip snapshot from the lead being converted.
     *
     * <p>Deliberately routed through the same {@link TripSnapshotRequest} shape the direct-create
     * path uses instead of copying field-by-field onto the entity: the mode gating (only the group
     * matching {@code departureMode} is kept), the adults fallback and the assistance-unticked rule
     * all live in {@link #buildTripSnapshot} already, and a second hand-rolled copy of them would
     * drift from the first the next time one of those rules changes.
     */
    private BookingTripSnapshot buildTripSnapshotFromLead(Lead lead) {
        if (lead == null) return null;

        TripSnapshotRequest.Departure departure = new TripSnapshotRequest.Departure();
        departure.setCountry(lead.getDepartCountry());
        departure.setCity(lead.getDepartCity());
        departure.setMode(lead.getDepartureMode());
        departure.setAirport(lead.getDepartureAirport());
        departure.setAirportCode(lead.getAirportCode());
        departure.setRailwayStation(lead.getRailwayStation());
        departure.setTrainClass(lead.getTrainClass());
        departure.setPickupAddress(lead.getPickupAddress());
        departure.setPickupDateTime(lead.getPickupDateTime());
        departure.setVehiclePreference(lead.getVehiclePreference());
        // The lead keeps flight and train times in separate columns; the request carries one
        // `preferredTime` that buildTripSnapshot files under whichever mode is set.
        departure.setPreferredTime(lead.getDepartureMode() == DepartureMode.TRAIN
                ? lead.getPreferredTrainTime()
                : lead.getPreferredFlightTime());

        TripSnapshotRequest.Travellers travellers = new TripSnapshotRequest.Travellers();
        travellers.setRooms(lead.getRooms());
        travellers.setMale(lead.getMale());
        travellers.setFemale(lead.getFemale());
        travellers.setTotalAdults(lead.getAdults());
        travellers.setChildren(lead.getChildren());
        travellers.setInfants(lead.getInfants());
        travellers.setExtraBeds(lead.getExtraBeds());

        TripSnapshotRequest.SpecialAssistance assistance = new TripSnapshotRequest.SpecialAssistance();
        assistance.setRequired(lead.getSpecialAssistanceRequired());
        assistance.setPassengerCount(lead.getAssistancePassengerCount());
        assistance.setNotes(lead.getSpecialAssistanceNotes());
        assistance.setTypes(lead.getSpecialAssistanceTypes() == null
                ? null : new ArrayList<>(lead.getSpecialAssistanceTypes()));

        List<TripSnapshotRequest.TripLeg> legs = new ArrayList<>();
        if (lead.getItinerary() != null) {
            // Day order is the lead's own, not list order: an itinerary edited on the lead can hold
            // its rows in any sequence, and buildTripSnapshot renumbers from position.
            lead.getItinerary().stream()
                    .sorted(Comparator.comparing(LeadItinerary::getDayNumber,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .forEach(item -> {
                        TripSnapshotRequest.TripLeg leg = new TripSnapshotRequest.TripLeg();
                        leg.setDestination(item.getDestination());
                        leg.setCity(item.getCity());
                        leg.setNights(item.getNights());
                        leg.setDayNumber(item.getDayNumber());
                        legs.add(leg);
                    });
        }

        TripSnapshotRequest request = new TripSnapshotRequest();
        request.setPackageType(lead.getPackageType());
        request.setNotes(lead.getNotes());
        request.setDeparture(departure);
        request.setTravellers(travellers);
        request.setSpecialAssistance(assistance);
        request.setItinerary(legs);
        return buildTripSnapshot(request);
    }

    /**
     * Monthly booking-quota gate. Rejects a new booking that would exceed the tenant's
     * {@code maxBookingsPerMonth} for the target booking's calendar month ({@code null}/≤0 =
     * unlimited). Mirrors the user-seat cap in {@code UserServiceImpl}; soft-alerting as the tenant
     * approaches the limit is handled separately by the usage-alert scheduler. {@code Tenant} is
     * platform-level (no tenant filter), so a direct {@code findById} is correct here.
     */
    private void enforceBookingQuota(Long tenantId, LocalDate bookingDate) {
        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null) return;
        Integer limit = tenant.getMaxBookingsPerMonth();
        if (limit == null || limit <= 0) return;   // unlimited

        LocalDate date = bookingDate != null ? bookingDate : LocalDate.now();
        LocalDate monthStart = date.withDayOfMonth(1);
        LocalDate nextMonthStart = monthStart.plusMonths(1);
        long used = bookingRepository.countByTenantForMonth(tenantId, monthStart, nextMonthStart);
        if (used >= limit) {
            throw new BusinessException(
                    "Your plan allows up to " + limit + " new bookings for "
                            + monthStart.getMonth() + " " + monthStart.getYear()
                            + ". Upgrade your plan or contact support to add more.",
                    HttpStatus.FORBIDDEN);
        }
    }

    // ── Convert Lead → Booking ────────────────────────────────────────────────

    @Override
    @Transactional
    public BookingResponseDTO convertLeadToBooking(UUID leadPublicId, LeadConversionRequestDTO request) {
        Long tenantId = requireTenantId();
        log.info("Converting lead {} to booking", leadPublicId);

        // Monthly booking-quota gate (same cap as direct create; convert also produces a new booking).
        enforceBookingQuota(tenantId, request.getBookingDate());

        // Tenant + row-level scope (LEAD_UPDATE — converting mutates the lead). Returns the
        // managed Lead so the stage flip below participates in this same transaction.
        Lead lead = leadAccessGuard.requireVisible(leadPublicId, "LEAD_UPDATE");

        // Duplicate guard — never silently create a SECOND active booking for the same lead.
        // Re-submits and double-clicks are rejected with a friendly 409 naming the existing one.
        // CANCELLED bookings are excluded: cancelling reopens the lead (REOPENED) but retains the
        // booking row, so a reopened lead must be re-convertible even though its old cancelled
        // booking still exists.
        bookingRepository.findFirstByLeadIdAndTenantIdAndStatusNotAndDeletedAtIsNullOrderByIdDesc(
                        lead.getId(), tenantId, BookingStatus.CANCELLED)
                .ifPresent(existing -> {
                    throw new BusinessException(
                            "This lead is already converted to booking " + existing.getBookingCode()
                                    + ". Open that booking instead of creating another.",
                            HttpStatus.CONFLICT);
                });

        // Optional source quotation — validate it belongs to this lead + tenant before linking.
        UUID sourceQuotationPublicId = null;
        UUID quotationPolicyPublicId = null;   // the structured cancellation policy the quote was priced under
        if (request.getQuotationPublicId() != null) {
            Quotation quotation = quotationRepository
                    .findByPublicIdAndTenantIdAndDeletedAtIsNull(request.getQuotationPublicId(), tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Quotation not found: " + request.getQuotationPublicId()));
            boolean belongsToLead = Objects.equals(quotation.getLeadId(), lead.getId())
                    || Objects.equals(quotation.getLeadPublicId(), lead.getPublicId());
            if (!belongsToLead) {

                throw new BusinessException(
                        "The selected quotation does not belong to this lead.", HttpStatus.BAD_REQUEST);
            }
            sourceQuotationPublicId = quotation.getPublicId();
            quotationPolicyPublicId = quotation.getCancellationPolicyPublicId();
        }

        // Resolve or create the customer from the lead (phone is the per-tenant natural key).
        Customer customer = resolveOrCreateCustomer(
                lead, request.getCustomerName(), request.getCustomerEmail(), tenantId);

        // Build the booking, carrying over the reviewed details + source back-links.
        Booking booking = Booking.builder()
                .tenantId(tenantId)
                .bookingCode(bookingCodeGenerator.generate(tenantId))
                .customerId(customer.getId())
                .customerPublicId(customer.getPublicId())
                // From the RESOLVED customer, not the request. Direct create reads the row while this
                // path used to read the request, so the same customer could end up with two different
                // name snapshots depending on which door the booking came through.
                .customerNameSnapshot(customer.getName())
                .destinationSnapshot(request.getDestination())
                .leadId(lead.getId())
                .sourceLeadPublicId(lead.getPublicId())
                .sourceQuotationPublicId(sourceQuotationPublicId)
                // Default: the LEAD's assignee — the person who nurtured the deal keeps the
                // customer, not whoever clicked Convert. Overridable via request.assignedUserId.
                .assignedUserId(assigneeResolver.resolveForConvert(
                        lead, request.getAssignedUserId(), tenantId))
                .status(BookingStatus.PENDING)
                .bookingDate(request.getBookingDate() != null ? request.getBookingDate() : LocalDate.now())
                .travelDate(request.getTravelDate())
                // customerAmount / vendorCost are stored as-is; the helper derives gst/tcs/total/profit.
                .customerAmount(request.getCustomerAmount())
                .vendorCost(request.getVendorCost())
                .overseasTourPackage(Boolean.TRUE.equals(request.getOverseasTourPackage()))
                .services(request.getServices() != null
                        ? new ArrayList<>(request.getServices())
                        : new ArrayList<>())
                .build();

        BigDecimal paid = request.getPaidAmount() != null ? request.getPaidAmount() : BigDecimal.ZERO;
        calculateAndApplyFinancials(booking, request.getCustomerAmount(), request.getVendorCost(), paid);

        // Traveller / departure / itinerary detail, carried over from the lead. The conversion form
        // sends no snapshot block of its own, and this path used to build none — so converting an
        // enquiry silently dropped every field V2 PART 2 was added to store, through the very door
        // most bookings come in by. Attached before save so the cascade persists it in this
        // transaction.
        booking.attachTripSnapshot(buildTripSnapshotFromLead(lead));

        // Pin the governing cancellation policy: the exact version the quotation was priced under
        // (so the customer is charged the terms they were quoted), else the company default.
        pinCancellationPolicy(booking, quotationPolicyPublicId, tenantId);

        Booking saved = bookingRepository.save(booking);
        // Carry an initial payment into the ledger so paidAmount and the receipts table reconcile.
        if (paid.signum() > 0) {
            recordPaymentLedgerRow(saved, paid, "Opening balance",
                    saved.getBookingDate(), null, "Initial payment recorded during lead conversion");
            // Broker commission accrues on payment received — an upfront payment counts too.
            commissionService.syncForBooking(saved);
        }

        // Flip the lead to CONVERTED — keep it for history, stamp the back-link to the booking.
        lead.setLeadStage(LeadStage.CONVERTED);
        lead.setConvertedAt(LocalDateTime.now());
        lead.setConvertedBookingPublicId(saved.getPublicId());
        // The customer resolved above IS the customer this lead became; record it, so the lead's
        // customer card resolves even when the match could not be made at creation time.
        backLinkCustomerToLead(lead, customer);
        leadRepository.save(lead);

        log.info("Lead {} converted to booking {} (tenant {})",
                leadPublicId, saved.getBookingCode(), tenantId);

        publishBookingEvent(NotificationType.BOOKING_CREATED, saved,
                "Lead converted: " + saved.getBookingCode(),
                lead.getCustomerName() + " was converted to booking " + saved.getBookingCode());

        return toResponse(saved);
    }

    /**
     * Resolve the tenant customer for a conversion, in order:
     *   1. Reuse the live customer with this (normalised) phone — a repeat customer.
     *   2. Else restore-and-reuse a trashed customer with this phone — avoids an INSERT that
     *      would collide with the (tenant_id, phone) partial unique index and 500 (the failure
     *      that hit "convert → cancel → same customer converts again").
     *   3. Else create a fresh customer, stamped with provenance ({@code createdFromLeadId}) so
     *      cancel-cleanup can safely reclaim ONLY the customers conversion created itself.
     * Phone is normalised the same way everywhere ({@link PhoneNormalizer}) so a lead never
     * spawns a duplicate of an otherwise-identical customer, and a blank phone is rejected up
     * front instead of creating an unmatchable / NOT-NULL-violating row.
     */
    private Customer resolveOrCreateCustomer(Lead lead, String name, String email, Long tenantId) {
        String phone = PhoneNormalizer.normalize(lead.getPhone());
        if (PhoneNormalizer.isBlank(phone)) {
            throw new BusinessException(
                    "This lead has no phone number, so it cannot be converted to a booking. "
                            + "Add a phone number to the lead first.",
                    HttpStatus.BAD_REQUEST);
        }
        String resolvedName = (name != null && !name.isBlank()) ? name : lead.getCustomerName();
        String resolvedEmail = (email != null && !email.isBlank()) ? email.trim().toLowerCase()
                : lead.getEmail();

        // 1. Live customer — reuse, and sync across what the agent just reviewed on the form.
        //    Previously this branch synced NOTHING: a corrected name, a newly-collected birthday and
        //    the email the modal has always sent were all discarded, so converting a lead could not
        //    improve the customer record no matter what the agent typed.
        Optional<Customer> live = customerRepository
                .findByPhoneAndTenantIdAndDeletedAtIsNull(phone, tenantId);
        if (live.isPresent()) {
            Customer existing = live.get();
            syncFromLead(existing, lead, resolvedName, resolvedEmail);
            return customerRepository.save(existing);
        }

        // 2. Trashed customer with the same phone — restore rather than insert a colliding row.
        Optional<Customer> trashed = customerRepository
                .findFirstByPhoneAndTenantIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(phone, tenantId);
        if (trashed.isPresent()) {
            Customer revived = trashed.get();
            revived.restore();
            syncFromLead(revived, lead, resolvedName, resolvedEmail);
            Customer saved = customerRepository.save(revived);
            log.info("Restored trashed customer {} for conversion of lead {}",
                    saved.getCustomerCode(), lead.getPublicId());
            return saved;
        }

        // 3. Brand-new customer — stamp provenance so cancel-cleanup can reclaim it later.
        //    Carries the profile the lead collected: birthday, anniversary and origin city were all
        //    being thrown away here, which is why Lead.birthDate had no reader anywhere in the app
        //    and the marketing birthday automations never fired for a converted lead.
        Customer customer = Customer.builder()
                .tenantId(tenantId)
                .customerCode(customerCodeGenerator.generate(tenantId))
                .name(resolvedName)
                .phone(phone)
                .phoneNormalized(customerMatcher.canonicalPhone(phone))
                .email(resolvedEmail)
                .birthday(lead.getBirthDate())
                .anniversary(lead.getAnniversaryDate())
                .city(cityOrNull(lead.getDepartCity()))
                .commPref(lead.getPreferredCommunication())
                .createdFromLeadId(lead.getId())
                .build();
        Customer created = customerRepository.save(customer);
        log.info("Created customer {} from lead {} during conversion",
                created.getCustomerCode(), lead.getPublicId());
        return created;
    }

    /**
     * Fold what the lead knows into an existing customer record.
     *
     * <p>The asymmetry is the point:
     * <ul>
     *   <li><b>{@code name} — last-write-wins.</b> The agent has just confirmed it with the customer
     *       while converting.</li>
     *   <li><b>everything else — fill-if-empty.</b> A lead form is thinner than a customer master
     *       record; letting it write over a populated field would erase a richer profile with a
     *       sparser one.</li>
     *   <li><b>{@code phone} — never written.</b> It is the per-tenant natural key this customer was
     *       just <em>found</em> by, and {@code uq_customers_phone_tenant} would reject a collision
     *       anyway.</li>
     * </ul>
     */
    private void syncFromLead(Customer customer, Lead lead, String resolvedName, String resolvedEmail) {
        if (resolvedName != null && !resolvedName.isBlank()) {
            customer.setName(resolvedName);
        }
        if (isBlank(customer.getEmail()) && resolvedEmail != null && !resolvedEmail.isBlank()) {
            customer.setEmail(resolvedEmail.trim().toLowerCase());
        }
        if (customer.getBirthday() == null)    customer.setBirthday(lead.getBirthDate());
        if (customer.getAnniversary() == null) customer.setAnniversary(lead.getAnniversaryDate());
        if (isBlank(customer.getCity()))       customer.setCity(cityOrNull(lead.getDepartCity()));
        if (customer.getCommPref() == null)    customer.setCommPref(lead.getPreferredCommunication());
        // Backfill the canonical key on rows that predate the column, so this customer becomes
        // findable by the lead auto-prefill from now on.
        if (isBlank(customer.getPhoneNormalized())) {
            customer.setPhoneNormalized(customerMatcher.canonicalPhone(customer.getPhone()));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** "Not Specified" is the lead form's placeholder for an untouched origin — not a real city. */
    private static String cityOrNull(String city) {
        if (isBlank(city) || "Not Specified".equalsIgnoreCase(city.trim())) return null;
        return city.trim();
    }

    // ── Get by ID ────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public BookingResponseDTO getById(UUID publicId) {
        return toResponse(findActiveByPublicId(publicId));
    }

    // ── Get by Code ──────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public BookingResponseDTO getByCode(String code) {
        Booking booking = bookingRepository.findByBookingCodeAndDeletedAtIsNull(code)
                .orElseThrow(() -> new BookingNotFoundException(code));
        subAgentScope.assertVisible(booking, code);   // sub-agent may only fetch its own booking
        return toResponse(booking);
    }

    // ── Get All (Paginated) ──────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<BookingResponseDTO> getAll(int page, int size, String sortBy, String sortDir) {
        Sort sort = sortDir.equalsIgnoreCase("desc")
                ? Sort.by(sortBy).descending()
                : Sort.by(sortBy).ascending();

        Pageable pageable  = PageRequest.of(page, size, sort);
        Specification<Booking> spec = BookingSpecification.isActive();
        Long ownerFilter = subAgentScope.ownerFilter();
        if (ownerFilter != null) spec = spec.and(BookingSpecification.ownedBy(ownerFilter));
        Page<Booking> bookingPage = bookingRepository.findAll(spec, pageable);

        List<BookingResponseDTO> content = toResponses(bookingPage.getContent());

        return PagedApiResponse.of(
                "Bookings fetched successfully",
                content,
                PaginationMeta.from(bookingPage, sortBy, sortDir)
        );
    }

    // ── Update ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public BookingResponseDTO update(UUID publicId, UpdateBookingRequestDTO request) {
        log.info("Updating booking publicId: {}", publicId);

        Booking booking = findActiveByPublicId(publicId);
        assertEditableBooking(booking);

        // Computed BEFORE the mapper overwrites the entity, and on "did it MOVE?" rather than the old
        // "was it SENT?".
        //
        // Two defects met here. (1) overseasTourPackage was absent from the trigger although it is a
        // tax INPUT: the mapper applies it (BookingMapperImpl.updateEntity), so reclassifying a booking
        // persisted the new classification while leaving tcs / totalPayable / paymentStatus at the old
        // one — the exact opposite of what this DTO's own javadoc promises. Under an OVERSEAS_ONLY
        // tenant that is real money: flipping a ₹1,00,000 booking to domestic left ₹5,000 of s.206C(1G)
        // TCS over-collected and still reported into sumTcsCollected().
        // (2) Presence-based detection re-ran recomputeTotals on saves that changed nothing — and
        // recomputeTotals reads the tenant's LIVE tax rates, no rate being pinned to the booking — so a
        // form that round-trips every field silently re-rated an old booking at today's rate. Testing
        // for an actual change removes the common case; pinning the rate is the real fix and is not
        // one this method can make (see the note on recomputeTotals).
        boolean amountsChanged = moved(request.getCustomerAmount(), booking.getCustomerAmount())
                || moved(request.getVendorCost(), booking.getVendorCost())
                || (request.getOverseasTourPackage() != null
                    && request.getOverseasTourPackage() != booking.isOverseasTourPackage());

        bookingMapper.updateEntity(request, booking);   // applies non-null DTO fields → entity

        // ── vendorCost is SERVER-OWNED on a marketplace-linked booking ───────────────────────────
        //
        // Invariant: vendorCost = what the tenant typed + every marketplace payable on this booking.
        // BookingExpense's own javadoc states it — VENDOR rows are excluded from totalInternalCosts
        // precisely "because Booking.vendorCost already represents everything paid to suppliers".
        //
        // Without this, an ordinary edit clobbers it: the mapper above applies the client's
        // vendorCost verbatim and recomputeTotals then re-derives from that, silently dropping the
        // platform payable. netProfit is overstated by exactly that amount — and because
        // BookingProfitService FREEZES the figure on cancellation, the error becomes permanent in
        // the credit note. The marketplace cannot fix this from its side; it does not own the field.
        //
        // …but it is protected by a FLOOR, not by addition, and that is the correction here.
        //
        // This used to ADD sumMarketplacePayable to whatever the client sent, on the assumption that
        // the client was sending the tenant-typed portion alone. It cannot: BookingResponseDTO exposes
        // only the COMBINED figure and no field carries the split, so the edit form loads ₹80,000
        // (₹60,000 typed + ₹20,000 platform) and posts ₹80,000 back — which became ₹1,00,000, then
        // ₹1,20,000 on the next save. netProfit fell by ₹20,000 each time, and if the booking was later
        // cancelled the inflated cost froze permanently into sunkVendorCost and the credit note.
        //
        // So the incoming value IS the whole vendorCost, and the payable is defended by refusing to go
        // under it. That is also how CrmBookingLinkAdapter.syncVendorCost reads the field back
        // (typedPortion = vendorCost − marketplacePayable), so writer and reader now agree on what the
        // column means.
        // Patch semantics: only re-point the vendor when the client actually sent one. Treating a
        // null as "clear it" would silently unlink the supplier on every partial update that does not
        // carry the field — which is most of them. Unlinking is therefore its own explicit flag, and
        // it wins over a publicId sent alongside it.
        if (Boolean.TRUE.equals(request.getClearVendor())) {
            applyVendorLink(booking, null);
        } else if (request.getVendorPublicId() != null) {
            applyVendorLink(booking, request.getVendorPublicId());
        }

        if (request.getVendorCost() != null) {
            BigDecimal marketplacePayable = nz(expenseRepository.sumMarketplacePayable(booking.getId()));
            if (nz(booking.getVendorCost()).compareTo(marketplacePayable) < 0) {
                throw new BusinessException(
                        "Vendor cost ₹" + booking.getVendorCost() + " is below the ₹" + marketplacePayable
                                + " payable to the hotel marketplace on this booking.",
                        HttpStatus.CONFLICT);
            }
        }

        // Destination is a free-text snapshot (mapper ignores it) — apply it explicitly.
        if (request.getDestination() != null) {
            booking.setDestinationSnapshot(request.getDestination());
        }

        applyTripSnapshotUpdate(booking, request.getTripSnapshot());

        if (request.getAssignedUserId() != null) {
            booking.setAssignedUserId(assigneeResolver.resolveForUpdate(
                    request.getAssignedUserId(), booking.getAssignedUserId(), booking.getTenantId()));
        }

        if (request.getServices() != null) {
            booking.setServices(new ArrayList<>(request.getServices()));
        }

        // Status is server-owned (mapper ignores it). Apply here behind lifecycle guards so an
        // edit can never silently skip the cancel() flow or mutate a locked booking.
        boolean statusChanged = applyStatusOnUpdate(booking, request.getStatus());

        // Amount changes refresh derived totals first, so any paidAmount target below is validated
        // against the new total payable from this same edit request.
        if (amountsChanged) {
            recomputeTotals(booking, booking.getCustomerAmount(), booking.getVendorCost());

            // Reducing the amount below what has already been paid is inconsistent. Throwing rolls back.
            if (booking.getPaidAmount().compareTo(booking.getTotalPayable()) > 0) {
                throw new BusinessException(
                        "Paid amount ₹" + booking.getPaidAmount()
                            + " exceeds total payable ₹" + booking.getTotalPayable());
            }
        }

        boolean paidAmountChanged = applyPaidAmountOnUpdate(booking, request.getPaidAmount());

        Booking saved = bookingRepository.save(booking);
        if (amountsChanged || paidAmountChanged) {
            commissionService.syncForBooking(saved);
        }

        // Mirror updateStatus(): notify on a real status change so the bell/feed stays in sync.
        if (statusChanged) {
            publishBookingEvent(statusEventType(saved.getStatus()), saved,
                    "Booking " + saved.getStatus() + ": " + saved.getBookingCode(),
                    "Booking " + saved.getBookingCode() + " status changed to " + saved.getStatus());
        }

        return toResponse(saved);
    }

    /**
     * Apply a client-requested status change coming from the general update, guarding the
     * booking lifecycle. Returns {@code true} only if the status actually changed (so the
     * caller can fire the notification).
     *
     * <p>CANCELLED is refused here — it must go through {@code cancel()}, which reopens the
     * linked lead and reclaims a conversion-created customer; setting it via a field edit would
     * skip all of that. A COMPLETED or CANCELLED booking is terminal, so its status is locked.</p>
     */
    private boolean applyStatusOnUpdate(Booking booking, BookingStatus newStatus) {
        if (newStatus == null || newStatus == booking.getStatus()) {
            return false;
        }
        if (newStatus == BookingStatus.CANCELLED) {
            throw new BusinessException(
                    "To cancel a booking, use the cancel action — it reopens the linked lead and "
                            + "cleans up a conversion-created customer. Setting CANCELLED here would skip that.",
                    HttpStatus.CONFLICT);
        }
        if (newStatus == BookingStatus.REFUNDED) {
            throw new BusinessException(
                    "To mark a booking refunded, use the refund flow so the refund ledger, voucher, "
                            + "and commission reversal are recorded.",
                    HttpStatus.CONFLICT);
        }
        if (isTerminalStatus(booking.getStatus())) {
            throw new BusinessException(
                    "A " + booking.getStatus() + " booking is locked; its status can no longer be changed.",
                    HttpStatus.CONFLICT);
        }
        booking.setStatus(newStatus);
        return true;
    }

    private void assertEditableBooking(Booking booking) {
        if (isTerminalStatus(booking.getStatus())) {
            throw new BusinessException(
                    "A " + booking.getStatus() + " booking is locked; it can no longer be edited.",
                    HttpStatus.CONFLICT);
        }
    }

    private static boolean isTerminalStatus(BookingStatus status) {
        return status != null && TERMINAL_STATUSES.contains(status);
    }

    /**
     * The payment ledger's own lifecycle gate — deliberately NARROWER than
     * {@link #assertEditableBooking}, which also locks COMPLETED. A completed trip may still be
     * collecting its final balance, so receipts must keep flowing; what must stop is moving
     * {@code paidAmount} after a cancellation has frozen {@code refundDue} onto the credit/debit
     * note. Kept in step with {@code BookingPaymentServiceImpl.assertLedgerMutable}, which guards
     * the POST/DELETE {@code /payments} twins of this PATCH.
     */
    private static void assertLedgerMutable(Booking booking) {
        if (booking.getStatus() == BookingStatus.CANCELLED
                || booking.getStatus() == BookingStatus.REFUNDED) {
            throw new BusinessException(
                    "A " + booking.getStatus() + " booking's payment ledger is closed — its refund "
                            + "settlement is already frozen on the cancellation note.",
                    HttpStatus.CONFLICT);
        }
    }

    // ── Update Status ────────────────────────────────────────────────────────

    @Override
    @Transactional
    public BookingResponseDTO updateStatus(UUID publicId, StatusUpdateRequestDTO request) {
        log.info("Updating status for booking publicId: {} to {}", publicId, request.getStatus());

        Booking booking = findActiveByPublicId(publicId);

        // Route through the SAME lifecycle guard the general PUT uses. This refuses a CANCELLED
        // transition (cancel must go through cancel() → moveBackToLead + derived-customer cleanup,
        // and is gated by the stricter BOOKING_CANCEL), locks terminal COMPLETED/CANCELLED, and
        // no-ops a same-status request — so a plain BOOKING_UPDATE user can no longer cancel,
        // un-cancel, or reopen a terminal booking through this endpoint.
        boolean statusChanged = applyStatusOnUpdate(booking, request.getStatus());
        if (!statusChanged) {
            return toResponse(booking);   // requested status == current: nothing to do
        }

        Booking saved = bookingRepository.save(booking);
        publishBookingEvent(statusEventType(saved.getStatus()), saved,
                "Booking " + saved.getStatus() + ": " + saved.getBookingCode(),
                "Booking " + saved.getBookingCode() + " status changed to " + saved.getStatus());
        return toResponse(saved);
    }

    // ── Cancel (with explicit lead handling) ──────────────────────────────────

    @Override
    @Transactional
    public BookingResponseDTO cancel(UUID publicId, CancelBookingRequestDTO request) {
        Long tenantId = requireTenantId();
        Booking booking = findActiveByPublicId(publicId);

        // A completed journey is locked: its lead must not revert and its history must not be erased.
        if (booking.getStatus() == BookingStatus.COMPLETED) {
            throw new BusinessException(
                    "A completed booking cannot be cancelled.", HttpStatus.CONFLICT);
        }
        // Advisory pre-check (fast, friendly). The authoritative double-cancel guard is the UNIQUE
        // booking_id on booking_cancellations + the @Version bump on the booking row below.
        if (booking.getStatus() == BookingStatus.CANCELLED
                || cancellationRepository.existsByBookingIdAndDeletedAtIsNull(booking.getId())) {
            throw new BusinessException("This booking is already cancelled.", HttpStatus.CONFLICT);
        }

        // ── Compute the cancellation charge under the booking's governing policy ──
        CancellationPolicy policy = resolveGoverningPolicyForCancel(booking, tenantId);

        BigDecimal override = request.getOverrideChargeBase();
        if (override != null) {
            // Overriding/waiving the computed charge moves money vs the policy — elevated gate.
            requireAuthority("BOOKING_REFUND",
                    "Overriding or waiving the cancellation charge requires refund permission.");
        }
        CancellationQuote quote = cancellationCalculator.calculate(
                booking, policy, LocalDate.now(), override, request.getVendorRecoverable());

        // No configured policy ⇒ a zero charge / full refund — must not be a silent default.
        if (quote.isNoPolicy()) {
            requireAuthority("BOOKING_REFUND",
                    "No cancellation policy is configured for this booking; cancelling it requires "
                            + "refund permission so the zero-charge/full-refund is an explicit decision.");
        }

        // Persist the immutable financial record. The UNIQUE booking_id makes a concurrent/double
        // cancel fail here (rolling back the whole transaction), so a refund can never be issued twice.
        BookingCancellation record = buildCancellationRecord(booking, quote, policy, request);
        cancellationRepository.save(record);

        // Mint the credit/debit note (number + frozen snapshot) inside this transaction so the number
        // is reserved atomically with the money; the PDF bytes render lazily on first fetch. Sets the
        // note number + doc id back on the record.
        cancellationDocumentService.issueCancellationNote(booking, record, quote);

        // Legacy booking with no pinned policy: freeze the resolved version now so it's reproducible.
        if (booking.getCancellationPolicyPublicId() == null && policy != null) {
            booking.setCancellationPolicyPublicId(policy.getPublicId());
            booking.setCancellationPolicyVersion(policy.getVersion());
        }

        // ── Lead / derived-customer disposition (unchanged behaviour) ──
        handleDerivedCustomerOnCancel(booking, tenantId);
        if (request.getAction() == CancelAction.PERMANENT_DELETE_LEAD) {
            requireAuthority("LEAD_PERMANENT_DELETE",
                    "You don't have permission to remove this lead. Please contact your administrator.");
            trashLeadOnCancel(booking, tenantId);
        } else {
            moveBackToLead(booking, tenantId);
        }

        // The booking is ALWAYS retained — only its status changes. Setting status + refundedAmount
        // dirties the row so the @Version optimistic lock serializes two concurrent cancels (the
        // loser gets a 409 instead of a second cancellation record).
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setRefundedAmount(BigDecimal.ZERO);   // nothing disbursed yet; the refund flow accrues it

        // Profit is REVISED, not left at the pre-cancellation figure. The trip did not happen, so
        // customerAmount was never earned; what the agency actually made is the retained charge less
        // the costs it could not recover. Leaving the old margin on the row meant every reader of
        // Booking.netProfit either had to know to exclude cancelled bookings (most did) or silently
        // reported the profit of a trip that never ran (BookingRevenueService did exactly that).
        booking.setTotalInternalCosts(nz(quote.getSunkInternalCosts()));
        booking.setNetProfit(nz(quote.getRevisedNetProfit()));

        Booking saved = bookingRepository.save(booking);

        // Notify only AFTER the money + record commit, so a rolled-back cancel never pushes a
        // "cancelled" SSE / notification row.
        publishBookingEventAfterCommit(NotificationType.BOOKING_CANCELLED, saved,
                "Booking cancelled: " + saved.getBookingCode(),
                "Booking " + saved.getBookingCode() + " was cancelled — retained "
                        + quote.getTotalRetained() + ", "
                        + (quote.isCustomerOwes()
                            ? "customer owes " + quote.getCustomerBalanceOwed()
                            : "refund due " + quote.getRefundToCustomer()));

        // Domain fact, separate from the notification above. Anything riding on this booking — today
        // a hotel-marketplace order still holding a room with a supplier — learns about it here.
        // This module does not know or care who listens; see BookingCancelledEvent.
        publishAfterCommit(new BookingCancelledEvent(
                saved.getPublicId(), saved.getId(), saved.getTenantId(), saved.getBookingCode(),
                request == null ? null : request.getReason()));
        log.info("Booking {} cancelled (tenant {}) — retained {}, refundDue {} [{}]",
                saved.getBookingCode(), tenantId, quote.getTotalRetained(),
                quote.getRefundDue(), record.getRefundStatus());
        return toResponse(saved);
    }

    /**
     * The policy governing this booking's cancellation: the exact version pinned at creation, else
     * (legacy/unpinned) the company default in force as of the booking date. May be null.
     */
    private CancellationPolicy resolveGoverningPolicyForCancel(Booking booking, Long tenantId) {
        CancellationPolicy policy = null;
        if (booking.getCancellationPolicyPublicId() != null) {
            policy = policyResolver.loadPinned(tenantId, booking.getCancellationPolicyPublicId()).orElse(null);
        }
        if (policy == null) {
            policy = policyResolver.companyDefaultAsOf(tenantId, booking.getBookingDate()).orElse(null);
        }
        return policy;
    }

    /** Freeze the computed quote + provenance + who/when into the immutable cancellation record. */
    private BookingCancellation buildCancellationRecord(Booking booking, CancellationQuote quote,
                                                        CancellationPolicy policy,
                                                        CancelBookingRequestDTO request) {
        boolean refundDueOwed = quote.getRefundDue() != null && quote.getRefundDue().signum() > 0;
        Long actorId = currentUserId();
        return BookingCancellation.builder()
                .tenantId(booking.getTenantId())
                .bookingId(booking.getId())
                .bookingCode(booking.getBookingCode())
                .cancelledByUserId(actorId)
                .cancelledByEmail(currentUserEmail())
                .cancelledAt(LocalDateTime.now())
                .cancelDate(quote.getCancelDate())
                .reason(request.getReason())
                .leadAction(request.getAction())
                .policyPublicId(policy != null ? policy.getPublicId() : null)
                .policyVersion(policy != null ? policy.getVersion() : null)
                .noPolicy(quote.isNoPolicy())
                .appliedBandMinDays(quote.getAppliedBandMinDays())
                .appliedDeductionType(quote.getAppliedDeductionType())
                .appliedDeductionValue(quote.getAppliedDeductionValue())
                .daysBeforeDeparture(quote.getDaysBeforeDeparture())
                .baseConsidered(quote.getBaseConsidered())
                .systemComputedChargeBase(quote.getSystemComputedChargeBase())
                .finalChargeBase(quote.getChargeBase())
                .overrideApplied(quote.isOverrideApplied())
                .overriddenByUserId(quote.isOverrideApplied() ? actorId : null)
                .overrideReason(quote.isOverrideApplied() ? request.getOverrideReason() : null)
                .gstOnCharge(quote.getGstOnCharge())
                .tcsRetained(quote.getTcsRetained())
                .totalRetained(quote.getTotalRetained())
                .paidAtCancel(quote.getPaidAmount())
                .refundDue(quote.getRefundDue())
                .customerBalanceOwed(quote.getCustomerBalanceOwed())
                .customerOwes(quote.isCustomerOwes())
                .refundStatus(refundDueOwed ? RefundStatus.PENDING : RefundStatus.NOT_APPLICABLE)
                .sunkVendorCost(quote.getSunkVendorCost())
                .vendorRecoverable(quote.getVendorRecoverable())
                .sunkInternalCosts(quote.getSunkInternalCosts())
                .revisedNetProfit(quote.getRevisedNetProfit())
                .build();
    }

    /** MOVE_TO_LEAD: re-activate the source lead (REOPENED), keeping the booking↔lead link. */
    private void moveBackToLead(Booking booking, Long tenantId) {
        Long leadId = booking.getLeadId();
        if (leadId == null) {
            log.info("Cancel/MOVE_TO_LEAD: booking {} has no associated lead — cancelling only",
                    booking.getBookingCode());
            return;
        }
        leadRepository.findByIdAndTenantIdAndDeletedAtIsNull(leadId, tenantId).ifPresent(lead -> {
            lead.setLeadStage(LeadStage.REOPENED);
            lead.setConvertedAt(null);
            lead.setConvertedBookingPublicId(null);
            leadRepository.save(lead);
            log.info("Lead {} reopened after cancelling booking {}",
                    lead.getPublicId(), booking.getBookingCode());
        });
    }

    /**
     * PERMANENT_DELETE_LEAD: soft-delete (Trash) the associated lead instead of the old hard
     * delete — fully recoverable. The lead's quotations cascade-trash via {@link LeadSoftDeletedEvent}
     * (the same path as a normal lead delete), so nothing is silently orphaned and the logic lives in
     * one place. The booking keeps its lead link, so restoring the lead from Trash reconnects them;
     * the retained cancelled booking stays meaningful via its own customer/destination snapshots.
     *
     * <p>"Permanent" is now a label, not a hard delete: only Trash delete-now and the 30-day
     * auto-purge ever remove a lead physically.</p>
     */
    private void trashLeadOnCancel(Booking booking, Long tenantId) {
        Long leadId = booking.getLeadId();
        if (leadId == null) {
            log.info("Cancel/remove-lead: booking {} has no associated lead — cancelling only",
                    booking.getBookingCode());
            return;
        }
        leadRepository.findByIdAndTenantIdAndDeletedAtIsNull(leadId, tenantId).ifPresent(lead -> {
            lead.softDelete(currentUserEmail());
            leadRepository.save(lead);
            // Cascade-trash the lead's quotations through the shared event (no manual loop here).
            eventPublisher.publishEvent(new LeadSoftDeletedEvent(lead.getId(), tenantId));
            log.warn("Lead {} moved to Trash while cancelling booking {} (recoverable)",
                    lead.getPublicId(), booking.getBookingCode());
        });
    }

    /**
     * Customer-removal guard for cancel. A customer is moved to Trash (soft-deleted) ONLY when
     * BOTH hold:
     *   (a) it was auto-created by a lead→booking conversion ({@code createdFromLeadId} set) —
     *       a manually-entered customer is NEVER reclaimed just because a booking was cancelled;
     *   (b) it has no other active (non-trashed) booking — otherwise it's a repeat customer and
     *       is preserved, the cancelled booking simply staying in its history ({@code customer_id}
     *       is NOT NULL, so the link is kept rather than nulled).
     * Always recoverable, never a hard delete. A COMPLETED booking can't reach here (it can't be
     * cancelled) and counts as "active" for (b), so any customer with completed-journey history —
     * even a conversion-derived one — is inherently protected.
     */
    private void handleDerivedCustomerOnCancel(Booking booking, Long tenantId) {
        Long customerId = booking.getCustomerId();
        if (customerId == null) return;

        long otherActive = bookingRepository
                .countByCustomerIdAndTenantIdAndDeletedAtIsNullAndIdNot(customerId, tenantId, booking.getId());
        if (otherActive > 0) {
            log.info("Cancel: customer {} retained ({} other active booking(s)); booking {} kept linked",
                    customerId, otherActive, booking.getBookingCode());
            return;
        }
        customerRepository.findByIdAndTenantIdAndDeletedAtIsNull(customerId, tenantId).ifPresent(customer -> {
            // Provenance gate: only reclaim customers conversion created itself. A hand-entered
            // customer (createdFromLeadId == null) whose first/only booking is cancelled stays.
            if (customer.getCreatedFromLeadId() == null) {
                log.info("Cancel: customer {} retained (manually created, not conversion-derived); booking {} kept linked",
                        customer.getCustomerCode(), booking.getBookingCode());
                return;
            }
            customer.softDelete(currentUserEmail());
            customerRepository.save(customer);
            log.info("Cancel: derived customer {} moved to Trash (no other active bookings)",
                    customer.getCustomerCode());
        });
    }

    /** Programmatic authority check for conditional gating within a single endpoint. */
    private void requireAuthority(String authority, String denyMessage) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean has = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> authority.equals(a.getAuthority()));
        if (!has) {
            throw new BusinessException(denyMessage, HttpStatus.FORBIDDEN);
        }
    }

    // ── Update Payment ───────────────────────────────────────────────────────

    @Override
    @Transactional
    public BookingResponseDTO updatePayment(UUID publicId, PaymentUpdateRequestDTO request) {
        log.info("Updating payment for booking publicId: {}", publicId);

        Booking booking = findActiveByPublicId(publicId);
        // Same freeze as POST/DELETE /payments: once cancelled, refundDue is frozen on the
        // cancellation note and moving paidAmount underneath it desyncs what the agency owes.
        assertLedgerMutable(booking);

        // ✅ Accumulate — this is an incremental payment, not a replacement
        BigDecimal newPaidAmount = booking.getPaidAmount().add(request.getAmount());

        if (newPaidAmount.compareTo(booking.getTotalPayable()) > 0) {
            throw new BusinessException(
                    "Total paid ₹" + newPaidAmount
                            + " exceeds total payable ₹" + booking.getTotalPayable());
        }

        booking.setPaidAmount(newPaidAmount);
        // pendingAmount is a @Transient getter; paymentStatus never downgrades a terminal REFUNDED.
        booking.setPaymentStatus(
                derivePaymentStatus(newPaidAmount, booking.getTotalPayable(), booking.getPaymentStatus()));

        Booking saved = bookingRepository.save(booking);
        // Record the receipt in the ledger so it shows in GET /payments and the invoice's
        // Payments-Received table — this PATCH path used to move paidAmount without a ledger row.
        recordPaymentLedgerRow(saved, request.getAmount(), "Payment",
                request.getPaymentDate(), request.getPaymentReference(), request.getNotes());
        // Broker commission accrues proportionally to net collection on every payment received.
        commissionService.syncForBooking(saved);
        publishBookingEvent(NotificationType.BOOKING_PAYMENT_UPDATED, saved,
                "Payment updated: " + saved.getBookingCode(),
                "₹" + request.getAmount() + " received for booking " + saved.getBookingCode()
                        + " (" + saved.getPaymentStatus() + ")");
        return toResponse(saved);
    }
    // ── Soft Delete ──────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void delete(UUID publicId) {
        log.info("Soft deleting booking publicId: {}", publicId);

        Booking booking = findActiveByPublicId(publicId);

        if (booking.getStatus() == BookingStatus.CONFIRMED
                || booking.getStatus() == BookingStatus.COMPLETED) {
            throw new BusinessException(
                    "Cannot delete a " + booking.getStatus() + " booking. Cancel it first.");
        }

        booking.softDelete(currentUserEmail());

        bookingRepository.save(booking);
        log.info("Booking soft deleted: {}", booking.getBookingCode());
    }

    // ── Get by Customer ──────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<BookingResponseDTO> getByCustomerId(Long customerId) {
        Long ownerFilter = subAgentScope.ownerFilter();
        return toResponses(bookingRepository.findAllByCustomerIdAndDeletedAtIsNull(customerId)
                .stream()
                .filter(b -> ownerFilter == null || ownerFilter.equals(b.getOwnerUserId()))
                .toList());
    }

    // ── Search ───────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<BookingResponseDTO> search(String keyword) {
        Specification<Booking> spec = BookingSpecification.isActive()
                .and(BookingSpecification.search(keyword));

        Long ownerFilter = subAgentScope.ownerFilter();
        if (ownerFilter != null) spec = spec.and(BookingSpecification.ownedBy(ownerFilter));

        return toResponses(bookingRepository.findAll(spec));
    }

    // ── Filter ───────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<BookingResponseDTO> filter(
            BookingStatus status,
            PaymentStatus paymentStatus,
            Integer bookingMonth,
            Integer travelMonth,
            Long customerId,
            LocalDate fromDate,
            LocalDate toDate,
            BigDecimal minAmount,
            BigDecimal maxAmount
    ) {
        Specification<Booking> spec = BookingSpecification.filter(
                status, paymentStatus, bookingMonth, travelMonth,
                customerId, fromDate, toDate, minAmount, maxAmount);

        Long ownerFilter = subAgentScope.ownerFilter();
        if (ownerFilter != null) spec = spec.and(BookingSpecification.ownedBy(ownerFilter));

        return toResponses(bookingRepository.findAll(spec));
    }

    // ── Stats ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public BookingStatsResponseDTO getStats() {
        // Agency revenue = delivered package sales + the cancellation charge actually retained.
        // A cancelled booking contributes ONLY the retained charge — sumTotalRevenue already
        // excludes it entirely, so nothing is double-counted. All-time, matching every other figure
        // on this endpoint (it carries no date filter).
        BigDecimal revenue  = nz(bookingRepository.sumTotalRevenue());
        BigDecimal retained = nz(cancellationRepository.sumRetainedChargeBase(
                requireTenantId(), LocalDate.of(1970, 1, 1), LocalDate.now()));
        BigDecimal netProfit       = nz(bookingRepository.sumNetProfit());
        BigDecimal cancelledProfit = nz(bookingRepository.sumCancelledProfit());

        return BookingStatsResponseDTO.builder()
                .retainedCancellationCharges(retained)
                .agencyRevenue(revenue.add(retained))
                .cancelledProfit(cancelledProfit)
                .totalProfit(netProfit.add(cancelledProfit))
                .totalBookings(bookingRepository.countByDeletedAtIsNull())
                .confirmedBookings(bookingRepository.countByStatusAndDeletedAtIsNull(BookingStatus.CONFIRMED))
                .pendingBookings(bookingRepository.countByStatusAndDeletedAtIsNull(BookingStatus.PENDING))
                .cancelledBookings(bookingRepository.countByStatusAndDeletedAtIsNull(BookingStatus.CANCELLED))
                .completedBookings(bookingRepository.countByStatusAndDeletedAtIsNull(BookingStatus.COMPLETED))
                .refundedBookings(bookingRepository.countByStatusAndDeletedAtIsNull(BookingStatus.REFUNDED))
                .totalRevenue(bookingRepository.sumTotalRevenue())
                .totalCollected(bookingRepository.sumTotalCollected())
                .totalPending(bookingRepository.sumTotalPending())
                .totalRefundAmount(bookingRepository.sumTotalRefund())
                .netProfit(bookingRepository.sumNetProfit())
                .gstCollected(bookingRepository.sumGstCollected())
                .tcsCollected(bookingRepository.sumTcsCollected())
                .build();
    }

    // ── Page Summary ─────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public BookingPageSummaryResponseDTO getPageSummary(int page, int size, String sortBy, String sortDir) {
        Sort sort = sortDir.equalsIgnoreCase("desc")
                ? Sort.by(sortBy).descending()
                : Sort.by(sortBy).ascending();

        Pageable pageable = PageRequest.of(page, size, sort);
        Page<Booking> bookingPage = bookingRepository.findAll(
                BookingSpecification.isActive(), pageable);

        // Money summary excludes CANCELLED/REFUNDED (mirrors the /stats aggregates) so a cancelled
        // booking's amount/balance never inflates revenue or receivables. isActive() is deliberately
        // left alone — the paginated list, search and CSV export must still SHOW cancelled bookings.
        List<Booking> bookings = bookingPage.getContent().stream()
                .filter(b -> b.getStatus() != BookingStatus.CANCELLED
                        && b.getStatus() != BookingStatus.REFUNDED)
                .toList();

        BigDecimal pageRevenue = bookings.stream()
                .map(Booking::getCustomerAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal pageProfit = bookings.stream()
                .map(Booking::getNetProfit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal pageGST = bookings.stream()
                .map(Booking::getGst)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal pageTCS = bookings.stream()
                .map(Booking::getTcs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal pagePending = bookings.stream()
                .map(Booking::getPendingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return BookingPageSummaryResponseDTO.builder()
                .totalRevenue(pageRevenue)      // ✅ was: pageRevenue
                .netProfit(pageProfit)          // ✅ was: pageProfit
                .gstCollected(pageGST)          // ✅ was: pageGST
                .tcsCollected(pageTCS)          // ✅ was: pageTCS
                .totalPending(pagePending)      // ✅ was: pagePendingAmount
                .build();
    }

    // ── Send Voucher (stub) ──────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public void sendVoucher(UUID publicId) {
        Booking booking = findActiveByPublicId(publicId);
        // Email integration will be wired in future sprint
        log.info("Voucher send requested for booking: {}", booking.getBookingCode());
    }

    // ── Assignment ───────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<BookingAssigneeDto> eligibleAssignees() {
        return assigneeResolver.eligibleUsers(requireTenantId()).stream()
                .map(u -> new BookingAssigneeDto(
                        u.getPublicId(), u.getName(), u.getEmail(),
                        u.getRole() != null ? u.getRole().name() : null))
                .toList();
    }

    // ── Private Helpers ──────────────────────────────────────────────────────

    /**
     * The single place a Booking becomes a full response. Builds the assignee view for exactly the
     * rows being rendered so nothing downstream has to remember to; every {@code toResponse} in this
     * class goes through here or {@link #toResponses(List)}.
     */
    /**
     * Single-booking response. Attaches the trip snapshot, which the list variant deliberately omits
     * — it is a LAZY association whose own itinerary and assistance collections are lazy too, so
     * mapping it per row would cost three extra queries for every booking on a page.
     */
    private BookingResponseDTO toResponse(Booking booking) {
        BookingResponseDTO response = bookingMapper.toResponse(booking, assigneeViewFactory.of(booking))
                .toBuilder()
                .tripSnapshot(toTripSnapshotResponse(booking.getTripSnapshot()))
                .build();
        return maskProfitUnlessAuthorized(response);
    }

    /**
     * Flat entity → the nested shape the form posts and the detail screen reads.
     *
     * <p>{@code preferredTime} folds the flight and train columns back onto the single key the
     * request uses, so a round-trip through create and read returns what was sent.
     *
     * <p>Must be called with the session open (it touches lazy collections) — every caller is inside
     * a {@code @Transactional} service method.
     */
    private TripSnapshotResponse toTripSnapshotResponse(BookingTripSnapshot snapshot) {
        if (snapshot == null) return null;

        DepartureMode mode = snapshot.getDepartureMode();
        TripSnapshotResponse.Departure departure = TripSnapshotResponse.Departure.builder()
                .country(snapshot.getDepartCountry())
                .city(snapshot.getDepartCity())
                .mode(mode)
                .airport(snapshot.getDepartureAirport())
                .airportCode(snapshot.getAirportCode())
                .railwayStation(snapshot.getRailwayStation())
                .trainClass(snapshot.getTrainClass())
                .preferredTime(mode == DepartureMode.TRAIN
                        ? snapshot.getPreferredTrainTime()
                        : snapshot.getPreferredFlightTime())
                .pickupAddress(snapshot.getPickupAddress())
                .pickupDateTime(snapshot.getPickupDateTime())
                .vehiclePreference(snapshot.getVehiclePreference())
                .build();

        return TripSnapshotResponse.builder()
                .packageType(snapshot.getPackageType())
                .departure(departure)
                .travellers(TripSnapshotResponse.Travellers.builder()
                        .rooms(snapshot.getRooms())
                        .male(snapshot.getMale())
                        .female(snapshot.getFemale())
                        .totalAdults(snapshot.getTotalAdults())
                        .children(snapshot.getChildren())
                        .infants(snapshot.getInfants())
                        .extraBeds(snapshot.getExtraBeds())
                        .build())
                .specialAssistance(TripSnapshotResponse.SpecialAssistance.builder()
                        .required(snapshot.getSpecialAssistanceRequired())
                        .types(snapshot.getSpecialAssistanceTypes() == null
                                ? List.of() : new ArrayList<>(snapshot.getSpecialAssistanceTypes()))
                        .passengerCount(snapshot.getAssistancePassengerCount())
                        .notes(snapshot.getSpecialAssistanceNotes())
                        .build())
                .itinerary(snapshot.getItinerary() == null ? List.of()
                        : snapshot.getItinerary().stream()
                                .map(leg -> TripSnapshotResponse.TripLeg.builder()
                                        .destination(leg.getDestination())
                                        .city(leg.getCity())
                                        .nights(leg.getNights())
                                        .dayNumber(leg.getDayNumber())
                                        .build())
                                .toList())
                .notes(snapshot.getNotes())
                .build();
    }

    /**
     * List/page variant — resolves every assignee on the page in ONE query. Mapping the list with
     * {@link #toResponse(Booking)} instead would be a query per row.
     */
    private List<BookingResponseDTO> toResponses(List<Booking> bookings) {
        BookingAssigneeView assignees = assigneeViewFactory.of(bookings);
        return bookings.stream()
                .map(b -> maskProfitUnlessAuthorized(bookingMapper.toResponse(b, assignees)))
                .toList();
    }

    /** Supplier rates and margin are not ordinary booking-read data. */
    private BookingResponseDTO maskProfitUnlessAuthorized(BookingResponseDTO response) {
        if (hasAuthority("BOOKING_PROFIT_READ")) return response;
        return response.toBuilder()
                .vendorCost(null)
                .totalInternalCosts(null)
                .netProfit(null)
                .build();
    }

    private boolean hasAuthority(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> authority.equals(a.getAuthority()));
    }

    private Booking findActiveByPublicId(UUID publicId) {
        Booking booking = bookingRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new BookingNotFoundException(publicId));
        // Single by-id chokepoint: 404s a sub-agent that doesn't own the booking (no-op for others).
        // Covers getById/update/updateStatus/updatePayment/delete/cancel/sendVoucher in one place.
        subAgentScope.assertVisible(booking, publicId);
        return booking;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "TenantContext is empty. Ensure JwtAuthFilter is running.");
        }
        return tenantId;
    }

    private String currentUserEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    /** Current tenant user's internal id, or null (e.g. SuperAdmin) — used as the notification actor. */
    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.getPrincipal() instanceof User u) ? u.getId() : null;
    }

    private static NotificationType statusEventType(BookingStatus status) {
        return switch (status) {
            case CONFIRMED -> NotificationType.BOOKING_CONFIRMED;
            case CANCELLED -> NotificationType.BOOKING_CANCELLED;
            default        -> NotificationType.BOOKING_STATUS_CHANGED;
        };
    }

    /** Fan-out to tenant admins (recipients resolved in the notification module), actor excluded. */
    private void publishBookingEvent(NotificationType type, Booking booking, String title, String message) {
        eventPublisher.publishEvent(buildBookingEvent(type, booking, title, message));
    }

    /**
     * Publish a booking notification only once the surrounding transaction commits. The in-app
     * channel SSE-pushes and writes a DB row synchronously, so publishing inline would fire the
     * notification even when the transaction later rolls back (e.g. the losing side of two concurrent
     * cancels). Falls back to immediate publish if no transaction is active.
     */
    private void publishBookingEventAfterCommit(NotificationType type, Booking booking,
                                                String title, String message) {
        NotifyEvent event = buildBookingEvent(type, booking, title, message);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { eventPublisher.publishEvent(event); }
            });
        } else {
            eventPublisher.publishEvent(event);
        }
    }

    /**
     * Publish an arbitrary domain event once the surrounding transaction commits, with the same
     * rollback safety as {@link #publishBookingEventAfterCommit} — a listener must never act on a
     * cancellation the database went on to roll back.
     */
    private void publishAfterCommit(Object event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { eventPublisher.publishEvent(event); }
            });
        } else {
            eventPublisher.publishEvent(event);
        }
    }

    private NotifyEvent buildBookingEvent(NotificationType type, Booking booking, String title, String message) {
        return NotifyEvent.builder()
                .type(type.name())
                .tenantId(booking.getTenantId())
                .actorUserId(currentUserId())
                .title(title)
                .message(message)
                .referenceType("BOOKING")
                .referencePublicId(booking.getPublicId())
                .channels(Set.of(DeliveryChannel.IN_APP))
                .build();
    }

    /**
     * Recompute the derived money fields (gst / tcs / totalPayable, and — via
     * {@link BookingProfitService} — totalInternalCosts / netProfit) and re-derive
     * paymentStatus from the booking's CURRENT paidAmount. Deliberately does NOT touch paidAmount —
     * that is owned by the payment ledger (POST/DELETE /payments, PATCH /payment), never by an edit
     * of the booking's amounts. Keeps totals consistent when customerAmount / vendorCost change.
     */
    private void recomputeTotals(Booking booking, BigDecimal rawCustomerAmount, BigDecimal rawVendorCost) {
        // Every figure is scaled to the paise, matching numeric(12,2) on the columns. totalPayable is
        // scaled explicitly rather than inheriting whatever scale the client happened to send:
        // @Digits(fraction = 2) caps the INPUT at 2dp but does not force it there, so a request
        // carrying "1000" or "1000.5" would otherwise leave a scale-0/1 value on the entity that does
        // not match what the next read brings back from the DB.
        // Tax comes from THIS TENANT's settings, not a platform constant: whether GST is added at
        // all, at what rate, and whether TCS applies never / only to overseas packages / always.
        // A domestic-only operator sets TCS to NEVER and stops collecting a tax that s.206C(1G)/394
        // does not impose on them.
        //
        // The two INPUTS are scaled here, not just the derived total: @Digits(fraction = 2) caps a
        // request at 2dp but does not force it there, so "1000" arrives as scale 0 and "1000.5" as
        // scale 1. Storing them raw left the in-memory entity — and therefore the create/update
        // response — quoting "1000" where every later GET, having round-tripped numeric(12,2),
        // quotes "1000.00". Same rupee, two strings, from the same endpoint.
        BigDecimal customerAmount = scaleMoney(rawCustomerAmount);
        BigDecimal vendorCost     = scaleMoney(rawVendorCost);

        BookingTaxCalculator.BookingTax tax = bookingTaxCalculator.compute(
                customerAmount, booking.isOverseasTourPackage(), accountingSettings.loadOrCreate(requireTenantId()));

        BigDecimal gst          = tax.gst();
        BigDecimal tcs          = tax.tcs();
        BigDecimal totalPayable = customerAmount.add(gst).add(tcs).setScale(2, RoundingMode.HALF_UP);

        booking.setCustomerAmount(customerAmount);
        booking.setVendorCost(vendorCost);
        booking.setGst(gst);
        booking.setTcs(tcs);
        booking.setTotalPayable(totalPayable);

        // Profit is NOT computed here — BookingProfitService is its only writer, so the formula lives
        // in exactly one place and the expense ledger and the booking edit can never disagree about
        // it. It re-reads both ledger terms itself; on create/convert the row has no id yet and
        // therefore no expense lines, so both are zero.
        profitService.applyInMemory(booking);

        booking.setPaymentStatus(
                derivePaymentStatus(booking.getPaidAmount(), totalPayable, booking.getPaymentStatus()));
    }

    /**
     * Legacy booking edit sends paidAmount as an absolute target. Increasing it is recorded as a
     * receipt adjustment so the booking total and itemised payment ledger stay reconciled. Decreases
     * must delete the incorrect receipt row through the payment ledger endpoint.
     */
    private boolean applyPaidAmountOnUpdate(Booking booking, BigDecimal requestedPaidAmount) {
        if (requestedPaidAmount == null) {
            return false;
        }

        BigDecimal currentPaidAmount = booking.getPaidAmount() != null
                ? booking.getPaidAmount()
                : BigDecimal.ZERO;
        BigDecimal totalPayable = booking.getTotalPayable() != null
                ? booking.getTotalPayable()
                : BigDecimal.ZERO;

        if (requestedPaidAmount.compareTo(totalPayable) > 0) {
            throw new BusinessException(
                    "Paid amount ₹" + requestedPaidAmount
                            + " exceeds total payable ₹" + totalPayable);
        }

        BigDecimal delta = requestedPaidAmount.subtract(currentPaidAmount);
        if (delta.signum() == 0) {
            return false;
        }

        if (delta.signum() < 0) {
            throw new BusinessException(
                    "To reduce paid amount, delete the incorrect payment ledger row.",
                    HttpStatus.CONFLICT);
        }

        booking.setPaidAmount(requestedPaidAmount);
        booking.setPaymentStatus(
                derivePaymentStatus(requestedPaidAmount, totalPayable, booking.getPaymentStatus()));
        recordPaymentLedgerRow(booking, delta, "Payment adjustment", LocalDate.now(), null,
                "Paid amount adjusted from booking edit");
        return true;
    }

    /**
     * Resolve and stamp the cancellation-policy version that governs this booking, once, at creation.
     * {@code quotationPolicyPublicId} is the structured policy the source quotation was priced under
     * (null for a direct booking, which falls back to the tenant company default as-of the booking
     * date). If the tenant somehow has no company default yet, the pin is left null and the cancel
     * flow resolves + pins it on first cancel — the pin is never silently skipped without a trace.
     */
    private void pinCancellationPolicy(Booking booking, UUID quotationPolicyPublicId, Long tenantId) {
        policyResolver.resolveForNewBooking(tenantId, quotationPolicyPublicId, booking.getBookingDate())
                .ifPresentOrElse(p -> {
                    booking.setCancellationPolicyPublicId(p.getPublicId());
                    booking.setCancellationPolicyVersion(p.getVersion());
                }, () -> log.warn("No cancellation policy resolved for booking {} (tenant {}); "
                        + "it will be resolved and pinned at cancel time", booking.getBookingCode(), tenantId));
    }

    /**
     * Resolve the supplier a booking's typed {@code vendorCost} is owed to, and snapshot its name.
     *
     * <p>Tenant-scoped by construction — {@code findByPublicIdAndTenantId}, never a bare
     * {@code findById}, which Hibernate's {@code tenantFilter} does not cover and which would let a
     * booking point at another agency's vendor. A publicId that resolves to nothing is a 404 rather
     * than a silently dropped field: the agent chose a supplier, and a booking that quietly forgot it
     * would send them looking for a payee that is not there.
     *
     * <p>Passing {@code null} CLEARS the link. That is the create contract (no vendor chosen) and,
     * on update, is unreachable by accident — the patch DTO treats null as "leave alone" and only
     * calls this when the client actually sent a value.
     */
    private void applyVendorLink(Booking booking, java.util.UUID vendorPublicId) {
        if (vendorPublicId == null) {
            booking.setVendorPublicId(null);
            booking.setVendorName(null);
            return;
        }
        var vendor = vendorRepository
                .findByPublicIdAndTenantId(vendorPublicId, requireTenantId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Vendor not found: " + vendorPublicId));
        booking.setVendorPublicId(vendor.getPublicId());
        booking.setVendorName(vendor.getVendorName());
    }

    /** Create/convert path: set the initial paidAmount, then compute totals + status. */
    private void calculateAndApplyFinancials(Booking booking,
                                             BigDecimal customerAmount,
                                             BigDecimal vendorCost,
                                             BigDecimal paidAmount) {
        booking.setPaidAmount(paidAmount);
        recomputeTotals(booking, customerAmount, vendorCost);
        if (paidAmount.compareTo(booking.getTotalPayable()) > 0) {
            throw new BusinessException(
                    "Paid amount ₹" + paidAmount
                            + " exceeds total payable ₹" + booking.getTotalPayable());
        }
    }

    /**
     * The create form's live preview. Runs the SAME pieces {@link #recomputeTotals} runs — the
     * tenant's settings through {@link BookingTaxCalculator}, then the payment-status derivation —
     * against the posted amounts, without a Booking entity. The internal-costs term is zero for the
     * same reason it is zero on create: an unsaved booking has no expense ledger.
     *
     * <p>Not {@code readOnly}: {@code accountingSettings.loadOrCreate} seeds the tenant's settings
     * row on first access, and a brand-new tenant's very first preview may be that first access.
     */
    @Override
    @Transactional
    public BookingFinancialPreviewResponse previewFinancials(BookingFinancialPreviewRequest request) {
        BigDecimal customerAmount = request.getCustomerAmount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal vendorCost = request.getVendorCost() != null ? request.getVendorCost() : BigDecimal.ZERO;
        BigDecimal paidAmount = request.getPaidAmount() != null ? request.getPaidAmount() : BigDecimal.ZERO;

        BookingTaxCalculator.BookingTax tax = bookingTaxCalculator.compute(
                customerAmount,
                Boolean.TRUE.equals(request.getOverseasTourPackage()),
                accountingSettings.loadOrCreate(requireTenantId()));

        BigDecimal totalPayable = customerAmount.add(tax.gst()).add(tax.tcs())
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal pending = totalPayable.subtract(paidAmount).max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);

        return BookingFinancialPreviewResponse.builder()
                .gst(tax.gst())
                .tcs(tax.tcs())
                .totalPayable(totalPayable)
                .netProfit(customerAmount.subtract(vendorCost).setScale(2, RoundingMode.HALF_UP))
                .pendingAmount(pending)
                // null current status: a preview has no row, so REFUNDED-preservation cannot apply.
                .paymentStatus(derivePaymentStatus(paidAmount, totalPayable, null))
                .build();
    }

    /**
     * Append a receipt to the payment ledger so {@code booking.paidAmount} and the itemised
     * {@code booking_payments} rows (the invoice's "Payments Received" table + {@code GET /payments})
     * stay reconciled. Does NOT mutate paidAmount — the caller already owns that. {@code tenantId} is
     * auto-stamped by {@code TenantEntityListener}.
     */
    private void recordPaymentLedgerRow(Booking booking, BigDecimal amount, String type,
                                        LocalDate date, String reference, String notes) {
        paymentRepository.save(BookingPayment.builder()
                .bookingId(booking.getId())
                .amount(amount)
                .paymentType(type)
                .paymentDate(date != null ? date : LocalDate.now())
                .reference(reference)
                .notes(notes)
                .build());
    }

    /**
     * True when the client sent a money field AND it differs from what is stored. {@code compareTo},
     * not {@code equals} — the latter is scale-sensitive, so a form posting "1000" back against a
     * stored "1000.00" would read as a change and re-rate the booking.
     */
    private static boolean moved(BigDecimal requested, BigDecimal stored) {
        return requested != null && requested.compareTo(nz(stored)) != 0;
    }

    /** Null-safe 2dp normalisation, matching {@code numeric(12,2)} on every money column. */
    private static BigDecimal scaleMoney(BigDecimal v) {
        return (v != null ? v : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    private PaymentStatus derivePaymentStatus(
            BigDecimal paidAmount, BigDecimal totalPayable, PaymentStatus current) {
        // REFUNDED is terminal — never let a totals recompute or a ledger op downgrade it.
        if (current == PaymentStatus.REFUNDED)          return PaymentStatus.REFUNDED;
        if (paidAmount.compareTo(BigDecimal.ZERO) == 0) return PaymentStatus.UNPAID;
        if (paidAmount.compareTo(totalPayable) >= 0)    return PaymentStatus.PAID;
        return PaymentStatus.PARTIAL;
    }
}
