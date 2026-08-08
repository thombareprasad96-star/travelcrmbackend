package com.crm.travelcrm.lead.dto;

import com.crm.travelcrm.auth.dto.UserDto;
import com.crm.travelcrm.customer.dto.response.CustomerMatchResponse;
import com.crm.travelcrm.customer.enums.CommunicationPreference;
import com.crm.travelcrm.lead.enums.DepartureMode;
import com.crm.travelcrm.lead.enums.LeadSource;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.lead.enums.LeadType;
import com.crm.travelcrm.quotation.dto.QuotationRefDto;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class LeadResponseDto {

    private UUID id;             // ← was Long, now UUID (exposes publicId)

    /**
     * Human-readable reference ("LD-26-0001") — what the UI shows. Distinct from {@link #id}, which
     * stays the UUID every route/link is keyed by. Null only on rows that predate the column and
     * were never backfilled.
     */
    private String leadCode;

    private String customerName;
    private String phone;
    private String email;
    private LeadSource leadSource;
    private LeadType leadType;
    private LeadStage leadStage;
    private UserDto assignedUser;
    private LocalDate birthDate;
    private LocalDate anniversaryDate;
    private CommunicationPreference preferredCommunication;
    private LocalDate followUpDate;
    private String packageType;
    private LocalDate travelDate;
    private BigDecimal budget;
    private Long logCount;

    /**
     * When this lead was last touched — the newest non-deleted {@code LeadLog.createdAt}.
     *
     * <p>Null means no activity has ever been logged, which is NOT the same as never contacted:
     * {@link #firstContactedAt} is the server's own stamp and an agent can move the stage without
     * writing a log. Read the two together.
     *
     * <p>Exists so the list can tell a lead that is going cold from one worked this morning.
     * {@code logCount} answered "has anyone ever spoken to them"; it could never answer "when".
     */
    private LocalDateTime lastActivityAt;
    private String departCountry;
    private String departCity;

    // ── Departure / transport ────────────────────────────────────────────────
    // departureMode must be returned or the edit form reopens with the transport section unset and
    // every field below it orphaned — it is the discriminator that decides which trio is rendered.
    private DepartureMode departureMode;
    private String departureAirport;
    private String airportCode;
    @JsonFormat(pattern = "HH:mm")
    private LocalTime preferredFlightTime;
    private String railwayStation;
    private String trainClass;
    @JsonFormat(pattern = "HH:mm")
    private LocalTime preferredTrainTime;
    private String pickupAddress;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    private LocalDateTime pickupDateTime;
    private String vehiclePreference;

    // ── Accessibility ────────────────────────────────────────────────────────
    private Boolean specialAssistanceRequired;
    private Integer assistancePassengerCount;
    private List<String> specialAssistanceTypes;
    private String specialAssistanceNotes;

    private Integer rooms;
    private Integer adults;
    /** Echoed under the second name the create form uses, so either key reads the same value. */
    private Integer totalAdults;
    private Integer male;
    private Integer female;
    private Integer children;
    private Integer infants;
    private Integer extraBeds;
    private List<String> services;
    private String notes;
    private List<ItineraryItem> itinerary;
    private List<RoomAllocationItem> roomAllocations;
    private LocalDateTime createdAt;

    // ── Existing-customer link ───────────────────────────────────────────────

    /** publicId of the customer this lead is linked to, or null if none matched. */
    private UUID customerId;

    /**
     * Populated on the CREATE response when an existing customer was matched by phone or email —
     * this is the signal the frontend renders as the "Customer found with this email / phone" popup.
     *
     * <p>Absent (null) on every other response, including creates that matched nothing: the popup
     * shows if and only if this object is present, so an unconditional empty object would make it
     * fire on every new customer.
     */
    private CustomerMatchResponse customerMatch;

    /** Ref to this lead's newest quotation (null when none) — drives the "View/Download vs Create" UI. */
    private QuotationRefDto latestQuotation;

    // ── Conversion state (Lead → Booking) ─────────────────────────────────────
    // Non-null once the lead has been converted. The UI uses convertedBookingPublicId
    // to relabel the "Convert to Booking" action to "View Booking" and prevent a
    // second accidental conversion.
    private LocalDateTime convertedAt;
    private UUID convertedBookingPublicId;

    // ── Claim window / first-response SLA ─────────────────────────────────────
    // Present on every lead response, list included: the claim button submits the version it saw,
    // so a list that omitted it would need a detail fetch per row before anyone could claim.

    /**
     * The compare-and-swap value to submit with a claim. Never null on the wire — legacy rows read
     * as 0 (see the mapper), because a null here would become {@code null} in the JSON and the
     * client would post it straight back as the expected version.
     */
    private int claimVersion;

    /** True while anyone eligible may claim this lead (not yet contacted, not terminal). */
    private boolean openToClaim;

    /** When first contact was made — null while the claim window is still open. */
    private LocalDateTime firstContactedAt;

    /** Measured create → first-contact time. Survives a manager reopening the window. */
    private Long firstResponseSeconds;

    /** The SLA target pinned at creation, so the client's countdown matches the server's judgement. */
    private Integer slaTargetSeconds;

    @Data
    @Builder
    public static class ItineraryItem {
        private UUID id;           // ← publicId
        private String destination;
        private String city;
        private Integer nights;
        private Integer dayNumber; // ← was missing

        // The master ids behind the two names. EditLead seeds its cascading Destination -> City
        // dropdowns from these; without them both selects reopen blank on a lead that plainly has
        // a destination and a city, and re-saving the lead loses the linkage entirely.
        private Long destinationId;
        private Long cityId;
    }

    @Data
    @Builder
    public static class RoomAllocationItem {
        private UUID id;
        private Integer roomNumber;
        private String roomCategoryPreference;
        private String bedPreference;
        private Integer adults;
        private Integer children;
        private Integer infants;
        private Integer extraBeds;
        private List<Integer> childAges;
    }
}
