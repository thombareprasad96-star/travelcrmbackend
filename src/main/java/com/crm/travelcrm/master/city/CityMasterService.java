package com.crm.travelcrm.master.city;


import com.crm.travelcrm.common.context.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CityMasterService {

    private final CityMasterRepository cityMasterRepository;

    private final CityMapper cityMapper;


    public void saveCity(CityMasterRequestDTO request) {
        CityMasterEntity city = CityMapper.toEntity(request);
        // SuperAdmin has no tenant in context → global city; tenant user → own city
        city.setTenantId(TenantContext.getTenantId());

        cityMasterRepository.save(city);
    }

    public Page<CityMasterResponseDTO> getAllCities(
            int page, int size, String sortBy, String sortDir) {

        Sort sort = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        Pageable pageable = PageRequest.of(page, size, sort);

        Long tenantId = TenantContext.getTenantId();
        Page<CityMasterEntity> cityPage = (tenantId == null)
                ? cityMasterRepository.findAll(pageable)
                : cityMasterRepository.findAllVisibleTo(tenantId, pageable);

        return cityPage.map(CityMapper::toResponseDTO);
    }

    public CityMasterResponseDTO getCityById(Long id) {
        return CityMapper.toResponseDTO(getVisibleCity(id));
    }


    public void updateCity(Long id, CityMasterRequestDTO request) {

        CityMasterEntity savedCity = getEditableCity(id);

        CityMapper.updateEntity(savedCity, request);

        cityMasterRepository.save(savedCity);
    }


    public void deleteCity(Long id) {

        CityMasterEntity city = getEditableCity(id);

        cityMasterRepository.delete(city);
    }

    /** Global cities and the caller's own cities are visible; other tenants' are not. */
    private CityMasterEntity getVisibleCity(Long id) {
        CityMasterEntity city = cityMasterRepository.findById(id)
                .orElseThrow(() ->
                        new RuntimeException("City not found with id : " + id));

        Long tenantId = TenantContext.getTenantId();
        if (tenantId != null
                && city.getTenantId() != null
                && !city.getTenantId().equals(tenantId)) {
            // Hide other tenants' cities — report as not found, not forbidden
            throw new RuntimeException("City not found with id : " + id);
        }
        return city;
    }

    /** SuperAdmin may edit anything; a tenant may edit only its own cities (global ones are read-only). */
    private CityMasterEntity getEditableCity(Long id) {
        CityMasterEntity city = getVisibleCity(id);

        Long tenantId = TenantContext.getTenantId();
        if (tenantId != null && !tenantId.equals(city.getTenantId())) {
            throw new RuntimeException("Global cities can only be modified by the platform admin");
        }
        return city;
    }

}