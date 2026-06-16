package com.crm.travelcrm.vendor.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class VendorResponseDTO {

    private Long id;
    private String vendorCode;
    private String vendorName;
    private String vendorType;
    private String contactPerson;
    private String phone;
    private String alternatePhone;
    private String email;
    private String whatsapp;
    private String contractType;
    private String paymentTerms;
    private String commPref;
    private String status;
    private String payStatus;

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
    private BigDecimal totalBusiness;
    private BigDecimal totalPaid;
    private BigDecimal outstanding;

    private String bankName;
    private String accountName;
    private String accountNumber;
    private String ifscCode;
    private String upiId;

    private String gstNumber;
    private String panNumber;

    private Double rating;
    private Integer ratingCount;
    private Boolean verified;

    private String notes;
    private String specialConditions;

    private LocalDateTime joinDate;
}