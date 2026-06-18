package com.crm.travelcrm.master.hotel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoomTypeRepository extends JpaRepository<RoomType, Long> {

    List<RoomType> findByHotelId(Long hotelId);

    List<RoomType> findByHotelIdOrderByNameAsc(Long hotelId);

    Optional<RoomType> findByIdAndHotelId(Long id, Long hotelId);
}