package com.crm.travelcrm.master.hotel;

import com.crm.travelcrm.common.cloudinary.CloudinaryService;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.master.geography.entity.City;
import com.crm.travelcrm.master.geography.entity.Destination;
import com.crm.travelcrm.master.geography.repository.CityRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A hotel imported from the marketplace is a <b>controlled projection</b>, not a copy.
 *
 * <p>{@code HotelMasterProjectionService.sync()} rewrites its descriptive fields on every catalog
 * version bump, so an edit accepted in the tenant's Hotel Master is not merely against the rules
 * (design doc §6.4) — it is <b>data loss on a timer</b>: it saves, it displays, and it silently
 * vanishes the next time the platform touches the catalog. That silence is why these paths throw
 * instead of quietly no-op'ing, and why this suite exists at all.
 *
 * <p><b>The false-positive cases matter as much as the blocking ones</b>, and are the reason the
 * guard compares against the STORED value rather than merely checking whether a key was present in
 * the payload. {@code Hotel.jsx} posts its entire model back on every save — name, stars, amenities
 * and all — so a "was this field sent?" test would reject every save of a projection, including one
 * that only touches the contact person. {@link TenantLocalFieldsStayWritable} pins that down.
 *
 * <p>Written in the established style: plain JUnit 5, hand-rolled Mockito mocks, no Spring context.
 * The MapStruct implementation is used for real rather than mocked — the guard's whole job is to run
 * before it, and a mock would not prove the ordering.
 */
class HotelProjectionReadOnlyTest {

    private static final long TENANT_ID = 7L;
    private static final long HOTEL_ID = 101L;
    private static final long CITY_ID = 55L;
    private static final long DESTINATION_ID = 9L;

    private HotelRepository hotelRepository;
    private RoomTypeRepository roomTypeRepository;
    private MealPlanRepository mealPlanRepository;
    private CityRepository cityRepository;
    private CloudinaryService cloudinaryService;
    private HotelServiceImpl service;

    @BeforeEach
    void setUp() {
        hotelRepository = mock(HotelRepository.class);
        roomTypeRepository = mock(RoomTypeRepository.class);
        mealPlanRepository = mock(MealPlanRepository.class);
        cityRepository = mock(CityRepository.class);
        cloudinaryService = mock(CloudinaryService.class);
        service = new HotelServiceImpl(hotelRepository, roomTypeRepository, mealPlanRepository,
                cityRepository, new HotelMapperImpl(), cloudinaryService);
        TenantContext.setTenantId(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private City city() {
        Destination destination = new Destination();
        destination.setId(DESTINATION_ID);
        destination.setName("Dubai");
        City city = new City();
        city.setId(CITY_ID);
        city.setName("Dubai");
        city.setDestination(destination);
        return city;
    }

    /** A hotel as the catalog last left it. {@code origin} decides everything this suite tests. */
    private Hotel hotel(HotelOrigin origin) {
        Hotel hotel = new Hotel();
        hotel.setId(HOTEL_ID);
        hotel.setTenantId(TENANT_ID);
        hotel.setCity(city());
        hotel.setOrigin(origin);
        hotel.setName("Atlantis The Palm");
        hotel.setStars(5);
        hotel.setRating(4.6);
        hotel.setAddress("Crescent Rd, The Palm Jumeirah");
        hotel.setPhone("+97144260000");
        hotel.setEmail("info@atlantis.example");
        hotel.setWebsite("https://atlantis.example");
        hotel.setMapUrl("https://maps.example/atlantis");
        hotel.setLatitude(25.1304);
        hotel.setLongitude(55.1171);
        hotel.setOverview("Ocean-themed resort on the Palm.");
        hotel.setImagePath("https://cdn.example/atlantis.jpg");
        hotel.setAmenities(new ArrayList<>(List.of("Wifi", "Pool", "Spa")));
        hotel.setContactPerson("Old Contact");
        if (origin == HotelOrigin.PLATFORM_SYNC) {
            hotel.setPlatformHotelPublicId(UUID.randomUUID());
            hotel.setPlatformCatalogVersion(4L);
            hotel.setSyncStatus(HotelSyncStatus.SYNCED);
            hotel.setMarketplaceBookable(true);
        }
        return hotel;
    }

    /**
     * Exactly what {@code transformHotelData()} posts after {@code transformHotelResponse()} has
     * round-tripped the row through the form: every field echoed back, numbers re-parsed.
     */
    private UpdateHotelRequest unchangedForm(Hotel from) {
        UpdateHotelRequest r = new UpdateHotelRequest();
        r.setName(from.getName());
        r.setDestinationId(DESTINATION_ID);
        r.setCity(from.getCity().getName());
        r.setStars(from.getStars());
        r.setRating(from.getRating());
        r.setAddress(from.getAddress());
        r.setPhone(from.getPhone());
        r.setEmail(from.getEmail());
        r.setWebsite(from.getWebsite());
        r.setMapUrl(from.getMapUrl());
        r.setLatitude(from.getLatitude());
        r.setLongitude(from.getLongitude());
        r.setOverview(from.getOverview());
        r.setImagePath(from.getImagePath());
        r.setAmenities(new ArrayList<>(from.getAmenities()));
        r.setContactPerson(from.getContactPerson());
        r.setIsDefault(false);
        return r;
    }

    private void givenHotel(Hotel hotel) {
        when(hotelRepository.findByIdAndTenantId(HOTEL_ID, TENANT_ID)).thenReturn(Optional.of(hotel));
        when(hotelRepository.save(any(Hotel.class))).thenAnswer(i -> i.getArgument(0));
        when(cityRepository.findByTenantIdAndDestinationIdAndNameIgnoreCase(
                TENANT_ID, DESTINATION_ID, "Dubai")).thenReturn(Optional.of(hotel.getCity()));
    }

    private void assertRejected(Throwable t, String... mustMention) {
        assertThat(t).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) t).getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(t.getMessage()).contains("Marketplace");
        for (String fragment : mustMention) {
            assertThat(t.getMessage()).contains(fragment);
        }
    }

