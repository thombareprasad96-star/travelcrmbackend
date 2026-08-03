package com.crm.travelcrm.fleet.mapper;

import com.crm.travelcrm.fleet.dto.FleetExpenseRequestDto;
import com.crm.travelcrm.fleet.dto.FleetExpenseResponseDto;
import com.crm.travelcrm.fleet.entity.FleetExpense;
import org.mapstruct.*;

/**
 * MapStruct mapper for fleet expenses. Every association (vehicle, trip, leg, driver, reversalOf) and
 * every server-owned money field (fxRate, baseAmount, postingDate) is ignored on the inbound side and
 * set by the service — mapping them would let a request body write figures the server is supposed to
 * own.
 */
@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface FleetExpenseMapper {

    @Mapping(target = "vehiclePublicId", source = "vehicle.publicId")
    @Mapping(target = "vehicleNumber", source = "vehicle.vehicleNumber")
    @Mapping(target = "tripPublicId", source = "trip.publicId")
    @Mapping(target = "driverPublicId", source = "driver.publicId")
    @Mapping(target = "driverName", source = "driver.name")
    @Mapping(target = "reversalOfPublicId", source = "reversalOf.publicId")
    @Mapping(target = "expenseTypeLabel", expression = "java(expense.getExpenseType().label())")
    @Mapping(target = "paidByLabel", expression = "java(expense.getPaidBy().label())")
    // Route and the two computed flags need data the entity alone does not carry; the service fills them.
    @Mapping(target = "tripRoute", ignore = true)
    @Mapping(target = "reversed", ignore = true)
    @Mapping(target = "editable", ignore = true)
    FleetExpenseResponseDto toDto(FleetExpense expense);

    @Mapping(target = "vehicle", ignore = true)
    @Mapping(target = "trip", ignore = true)
    @Mapping(target = "leg", ignore = true)
    @Mapping(target = "driver", ignore = true)
    @Mapping(target = "reversalOf", ignore = true)
    @Mapping(target = "fxRate", ignore = true)
    @Mapping(target = "baseAmount", ignore = true)
    @Mapping(target = "postingDate", ignore = true)
    @Mapping(target = "enteredAt", ignore = true)
    @Mapping(target = "enteredBy", ignore = true)
    FleetExpense toEntity(FleetExpenseRequestDto dto);

    /**
     * In-place edit while the trip is still open. The same ignores apply: an update must not be able
     * to move the row into another vehicle's books or rewrite its base amount.
     */
    @Mapping(target = "vehicle", ignore = true)
    @Mapping(target = "trip", ignore = true)
    @Mapping(target = "leg", ignore = true)
    @Mapping(target = "driver", ignore = true)
    @Mapping(target = "reversalOf", ignore = true)
    @Mapping(target = "fxRate", ignore = true)
    @Mapping(target = "baseAmount", ignore = true)
    @Mapping(target = "postingDate", ignore = true)
    @Mapping(target = "enteredAt", ignore = true)
    @Mapping(target = "enteredBy", ignore = true)
    void updateEntity(FleetExpenseRequestDto dto, @MappingTarget FleetExpense expense);
}
