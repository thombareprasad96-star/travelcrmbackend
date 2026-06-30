package com.crm.travelcrm.lead.dto;

import com.crm.travelcrm.lead.enums.LeadStage;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class LeadLogResponseDto {
    private UUID id;                 // publicId — never the internal Long id
    private String comment;
    private LeadStage stage;         // stage snapshot at log time
    private LocalDate followUpDate;
    private String addedBy;          // author display name
    private LocalDateTime createdAt;
}