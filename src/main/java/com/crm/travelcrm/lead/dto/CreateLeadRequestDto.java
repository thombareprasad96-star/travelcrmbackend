// ─── CreateLeadRequest.java ───────────────────────────────────────────────────
package com.crm.travelcrm.lead.dto;

import com.crm.travelcrm.customer.enums.CommunicationPreference;
import com.crm.travelcrm.lead.enums.DepartureMode;
import com.crm.travelcrm.lead.enums.LeadSource;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.lead.enums.LeadType;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Data
public class CreateLeadRequestDto {

    @NotBlank(message = "Customer name is required")
    @Size(min = 2, max = 150, message = "Customer name must be between 2 and 150 characters")
    private String customerName;

    @NotBlank(message = "Phone number is required")
    @Pattern(
            regexp = "^\\+?[1-9]\\d{7,14}$",
            message = "Enter a valid phone number"
    )
    private String phone;




    @Email(message = "Enter a valid email address")
    @Size(max = 150, message = "Email must not exceed 150 characters")
    private String email;

    @NotNull(message = "Lead source is required")
    private LeadSource leadSource;

    @NotNull(message = "Lead type is required")
    private LeadType leadType;

    @NotNull(message = "Lead stage is required")
    private LeadStage leadStage;

    // publicId of the tenant user this lead is assigned to — every lead
    // must have an owner (assigned_user_id is NOT NULL in the database)
    @NotNull(message = "Assigned user is required")
    private UUID assignedUserId;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate birthDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate anniversaryDate;

    /** Same vocabulary as {@code Customer.commPref} so conversion can copy it across unchanged. */
    private CommunicationPreference preferredCommunication;

    /** Intended call-back date. The create screen also posts a follow-up log to raise the Reminder. */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate followUpDate;

    @Size(max = 50, message = "Package type must not exceed 50 characters")
    private String packageType;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate travelDate;

    // ── Departure / transport ────────────────────────────────────────────────
    // departureMode gates which of the three groups below is filled in. No cross-field validation
    // here on purpose: a clerk who picks Flight, types an airport, then switches to Train must not
    // be blocked by the now-irrelevant airport value — the service clears the unused groups instead.

    private DepartureMode departureMode;

    @Size(max = 150, message = "Departure airport must not exceed 150 characters")
    private String departureAirport;

    @Size(max = 10, message = "Airport code must not exceed 10 characters")
    private String airportCode;

    /** {@code <input type="time">} — sends "HH:mm". */
    @JsonFormat(pattern = "HH:mm")
    private LocalTime preferredFlightTime;

    @Size(max = 150, message = "Railway station must not exceed 150 characters")
    private String railwayStation;

    @Size(max = 50, message = "Train class must not exceed 50 characters")
    private String trainClass;

    @JsonFormat(pattern = "HH:mm")
    private LocalTime preferredTrainTime;

    @Size(max = 1000, message = "Pickup address must not exceed 1000 characters")
    private String pickupAddress;

    /** {@code <input type="datetime-local">} — sends "yyyy-MM-dd'T'HH:mm", no seconds, no zone. */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    private LocalDateTime pickupDateTime;

    @Size(max = 150, message = "Vehicle preference must not exceed 150 characters")
    private String vehiclePreference;

    // ── Accessibility ────────────────────────────────────────────────────────

    private Boolean specialAssistanceRequired;

    @Min(value = 0, message = "Assistance passenger count cannot be negative")
    private Integer assistancePassengerCount;

    private List<String> specialAssistanceTypes;

    @Size(max = 2000, message = "Assistance notes must not exceed 2000 characters")
    private String specialAssistanceNotes;

    // Customer budget in INR. Frontend Kanban sends this as `budget`.
    @DecimalMin(value = "0.0", message = "Budget cannot be negative")
    @Digits(integer = 13, fraction = 2, message = "Budget is invalid")
    private BigDecimal budget;

    @Size(max = 100)
    private String departCountry;

    @Size(max = 100)
    private String departCity;

    @Min(value = 0, message = "Rooms cannot be negative")
    private Integer rooms;

    @Min(value = 1, message = "At least 1 adult is required")
    private Integer adults;

    @Min(value = 0, message = "Male count cannot be negative")
    private Integer male;

    @Min(value = 0, message = "Female count cannot be negative")
    private Integer female;

    /**
     * The form sends the adult total under BOTH {@code adults} and {@code totalAdults}. Bound here
     * purely so the two stay interchangeable; the service prefers {@code adults} and falls back to
     * this. Not persisted separately — {@code Lead.adults} is the one column.
     */
    @Min(value = 0, message = "Adult count cannot be negative")
    private Integer totalAdults;

    @Min(value = 0, message = "Children count cannot be negative")
    private Integer children;

    @Min(value = 0, message = "Infants count cannot be negative")
    private Integer infants;

    @Min(value = 0, message = "Extra beds count cannot be negative")
    private Integer extraBeds;

    private List<String> services;

    @Size(max = 2000, message = "Notes must not exceed 2000 characters")
    private String notes;

    @Valid
    private List<LeadItineraryRequestDto> itinerary;
}


