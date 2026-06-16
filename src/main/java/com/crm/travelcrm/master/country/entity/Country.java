package com.crm.travelcrm.master.country.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.*;

@Entity
@Table(name = "countries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Country extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String countryName;

    @Column(nullable = false, unique = true, length = 5)
    private String countryCode;

}