package com.crm.travelcrm.quotation.dto;

import com.crm.travelcrm.quotation.enums.QuotationStage;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Lightweight quotation row for list/grid views (paginated listing & by-lead list).
 * Carries just enough to render a card without loading every nested collection.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QuotationSummaryDto {

    private UUID publicId;
    private UUID leadId;          // Lead.publicId
    private String title;
    private String version;
    private Integer versionNumber;
    private String pdfUrl;
    private QuotationStage stage;
    private String customerName;
    private String destination;
    private LocalDate travelDate;
    private BigDecimal grandTotal;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}