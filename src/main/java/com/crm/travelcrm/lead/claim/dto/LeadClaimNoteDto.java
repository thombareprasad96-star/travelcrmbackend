package com.crm.travelcrm.lead.claim.dto;

import lombok.Data;

/**
 * Optional body for the actions that take nothing but a reason ({@code /contacted},
 * {@code /reopen-claim}). A one-field DTO rather than a query parameter because the reason is free
 * text a user types — a note containing "&" or a newline has no business in a URL, and it would land
 * in access logs.
 */
@Data
public class LeadClaimNoteDto {

    /** Surfaced in the assignment history. Trimmed and truncated to 255 by the service. */
    private String note;
}
