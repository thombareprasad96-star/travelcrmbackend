package com.crm.travelcrm.master.geography.mapper;

import com.crm.travelcrm.master.geography.dto.request.CreateDestinationRequest;
import com.crm.travelcrm.master.geography.dto.request.UpdateDestinationRequest;
import com.crm.travelcrm.master.geography.dto.response.DestinationDto;
import com.crm.travelcrm.master.geography.dto.response.DestinationListResponseDTO;
import com.crm.travelcrm.master.geography.entity.Destination;
import org.mapstruct.*;

/**
 * MapStruct mapper for {@link Destination}.
 *
 * The {@code country} association is resolved + tenant-checked by the service,
 * so it is never mapped from the incoming request body.
 */
@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface DestinationMapper {

    // ── Single-item response (POST / PUT / GET by id) ─────────────────────────

    @Mapping(target = "destinationId", source = "id")
    @Mapping(target = "countryId",     source = "country.id")
    @Mapping(target = "countryName",   source = "country.name")
    DestinationDto toDto(Destination destination);

    // ── List response (GET /destinations paginated) ───────────────────────────

    @Mapping(target = "id",      source = "id")
    @Mapping(target = "country", source = "country.name")   // Country entity → string name
    DestinationListResponseDTO toListResponseDTO(Destination destination);

    // ── Write operations ──────────────────────────────────────────────────────

    /**
     * Create entity from request. country, tenantId and global are set by the
     * service after this call — countryId in the request is intentionally ignored
     * here (IGNORE policy covers it).
     */
    @Mapping(target = "country", ignore = true)
    Destination toEntity(CreateDestinationRequest request);

    /** Partial update — null fields in request are skipped (IGNORE policy). */
    void updateEntity(UpdateDestinationRequest request, @MappingTarget Destination destination);
}