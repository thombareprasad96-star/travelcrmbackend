package com.crm.travelcrm.company.dto;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder
public class CompanyDTO {
    UUID    publicId;
    String  name;
    String  prefix;
    String  email;
    String  phone;
    String  website;
    Integer operatingSince;
    Integer totalReviews;
    Integer tripsSold;
    String  gstin;
    String  tan;
    String  status;
    String  address;
    String  state;
    String  logoUrl;
    String  faviconUrl;
    String  createdDate;   // formatted e.g. "May 29, 2026"
}