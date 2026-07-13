package com.crm.travelcrm.booking.dto.response;

import com.crm.travelcrm.booking.enums.ServiceItemStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
public class BookingServiceItemResponse {

    private UUID              publicId;
    private String            serviceType;
    private String            title;
    private String            description;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate         serviceDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate         endDate;

    private ServiceItemStatus status;
    private BigDecimal        cost;
    private BigDecimal        vendorCost;

    // Vendor snapshot
    private UUID              vendorPublicId;
    private String            vendorName;

    private String            confirmationNumber;
    private String            notes;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime     createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime     updatedAt;
}