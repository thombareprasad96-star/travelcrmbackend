package com.crm.travelcrm.lead.service;

import com.crm.travelcrm.lead.dto.CreateLeadRequestDto;
import com.crm.travelcrm.lead.dto.LeadResponseDto;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface LeadService {
    LeadResponseDto createLead(CreateLeadRequestDto request);
    Page<LeadResponseDto> getAllLeads(int page, int size, String sortBy, String sortDir);
    LeadResponseDto searchLead(String keyword);
    LeadResponseDto updateLead(UUID publicId, CreateLeadRequestDto request);  // ← UUID
    void deleteLead(UUID publicId);                                            // ← UUID
}