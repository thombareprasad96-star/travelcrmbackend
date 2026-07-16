package com.crm.travelcrm.marketing.service;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.marketing.dto.*;
import com.crm.travelcrm.marketing.enums.DripStatus;
import com.crm.travelcrm.marketing.enums.EnrollmentStatus;

import java.util.UUID;

public interface DripService {

    PagedApiResponse<DripSequenceResponse> list(int page, int size, String sortBy, String sortDir, String search, DripStatus status);

    ApiResponse<DripSequenceResponse> get(UUID publicId);

    ApiResponse<DripSequenceResponse> create(CreateDripSequenceRequest request);

    ApiResponse<DripSequenceResponse> update(UUID publicId, UpdateDripSequenceRequest request);

    ApiResponse<Void> delete(UUID publicId);

    ApiResponse<DripSequenceResponse> activate(UUID publicId);

    ApiResponse<DripSequenceResponse> pause(UUID publicId);

    PagedApiResponse<DripEnrollmentResponse> enrollments(UUID publicId, int page, int size, EnrollmentStatus status);
}