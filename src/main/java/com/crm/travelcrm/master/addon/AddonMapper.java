package com.crm.travelcrm.master.addon;

import org.mapstruct.*;

@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface AddonMapper {

    @Mapping(target = "addonId",         source = "id")
    @Mapping(target = "cityId",          source = "city.id")
    @Mapping(target = "cityName",        source = "city.name")
    @Mapping(target = "destinationId",   source = "city.destination.id")
    @Mapping(target = "destinationName", source = "city.destination.name")
    AddonDto toDto(Addon addon);

    @Mapping(target = "city", ignore = true)
    Addon toEntity(CreateAddonRequest request);

    @Mapping(target = "city", ignore = true)
    void updateEntity(UpdateAddonRequest request, @MappingTarget Addon addon);
}