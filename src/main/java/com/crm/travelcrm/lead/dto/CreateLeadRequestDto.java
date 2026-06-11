// ─── CreateLeadRequest.java ───────────────────────────────────────────────────
package com.crm.travelcrm.lead.dto;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.lead.enums.LeadSource;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.lead.enums.LeadType;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

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

    @NotBlank(message = "Email is required")
    @Email(message = "Enter a valid email address")
    @Size(max = 150, message = "Email must not exceed 150 characters")
    private String email;

    @NotNull(message = "Lead source is required")
    private LeadSource leadSource;

    @NotNull(message = "Lead type is required")
    private LeadType leadType;

    @NotNull(message = "Lead stage is required")
    private LeadStage leadStage;

    private User assignedUser;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate birthDate;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate travelDate;

    @Size(max = 100)
    private String departCountry;

    @Size(max = 100)
    private String departCity;

    @Min(value = 0, message = "Rooms cannot be negative")
    private Integer rooms;

    @Min(value = 1, message = "At least 1 adult is required")
    private Integer adults;

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


