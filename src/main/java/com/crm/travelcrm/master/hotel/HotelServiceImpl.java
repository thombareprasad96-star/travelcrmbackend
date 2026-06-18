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

import java.util.Arrays;
import java.util.List;
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

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<HotelDto> getAll(int page, int size, String sortBy, String sortDir) {
        Long tenantId = GeographySupport.currentTenantId();
        Page<Hotel> result = hotelRepository.findByTenantId(
                tenantId, PageRequest.of(page, size, GeographySupport.buildSort(sortBy, sortDir)));
        return PagedApiResponse.of("Hotels fetched successfully",
                result.map(hotelMapper::toDto).getContent(),
                PaginationMeta.from(result, sortBy, sortDir));
    }

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<HotelDto> getByDestination(Long destinationId, int page, int size, String sortBy, String sortDir) {
        Long tenantId = GeographySupport.currentTenantId();
        Page<Hotel> result = hotelRepository.findByTenantIdAndDestinationId(
                tenantId, destinationId, PageRequest.of(page, size, GeographySupport.buildSort(sortBy, sortDir)));
        return PagedApiResponse.of("Hotels fetched successfully",
                result.map(hotelMapper::toDto).getContent(),
                PaginationMeta.from(result, sortBy, sortDir));
    }

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<HotelDto> getByCity(Long cityId, int page, int size, String sortBy, String sortDir) {
        Long tenantId = GeographySupport.currentTenantId();
        Page<Hotel> result = hotelRepository.findByTenantIdAndCityId(
                tenantId, cityId, PageRequest.of(page, size, GeographySupport.buildSort(sortBy, sortDir)));
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
        hotelRepository.delete(hotel);
        log.info("Hotel deleted | id: {}", hotelId);
    }

    @Override
    @Transactional
    public HotelDto setDefault(Long hotelId) {
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
        hotelMapper.updateRoomTypeEntity(request, roomType);
        return hotelMapper.toRoomTypeDto(roomTypeRepository.save(roomType));
    }

    @Override
    @Transactional
    public void deleteRoomType(Long hotelId, Long roomTypeId) {
        findOrThrow(hotelId);
        RoomType roomType = roomTypeRepository.findByIdAndHotelId(roomTypeId, hotelId)
                .orElseThrow(() -> new ResourceNotFoundException("Room type not found: " + roomTypeId));
        roomTypeRepository.delete(roomType);
    }

    @Override
    @Transactional
    public ApiResponse<String> uploadRoomImages(Long hotelId, Long roomTypeId, MultipartFile[] files) {
        findOrThrow(hotelId);
        RoomType roomType = roomTypeRepository.findByIdAndHotelId(roomTypeId, hotelId)
                .orElseThrow(() -> new ResourceNotFoundException("Room type not found: " + roomTypeId));

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
        hotelMapper.updateMealPlanEntity(request, mealPlan);
        return hotelMapper.toMealPlanDto(mealPlanRepository.save(mealPlan));
    }

    @Override
    @Transactional
    public void deleteMealPlan(Long hotelId, Long mealPlanId) {
        findOrThrow(hotelId);
        MealPlan mealPlan = mealPlanRepository.findByIdAndHotelId(mealPlanId, hotelId)
                .orElseThrow(() -> new ResourceNotFoundException("Meal plan not found: " + mealPlanId));
        mealPlanRepository.delete(mealPlan);
    }

    private Hotel findOrThrow(Long hotelId) {
        Long tenantId = GeographySupport.currentTenantId();
        return hotelRepository.findByIdAndTenantId(hotelId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Hotel not found: " + hotelId));
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