    // ── the blocking half ───────────────────────────────────────────────────

    @Nested
    @DisplayName("a PLATFORM_SYNC hotel refuses every platform-owned edit")
    class PlatformOwnedFieldsAreLocked {

        @Test
        @DisplayName("renaming it is rejected, and nothing is written")
        void renameRejected() {
            Hotel projection = hotel(HotelOrigin.PLATFORM_SYNC);
            givenHotel(projection);
            UpdateHotelRequest r = unchangedForm(projection);
            r.setName("Atlantis (our rate)");

            assertRejected(catchThrown(r), "name");
            // The throw is what protects the row, not the rollback: assert nothing reached the repo.
            verify(hotelRepository, never()).save(any(Hotel.class));
            assertThat(projection.getName()).isEqualTo("Atlantis The Palm");
        }

        @Test
        @DisplayName("star rating, review rating and coordinates are all locked")
        void numericFieldsRejected() {
            Hotel projection = hotel(HotelOrigin.PLATFORM_SYNC);
            givenHotel(projection);
            UpdateHotelRequest r = unchangedForm(projection);
            r.setStars(4);
            r.setRating(3.1);
            r.setLatitude(0.0);

            assertRejected(catchThrown(r), "stars", "rating", "latitude");
        }

        @Test
        @DisplayName("amenities are locked, and order alone is not treated as a change")
        void amenitiesRejectedOnlyWhenTheSetDiffers() {
            Hotel projection = hotel(HotelOrigin.PLATFORM_SYNC);
            givenHotel(projection);

            UpdateHotelRequest reordered = unchangedForm(projection);
            reordered.setAmenities(new ArrayList<>(List.of("Spa", "Wifi", "Pool")));
            assertThatCode(() -> service.update(HOTEL_ID, reordered)).doesNotThrowAnyException();

            UpdateHotelRequest added = unchangedForm(projection);
            added.setAmenities(new ArrayList<>(List.of("Wifi", "Pool", "Spa", "Helipad")));
            assertRejected(catchThrown(added), "amenities");
        }

        @Test
        @DisplayName("moving it to another city is rejected")
        void relocationRejected() {
            Hotel projection = hotel(HotelOrigin.PLATFORM_SYNC);
            givenHotel(projection);
            UpdateHotelRequest r = unchangedForm(projection);
            r.setCity("Abu Dhabi");

            assertRejected(catchThrown(r), "city");
        }

        @Test
        @DisplayName("the message names every locked field the caller tried to change")
        void messageEnumeratesTheBlockedFields() {
            Hotel projection = hotel(HotelOrigin.PLATFORM_SYNC);
            givenHotel(projection);
            UpdateHotelRequest r = unchangedForm(projection);
            r.setName("Renamed");
            r.setOverview("Rewritten");

            assertRejected(catchThrown(r), "name", "overview");
        }

