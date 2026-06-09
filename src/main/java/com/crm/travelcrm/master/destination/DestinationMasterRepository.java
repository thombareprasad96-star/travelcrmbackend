package com.crm.travelcrm.master.destination;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DestinationMasterRepository extends JpaRepository<DestinationMasterEntity, Long> {
}