package com.crm.travelcrm.master.destination;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/destinations")
@RequiredArgsConstructor
public class DestinationMasterController {

    private final DestinationMasterService destinationMasterService;

    // Create
    @PostMapping
    public ResponseEntity<String> saveDestination(
            @RequestBody DestinationMasterRequestDTO request) {

        destinationMasterService.saveDestination(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body("Destination saved successfully");
    }

    // Get All (paginated)
    @GetMapping
    public ResponseEntity<PagedApiResponse<DestinationMasterResponseDTO>> getAllDestinations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {

        Page<DestinationMasterResponseDTO> destinationPage =
                destinationMasterService.getAllDestinations(page, size, sortBy, sortDir);

        return ResponseEntity.ok(
                PagedApiResponse.of(
                        "Destinations fetched successfully",
                        destinationPage.getContent(),
                        PaginationMeta.from(destinationPage, sortBy, sortDir)));
    }

    // Get By Id
    @GetMapping("/{id}")
    public ResponseEntity<DestinationMasterResponseDTO> getDestinationById(
            @PathVariable Long id) {

        DestinationMasterResponseDTO destination =
                destinationMasterService.getDestinationById(id);

        return ResponseEntity.ok(destination);
    }

    // Update
    @PutMapping("/{id}")
    public ResponseEntity<String> updateDestination(
            @PathVariable Long id,
            @RequestBody DestinationMasterRequestDTO request) {

        destinationMasterService.updateDestination(id, request);
        return ResponseEntity.ok("Destination updated successfully");
    }

    // Delete
    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteDestination(
            @PathVariable Long id) {

        destinationMasterService.deleteDestination(id);
        return ResponseEntity.ok("Destination deleted successfully");
    }
}