        private Throwable catchThrown(UpdateHotelRequest r) {
            try {
                service.update(HOTEL_ID, r);
            } catch (Throwable t) {
                return t;
            }
            throw new AssertionError("expected the update to be rejected, but it succeeded");
        }
    }

    // ── the half that must keep working ─────────────────────────────────────

    @Nested
    @DisplayName("what stays the tenant's own on a projection")
    class TenantLocalFieldsStayWritable {

        /**
         * The regression this whole design turns on. The form posts every field back; if the guard
         * asked "was this key sent?" instead of "did this value change?", this save would 409 and the
         * hotel screen would be unusable for every marketplace row.
         */
        @Test
        @DisplayName("a full-form re-post that only changes contactPerson succeeds")
        void contactPersonOnlyEditSucceeds() {
            Hotel projection = hotel(HotelOrigin.PLATFORM_SYNC);
            givenHotel(projection);
            UpdateHotelRequest r = unchangedForm(projection);
            r.setContactPerson("Priya — front desk");

            HotelDto dto = service.update(HOTEL_ID, r);

            assertThat(dto.getContactPerson()).isEqualTo("Priya — front desk");
            assertThat(dto.isPlatformOwned()).isTrue();
            verify(hotelRepository).save(projection);
        }

        @Test
        @DisplayName("an empty string for a field stored as null is not a change")
        void blankForNullIsNotAnEdit() {
            Hotel projection = hotel(HotelOrigin.PLATFORM_SYNC);
            projection.setWebsite(null);
            projection.setOverview(null);
            givenHotel(projection);
            UpdateHotelRequest r = unchangedForm(projection);
            r.setWebsite("");     // a controlled React input yields "" for an empty box
            r.setOverview("");

            assertThatCode(() -> service.update(HOTEL_ID, r)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("marking it the default is allowed — isDefault is never synced")
        void setDefaultAllowed() {
            Hotel projection = hotel(HotelOrigin.PLATFORM_SYNC);
            givenHotel(projection);

            service.setDefault(HOTEL_ID);

            assertThat(projection.isDefault()).isTrue();
            verify(hotelRepository).save(projection);
        }

        /**
         * The marketplace exposes an import endpoint and no unlink, so refusing here would strand an
         * imported hotel in the master forever. Safe because the projection's unique index is partial
         * on {@code deleted_at IS NULL} — a re-import simply builds a fresh row.
         */
        @Test
        @DisplayName("deleting it is allowed — it is the only un-import there is")
        void deleteAllowed() {
            Hotel projection = hotel(HotelOrigin.PLATFORM_SYNC);
            givenHotel(projection);

            service.delete(HOTEL_ID);

            assertThat(projection.getDeletedAt()).isNotNull();
            verify(hotelRepository).save(projection);
        }

        @Test
        @DisplayName("a tenant-authored hotel is untouched by any of this")
        void tenantHotelFullyEditable() {
            Hotel own = hotel(HotelOrigin.TENANT);
            givenHotel(own);
            UpdateHotelRequest r = unchangedForm(own);
            r.setName("My Private Resort");
            r.setStars(3);
            r.setAmenities(new ArrayList<>(List.of("Wifi")));

            HotelDto dto = service.update(HOTEL_ID, r);

            assertThat(dto.getName()).isEqualTo("My Private Resort");
            assertThat(dto.isPlatformOwned()).isFalse();
        }
    }

    // ── children ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("room types and meal plans follow their own source, not the hotel's")
    class ChildRowsFollowTheirSource {

        private RoomType roomType(UUID source) {
            RoomType rt = new RoomType();
            rt.setId(300L);
            rt.setName("Ocean Deluxe");
            rt.setPlatformSourcePublicId(source);
            return rt;
        }

        private MealPlan mealPlan(UUID source) {
            MealPlan mp = new MealPlan();
            mp.setId(400L);
            mp.setName("Half Board");
            mp.setDescription("Breakfast and dinner");
            mp.setPrice(new BigDecimal("2500.00"));
            mp.setPlatformSourcePublicId(source);
            return mp;
        }

        @BeforeEach
        void hotelIsAProjection() {
            givenHotel(hotel(HotelOrigin.PLATFORM_SYNC));
            when(roomTypeRepository.save(any(RoomType.class))).thenAnswer(i -> i.getArgument(0));
            when(mealPlanRepository.save(any(MealPlan.class))).thenAnswer(i -> i.getArgument(0));
        }

        @Test
        @DisplayName("a catalog room type cannot have descriptive fields edited, be deleted or be given images")
        void catalogRoomTypeIsLocked() {
            RoomType fromCatalog = roomType(UUID.randomUUID());
            when(roomTypeRepository.findByIdAndHotelId(300L, HOTEL_ID)).thenReturn(Optional.of(fromCatalog));

            UpdateRoomTypeRequest r = new UpdateRoomTypeRequest();
            r.setName("Ocean Deluxe (ours)");

            assertThatThrownBy(() -> service.updateRoomType(HOTEL_ID, 300L, r))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("Marketplace");
            assertThatThrownBy(() -> service.deleteRoomType(HOTEL_ID, 300L))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("Marketplace");

            // Refused BEFORE the bytes are spent — the upload is quota-metered and syncRooms() would
            // clear the image list on the next catalog version anyway.
            assertThatThrownBy(() -> service.uploadRoomImages(HOTEL_ID, 300L, new org.springframework.web.multipart.MultipartFile[0]))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("Marketplace");
            verify(cloudinaryService, never()).uploadImage(any(), any());
            verify(roomTypeRepository, never()).delete(any(RoomType.class));
        }

        @Test
        @DisplayName("a catalog room type accepts a tenant selling-rate change without unlocking its description")
        void catalogRoomTypeAcceptsTenantBaseRate() {
            RoomType fromCatalog = roomType(UUID.randomUUID());
            fromCatalog.setBaseRate(null);
            when(roomTypeRepository.findByIdAndHotelId(300L, HOTEL_ID)).thenReturn(Optional.of(fromCatalog));

            UpdateRoomBaseRateRequest rateOnly = new UpdateRoomBaseRateRequest();
            rateOnly.setBaseRate(new BigDecimal("8750.00"));

            RoomTypeDto dto = service.updateRoomBaseRate(HOTEL_ID, 300L, rateOnly);

            assertThat(dto.getBaseRate()).isEqualByComparingTo("8750.00");
            assertThat(dto.isPlatformOwned()).isTrue();
            assertThat(dto.getName()).isEqualTo("Ocean Deluxe");
            verify(roomTypeRepository).save(fromCatalog);

            UpdateRoomTypeRequest rename = new UpdateRoomTypeRequest();
            rename.setName("Ocean Deluxe (ours)");
            assertThatThrownBy(() -> service.updateRoomType(HOTEL_ID, 300L, rename))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("Marketplace");
        }

        @Test
        @DisplayName("a room type the tenant added to an imported hotel stays theirs")
        void tenantAddedRoomTypeStaysEditable() {
            RoomType own = roomType(null);
            when(roomTypeRepository.findByIdAndHotelId(300L, HOTEL_ID)).thenReturn(Optional.of(own));

            UpdateRoomTypeRequest r = new UpdateRoomTypeRequest();
            r.setName("Our Negotiated Suite");

            RoomTypeDto dto = service.updateRoomType(HOTEL_ID, 300L, r);

            assertThat(dto.getName()).isEqualTo("Our Negotiated Suite");
            assertThat(dto.isPlatformOwned()).isFalse();
        }

        /**
         * The one asymmetry in the whole design, and it is deliberate: {@code syncMealPlans()} never
         * writes {@code price} because the catalog has none — that number is the tenant's selling
         * price. So a catalog meal plan is locked on its text and open on its price.
         */
        @Test
        @DisplayName("a catalog meal plan accepts a price change but not a rename")
        void catalogMealPlanIsPriceOnly() {
            MealPlan fromCatalog = mealPlan(UUID.randomUUID());
            when(mealPlanRepository.findByIdAndHotelId(400L, HOTEL_ID)).thenReturn(Optional.of(fromCatalog));

            UpdateMealPlanRequest priceOnly = new UpdateMealPlanRequest();
            priceOnly.setPrice(new BigDecimal("2999.00"));
            MealPlanDto dto = service.updateMealPlan(HOTEL_ID, 400L, priceOnly);
            assertThat(dto.getPrice()).isEqualByComparingTo("2999.00");
            assertThat(dto.isPlatformOwned()).isTrue();

            UpdateMealPlanRequest rename = new UpdateMealPlanRequest();
            rename.setName("Half Board Plus");
            assertThatThrownBy(() -> service.updateMealPlan(HOTEL_ID, 400L, rename))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("name");

            assertThatThrownBy(() -> service.deleteMealPlan(HOTEL_ID, 400L))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("Marketplace");
            verify(mealPlanRepository, never()).delete(any(MealPlan.class));
        }
    }
}
