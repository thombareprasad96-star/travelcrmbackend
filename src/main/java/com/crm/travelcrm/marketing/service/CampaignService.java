package com.crm.travelcrm.marketing.service;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.marketing.dto.*;
import com.crm.travelcrm.marketing.enums.CampaignStatus;
import com.crm.travelcrm.marketing.enums.MarketingChannel;
import com.crm.travelcrm.marketing.enums.RecipientStatus;

import java.util.UUID;

public interface CampaignService {

    PagedApiResponse<CampaignResponse> list(int page, int size, String sortBy, String sortDir,
                                            String search, CampaignStatus status, MarketingChannel channel);

    ApiResponse<CampaignSummaryResponse> summary();

    ApiResponse<CampaignResponse> get(UUID publicId);

    ApiResponse<CampaignResponse> create(CreateCampaignRequest request);

    ApiResponse<CampaignResponse> update(UUID publicId, UpdateCampaignRequest request);

    ApiResponse<Void> delete(UUID publicId);

    ApiResponse<CampaignResponse> send(UUID publicId);

    ApiResponse<CampaignResponse> cancel(UUID publicId);

    ApiResponse<Void> test(UUID publicId, SendTestRequest request);

    PagedApiResponse<CampaignRecipientResponse> recipients(UUID publicId, int page, int size, RecipientStatus status);
}