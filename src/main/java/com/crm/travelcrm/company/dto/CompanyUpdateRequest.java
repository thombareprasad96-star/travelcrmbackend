package com.crm.travelcrm.company.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

// JSON-field update for the company profile (logo/favicon have their own endpoints).
@Data
public class CompanyUpdateRequest {

    @NotBlank(message = "Company name is required")
    @Size(max = 255)
    private String name;

    @Size(max = 10, message = "Prefix must be 10 characters or fewer")
    private String prefix;

    @NotBlank(message = "Email is required")
    @Email(message = "Enter a valid email")
    @Size(max = 255)
    private String email;

    @Size(max = 20)
    private String phone;

    @Size(max = 500)
    private String website;

    private Integer operatingSince;
    private Integer totalReviews;
    private Integer tripsSold;

    @Size(max = 15)
    private String gstin;

    @Size(max = 10)
    private String tan;

    private String address;

    @Size(max = 100)
    private String state;
}