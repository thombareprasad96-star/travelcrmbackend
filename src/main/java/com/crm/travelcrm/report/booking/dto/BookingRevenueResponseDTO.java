package com.crm.travelcrm.report.booking.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/** Paginated revenue rows — read as {@code res.data.bookings} / {@code res.data.total} by the FE. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingRevenueResponseDTO {
    private List<RevenueBookingRowDTO> bookings;
    private long total;
    private int  page;
    private int  perPage;
    private int  totalPages;
}