package com.crm.travelcrm.quotationtemplate.service;

import com.crm.travelcrm.quotationtemplate.dto.TemplateMatchRequest;
import com.crm.travelcrm.quotationtemplate.dto.TemplateMatchResponse;

import java.util.List;

public interface TemplateMatchService {

    /** Rank this tenant's active templates against a lead. Highest match first, weakest dropped. */
    List<TemplateMatchResponse> match(TemplateMatchRequest request);
}