package com.crm.travelcrm.fleet.mapper;

import com.crm.travelcrm.fleet.dto.FleetTripCreateDto;
import com.crm.travelcrm.fleet.dto.FleetTripResponseDto;
import com.crm.travelcrm.fleet.dto.FleetTripUpdateDto;
import com.crm.travelcrm.fleet.entity.FleetTrip;
import org.mapstruct.*;

import java.math.BigDecimal;

/**
 * MapStruct mapper for {@link FleetTrip}. Vehicle/driver names come from the real
 * associations (fetched via {@code @EntityGraph}); associations, the booking link and
 * the status are resolved/managed by the service, never auto-mapped.
 */
@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface FleetTripMapper {

    @Mapping(target = "vehiclePublicId", source = "vehicle.publicId")
    @Mapping(target = "vehicleNumber", source = "vehicle.vehicleNumber")
    @Mapping(target = "driverPublicId", source = "driver.publicId")
    @Mapping(target = "driverName", source = "driver.name")
    FleetTripResponseDto toDto(FleetTrip trip);

    @AfterMapping
    default void computeTotalExpense(FleetTrip trip, @MappingTarget FleetTripResponseDto dto) {
        BigDecimal total = null;
        for (BigDecimal cost : new BigDecimal[]{
                trip.getFuelCost(), trip.getTollCost(), trip.getDriverAllowance()}) {
            if (cost != null) {
                total = (total == null) ? cost : total.add(cost);
            }
        }
        dto.setTotalExpense(total);
    }

    @Mapping(target = "vehicle", ignore = true)
    @Mapping(target = "driver", ignore = true)
    @Mapping(target = "bookingPublicId", ignore = true)
    FleetTrip toEntity(FleetTripCreateDto dto);

    /** Applies only the non-null fields of {@code dto}; the service revalidates + recomputes distance. */
    @Mapping(target = "vehicle", ignore = true)
    @Mapping(target = "driver", ignore = true)
    @Mapping(target = "bookingPublicId", ignore = true)
    void updateEntity(FleetTripUpdateDto dto, @MappingTarget FleetTrip trip);
}