package com.crm.travelcrm.report.followup.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Result of a bulk-complete: how many succeeded / failed plus a display message. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkCompleteResponseDTO {
    private int    completed;
    private int    failed;
    private String message;
}