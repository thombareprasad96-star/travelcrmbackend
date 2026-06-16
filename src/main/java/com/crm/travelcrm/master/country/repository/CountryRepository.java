package com.crm.travelcrm.master.country.repository;

import com.crm.travelcrm.master.country.entity.Country;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface CountryRepository extends JpaRepository<Country, Long> {
    boolean existsByCountryNameIgnoreCase(String countryName);
    boolean existsByCountryCodeIgnoreCase(String countryCode);
    List<Country> findAllByOrderByCountryNameAsc();
}