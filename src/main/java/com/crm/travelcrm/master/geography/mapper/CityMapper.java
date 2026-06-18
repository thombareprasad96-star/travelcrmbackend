package com.crm.travelcrm.master.geography.mapper;

import com.crm.travelcrm.master.geography.dto.request.CreateCityRequest;
import com.crm.travelcrm.master.geography.dto.request.UpdateCityRequest;
import com.crm.travelcrm.master.geography.dto.response.CityDto;
import com.crm.travelcrm.master.geography.entity.City;
import org.mapstruct.*;
import org.springframework.util.StringUtils;

/**
 * MapStruct mapper for {@link City}. The {@code destination} association is set by
 * the service. The country reference on the DTO is derived by walking
 * {@code City → Destination → Country} (safe while the JPA session is open).
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
    @Mapping(target = "countryId",       source = "destination.country.id")
    @Mapping(target = "countryName",     source = "destination.country.name")
    @Mapping(target = "country",         ignore = true)
    CityDto toDto(City city);

    @AfterMapping
    default void fillCountry(City city, @MappingTarget CityDto.CityDtoBuilder dto) {
        if (city.getDestination() != null && city.getDestination().getCountry() != null) {
            dto.country(city.getDestination().getCountry().getName());
        } else if (StringUtils.hasText(city.getCountry())) {
            dto.country(city.getCountry());
        }
    }

    City toEntity(CreateCityRequest request);

    void updateEntity(UpdateCityRequest request, @MappingTarget City city);
}