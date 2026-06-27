package com.crm.travelcrm.report.geographic.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One grouped row of the Geographic Distribution table. {@code id} is a 1-based row index (a
 * React key for an aggregate row — not an entity id). {@code hot/warm/cold} have no source in the
 * current Lead model and are always 0; {@code fresh} = leads of type "Fresh Lead", {@code converted}
 * = leads in the Converted stage.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeoRowDTO {
    private int    id;
    private String city;           // grouped label: departing city / country
    private String country;
    private long   total;
    private long   hot;
    private long   warm;
    private long   cold;
    private long   fresh;
    private long   converted;
    private double conversionRate; // converted / total * 100
    private double distribution;   // total / grandTotal * 100
}