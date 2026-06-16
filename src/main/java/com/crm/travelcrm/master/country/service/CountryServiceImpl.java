package com.crm.travelcrm.master.country.service;

import com.crm.travelcrm.master.country.CountryMapper.CountryMapper;
import com.crm.travelcrm.master.country.Dto.CountryRequestDTO;
import com.crm.travelcrm.master.country.Dto.CountryResponseDTO;
import com.crm.travelcrm.master.country.entity.Country;
import com.crm.travelcrm.master.country.repository.CountryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CountryServiceImpl implements CountryService {

    private final CountryRepository countryRepository;
    private final CountryMapper countryMapper;

    @Override
    public String createCountries(List<CountryRequestDTO> requests) {

        List<Country> countries = new ArrayList<>();

        for (CountryRequestDTO countryRequestDTO : requests) {

            if (countryRepository.existsByCountryNameIgnoreCase(
                    countryRequestDTO.getCountryName())) {
                throw new RuntimeException(
                        "Country name already exists: " + countryRequestDTO.getCountryName());
            }

            if (countryRepository.existsByCountryCodeIgnoreCase(
                    countryRequestDTO.getCountryCode())) {
                throw new RuntimeException(
                        "Country code already exists: " + countryRequestDTO.getCountryCode());
            }
            countries.add(countryMapper.toEntity(countryRequestDTO));
        }
        countryRepository.saveAll(countries);
        return countries.size() + " countries created successfully";
    }


    @Override
    public List<CountryResponseDTO> getAllCountries() {

        return countryRepository.findAllByOrderByCountryNameAsc()
                .stream()
                .map(countryMapper::toResponse)
                .toList();
    }
}