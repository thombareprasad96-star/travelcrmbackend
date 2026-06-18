package com.crm.travelcrm.master.airline;

import org.mapstruct.*;

@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface AirlineMapper {

    @Mapping(target = "airlineId",       source = "id")
    @Mapping(target = "cityId",          source = "city.id")
    @Mapping(target = "cityName",        source = "city.name")
    @Mapping(target = "destinationId",   source = "city.destination.id")
    @Mapping(target = "destinationName", source = "city.destination.name")
    AirlineDto toDto(Airline airline);

    @Mapping(target = "city", ignore = true)
    Airline toEntity(CreateAirlineRequest request);

    @Mapping(target = "city", ignore = true)
    void updateEntity(UpdateAirlineRequest request, @MappingTarget Airline airline);
}