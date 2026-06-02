// ─── LeadService.java (Interface) ────────────────────────────────────────────
package com.crm.travelcrm.lead.service;


import com.crm.travelcrm.lead.dto.CreateLeadRequest;
import com.crm.travelcrm.lead.dto.LeadResponse;
import org.springframework.data.domain.Page;

public interface LeadService {
    LeadResponse createLead(CreateLeadRequest request);

    LeadResponse searchLead(String keyword);

    Page<LeadResponse> getAllLeads(int page, int size, String sortBy, String sortDir);
}