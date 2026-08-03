package com.crm.travelcrm.lead.mapper;

import com.crm.travelcrm.auth.dto.UserDto;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.util.PhoneCanonicalizer;
import com.crm.travelcrm.lead.dto.CreateLeadRequestDto;
import com.crm.travelcrm.lead.dto.LeadResponseDto;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.entity.LeadItinerary;
import com.crm.travelcrm.lead.enums.DepartureMode;
import com.crm.travelcrm.lead.ingest.LeadActor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class LeadMapper {

    /**
     * Country prefix assumed for phones typed without one, when canonicalising into
     * {@code phone_normalized}. Affects the shadow column only — the raw {@code phone} is never
     * rewritten.
     */
    private final String defaultCountryCode;

    public LeadMapper(@Value("${app.lead.default-country-code:+91}") String defaultCountryCode) {
        this.defaultCountryCode = defaultCountryCode;
    }

    /**
     * The canonical form of a phone for {@code leads.phone_normalized}, using this deployment's
     * default country.
     *
     * <p>Exposed so the update path and the backfill derive the key through the <em>same</em> code as
     * create. A second call site doing its own {@code PhoneCanonicalizer.canonical(phone, "+91")}
     * would hardcode the country and drift the moment the property changes.
     */
    public String canonicalPhone(String rawPhone) {
        return PhoneCanonicalizer.canonical(rawPhone, defaultCountryCode);
    }

    /**
     * The <b>single construction point</b> for a {@link Lead} — human and machine paths both land
     * here, which is what keeps {@code origin}, {@code sourceIntegrationId} and
     * {@code phoneNormalized} impossible to forget on either.
     *
     * <p>The {@code actor} is mandatory rather than nullable-defaulting-to-MANUAL: a forgotten actor
     * must fail to compile, not silently mint a lead that claims a human made it.
     */
    public Lead toEntity(CreateLeadRequestDto request, LeadActor actor) {
        Lead lead = Lead.builder()
                .customerName(request.getCustomerName())
                .phone(request.getPhone())
                // Canonical shadow key. Derived here because this is the single construction point
                // for a Lead — deriving it in the service would mean the ingest path could forget to.
                .phoneNormalized(canonicalPhone(request.getPhone()))
                // Null-guarded: leads.email is nullable now (an IVR call has a phone and nothing
                // else), and this used to be an unguarded .toLowerCase() — an NPE the moment the
                // first email-less inbound lead arrived. The DTO's @NotBlank hides this on the human
                // path only: the machine path builds the DTO programmatically, so no @Valid runs.
                .email(request.getEmail() == null ? null : request.getEmail().toLowerCase())
                // origin and sourceIntegrationId come from the ACTOR, never from the request — a DTO
                // field for either would let a caller self-declare INTEGRATION origin and forge
                // attribution.
                .origin(actor.origin())
                .sourceIntegrationId(actor.integrationId())
                .leadSource(request.getLeadSource())
                .leadType(request.getLeadType())
                .leadStage(request.getLeadStage())
                // assignedUser is resolved from assignedUserId in the service
                // (tenant-scoped lookup) and set there
                .birthDate(request.getBirthDate())
                .anniversaryDate(request.getAnniversaryDate())
                .preferredCommunication(request.getPreferredCommunication())
                .followUpDate(request.getFollowUpDate())
                .packageType(request.getPackageType())
                .travelDate(request.getTravelDate())
                .budget(request.getBudget())
                .departCountry(request.getDepartCountry())
                .departCity(request.getDepartCity())
                // Transport: only the group the mode selects survives — see clearUnusedTransport.
                .departureMode(request.getDepartureMode())
                .departureAirport(request.getDepartureAirport())
                .airportCode(upper(request.getAirportCode()))
                .preferredFlightTime(request.getPreferredFlightTime())
                .railwayStation(request.getRailwayStation())
                .trainClass(request.getTrainClass())
                .preferredTrainTime(request.getPreferredTrainTime())
                .pickupAddress(request.getPickupAddress())
                .pickupDateTime(request.getPickupDateTime())
                .vehiclePreference(request.getVehiclePreference())
                .specialAssistanceRequired(request.getSpecialAssistanceRequired())
                .assistancePassengerCount(request.getAssistancePassengerCount())
                .specialAssistanceTypes(request.getSpecialAssistanceTypes() != null
                        ? new ArrayList<>(request.getSpecialAssistanceTypes())
                        : new ArrayList<>())
                .specialAssistanceNotes(request.getSpecialAssistanceNotes())
                .rooms(request.getRooms())
                .adults(resolveAdults(request))
                .male(request.getMale())
                .female(request.getFemale())
                .children(request.getChildren())
                .infants(request.getInfants())
                .extraBeds(request.getExtraBeds())
                .services(request.getServices() != null
                        ? new ArrayList<>(request.getServices())
                        : new ArrayList<>())
                .notes(request.getNotes())
                .build();

        clearUnusedTransport(lead);
        clearUnusedAssistance(lead);

        if (request.getItinerary() != null) {
            request.getItinerary().forEach(itinReq -> {
                LeadItinerary itinerary = LeadItinerary.builder()
                        .destination(itinReq.getDestination())
                        .city(itinReq.getCity())
                        .nights(itinReq.getNights())
                        .dayNumber(itinReq.getDayNumber())   // ← was missing
                        .destinationId(itinReq.getDestinationId())
                        .cityId(itinReq.getCityId())
                        .build();
                lead.addItinerary(itinerary);
            });
        }

        return lead;
    }

    public LeadResponseDto toResponse(Lead lead) {
        List<LeadResponseDto.ItineraryItem> itineraryItems =
                lead.getItinerary() == null
                        ? Collections.emptyList()
                        : lead.getItinerary().stream()
                          .map(i -> LeadResponseDto.ItineraryItem.builder()
                                    .id(i.getPublicId())           // ← was i.getId()
                                    .destination(i.getDestination())
                                    .city(i.getCity())
                                    .nights(i.getNights())
                                    .dayNumber(i.getDayNumber())   // ← was missing
                                    .destinationId(i.getDestinationId())
                                    .cityId(i.getCityId())
                                    .build())
                          .collect(Collectors.toList());

        return LeadResponseDto.builder()
                .id(lead.getPublicId())                // ← was lead.getId()
                .leadCode(lead.getLeadCode())          // display reference; id stays the UUID
                .customerName(lead.getCustomerName())
                .phone(lead.getPhone())
                .email(lead.getEmail())
                .leadSource(lead.getLeadSource())
                .leadType(lead.getLeadType())
                .leadStage(lead.getLeadStage())
                .assignedUser(toUserDto(lead.getAssignedUser()))
                .birthDate(lead.getBirthDate())
                .anniversaryDate(lead.getAnniversaryDate())
                .preferredCommunication(lead.getPreferredCommunication())
                .followUpDate(lead.getFollowUpDate())
                .packageType(lead.getPackageType())
                .travelDate(lead.getTravelDate())
                .budget(lead.getBudget())
                .departCountry(lead.getDepartCountry())
                .departCity(lead.getDepartCity())
                .departureMode(lead.getDepartureMode())
                .departureAirport(lead.getDepartureAirport())
                .airportCode(lead.getAirportCode())
                .preferredFlightTime(lead.getPreferredFlightTime())
                .railwayStation(lead.getRailwayStation())
                .trainClass(lead.getTrainClass())
                .preferredTrainTime(lead.getPreferredTrainTime())
                .pickupAddress(lead.getPickupAddress())
                .pickupDateTime(lead.getPickupDateTime())
                .vehiclePreference(lead.getVehiclePreference())
                .specialAssistanceRequired(lead.getSpecialAssistanceRequired())
                .assistancePassengerCount(lead.getAssistancePassengerCount())
                // Same defensive copy as services: a PersistentBag in the DTO defers initialization
                // to Jackson, which runs after the transaction has already closed.
                .specialAssistanceTypes(lead.getSpecialAssistanceTypes() == null
                        ? Collections.emptyList()
                        : new ArrayList<>(lead.getSpecialAssistanceTypes()))
                .specialAssistanceNotes(lead.getSpecialAssistanceNotes())
                .rooms(lead.getRooms())
                .adults(lead.getAdults())
                // The create form reads the adult total back under `totalAdults`; the rest of the app
                // reads `adults`. One column, echoed under both names, so neither reader breaks.
                .totalAdults(lead.getAdults())
                .male(lead.getMale())
                .female(lead.getFemale())
                .children(lead.getChildren())
                .infants(lead.getInfants())
                .extraBeds(lead.getExtraBeds())
                // Copy into a plain list while the session is open — putting the
                // Hibernate PersistentBag into the DTO defers initialization to
                // Jackson, which runs after the transaction has closed.
                .services(lead.getServices() == null
                        ? Collections.emptyList()
                        : new ArrayList<>(lead.getServices()))
                .notes(lead.getNotes())
                .itinerary(itineraryItems)
                .createdAt(lead.getCreatedAt())
                .customerId(lead.getCustomerPublicId())
                .convertedAt(lead.getConvertedAt())
                .convertedBookingPublicId(lead.getConvertedBookingPublicId())
                .build();
    }

    // ── Shared write-side rules ──────────────────────────────────────────────
    // Public because updateLead sets fields manually and does NOT go through toEntity. Every rule
    // that shapes a Lead on write has to live somewhere both paths can reach, or the field saves
    // correctly on create and then quietly reverts on the first edit.

    /**
     * The adult count, preferring {@code adults} and falling back to {@code totalAdults}.
     *
     * <p>The form sends the same number under both keys and the app reads it back under both. One
     * column stores it; this decides which key wins when they disagree (they should not, but a
     * hand-built payload can).
     */
    public Integer resolveAdults(CreateLeadRequestDto request) {
        if (request.getAdults() != null) return request.getAdults();
        if (request.getTotalAdults() != null) return request.getTotalAdults();
        // Last resort: the split, when a caller sent only male/female.
        if (request.getMale() != null || request.getFemale() != null) {
            return (request.getMale() == null ? 0 : request.getMale())
                    + (request.getFemale() == null ? 0 : request.getFemale());
        }
        return null;
    }

    /**
     * Null out the transport fields that do not belong to the chosen {@code departureMode}.
     *
     * <p>The form hides the irrelevant groups but does not clear them: pick Flight, type an airport,
     * switch to Train, and the payload still carries the airport. Persisting that leaves a lead
     * claiming a train journey with an airport code attached — and the moment someone reads the
     * transport block without checking the mode first, it reads as real data. Cleared here rather
     * than rejected in validation so switching modes stays a free action for the clerk.
     */
    public void clearUnusedTransport(Lead lead) {
        DepartureMode mode = lead.getDepartureMode();

        if (mode != DepartureMode.FLIGHT) {
            lead.setDepartureAirport(null);
            lead.setAirportCode(null);
            lead.setPreferredFlightTime(null);
        }
        if (mode != DepartureMode.TRAIN) {
            lead.setRailwayStation(null);
            lead.setTrainClass(null);
            lead.setPreferredTrainTime(null);
        }
        if (mode != DepartureMode.CAR) {
            lead.setPickupAddress(null);
            lead.setPickupDateTime(null);
            lead.setVehiclePreference(null);
        }
    }

    /** Same rule for the accessibility block: unticking "assistance required" must clear its detail. */
    public void clearUnusedAssistance(Lead lead) {
        if (!Boolean.TRUE.equals(lead.getSpecialAssistanceRequired())) {
            lead.setAssistancePassengerCount(0);
            lead.setSpecialAssistanceNotes(null);
            if (lead.getSpecialAssistanceTypes() != null) {
                lead.getSpecialAssistanceTypes().clear();
            }
        }
    }

    private String upper(String value) {
        return value == null ? null : value.trim().toUpperCase();
    }

    // Must be called while the Hibernate session is still open (inside the
    // @Transactional service method) so the lazy proxy can be initialized.
    private UserDto toUserDto(User user) {
        if (user == null) return null;
        return new UserDto(
                user.getPublicId(),
                user.getName(),
                user.getRole().name(),
                user.getEmail());
    }
}