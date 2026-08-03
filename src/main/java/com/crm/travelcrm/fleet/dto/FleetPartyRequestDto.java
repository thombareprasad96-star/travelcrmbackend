package com.crm.travelcrm.fleet.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Create/update payload for a hired-vehicle owner, supplier or garage. */
@Getter
@Setter
public class FleetPartyRequestDto {

    @NotBlank(message = "Name is required")
    @Size(max = 150)
    private String name;

    @Size(max = 120)
    private String contactPerson;

    @Size(max = 20)
    private String phone;

    @Email(message = "Enter a valid email")
    @Size(max = 150)
    private String email;

    @Size(max = 400)
    private String address;

    @Size(max = 100)
    private String city;

    @Size(max = 20)
    private String gstin;

    @Size(max = 15)
    private String pan;

    @Size(max = 120)
    private String bankName;

    @Size(max = 150)
    private String accountName;

    @Size(max = 40)
    private String accountNumber;

    @Size(max = 15)
    private String ifscCode;

    @Size(max = 100)
    private String upiId;

    @PositiveOrZero(message = "A rate cannot be negative")
    private BigDecimal agreedRate;

    @Size(max = 1000)
    private String notes;

    /** Null on create means active. Setting false retires the party without touching its history. */
    private Boolean active;
}
