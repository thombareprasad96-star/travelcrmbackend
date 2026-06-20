package com.crm.travelcrm.vendor.dto.request;

import com.crm.travelcrm.vendor.enums.VendorStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class VendorRequestDTO {

    @NotBlank(message = "Vendor name is required")
    private String vendorName;

    @NotBlank(message = "Vendor type is required")
    private String vendorType;

    private String contactPerson;

    @NotBlank(message = "Phone number is required")
    private String phone;

    private String alternatePhone;
    private String email;
    private String whatsapp;
    private String contractType;
    private String paymentTerms;
    private String commPref;
    /** Optional on create — defaults to ACTIVE server-side. */
    private VendorStatus status;

    private String city;
    private String state;
    private String country;
    private String address;
    private String pincode;
    private String coverageAreas;

    private List<String> services;
    private String serviceDescription;

    private BigDecimal commissionRate;
    private String currency;
    private String creditPeriod;
    private BigDecimal creditLimit;
    private BigDecimal openingBalance;

    private String bankName;
    private String accountName;
    private String accountNumber;
    private String ifscCode;
    private String upiId;

    private String gstNumber;
    private String panNumber;

    private String notes;
    private String specialConditions;
}