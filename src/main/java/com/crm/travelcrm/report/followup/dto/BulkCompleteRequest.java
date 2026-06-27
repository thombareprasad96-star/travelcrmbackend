package com.crm.travelcrm.report.followup.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

/** Body of {@code PATCH /api/reports/followup/tasks/bulk-complete}: reminder publicIds to complete. */
@Getter
@Setter
@NoArgsConstructor
public class BulkCompleteRequest {
    private List<UUID> ids;
}