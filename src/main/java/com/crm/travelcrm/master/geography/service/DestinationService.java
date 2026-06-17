package com.crm.travelcrm.master.geography.service;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.master.geography.dto.request.CreateDestinationRequest;
import com.crm.travelcrm.master.geography.dto.request.UpdateDestinationRequest;
import com.crm.travelcrm.master.geography.dto.response.DestinationDto;
import com.crm.travelcrm.master.geography.dto.response.DestinationListResponseDTO;
import org.springframework.data.domain.Page;

public interface DestinationService {

    /** Paginated list — SuperAdmin sees all; tenant user sees global + own. */
    Page<DestinationListResponseDTO> getAllDestinations(
            int page, int size, String sortBy, String sortDir);

    /** Destinations scoped to a specific country (tenant-checked). */
    PagedApiResponse<DestinationDto> getByCountry(
            Long countryId, int page, int size, String sortBy, String sortDir);

    /** Single destination by id (tenant-scoped). */
    DestinationDto getById(Long destinationId);

    /**
     * Create via nested route: POST /countries/{countryId}/destinations.
     * countryId comes from the URL path variable.
     */
    DestinationDto create(Long countryId, CreateDestinationRequest request);

    /**
     * Create via flat route: POST /destinations.
     * countryId must be present in the request body.
     */
    DestinationDto create(CreateDestinationRequest request);

    /** Partial update — null fields are ignored. */
    DestinationDto update(Long destinationId, UpdateDestinationRequest request);

    /** Hard delete — cascades to cities and their children. */
    void delete(Long destinationId);
}