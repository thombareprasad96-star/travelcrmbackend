package com.crm.travelcrm.master.hotel.service;

import com.crm.travelcrm.common.cloudinary.CloudinaryService;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.master.destination.DestinationMasterRepository;
import com.crm.travelcrm.master.hotel.dto.request.HotelRequestDTO;
import com.crm.travelcrm.master.hotel.dto.request.MealPlanRequestDTO;
import com.crm.travelcrm.master.hotel.dto.request.RoomTypeRequestDTO;
import com.crm.travelcrm.master.hotel.dto.response.DestinationWithHotelsDTO;
import com.crm.travelcrm.master.hotel.dto.response.HotelResponseDTO;
import com.crm.travelcrm.master.hotel.dto.response.MealPlanResponseDTO;
import com.crm.travelcrm.master.hotel.dto.response.RoomTypeResponseDTO;
import com.crm.travelcrm.master.hotel.entity.HotelEntity;
import com.crm.travelcrm.master.hotel.entity.MealPlanEntity;
import com.crm.travelcrm.master.hotel.entity.RoomTypeEntity;
import com.crm.travelcrm.master.hotel.repository.HotelRepository;
import com.crm.travelcrm.master.hotel.repository.MealPlanRepository;
import com.crm.travelcrm.master.hotel.repository.RoomTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class HotelService {

    private final HotelRepository       hotelRepository;
    private final RoomTypeRepository    roomTypeRepository;
    private final MealPlanRepository    mealPlanRepository;
    private final DestinationMasterRepository destinationMasterRepository;
    private final CloudinaryService     cloudinaryService;

    // ── GET ALL (grouped by destination) ─────────────────────────────────────

    public List<DestinationWithHotelsDTO> getAllGroupedByDestination() {
        List<HotelEntity> allHotels = hotelRepository.findAll();

        // group hotels by destinationId preserving insert order
        Map<Long, List<HotelEntity>> byDest = new LinkedHashMap<>();
        for (HotelEntity h : allHotels) {
            byDest.computeIfAbsent(h.getDestinationId(), k -> new ArrayList<>()).add(h);
        }

        List<DestinationWithHotelsDTO> result = new ArrayList<>();
        byDest.forEach((destId, hotels) -> {
            String destName = destinationMasterRepository.findById(destId)
                    .map(d -> d.getName())
                    .orElse("Unknown");

            List<String> cities = hotels.stream()
                    .map(HotelEntity::getCity)
                    .filter(c -> c != null && !c.isBlank())
                    .distinct()
                    .collect(Collectors.toList());

            result.add(DestinationWithHotelsDTO.builder()
                    .id(destId)
                    .name(destName)
                    .cities(cities)
                    .hotels(hotels.stream().map(this::toResponse).collect(Collectors.toList()))
                    .build());
        });
        return result;
    }

    // ── GET BY DESTINATION ────────────────────────────────────────────────────

    public List<HotelResponseDTO> getByDestination(Long destinationId) {
        return hotelRepository.findByDestinationId(destinationId)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    // ── GET BY ID ─────────────────────────────────────────────────────────────

    public HotelResponseDTO getById(Long id) {
        return toResponse(findHotelOrThrow(id));
    }

    // ── CREATE ────────────────────────────────────────────────────────────────

    @Transactional
    public HotelResponseDTO create(HotelRequestDTO req) {
        HotelEntity hotel = HotelEntity.builder()
                .destinationId(req.getDestinationId())
                .name(req.getName())
                .city(req.getCity())
                .stars(req.getStars())
                .rating(req.getRating())
                .isDefault(req.getIsDefault() != null && req.getIsDefault())
                .address(req.getAddress())
                .mapUrl(req.getMapUrl())
                .latitude(req.getLatitude())
                .longitude(req.getLongitude())
                .contactPerson(req.getContactPerson())
                .phone(req.getPhone())
                .email(req.getEmail())
                .website(req.getWebsite())
                .overview(req.getOverview())
                .amenities(req.getAmenities() != null ? req.getAmenities() : new ArrayList<>())
                .build();

        if (Boolean.TRUE.equals(hotel.getIsDefault())) {
            clearDefaultForDestination(req.getDestinationId(), null);
        }

        HotelEntity saved = hotelRepository.save(hotel);

        if (req.getRoomTypes() != null) {
            for (RoomTypeRequestDTO r : req.getRoomTypes()) {
                RoomTypeEntity room = buildRoomType(r, saved);
                saved.getRoomTypes().add(roomTypeRepository.save(room));
            }
        }
        if (req.getMealPlans() != null) {
            for (MealPlanRequestDTO m : req.getMealPlans()) {
                MealPlanEntity meal = buildMealPlan(m, saved);
                saved.getMealPlans().add(mealPlanRepository.save(meal));
            }
        }

        return toResponse(saved);
    }

    // ── UPDATE ────────────────────────────────────────────────────────────────

    @Transactional
    public HotelResponseDTO update(Long id, HotelRequestDTO req) {
        HotelEntity hotel = findHotelOrThrow(id);

        hotel.setDestinationId(req.getDestinationId());
        hotel.setName(req.getName());
        hotel.setCity(req.getCity());
        hotel.setStars(req.getStars());
        hotel.setRating(req.getRating());
        hotel.setAddress(req.getAddress());
        hotel.setMapUrl(req.getMapUrl());
        hotel.setLatitude(req.getLatitude());
        hotel.setLongitude(req.getLongitude());
        hotel.setContactPerson(req.getContactPerson());
        hotel.setPhone(req.getPhone());
        hotel.setEmail(req.getEmail());
        hotel.setWebsite(req.getWebsite());
        hotel.setOverview(req.getOverview());
        if (req.getAmenities() != null) hotel.setAmenities(req.getAmenities());

        boolean newDefault = req.getIsDefault() != null && req.getIsDefault();
        if (newDefault && !Boolean.TRUE.equals(hotel.getIsDefault())) {
            clearDefaultForDestination(req.getDestinationId(), id);
        }
        hotel.setIsDefault(newDefault);

        return toResponse(hotelRepository.save(hotel));
    }

    // ── DELETE ────────────────────────────────────────────────────────────────

    @Transactional
    public void delete(Long id) {
        HotelEntity hotel = findHotelOrThrow(id);
        hotelRepository.delete(hotel);
    }

    // ── SET DEFAULT ───────────────────────────────────────────────────────────

    @Transactional
    public void setDefault(Long hotelId, Long destinationId) {
        clearDefaultForDestination(destinationId, null);
        HotelEntity hotel = findHotelOrThrow(hotelId);
        hotel.setIsDefault(true);
        hotelRepository.save(hotel);
    }

    // ── UPLOAD HOTEL IMAGE ────────────────────────────────────────────────────

    public String uploadHotelImage(MultipartFile file) {
        return cloudinaryService.uploadImage(file, "hotels");
    }

    // ── ROOM TYPES ────────────────────────────────────────────────────────────

    @Transactional
    public RoomTypeResponseDTO addRoomType(Long hotelId, RoomTypeRequestDTO req) {
        HotelEntity hotel = findHotelOrThrow(hotelId);
        RoomTypeEntity room = buildRoomType(req, hotel);
        return toRoomResponse(roomTypeRepository.save(room));
    }

    @Transactional
    public RoomTypeResponseDTO updateRoomType(Long hotelId, Long roomTypeId, RoomTypeRequestDTO req) {
        findHotelOrThrow(hotelId);
        RoomTypeEntity room = roomTypeRepository.findById(roomTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("Room type not found: " + roomTypeId));

        room.setName(req.getName());
        room.setSize(req.getSize());
        room.setOccupancy(req.getOccupancy());
        room.setBedType(req.getBedType());
        room.setDescription(req.getDescription());
        return toRoomResponse(roomTypeRepository.save(room));
    }

    @Transactional
    public void deleteRoomType(Long hotelId, Long roomTypeId) {
        findHotelOrThrow(hotelId);
        RoomTypeEntity room = roomTypeRepository.findById(roomTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("Room type not found: " + roomTypeId));
        roomTypeRepository.delete(room);
    }

    public List<String> uploadRoomImages(Long hotelId, Long roomTypeId, List<MultipartFile> files) {
        findHotelOrThrow(hotelId);
        RoomTypeEntity room = roomTypeRepository.findById(roomTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("Room type not found: " + roomTypeId));

        List<String> urls = new ArrayList<>();
        for (MultipartFile file : files) {
            String url = cloudinaryService.uploadImage(file, "hotels/rooms");
            urls.add(url);
            room.getImages().add(url);
        }
        roomTypeRepository.save(room);
        return urls;
    }

    // ── MEAL PLANS ────────────────────────────────────────────────────────────

    @Transactional
    public MealPlanResponseDTO addMealPlan(Long hotelId, MealPlanRequestDTO req) {
        HotelEntity hotel = findHotelOrThrow(hotelId);
        return toMealResponse(mealPlanRepository.save(buildMealPlan(req, hotel)));
    }

    @Transactional
    public MealPlanResponseDTO updateMealPlan(Long hotelId, Long mealPlanId, MealPlanRequestDTO req) {
        findHotelOrThrow(hotelId);
        MealPlanEntity meal = mealPlanRepository.findById(mealPlanId)
                .orElseThrow(() -> new ResourceNotFoundException("Meal plan not found: " + mealPlanId));

        meal.setName(req.getName());
        meal.setPrice(req.getPrice());
        meal.setDescription(req.getDescription());
        return toMealResponse(mealPlanRepository.save(meal));
    }

    @Transactional
    public void deleteMealPlan(Long hotelId, Long mealPlanId) {
        findHotelOrThrow(hotelId);
        MealPlanEntity meal = mealPlanRepository.findById(mealPlanId)
                .orElseThrow(() -> new ResourceNotFoundException("Meal plan not found: " + mealPlanId));
        mealPlanRepository.delete(meal);
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    private HotelEntity findHotelOrThrow(Long id) {
        return hotelRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Hotel not found: " + id));
    }

    private void clearDefaultForDestination(Long destinationId, Long excludeHotelId) {
        hotelRepository.findByDestinationIdAndIsDefaultTrue(destinationId).forEach(h -> {
            if (excludeHotelId == null || !h.getId().equals(excludeHotelId)) {
                h.setIsDefault(false);
                hotelRepository.save(h);
            }
        });
    }

    private RoomTypeEntity buildRoomType(RoomTypeRequestDTO req, HotelEntity hotel) {
        return RoomTypeEntity.builder()
                .hotel(hotel)
                .name(req.getName())
                .size(req.getSize())
                .occupancy(req.getOccupancy())
                .bedType(req.getBedType())
                .description(req.getDescription())
                .build();
    }

    private MealPlanEntity buildMealPlan(MealPlanRequestDTO req, HotelEntity hotel) {
        return MealPlanEntity.builder()
                .hotel(hotel)
                .name(req.getName())
                .price(req.getPrice())
                .description(req.getDescription())
                .build();
    }

    HotelResponseDTO toResponse(HotelEntity h) {
        return HotelResponseDTO.builder()
                .id(h.getId())
                .destinationId(h.getDestinationId())
                .name(h.getName())
                .city(h.getCity())
                .stars(h.getStars())
                .rating(h.getRating())
                .isDefault(h.getIsDefault())
                .address(h.getAddress())
                .mapUrl(h.getMapUrl())
                .latitude(h.getLatitude())
                .longitude(h.getLongitude())
                .contactPerson(h.getContactPerson())
                .phone(h.getPhone())
                .email(h.getEmail())
                .website(h.getWebsite())
                .overview(h.getOverview())
                .imageUrl(h.getImageUrl())
                .amenities(h.getAmenities())
                .roomTypes(h.getRoomTypes().stream().map(this::toRoomResponse).collect(Collectors.toList()))
                .mealPlans(h.getMealPlans().stream().map(this::toMealResponse).collect(Collectors.toList()))
                .build();
    }

    private RoomTypeResponseDTO toRoomResponse(RoomTypeEntity r) {
        return RoomTypeResponseDTO.builder()
                .id(r.getId())
                .name(r.getName())
                .size(r.getSize())
                .occupancy(r.getOccupancy())
                .bedType(r.getBedType())
                .description(r.getDescription())
                .images(r.getImages())
                .build();
    }

    private MealPlanResponseDTO toMealResponse(MealPlanEntity m) {
        return MealPlanResponseDTO.builder()
                .id(m.getId())
                .name(m.getName())
                .price(m.getPrice())
                .description(m.getDescription())
                .build();
    }
}