package com.crm.travelcrm.report.intldomestic.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** A top destination row (grouped by the booking's destination snapshot). */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DestinationDTO {
    private String     name;
    private String     country;
    private long       bookings;
    private BigDecimal revenue;
}