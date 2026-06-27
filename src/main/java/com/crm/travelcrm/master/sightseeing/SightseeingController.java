package com.crm.travelcrm.master.sightseeing;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sightseeings")
@RequiredArgsConstructor



























public class SightseeingController {

    private final SightseeingService sightseeingService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PagedApiResponse<SightseeingDto>> getAll(
            @RequestParam(required = false) String destination,
            @RequestParam(required = false) String city,
            @RequestParam(defaultValue = "0")         int page,
            @RequestParam(defaultValue = "20")        int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc")      String sortDir) {

        if (destination != null || city != null) {
            return ResponseEntity.ok(sightseeingService.filter(destination, city, page, size, sortBy, sortDir));
        }
        return ResponseEntity.ok(sightseeingService.getAll(page, size, sortBy, sortDir));
    }

    @GetMapping("/city/{cityId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PagedApiResponse<SightseeingDto>> getByCity(
            @PathVariable Long cityId,
            @RequestParam(defaultValue = "0")         int page,
            @RequestParam(defaultValue = "20")        int size,
            @RequestParam(defaultValue = "sequence")  String sortBy,
            @RequestParam(defaultValue = "asc")       String sortDir) {
        return ResponseEntity.ok(sightseeingService.getByCity(cityId, page, size, sortBy, sortDir));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<SightseeingDto>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Sightseeing fetched", sightseeingService.getById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')")
    public ResponseEntity<ApiResponse<SightseeingDto>> create(@Valid @RequestBody CreateSightseeingRequest request) {
        SightseeingDto created = sightseeingService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Sightseeing created", created, 201));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')")
    public ResponseEntity<ApiResponse<SightseeingDto>> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateSightseeingRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Sightseeing updated", sightseeingService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        sightseeingService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/upload-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')")
    public ResponseEntity<ApiResponse<Map<String, String>>> uploadImage(@RequestParam("file") MultipartFile file) {
        String url = sightseeingService.uploadImage(file);
        return ResponseEntity.ok(ApiResponse.success("Image uploaded", Map.of("imagePath", url)));
    }

    @GetMapping("/search")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<SightseeingDto>>> search(@RequestParam String q) {
        return ResponseEntity.ok(ApiResponse.success("Search results", sightseeingService.search(q)));
    }
}