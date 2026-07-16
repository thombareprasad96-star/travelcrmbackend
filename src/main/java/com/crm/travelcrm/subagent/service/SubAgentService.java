package com.crm.travelcrm.subagent.service;

import com.crm.travelcrm.subagent.dto.CreateSubAgentRequest;
import com.crm.travelcrm.subagent.dto.CreateSubAgentResponse;
import com.crm.travelcrm.subagent.dto.SubAgentResponse;
import com.crm.travelcrm.subagent.dto.UpdateSubAgentRequest;

import java.util.List;
import java.util.UUID;

public interface SubAgentService {

    CreateSubAgentResponse create(CreateSubAgentRequest request);

    List<SubAgentResponse> list();

    SubAgentResponse get(UUID publicId);

    SubAgentResponse update(UUID publicId, UpdateSubAgentRequest request);

    void delete(UUID publicId);
}
