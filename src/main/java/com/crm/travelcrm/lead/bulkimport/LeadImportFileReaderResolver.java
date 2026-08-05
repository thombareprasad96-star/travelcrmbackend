package com.crm.travelcrm.lead.bulkimport;

import com.crm.travelcrm.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Picks the {@link LeadImportFileReader} for an upload. A factory rather than an if/else in the
 * service, so a new format is a new bean and nothing else — the same shape as {@code OtpSenderResolver}.
 */
@Component
@RequiredArgsConstructor
public class LeadImportFileReaderResolver {

    private final List<LeadImportFileReader> readers;

    public LeadImportFileReader resolve(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("Choose a file to import.", HttpStatus.BAD_REQUEST);
        }
        String filename = file.getOriginalFilename();
        String contentType = file.getContentType();

        return readers.stream()
                .filter(r -> r.supports(filename, contentType))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        "Unsupported file type. Upload a .csv, .xlsx or .xls file.",
                        HttpStatus.BAD_REQUEST));
    }
}
