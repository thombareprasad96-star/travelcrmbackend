package com.crm.travelcrm.vendor.util;

import com.crm.travelcrm.vendor.repository.VendorRepository;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VendorCodeGenerator {

    private static final Logger log = LogManager.getLogger(VendorCodeGenerator.class);

    private static final String PREFIX       = "VND";
    private static final long   STARTING_SEQ = 10001L;

    private final VendorRepository vendorRepository;

    public String generate() {
        long nextSeq = vendorRepository.findTopByOrderByIdDesc()
                .map(vendor -> {
                    String code = vendor.getVendorCode();
                    try {
                        return Long.parseLong(code.substring(PREFIX.length())) + 1;
                    } catch (NumberFormatException e) {
                        log.warn("Could not parse vendor code '{}', using starting sequence", code);
                        return STARTING_SEQ;
                    }
                })
                .orElse(STARTING_SEQ);

        String code = PREFIX + nextSeq;
        log.debug("Generated vendor code: {}", code);
        return code;
    }
}