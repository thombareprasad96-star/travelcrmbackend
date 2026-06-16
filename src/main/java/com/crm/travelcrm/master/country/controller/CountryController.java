package com.crm.travelcrm.master.country.controller;

import com.crm.travelcrm.master.country.Dto.CountryRequestDTO;
import com.crm.travelcrm.master.country.Dto.CountryResponseDTO;
import com.crm.travelcrm.master.country.service.CountryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/countries")
@RequiredArgsConstructor
public class CountryController {

    private final CountryService countryService;

    @PostMapping
    public ResponseEntity<String> createCountries(
            @Valid @RequestBody List<CountryRequestDTO> requests) {
        countryService.createCountries(requests);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body("Countries created successfully");
    }

    @GetMapping
    public ResponseEntity<List<CountryResponseDTO>> getAllCountries() {
        return new ResponseEntity<>(countryService.getAllCountries(), HttpStatus.OK);
    }
}