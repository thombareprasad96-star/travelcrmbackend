package com.crm.travelcrm.hotelmarketplace.catalog.repository;

import com.crm.travelcrm.hotelmarketplace.catalog.entity.PlatformHotelMealPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Meal plans of a catalog hotel. Always reached through the parent — never by bare id. */
public interface PlatformHotelMealPlanRepository extends JpaRepository<PlatformHotelMealPlan, Long> {

    List<PlatformHotelMealPlan> findByHotelIdAndDeletedAtIsNullOrderByCodeAsc(long hotelId);

    List<PlatformHotelMealPlan> findByHotelIdAndActiveTrueAndDeletedAtIsNullOrderByCodeAsc(long hotelId);

    /** Scoped by parent as well as publicId — see the note on the room repository. */
    Optional<PlatformHotelMealPlan> findByPublicIdAndHotelIdAndDeletedAtIsNull(UUID publicId, long hotelId);
}
