package com.crm.travelcrm.master.country.service;

import com.crm.travelcrm.master.country.Dto.CountryRequestDTO;
import com.crm.travelcrm.master.country.Dto.CountryResponseDTO;

import java.util.List;

public interface CountryService {
    String createCountries(List<CountryRequestDTO> requests);

    List<CountryResponseDTO> getAllCountries();
}