package com.crm.travelcrm.master.hotel.repository;

import com.crm.travelcrm.master.hotel.entity.HotelEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HotelRepository extends JpaRepository<HotelEntity, Long> {

    List<HotelEntity> findByDestinationId(Long destinationId);

    List<HotelEntity> findByDestinationIdAndIsDefaultTrue(Long destinationId);
}