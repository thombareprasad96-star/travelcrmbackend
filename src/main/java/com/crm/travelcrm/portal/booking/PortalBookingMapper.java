package com.crm.travelcrm.portal.booking;

import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.portal.booking.dto.TravelerBookingDetailDto;
import com.crm.travelcrm.portal.booking.dto.TravelerBookingSummaryDto;
import com.crm.travelcrm.portal.booking.dto.TravelerPaymentStatusDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written, whitelist mapper Booking → traveler-safe DTOs. The single place that decides which
 * booking fields a customer may see; internal fields ({@code vendorCost}, {@code netProfit},
 * {@code customerId}, snapshots used only by staff) are simply never copied.
 */
@Component
public class PortalBookingMapper {

    public TravelerBookingSummaryDto toSummary(Booking b) {
        return TravelerBookingSummaryDto.builder()
                .publicId(b.getPublicId())
                .bookingCode(b.getBookingCode())
                .destination(b.getDestinationSnapshot())
                .bookingDate(b.getBookingDate())
                .travelDate(b.getTravelDate())
                .status(b.getStatus())
                .paymentStatus(b.getPaymentStatus())
                .totalPayable(b.getTotalPayable())
                .paidAmount(b.getPaidAmount())
                .pendingAmount(b.getPendingAmount())
                .build();
    }

    public TravelerBookingDetailDto toDetail(Booking b) {
        return TravelerBookingDetailDto.builder()
                .publicId(b.getPublicId())
                .bookingCode(b.getBookingCode())
                .destination(b.getDestinationSnapshot())
                .bookingDate(b.getBookingDate())
                .travelDate(b.getTravelDate())
                .status(b.getStatus())
                .paymentStatus(b.getPaymentStatus())
                .baseAmount(b.getCustomerAmount())
                .gst(b.getGst())
                .tcs(b.getTcs())
                .totalPayable(b.getTotalPayable())
                .paidAmount(b.getPaidAmount())
                .pendingAmount(b.getPendingAmount())
                .services(b.getServices() == null ? new ArrayList<>() : new ArrayList<>(b.getServices()))
                .build();
    }

    public TravelerPaymentStatusDto toPaymentStatus(Booking b) {
        return TravelerPaymentStatusDto.builder()
                .bookingPublicId(b.getPublicId())
                .bookingCode(b.getBookingCode())
                .totalPayable(b.getTotalPayable())
                .paidAmount(b.getPaidAmount())
                .pendingAmount(b.getPendingAmount())
                .paymentStatus(b.getPaymentStatus())
                .build();
    }

    public List<TravelerBookingSummaryDto> toSummaries(List<Booking> bookings) {
        List<TravelerBookingSummaryDto> out = new ArrayList<>(bookings.size());
        for (Booking b : bookings) out.add(toSummary(b));
        return out;
    }
}
