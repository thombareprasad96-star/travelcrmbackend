package com.crm.travelcrm.lead.mapper;

import com.crm.travelcrm.auth.dto.UserDto;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.util.PhoneCanonicalizer;
import com.crm.travelcrm.lead.dto.CreateLeadRequestDto;
import com.crm.travelcrm.lead.dto.LeadResponseDto;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.entity.LeadItinerary;
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
                .travelDate(request.getTravelDate())
                .budget(request.getBudget())
                .departCountry(request.getDepartCountry())
                .departCity(request.getDepartCity())
                .rooms(request.getRooms())
                .adults(request.getAdults())
                .children(request.getChildren())
                .infants(request.getInfants())
                .extraBeds(request.getExtraBeds())
                .services(request.getServices() != null
                        ? new ArrayList<>(request.getServices())
                        : new ArrayList<>())
                .notes(request.getNotes())
                .build();

        if (request.getItinerary() != null) {
            request.getItinerary().forEach(itinReq -> {
                LeadItinerary itinerary = LeadItinerary.builder()
                        .destination(itinReq.getDestination())
                        .city(itinReq.getCity())
                        .nights(itinReq.getNights())
                        .dayNumber(itinReq.getDayNumber())   // ← was missing
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
                                    .build())
                          .collect(Collectors.toList());

        return LeadResponseDto.builder()
                .id(lead.getPublicId())                // ← was lead.getId()
                .customerName(lead.getCustomerName())
                .phone(lead.getPhone())
                .email(lead.getEmail())
                .leadSource(lead.getLeadSource())
                .leadType(lead.getLeadType())
                .leadStage(lead.getLeadStage())
                .assignedUser(toUserDto(lead.getAssignedUser()))
                .birthDate(lead.getBirthDate())
                .travelDate(lead.getTravelDate())
                .budget(lead.getBudget())
                .departCountry(lead.getDepartCountry())
                .departCity(lead.getDepartCity())
                .rooms(lead.getRooms())
                .adults(lead.getAdults())
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
                .convertedAt(lead.getConvertedAt())
                .convertedBookingPublicId(lead.getConvertedBookingPublicId())
                .build();
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