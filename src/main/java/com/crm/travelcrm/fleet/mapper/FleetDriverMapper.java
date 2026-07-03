package com.crm.travelcrm.fleet.mapper;

import com.crm.travelcrm.fleet.dto.FleetDriverRequestDto;
import com.crm.travelcrm.fleet.dto.FleetDriverResponseDto;
import com.crm.travelcrm.fleet.entity.FleetDriver;
import org.mapstruct.*;

/** MapStruct mapper for {@link FleetDriver}. The {@code onTrip} DTO flag is computed by the service. */
@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface FleetDriverMapper {

    FleetDriverResponseDto toDto(FleetDriver driver);

    FleetDriver toEntity(FleetDriverRequestDto dto);

    /**
     * Full-replace of the profile fields the form owns — a null in the request CLEARS the
     * field (the FE always sends the whole form). Status stays lifecycle-managed.
     */
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.SET_TO_NULL)
    void updateEntity(FleetDriverRequestDto dto, @MappingTarget FleetDriver driver);
}