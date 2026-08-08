package com.crm.travelcrm.master.hotel;

import com.crm.travelcrm.common.cloudinary.CloudinaryService;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.master.geography.entity.City;
import com.crm.travelcrm.master.geography.repository.CityRepository;
import com.crm.travelcrm.master.geography.support.GeographySupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class HotelServiceImpl implements HotelService {

    private final HotelRepository hotelRepository;
    private final RoomTypeRepository roomTypeRepository;
    private final MealPlanRepository mealPlanRepository;
    private final CityRepository cityRepository;
    private final HotelMapper hotelMapper;
    private final CloudinaryService cloudinaryService;

    /**
     * Columns a client may sort the hotel list by. Anything outside this set falls back to
     * {@code createdAt} rather than reaching {@code PageRequest.of(...)} and 500-ing on an
     * unknown property.
     */
    private static final Set<String> SORTABLE = Set.of("name", "stars", "rating", "createdAt", "updatedAt");

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<HotelDto> getAll(int page, int size, String sortBy, String sortDir,
                                             String q, Long destinationId, String city, Integer stars) {
        Long tenantId = GeographySupport.currentTenantId();
        Page<Hotel> result = hotelRepository.search(
                tenantId,
                destinationId,
                city == null ? "" : city.trim(),
                stars,
                q == null ? "" : q.trim(),
                GeographySupport.pageRequest(page, size, GeographySupport.buildSort(sortBy, sortDir, SORTABLE)));
        return PagedApiResponse.of("Hotels fetched successfully",
                result.map(hotelMapper::toDto).getContent(),
                PaginationMeta.from(result, sortBy, sortDir));
    }

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<HotelDto> getByDestination(Long destinationId, int page, int size, String sortBy, String sortDir) {
        Long tenantId = GeographySupport.currentTenantId();
        Page<Hotel> result = hotelRepository.findByTenantIdAndDestinationId(
                tenantId, destinationId,
                GeographySupport.pageRequest(page, size, GeographySupport.buildSort(sortBy, sortDir, SORTABLE)));
        return PagedApiResponse.of("Hotels fetched successfully",
                result.map(hotelMapper::toDto).getContent(),
                PaginationMeta.from(result, sortBy, sortDir));
    }

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<HotelDto> getByCity(Long cityId, int page, int size, String sortBy, String sortDir) {
        Long tenantId = GeographySupport.currentTenantId();
        Page<Hotel> result = hotelRepository.findByTenantIdAndCityId(
                tenantId, cityId,
                GeographySupport.pageRequest(page, size, GeographySupport.buildSort(sortBy, sortDir, SORTABLE)));
        return PagedApiResponse.of("Hotels fetched successfully",
                result.map(hotelMapper::toDto).getContent(),
                PaginationMeta.from(result, sortBy, sortDir));
    }

    @Override
    @Transactional(readOnly = true)
    public HotelDto getById(Long hotelId) {
        return hotelMapper.toDto(findOrThrow(hotelId));
    }

    @Override
    @Transactional
    public HotelDto create(CreateHotelRequest request) {
        Long tenantId = GeographySupport.currentTenantId();
        City city = resolveCity(request.getDestinationId(), request.getCity(), tenantId);

        if (hotelRepository.existsByTenantIdAndNameAndCityId(tenantId, request.getName().trim(), city.getId())) {
            throw new BusinessException("A hotel named '" + request.getName() + "' already exists in this city", HttpStatus.CONFLICT);
        }

        Hotel hotel = hotelMapper.toEntity(request);
        hotel.setName(request.getName().trim());
        hotel.setCity(city);
        hotel.setTenantId(tenantId);

        Hotel saved = hotelRepository.save(hotel);
        log.info("Hotel created | id: {} | cityId: {} | tenantId: {}", saved.getId(), city.getId(), tenantId);
        return hotelMapper.toDto(saved);
    }

    @Override
    @Transactional
    public HotelDto update(Long hotelId, UpdateHotelRequest request) {
        Long tenantId = GeographySupport.currentTenantId();
        Hotel hotel = findOrThrow(hotelId);
        assertNoPlatformOwnedEdits(hotel, request);

        if (request.getDestinationId() != null || StringUtils.hasText(request.getCity())) {
            City city = resolveCity(request.getDestinationId(), request.getCity(), tenantId);
            hotel.setCity(city);
        }

        if (StringUtils.hasText(request.getName())) {
            String name = request.getName().trim();
            Long cityId = hotel.getCity().getId();
            if (hotelRepository.existsByTenantIdAndNameAndCityIdAndIdNot(tenantId, name, cityId, hotelId)) {
                throw new BusinessException("A hotel named '" + name + "' already exists in this city", HttpStatus.CONFLICT);
            }
            hotel.setName(name);
        }

        hotelMapper.updateEntity(request, hotel);
        if (request.getIsDefault() != null) {
            hotel.setDefault(request.getIsDefault());
        }
        Hotel saved = hotelRepository.save(hotel);
        log.info("Hotel updated | id: {} | tenantId: {}", hotelId, tenantId);
        return hotelMapper.toDto(saved);
    }

    @Override
    @Transactional
    public void delete(Long hotelId) {
        Hotel hotel = findOrThrow(hotelId);
        // NOT blocked for a PLATFORM_SYNC row, unlike every edit path above and below. This is the
        // only un-import there is: the marketplace exposes POST /hotels/{id}/import but no unlink,
        // so refusing here would strand an imported hotel in the master permanently. It is safe
        // because uq_hotels_platform_link_per_tenant is partial on `deleted_at IS NULL` (V2 PART 13)
        // — the trashed projection stops matching, and a re-import simply builds a fresh one.
        //
        // Leaf master — bookings/quotations reference it only by name snapshot (no FK), so it
        // moves to Trash freely and existing records keep resolving. Recoverable until purge.
        hotel.softDelete(GeographySupport.currentUsername());
        hotelRepository.save(hotel);
        log.info("Hotel moved to Trash | id: {}", hotelId);
    }

    @Override
    @Transactional
    public HotelDto setDefault(Long hotelId) {
        // Allowed on a projection: isDefault is an explicitly tenant-local field (§6.4) and the sync
        // never copies it, so there is nothing here for a catalog refresh to revert.
        Hotel hotel = findOrThrow(hotelId);
        hotel.setDefault(true);
        return hotelMapper.toDto(hotelRepository.save(hotel));
    }

    @Override
    public String uploadImage(MultipartFile file) {
        return cloudinaryService.uploadImage(file, "hotels");
    }

    @Override
    @Transactional
    public RoomTypeDto addRoomType(Long hotelId, CreateRoomTypeRequest request) {
        // Allowed even on a projection. A room the tenant adds carries no platformSourcePublicId, and
        // deactivateVanished() only touches rows that have one — so their own row survives every
        // future sync untouched. An agency with a privately negotiated category needs this.
        Hotel hotel = findOrThrow(hotelId);
        RoomType roomType = hotelMapper.toRoomTypeEntity(request);
        roomType.setHotel(hotel);
        RoomType saved = roomTypeRepository.save(roomType);
        return hotelMapper.toRoomTypeDto(saved);
    }

    @Override
    @Transactional
    public RoomTypeDto updateRoomType(Long hotelId, Long roomTypeId, UpdateRoomTypeRequest request) {
        findOrThrow(hotelId);
        RoomType roomType = roomTypeRepository.findByIdAndHotelId(roomTypeId, hotelId)
                .orElseThrow(() -> new ResourceNotFoundException("Room type not found: " + roomTypeId));
        // Every field on UpdateRoomTypeRequest — name, size, occupancy, bedType, description — is one
        // syncRooms() overwrites, so there is no partial edit worth allowing here.
        assertRoomTypeIsTenantOwned(roomType, "edited");
        hotelMapper.updateRoomTypeEntity(request, roomType);
        return hotelMapper.toRoomTypeDto(roomTypeRepository.save(roomType));
    }

    @Override
    @Transactional
    public RoomTypeDto updateRoomBaseRate(
            Long hotelId,
            Long roomTypeId,
            UpdateRoomBaseRateRequest request) {
        findOrThrow(hotelId);
        RoomType roomType = roomTypeRepository.findByIdAndHotelId(roomTypeId, hotelId)
                .orElseThrow(() -> new ResourceNotFoundException("Room type not found: " + roomTypeId));

        // The catalog owns the room description, not the tenant's selling rate. Projection sync
        // deliberately never writes baseRate, so this narrow update is safe for either row origin.
        roomType.setBaseRate(request.getBaseRate());
        return hotelMapper.toRoomTypeDto(roomTypeRepository.save(roomType));
    }

    @Override
    @Transactional
    public void deleteRoomType(Long hotelId, Long roomTypeId) {
        findOrThrow(hotelId);
        RoomType roomType = roomTypeRepository.findByIdAndHotelId(roomTypeId, hotelId)
                .orElseThrow(() -> new ResourceNotFoundException("Room type not found: " + roomTypeId));
        // This is a HARD delete, and the row would come back at the next sync with a NEW publicId —
        // orphaning every quotation and booking line that named the old one. That is precisely the
        // failure syncRooms() upserts to avoid; withdrawal is the catalog's job (it deactivates).
        assertRoomTypeIsTenantOwned(roomType, "deleted");
        roomTypeRepository.delete(roomType);
    }

    @Override
    @Transactional
    public ApiResponse<String> uploadRoomImages(Long hotelId, Long roomTypeId, MultipartFile[] files) {
        findOrThrow(hotelId);
        RoomType roomType = roomTypeRepository.findByIdAndHotelId(roomTypeId, hotelId)
                .orElseThrow(() -> new ResourceNotFoundException("Room type not found: " + roomTypeId));
        // syncRooms() does images.clear() then addAll(catalog images), so an upload here survives only
        // until the next catalog version — and it would have already been metered against the tenant's
        // storage quota by then. Refuse before the bytes are spent, not after.
        assertRoomTypeIsTenantOwned(roomType, "given images");

        List<String> urls = Arrays.stream(files)
                .map(f -> cloudinaryService.uploadImage(f, "hotels/rooms"))
                .collect(Collectors.toList());
        roomType.getImages().addAll(urls);
        roomTypeRepository.save(roomType);
        return ApiResponse.success("Images uploaded successfully", urls.toString());
    }

    @Override
    @Transactional
    public MealPlanDto addMealPlan(Long hotelId, CreateMealPlanRequest request) {
        // Allowed on a projection, for the same reason as addRoomType: a tenant-authored plan has no
        // platformSourcePublicId and survives every sync.
        Hotel hotel = findOrThrow(hotelId);
        MealPlan mealPlan = hotelMapper.toMealPlanEntity(request);
        mealPlan.setHotel(hotel);
        MealPlan saved = mealPlanRepository.save(mealPlan);
        return hotelMapper.toMealPlanDto(saved);
    }

    @Override
    @Transactional
    public MealPlanDto updateMealPlan(Long hotelId, Long mealPlanId, UpdateMealPlanRequest request) {
        findOrThrow(hotelId);
        MealPlan mealPlan = mealPlanRepository.findByIdAndHotelId(mealPlanId, hotelId)
                .orElseThrow(() -> new ResourceNotFoundException("Meal plan not found: " + mealPlanId));
        assertMealPlanEditIsPriceOnly(mealPlan, request);
        hotelMapper.updateMealPlanEntity(request, mealPlan);
        return hotelMapper.toMealPlanDto(mealPlanRepository.save(mealPlan));
    }

    @Override
    @Transactional
    public void deleteMealPlan(Long hotelId, Long mealPlanId) {
        findOrThrow(hotelId);
        MealPlan mealPlan = mealPlanRepository.findByIdAndHotelId(mealPlanId, hotelId)
                .orElseThrow(() -> new ResourceNotFoundException("Meal plan not found: " + mealPlanId));
        // Same hard-delete-then-resurrect-with-a-new-publicId problem as deleteRoomType.
        if (mealPlan.isPlatformOwned()) {
            throw platformOwnedChild("meal plan", mealPlan.getName(), "deleted");
        }
        mealPlanRepository.delete(mealPlan);
    }

    private Hotel findOrThrow(Long hotelId) {
        Long tenantId = GeographySupport.currentTenantId();
        return hotelRepository.findByIdAndTenantId(hotelId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Hotel not found: " + hotelId));
    }

    // ── Marketplace projections are read-only (design doc §6.4) ─────────────────────────────────
    //
    // A hotel imported from the platform catalog is a CONTROLLED PROJECTION, not a copy. The catalog
    // owns its descriptive fields and HotelMasterProjectionService.sync() rewrites them on every
    // version bump. So an edit accepted here is not merely against the rules — it is DATA LOSS ON A
    // TIMER: it saves, it displays, and it silently vanishes the next time the catalog moves. That
    // silence is the whole problem, and it is why these throw rather than quietly no-op.
    //
    // What stays the tenant's, and is deliberately still writable:
    //   RoomType   - baseRate through the narrow rate endpoint (syncRooms() never writes it)
    //   Hotel      — contactPerson, isDefault  (§6.4; sync() explicitly does not copy either)
    //   MealPlan   — price                     (their selling price; syncMealPlans() never writes it)
    //   delete()   — the only un-import path there is (see the note on that method)
    //   new room types / meal plans they add themselves (no platformSourcePublicId ⇒ sync ignores them)
    //
    // 409 CONFLICT, not 403: this is not about who the caller is. No permission and no role can edit
    // a projection, because the conflict is with the state of the resource itself.
    //
    // FRONTEND: HotelDto/RoomTypeDto/MealPlanDto now carry `platformOwned` so the master screen can
    // render these rows read-only. That is the real UX; this guard is the backstop for a direct API
    // call. It matters that both exist — the FE error policy treats 409 as call-site-rendered and
    // shows no automatic toast, so a UI that still offers an enabled Save button would appear to do
    // nothing at all when it is pressed.

    /**
     * Reject an update that would change any platform-owned field on a projection.
     *
     * <p>Compares against the STORED value rather than merely checking whether a field was sent. The
     * hotel form posts its whole model back, so a "was this key present?" test would 409 every save —
     * including one that only touches contactPerson. Only a real change is refused.
     *
     * <p>{@code null} and {@code ""} are treated as the same value throughout: a controlled React
     * input yields {@code ""} for an empty box, and a projection whose {@code website} is null would
     * otherwise be unsaveable forever.
     */
    private void assertNoPlatformOwnedEdits(Hotel hotel, UpdateHotelRequest r) {
        if (!hotel.isPlatformOwned()) {
            return;
        }
        City current = hotel.getCity();
        List<String> blocked = new ArrayList<>();

        if (r.getDestinationId() != null && current != null && current.getDestination() != null
                && !r.getDestinationId().equals(current.getDestination().getId())) {
            blocked.add("destination");
        }
        if (textChanged(r.getCity(), current == null ? null : current.getName())) blocked.add("city");
        if (textChanged(r.getName(), hotel.getName()))            blocked.add("name");
        if (textChanged(r.getAddress(), hotel.getAddress()))      blocked.add("address");
        if (textChanged(r.getPhone(), hotel.getPhone()))          blocked.add("phone");
        if (textChanged(r.getEmail(), hotel.getEmail()))          blocked.add("email");
        if (textChanged(r.getWebsite(), hotel.getWebsite()))      blocked.add("website");
        if (textChanged(r.getMapUrl(), hotel.getMapUrl()))        blocked.add("mapUrl");
        if (textChanged(r.getOverview(), hotel.getOverview()))    blocked.add("overview");
        if (textChanged(r.getImagePath(), hotel.getImagePath()))  blocked.add("image");
        if (valueChanged(r.getStars(), hotel.getStars()))         blocked.add("stars");
        if (valueChanged(r.getRating(), hotel.getRating()))       blocked.add("rating");
        if (valueChanged(r.getLatitude(), hotel.getLatitude()))   blocked.add("latitude");
        if (valueChanged(r.getLongitude(), hotel.getLongitude())) blocked.add("longitude");
        if (r.getAmenities() != null && !normalize(r.getAmenities()).equals(normalize(hotel.getAmenities()))) {
            blocked.add("amenities");
        }

        if (!blocked.isEmpty()) {
            log.info("Rejected edit to platform-owned fields {} on projection {} (catalog hotel {})",
                    blocked, hotel.getId(), hotel.getPlatformHotelPublicId());
            throw new BusinessException(
                    "'" + hotel.getName() + "' comes from the Hotel Marketplace, so "
                            + join(blocked) + " " + (blocked.size() == 1 ? "is" : "are")
                            + " maintained by the platform and cannot be edited here. "
                            + "You can still set the contact person and mark it as default.",
                    HttpStatus.CONFLICT);
        }
    }

    /** Refuse a write to a room type the catalog owns. {@code verb} completes "cannot be ...". */
    private void assertRoomTypeIsTenantOwned(RoomType roomType, String verb) {
        if (roomType.isPlatformOwned()) {
            throw platformOwnedChild("room type", roomType.getName(), verb);
        }
    }

    /**
     * A catalog meal plan accepts a price change and nothing else — price is the tenant's own selling
     * price and {@code syncMealPlans()} deliberately never writes it, so it is the one field here that
     * a catalog refresh will not revert.
     */
    private void assertMealPlanEditIsPriceOnly(MealPlan mealPlan, UpdateMealPlanRequest r) {
        if (!mealPlan.isPlatformOwned()) {
            return;
        }
        List<String> blocked = new ArrayList<>();
        if (textChanged(r.getName(), mealPlan.getName()))               blocked.add("name");
        if (textChanged(r.getDescription(), mealPlan.getDescription())) blocked.add("description");
        if (!blocked.isEmpty()) {
            throw new BusinessException(
                    "The meal plan '" + mealPlan.getName() + "' comes from the Hotel Marketplace, so its "
                            + join(blocked) + " cannot be edited here. Its price is yours to set.",
                    HttpStatus.CONFLICT);
        }
    }

    private BusinessException platformOwnedChild(String kind, String name, String verb) {
        return new BusinessException(
                "The " + kind + " '" + name + "' comes from the Hotel Marketplace and cannot be "
                        + verb + " here — the platform maintains it, and the next catalog sync would "
                        + "undo the change. Add your own " + kind + " instead.",
                HttpStatus.CONFLICT);
    }

    /** True when a non-null requested value actually differs from what is stored (null ≡ ""). */
    private static boolean textChanged(String requested, String current) {
        return requested != null && !requested.trim().equals(current == null ? "" : current.trim());
    }

    /** True when a non-null requested value actually differs from what is stored. */
    private static boolean valueChanged(Object requested, Object current) {
        return requested != null && !requested.equals(current);
    }

    private static List<String> normalize(List<String> values) {
        return values == null ? List.of()
                : values.stream().filter(StringUtils::hasText).map(String::trim).sorted().toList();
    }

    private static String join(List<String> fields) {
        if (fields.size() == 1) return fields.get(0);
        return String.join(", ", fields.subList(0, fields.size() - 1))
                + " and " + fields.get(fields.size() - 1);
    }

    /**
     * Resolves a City from (destinationId + city name) or throws.
     * The city must already exist — hotels cannot implicitly create cities.
     */
    private City resolveCity(Long destinationId, String cityName, Long tenantId) {
        if (destinationId != null && StringUtils.hasText(cityName)) {
            return cityRepository.findByTenantIdAndDestinationIdAndNameIgnoreCase(
                            tenantId, destinationId, cityName.trim())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "City '" + cityName + "' not found under destination " + destinationId));
        }
        if (destinationId != null) {
            // No city name supplied — return first city under this destination (fallback)
            return cityRepository.findByTenantIdAndDestinationId(
                            tenantId, destinationId,
                            PageRequest.of(0, 1, GeographySupport.buildSort("id", "asc")))
                    .stream().findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "No cities found for destination " + destinationId));
        }
        throw new BusinessException("destinationId is required to create a hotel", HttpStatus.BAD_REQUEST);
    }
}
