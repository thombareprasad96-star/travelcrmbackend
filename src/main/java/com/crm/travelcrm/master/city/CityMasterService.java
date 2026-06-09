package com.crm.travelcrm.master.city;


import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CityMasterService {

    private final CityMasterRepository cityMasterRepository;

    private final CityMapper cityMapper;


    public void saveCity(CityMasterRequestDTO request) {
        CityMasterEntity city = CityMasterEntity.builder()
                .country(request.getCountry())
                .city(request.getName())
                .airportCode(request.getCode())
                .status(request.getStatus())
                .build();

        cityMasterRepository.save(city);
    }

    public Page<CityMasterResponseDTO> getAllCities(
            int page, int size, String sortBy, String sortDir) {

        Sort sort = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        Pageable pageable = PageRequest.of(page, size, sort);
        Page<CityMasterEntity> cityPage = cityMasterRepository.findAll(pageable);

        return cityPage.map(CityMapper::toResponseDTO);
    }

    public CityMasterResponseDTO getCityById(Long id) {
        CityMasterEntity city = cityMasterRepository.findById(id)
                .orElseThrow(() ->
                        new RuntimeException("City not found with id : " + id));

        return cityMapper.toResponseDTO(city);
    }


    public void updateCity(Long id, CityMasterRequestDTO request) {

        CityMasterEntity savedCity = cityMasterRepository.findById(id)
                .orElseThrow(() ->
                        new RuntimeException("City not found with id : " + id));

        savedCity.setCountry(request.getCountry());
        savedCity.setCity(request.getName());
        savedCity.setAirportCode(request.getCode());
        savedCity.setStatus(request.getStatus());

        cityMasterRepository.save(savedCity);
    }


    public void deleteCity(Long id) {

        CityMasterEntity city = cityMasterRepository.findById(id)
                .orElseThrow(() ->
                        new RuntimeException("City not found with id : " + id));

        cityMasterRepository.delete(city);
    }


}
