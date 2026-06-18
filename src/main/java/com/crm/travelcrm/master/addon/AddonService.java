package com.crm.travelcrm.master.addon;

import com.crm.travelcrm.common.dto.PagedApiResponse;

public interface AddonService {

    PagedApiResponse<AddonDto> getAll(int page, int size, String sortBy, String sortDir);

    PagedApiResponse<AddonDto> getByCity(Long cityId, int page, int size, String sortBy, String sortDir);

    AddonDto getById(Long id);

    AddonDto create(CreateAddonRequest request);

    AddonDto update(Long id, UpdateAddonRequest request);

    void delete(Long id);
}