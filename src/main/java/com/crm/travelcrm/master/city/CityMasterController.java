package com.crm.travelcrm.master.city;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/cities")
@RequiredArgsConstructor
public class CityMasterController {

    private final CityMasterService cityMasterService;

    @PostMapping
    public ResponseEntity<String> saveCity(
            @RequestBody CityMasterRequestDTO request) {
        cityMasterService.saveCity(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body("City saved successfully");
    }

    @GetMapping
    public ResponseEntity<PagedApiResponse<CityMasterResponseDTO>> getAllCities(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {

        Page<CityMasterResponseDTO> cityPage =
                cityMasterService.getAllCities(page, size, sortBy, sortDir);

        return ResponseEntity.ok(
                PagedApiResponse.of(
                        "Cities  fetched successfully",
                        cityPage.getContent(),        // ✅ already List<LeadResponse>
                        PaginationMeta.from(cityPage, sortBy, sortDir)));
    }


    // Get City By Id
    @GetMapping("/{id}")
    public ResponseEntity<CityMasterResponseDTO> getCityById(
            @PathVariable Long id) {

        CityMasterResponseDTO city =
                cityMasterService.getCityById(id);

        return ResponseEntity.ok(city);
    }

    // Update City
    @PutMapping("/{id}")
    public ResponseEntity<String> updateCity(
            @PathVariable Long id,
            @RequestBody CityMasterRequestDTO request) {

        cityMasterService.updateCity(id, request);

        return ResponseEntity.ok("City updated successfully");
    }

    // Delete City
    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteCity(
            @PathVariable Long id) {

        cityMasterService.deleteCity(id);

        return ResponseEntity.ok("City deleted successfully");
    }
}


