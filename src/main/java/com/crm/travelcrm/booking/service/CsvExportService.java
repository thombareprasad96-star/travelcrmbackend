package com.crm.travelcrm.booking.service;

import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.mapper.BookingMapper;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.booking.specification.BookingSpecification;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CsvExportService {

    private static final Logger log = LogManager.getLogger(CsvExportService.class);

    private final BookingRepository bookingRepository;
    private final BookingMapper     bookingMapper;

    @Transactional(readOnly = true)
    public byte[] exportBookings() {
        log.info("Starting CSV export for all active bookings");

        List<Booking> bookings = bookingRepository.findAll(BookingSpecification.isActive());

        ByteArrayOutputStream out    = new ByteArrayOutputStream();
        PrintWriter           writer = new PrintWriter(out, true, StandardCharsets.UTF_8);

        // Manually write header — compatible with all versions
        String[] headers = {
                "Booking Code", "Customer Name", "Destination",
                "Booking Date", "Travel Date", "Status", "Payment Status",
                "Customer Amount", "Vendor Cost", "GST", "TCS",
                "Total Payable", "Paid Amount", "Pending Amount", "Net Profit",
                "Services", "Created By", "Created At"
        };

        try (CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT)) {

            // print header row
            printer.printRecord((Object[]) headers);

            for (Booking b : bookings) {
                printer.printRecord(
                        b.getBookingCode(),
                        b.getCustomerNameSnapshot(),
                        b.getDestinationSnapshot(),
                        b.getBookingDate(),
                        b.getTravelDate(),
                        b.getStatus(),
                        b.getPaymentStatus(),
                        b.getCustomerAmount(),
                        b.getVendorCost(),
                        b.getGst(),
                        b.getTcs(),
                        b.getTotalPayable(),
                        b.getPaidAmount(),
                        b.getPendingAmount(),
                        b.getNetProfit(),
                        b.getServices() != null ? String.join(", ", b.getServices()) : "",
                        b.getCreatedBy(),
                        b.getCreatedAt()
                );
            }

            printer.flush();
            log.info("CSV export completed. Total records: {}", bookings.size());
            return out.toByteArray();

        } catch (IOException e) {
            log.error("Failed to generate CSV export", e);
            throw new RuntimeException("Failed to generate CSV export", e);
        }
    }
}