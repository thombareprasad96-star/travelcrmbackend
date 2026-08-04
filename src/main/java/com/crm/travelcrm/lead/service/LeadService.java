package com.crm.travelcrm.lead.service;

import com.crm.travelcrm.lead.dto.CreateLeadRequestDto;
import com.crm.travelcrm.lead.dto.LeadBoardColumnDto;
import com.crm.travelcrm.lead.dto.LeadResponseDto;
import com.crm.travelcrm.lead.dto.LeadStatsSummaryDto;
import com.crm.travelcrm.lead.dto.UserLeadStageCountDto;
import com.crm.travelcrm.lead.dto.UserWorkloadDto;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.lead.ingest.IngestPolicy;
import com.crm.travelcrm.lead.ingest.LeadActor;
import org.springframework.data.domain.Page;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface LeadService {
    /** The interactive path: the authenticated user is the actor. */
    LeadResponseDto createLead(CreateLeadRequestDto request);

    /**
     * The one shared create path. Machine ingestion calls this with a non-human {@link LeadActor}
     * rather than forking a parallel implementation — a fork drifts on quota, dedup and assignment,
     * and there is exactly one caller of the 1-arg form to keep in step
     * ({@code LeadController.java:49}).
     *
     * <p><b>Privilege note:</b> this takes a caller-supplied actor, so it is only as safe as
     * {@link LeadActor} being un-forgeable. Read that class before widening its factories.
     */
    LeadResponseDto createLead(CreateLeadRequestDto request, LeadActor actor, IngestPolicy policy);
    /** Unfiltered list — delegates to the filtered form with every filter null. */
    Page<LeadResponseDto> getAllLeads(int page, int size, String sortBy, String sortDir);

    /**
     * The leads list with server-side narrowing. {@code stage} and {@code leadType} arrive as the
     * enums' wire {@code displayName} ("New Lead", "Fresh"), not the constant name; {@code fromDate}
     * /{@code toDate} bound {@code createdAt} inclusively. Any null/blank filter is simply not
     * applied, so this is a superset of {@link #getAllLeads(int, int, String, String)}.
     */
    Page<LeadResponseDto> getAllLeads(int page, int size, String sortBy, String sortDir,
                                      String search, String stage, String leadType,
                                      LocalDate fromDate, LocalDate toDate);
    LeadResponseDto getLeadById(UUID publicId);                                // ← UUID
    LeadResponseDto searchLead(String keyword);
    LeadResponseDto updateLead(UUID publicId, CreateLeadRequestDto request);  // ← UUID
    void deleteLead(UUID publicId);                                            // ← UUID

    // ── Kanban board ────────────────────────────────────────────────────────────
    /** All leads grouped into the seven pipeline columns, with per-column roll-ups. */
    List<LeadBoardColumnDto> getLeadBoard();

    /** Move a single lead to a new stage (drag-and-drop) without touching other fields. */
    LeadResponseDto updateLeadStage(UUID publicId, LeadStage newStage);

    // ── Statistics ────────────────────────────────────────────────────────────
    long getLeadCountForUser(UUID userPublicId);
    List<UserWorkloadDto> getUserWorkload();
    List<UserLeadStageCountDto> getLeadStageBreakdownPerUser();

    /**
     * The All-Leads dashboard roll-up, computed in the database over the caller's whole row-level
     * scope — the cards must not be a client-side reduce over one page of leads.
     *
     * @param from first day of the reporting window (inclusive); null ⇒ start of the tenant's
     *             current calendar month, in the TENANT's timezone
     * @param to   last day of the window (inclusive); null ⇒ the tenant's today
     */
    LeadStatsSummaryDto getStatsSummary(LocalDate from, LocalDate to);
}