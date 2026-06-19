package com.crm.travelcrm.lead.service;

import com.crm.travelcrm.lead.dto.CreateLeadRequestDto;
import com.crm.travelcrm.lead.dto.LeadBoardColumnDto;
import com.crm.travelcrm.lead.dto.LeadResponseDto;
import com.crm.travelcrm.lead.dto.UserLeadStageCountDto;
import com.crm.travelcrm.lead.dto.UserWorkloadDto;
import com.crm.travelcrm.lead.enums.LeadStage;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.UUID;

public interface LeadService {
    LeadResponseDto createLead(CreateLeadRequestDto request);
    Page<LeadResponseDto> getAllLeads(int page, int size, String sortBy, String sortDir);
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
}