package com.crm.travelcrm.master.geography.mapper;

import com.crm.travelcrm.master.geography.dto.request.CreateCountryRequest;
import com.crm.travelcrm.master.geography.dto.request.UpdateCountryRequest;
import com.crm.travelcrm.master.geography.dto.response.CountryDto;
import com.crm.travelcrm.master.geography.entity.Country;
import org.mapstruct.*;

/**
 * MapStruct mapper for {@link Country}. Server-managed and relational fields are
 * never written from client input; {@code null}s in update payloads are ignored.
 */
@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface CountryMapper {

    @Mapping(target = "countryId", source = "id")
    CountryDto toDto(Country country);

    Country toEntity(CreateCountryRequest request);

    void updateEntity(UpdateCountryRequest request, @MappingTarget Country country);
}