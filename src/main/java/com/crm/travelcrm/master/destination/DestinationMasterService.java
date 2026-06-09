package com.crm.travelcrm.master.destination;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DestinationMasterService {

    private final DestinationMasterRepository destinationMasterRepository;

    private final DestinationMapper destinationMapper;

    public void saveDestination(DestinationMasterRequestDTO request) {
        DestinationMasterEntity entity = DestinationMapper.toEntity(request);
        destinationMasterRepository.save(entity);
    }

    public Page<DestinationMasterResponseDTO> getAllDestinations(
            int page, int size, String sortBy, String sortDir) {

        Sort sort = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        Pageable pageable = PageRequest.of(page, size, sort);
        Page<DestinationMasterEntity> destinationPage = destinationMasterRepository.findAll(pageable);

        return destinationPage.map(DestinationMapper::toResponseDTO);
    }

    public DestinationMasterResponseDTO getDestinationById(Long id) {
        DestinationMasterEntity entity = destinationMasterRepository.findById(id)
                .orElseThrow(() ->
                        new RuntimeException("Destination not found with id: " + id));

        return DestinationMapper.toResponseDTO(entity);
    }

    public void updateDestination(Long id, DestinationMasterRequestDTO request) {
        DestinationMasterEntity entity = destinationMasterRepository.findById(id)
                .orElseThrow(() ->
                        new RuntimeException("Destination not found with id: " + id));

        DestinationMapper.updateEntity(entity, request);
        destinationMasterRepository.save(entity);
    }

    public void deleteDestination(Long id) {
        DestinationMasterEntity entity = destinationMasterRepository.findById(id)
                .orElseThrow(() ->
                        new RuntimeException("Destination not found with id: " + id));

        destinationMasterRepository.delete(entity);
    }
}