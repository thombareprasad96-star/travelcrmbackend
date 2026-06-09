package com.crm.travelcrm.common.util;

import com.crm.travelcrm.booking.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BookingCodeGenerator {

    private static final Logger log = LogManager.getLogger(BookingCodeGenerator.class);

    private static final String PREFIX       = "BK";
    private static final long   STARTING_SEQ = 10001L;

    private final BookingRepository bookingRepository;

    /**
     * Generates the next booking code by finding the highest existing sequence number.
     * Format: BK10001, BK10002, ...
     */
    public String generate() {
        long nextSeq = bookingRepository.findTopByOrderByIdDesc()
                .map(booking -> {
                    String code = booking.getBookingCode();
                    try {
                        return Long.parseLong(code.substring(PREFIX.length())) + 1;
                    } catch (NumberFormatException e) {
                        log.warn("Could not parse booking code '{}', falling back to starting sequence", code);
                        return STARTING_SEQ;
                    }
                })
                .orElse(STARTING_SEQ);

        String code = PREFIX + nextSeq;
        log.debug("Generated booking code: {}", code);
        return code;
    }
}