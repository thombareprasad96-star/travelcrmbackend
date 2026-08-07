package com.crm.travelcrm.master.hotel;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HotelDto {

    Long hotelId;
    UUID publicId;

    Long cityId;
    String city;
    Long destinationId;
    String destinationName;
    Long countryId;
    String countryName;

    String name;
    Integer stars;
    Double rating;
    String address;
    String contactPerson;
    String phone;
    String email;
    String website;
    String mapUrl;
    Double latitude;
    Double longitude;
    String overview;
    List<String> amenities;
    boolean isDefault;
    String imagePath;

    List<RoomTypeDto> roomTypes;
    List<MealPlanDto> mealPlans;

    // ── Marketplace projection ──────────────────────────────────────────────
    // Until these existed the API gave a client NO way to tell a catalog projection from a hotel the
    // tenant typed, so the master screen rendered identical Edit/Delete controls over a row whose
    // edits the backend now rejects with 409 — and the FE error policy leaves 409 silent, so the
    // button would have appeared to do nothing. `platformOwned` is the flag to render read-only on.

    /** TENANT (theirs) or PLATFORM_SYNC (a controlled projection of a marketplace hotel). */
    HotelOrigin origin;

    /** Convenience for the UI: true ⇔ origin is PLATFORM_SYNC ⇔ the descriptive fields are locked. */
    boolean platformOwned;

    /** The catalog hotel this projects; null on a tenant-authored row. */
    UUID platformHotelPublicId;

    /** SYNCED / STALE / LOCATION_MAPPING_REQUIRED / SOURCE_INACTIVE; null on a tenant-authored row. */
    HotelSyncStatus syncStatus;

    LocalDateTime lastSyncedAt;

    /** Whether this row may be booked through the marketplace. False for every tenant-authored row. */
    boolean marketplaceBookable;

    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}