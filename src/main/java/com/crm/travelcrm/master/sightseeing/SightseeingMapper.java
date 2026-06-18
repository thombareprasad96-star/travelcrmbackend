package com.crm.travelcrm.master.sightseeing;

import org.mapstruct.*;

@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface SightseeingMapper {

    @Mapping(target = "sightseeingId",  source = "id")
    @Mapping(target = "cityId",         source = "city.id")
    @Mapping(target = "city",           source = "city.name")
    @Mapping(target = "destinationId",  source = "city.destination.id")
    @Mapping(target = "destination",    source = "city.destination.name")
    @Mapping(target = "countryId",      source = "city.destination.country.id")
    @Mapping(target = "countryName",    source = "city.destination.country.name")
    SightseeingDto toDto(Sightseeing sightseeing);

    @Mapping(target = "city", ignore = true)
    Sightseeing toEntity(CreateSightseeingRequest request);

    @Mapping(target = "city", ignore = true)
    void updateEntity(UpdateSightseeingRequest request, @MappingTarget Sightseeing sightseeing);
}