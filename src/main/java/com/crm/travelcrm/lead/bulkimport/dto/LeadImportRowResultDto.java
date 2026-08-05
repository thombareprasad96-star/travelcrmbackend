package com.crm.travelcrm.lead.bulkimport.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/** One row's verdict. {@code rowNumber} is the line number in the user's own file, so they can fix it. */
@Data
@Builder
public class LeadImportRowResultDto {

    private int rowNumber;

    /** Echoed back so the report is readable without re-opening the spreadsheet. */
    private String customerName;
    private String phone;

    private LeadImportOutcome outcome;

    /** Why — one entry per problem, all of them, not just the first. Empty when the row is fine. */
    private List<String> messages;

    // ── Set only when the row was actually created (outcome = IMPORTED) ───────
    private UUID leadPublicId;
    private String leadCode;
}
