package com.crm.travelcrm.master.cruise;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CruiseRoomTypeRepository extends JpaRepository<CruiseRoomType, Long> {

    Optional<CruiseRoomType> findByIdAndCruiseId(Long id, Long cruiseId);

    List<CruiseRoomType> findByCruiseIdOrderByNameAsc(Long cruiseId);
}