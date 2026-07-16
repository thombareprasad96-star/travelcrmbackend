package com.crm.travelcrm.marketing.service;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.marketing.dto.*;

import java.util.List;
import java.util.UUID;

public interface SegmentService {

    PagedApiResponse<SegmentResponse> list(int page, int size, String sortBy, String sortDir, String search);

    ApiResponse<SegmentResponse> get(UUID publicId);

    ApiResponse<SegmentResponse> create(CreateSegmentRequest request);

    ApiResponse<SegmentResponse> update(UUID publicId, UpdateSegmentRequest request);

    ApiResponse<Void> delete(UUID publicId);

    ApiResponse<SegmentPreviewResponse> preview(SegmentPreviewRequest request);

    PagedApiResponse<SegmentMemberResponse> members(UUID publicId, int page, int size);

    List<SegmentFieldDef> fields();
}