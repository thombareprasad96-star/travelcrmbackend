package com.crm.travelcrm.customer.dto.request;

import com.crm.travelcrm.customer.enums.CommunicationPreference;
import com.crm.travelcrm.customer.enums.CustomerStatus;
import com.crm.travelcrm.customer.enums.CustomerType;
import com.crm.travelcrm.customer.enums.LoyaltyTier;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;

/**
 * Payload for creating a customer. Field names mirror the body assembled in
 * {@code Createcustomer.jsx} exactly. Validation runs at the controller boundary
 * (via {@code @Valid}) before the service layer is ever reached.
 */
@Data
public class CreateCustomerRequest {

    @NotBlank(message = "Full name is required")
    @Size(min = 2, max = 150, message = "Name must be between 2 and 150 characters")
    private String name;

    @NotBlank(message = "Phone number is required")
    @Pattern(
            // Mirrors the front-end rule: digits, spaces, +, -, () between 7 and 16 chars
            regexp = "^[+\\d\\s\\-()\\u00A0]{7,16}$",
            message = "Enter a valid phone number"
    )
    private String phone;

    @Email(message = "Enter a valid email address")
    @Size(max = 150, message = "Email must not exceed 150 characters")
    private String email;

    @Size(max = 20, message = "Alternate phone must not exceed 20 characters")
    private String alternatePhone;

    /** Defaults to INDIVIDUAL in the service if omitted. */
    private CustomerType type;

    private CommunicationPreference commPref;

    /** Defaults to BRONZE in the service if omitted. */
    private LoyaltyTier tier;

    /** Defaults to ACTIVE in the service if omitted. */
    private CustomerStatus status;

    @Size(max = 100, message = "City must not exceed 100 characters")
    private String city;

    @Size(max = 100, message = "State must not exceed 100 characters")
    private String state;

    @Size(max = 1000, message = "Address must not exceed 1000 characters")
    private String address;

    @Pattern(regexp = "^$|^\\d{4,8}$", message = "Enter a valid pincode")
    private String pincode;

    @JsonFormat(pattern = "yyyy-MM-dd")
    @PastOrPresent(message = "Birthday cannot be in the future")
    private LocalDate birthday;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate anniversary;

    @Size(max = 30, message = "Passport number must not exceed 30 characters")
    private String passportNo;

    @Pattern(
            regexp = "^$|^[A-Za-z]{5}[0-9]{4}[A-Za-z]$",
            message = "Invalid PAN format"
    )
    private String panNo;

    @Size(max = 20, message = "Aadhar number must not exceed 20 characters")
    private String aadharNo;

    @Size(max = 2000, message = "Document notes must not exceed 2000 characters")
    private String documents;

    @Size(max = 2000, message = "Notes must not exceed 2000 characters")
    private String notes;
}