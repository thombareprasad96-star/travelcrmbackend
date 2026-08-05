package com.crm.travelcrm.master.hotel;

import org.mapstruct.*;

@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface HotelMapper {

    @Mapping(target = "hotelId",         source = "id")
    @Mapping(target = "cityId",          source = "city.id")
    @Mapping(target = "city",            source = "city.name")
    @Mapping(target = "destinationId",   source = "city.destination.id")
    @Mapping(target = "destinationName", source = "city.destination.name")
    @Mapping(target = "countryId",       source = "city.destination.country.id")
    @Mapping(target = "countryName",     source = "city.destination.country.name")
    // Stated explicitly although the names already line up: unmappedTargetPolicy is IGNORE on this
    // mapper, so a target that fails to resolve is silently left false/null rather than failing the
    // build. This one decides whether the UI locks the row, and a silent `false` unlocks a hotel the
    // service will then 409 on.
    @Mapping(target = "platformOwned",   source = "platformOwned")
    HotelDto toDto(Hotel hotel);

    @Mapping(target = "roomTypeId",    source = "id")
    @Mapping(target = "hotelId",       source = "hotel.id")
    @Mapping(target = "platformOwned", source = "platformOwned")
    RoomTypeDto toRoomTypeDto(RoomType roomType);

    @Mapping(target = "mealPlanId",    source = "id")
    @Mapping(target = "hotelId",       source = "hotel.id")
    @Mapping(target = "platformOwned", source = "platformOwned")
    MealPlanDto toMealPlanDto(MealPlan mealPlan);

    @Mapping(target = "city", ignore = true)
    @Mapping(target = "roomTypes", ignore = true)
    @Mapping(target = "mealPlans", ignore = true)
    Hotel toEntity(CreateHotelRequest request);

    @Mapping(target = "city", ignore = true)
    @Mapping(target = "roomTypes", ignore = true)
    @Mapping(target = "mealPlans", ignore = true)
    void updateEntity(UpdateHotelRequest request, @MappingTarget Hotel hotel);

    RoomType toRoomTypeEntity(CreateRoomTypeRequest request);

    void updateRoomTypeEntity(UpdateRoomTypeRequest request, @MappingTarget RoomType roomType);

    MealPlan toMealPlanEntity(CreateMealPlanRequest request);

    void updateMealPlanEntity(UpdateMealPlanRequest request, @MappingTarget MealPlan mealPlan);
}