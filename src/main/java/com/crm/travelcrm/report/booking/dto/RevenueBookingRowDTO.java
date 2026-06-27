package com.crm.travelcrm.report.booking.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row of the Booking Revenue table. External id is {@code publicId}. {@code type} (trip type)
 * and {@code customerPhone} have no source on the Booking entity and are returned null.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevenueBookingRowDTO {
    private UUID       publicId;
    private String     code;
    private String     customer;
    private String     customerDetail;
    private String     customerPhone;
    private BigDecimal customerAmount;
    private BigDecimal tcs;
    private BigDecimal totalPayable;
    private BigDecimal paid;
    private BigDecimal due;
    private BigDecimal vendorCost;
    private BigDecimal netProfit;
    private double     netMargin;
    private String     type;
    private String     status;
    private String     travelDate;
    private String     createdDate;
}