package com.crm.travelcrm.master.sightseeing;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface SightseeingService {

    PagedApiResponse<SightseeingDto> getAll(int page, int size, String sortBy, String sortDir);

    PagedApiResponse<SightseeingDto> filter(String destination, String city, int page, int size, String sortBy, String sortDir);

    PagedApiResponse<SightseeingDto> getByCity(Long cityId, int page, int size, String sortBy, String sortDir);

    SightseeingDto getById(Long id);

    SightseeingDto create(CreateSightseeingRequest request);

    SightseeingDto update(Long id, UpdateSightseeingRequest request);

    void delete(Long id);

    String uploadImage(MultipartFile file);

    List<SightseeingDto> search(String q);

    PagedApiResponse<SightseeingDto> getByDestionation(Long destionationId, int page, int size, String sortBy, String sortDir);
}