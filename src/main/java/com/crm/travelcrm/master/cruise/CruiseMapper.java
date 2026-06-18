package com.crm.travelcrm.master.cruise;

import org.mapstruct.*;

@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface CruiseMapper {

    @Mapping(target = "cruiseId",        source = "id")
    @Mapping(target = "cityId",          source = "city.id")
    @Mapping(target = "cityName",        source = "city.name")
    @Mapping(target = "destinationId",   source = "city.destination.id")
    @Mapping(target = "destinationName", source = "city.destination.name")
    CruiseDto toDto(Cruise cruise);

    @Mapping(target = "roomTypeId", source = "id")
    @Mapping(target = "cruiseId",   source = "cruise.id")
    CruiseRoomTypeDto toRoomTypeDto(CruiseRoomType roomType);

    @Mapping(target = "city", ignore = true)
    @Mapping(target = "roomTypes", ignore = true)
    Cruise toEntity(CreateCruiseRequest request);

    @Mapping(target = "city", ignore = true)
    @Mapping(target = "roomTypes", ignore = true)
    void updateEntity(UpdateCruiseRequest request, @MappingTarget Cruise cruise);

    @Mapping(target = "cruise", ignore = true)
    CruiseRoomType toRoomTypeEntity(CreateCruiseRoomTypeRequest request);

    void updateRoomTypeEntity(UpdateCruiseRoomTypeRequest request, @MappingTarget CruiseRoomType roomType);
}