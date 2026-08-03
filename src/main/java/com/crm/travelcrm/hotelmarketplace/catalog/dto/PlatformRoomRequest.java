package com.crm.travelcrm.hotelmarketplace.catalog.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * SuperAdmin create/update payload for a catalog room category.
 *
 * <p>No price field, and there is no place to put one: a room's price depends on the date, meal plan
 * and occupancy, so it belongs to a rate plan and a dated calendar.</p>
 */
@Data
public class PlatformRoomRequest {

    @NotBlank
    @Size(max = 150)
    private String name;

    @Min(0) private Integer maxAdults;
    @Min(0) private Integer maxChildren;
    @Min(1) private Integer maxOccupancy;

    @Size(max = 100) private String bedType;
    @Size(max = 100) private String size;

    private String description;

    private List<String> images;

    /** Omitted ⇒ stays/becomes active. Deactivating is how a room stops selling without deletion. */
    private Boolean active;
}
