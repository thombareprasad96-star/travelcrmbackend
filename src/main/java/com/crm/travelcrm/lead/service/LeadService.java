package com.crm.travelcrm.lead.service;

import com.crm.travelcrm.lead.dto.CreateLeadRequestDto;
import com.crm.travelcrm.lead.dto.LeadResponseDto;
import com.crm.travelcrm.lead.dto.UserLeadStageCountDto;
import com.crm.travelcrm.lead.dto.UserWorkloadDto;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.UUID;

public interface LeadService {
    LeadResponseDto createLead(CreateLeadRequestDto request);
    Page<LeadResponseDto> getAllLeads(int page, int size, String sortBy, String sortDir);
    LeadResponseDto searchLead(String keyword);
    LeadResponseDto updateLead(UUID publicId, CreateLeadRequestDto request);  // ← UUID
    void deleteLead(UUID publicId);                                            // ← UUID

    // ── Statistics ────────────────────────────────────────────────────────────
    long getLeadCountForUser(UUID userPublicId);
    List<UserWorkloadDto> getUserWorkload();
    List<UserLeadStageCountDto> getLeadStageBreakdownPerUser();
}