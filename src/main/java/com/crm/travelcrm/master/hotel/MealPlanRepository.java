package com.crm.travelcrm.master.hotel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MealPlanRepository extends JpaRepository<MealPlan, Long> {

    List<MealPlan> findByHotelId(Long hotelId);

    List<MealPlan> findByHotelIdOrderByNameAsc(Long hotelId);

    Optional<MealPlan> findByIdAndHotelId(Long id, Long hotelId);
}