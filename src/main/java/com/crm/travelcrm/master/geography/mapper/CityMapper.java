package com.crm.travelcrm.master.geography.mapper;

import com.crm.travelcrm.master.geography.dto.request.CreateCityRequest;
import com.crm.travelcrm.master.geography.dto.request.UpdateCityRequest;
import com.crm.travelcrm.master.geography.dto.response.CityDto;
import com.crm.travelcrm.master.geography.entity.City;
import org.mapstruct.*;

/**
 * MapStruct mapper for {@link City}.
 *
 * <p>The {@code country} (required) and {@code destination} (optional) associations
 * are resolved and tenant-checked by the service, so they are never auto-mapped from
 * the request body. The country reference on the DTO is read straight from the
 * {@code City → Country} FK (safe while the JPA session is open).</p>
 */
@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface CityMapper {

    @Mapping(target = "cityId",          source = "id")
    @Mapping(target = "destinationId",   source = "destination.id")
    @Mapping(target = "destinationName", source = "destination.name")
    @Mapping(target = "countryId",       source = "country.id")
    @Mapping(target = "countryName",     source = "country.name")
    @Mapping(target = "country",         source = "country.name")
    CityDto toDto(City city);

    @Mapping(target = "country",     ignore = true)   // Country FK set by service
    @Mapping(target = "destination", ignore = true)   // Destination FK set by service
    City toEntity(CreateCityRequest request);

    @Mapping(target = "country",     ignore = true)   // re-assigned by service
    @Mapping(target = "destination", ignore = true)   // re-assigned by service
    void updateEntity(UpdateCityRequest request, @MappingTarget City city);
}