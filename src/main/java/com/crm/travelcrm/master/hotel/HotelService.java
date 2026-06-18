package com.crm.travelcrm.master.hotel;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import org.springframework.web.multipart.MultipartFile;

public interface HotelService {

    PagedApiResponse<HotelDto> getAll(int page, int size, String sortBy, String sortDir);

    PagedApiResponse<HotelDto> getByDestination(Long destinationId, int page, int size, String sortBy, String sortDir);

    PagedApiResponse<HotelDto> getByCity(Long cityId, int page, int size, String sortBy, String sortDir);

    HotelDto getById(Long hotelId);

    HotelDto create(CreateHotelRequest request);

    HotelDto update(Long hotelId, UpdateHotelRequest request);

    void delete(Long hotelId);

    HotelDto setDefault(Long hotelId);

    String uploadImage(MultipartFile file);

    RoomTypeDto addRoomType(Long hotelId, CreateRoomTypeRequest request);

    RoomTypeDto updateRoomType(Long hotelId, Long roomTypeId, UpdateRoomTypeRequest request);

    void deleteRoomType(Long hotelId, Long roomTypeId);

    ApiResponse<String> uploadRoomImages(Long hotelId, Long roomTypeId, MultipartFile[] files);

    MealPlanDto addMealPlan(Long hotelId, CreateMealPlanRequest request);

    MealPlanDto updateMealPlan(Long hotelId, Long mealPlanId, UpdateMealPlanRequest request);

    void deleteMealPlan(Long hotelId, Long mealPlanId);
}