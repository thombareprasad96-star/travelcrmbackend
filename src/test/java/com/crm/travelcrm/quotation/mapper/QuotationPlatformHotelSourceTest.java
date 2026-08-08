package com.crm.travelcrm.quotation.mapper;

import com.crm.travelcrm.quotation.dto.QuotationRequestDto;
import com.crm.travelcrm.quotation.entity.Quotation;
import com.crm.travelcrm.quotation.entity.QuotationHotel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QuotationPlatformHotelSourceTest {

    @Test
    void canonicalMarketplaceSourceIsSnapshottedSeparatelyFromTenantMasterIds() {
        UUID tenantHotel = UUID.randomUUID();
        UUID tenantRoom = UUID.randomUUID();
        UUID tenantMeal = UUID.randomUUID();
        UUID platformHotel = UUID.randomUUID();
        UUID platformRoom = UUID.randomUUID();
        UUID platformMeal = UUID.randomUUID();

        QuotationRequestDto.HotelItem item = new QuotationRequestDto.HotelItem();
        item.setHotelMasterPublicId(tenantHotel);
        item.setRoomTypeMasterPublicId(tenantRoom);
        item.setMealPlanMasterPublicId(tenantMeal);
        item.setPlatformHotelPublicId(platformHotel);
        item.setPlatformRoomPublicId(platformRoom);
        item.setPlatformMealPlanPublicId(platformMeal);
        item.setName("Platform Grand");

        QuotationRequestDto.HotelSection hotelSection = new QuotationRequestDto.HotelSection();
        hotelSection.setIncluded(true);
        hotelSection.setHotels(List.of(item));

        QuotationRequestDto request = new QuotationRequestDto();
        request.setTitle("Platform hotel quote");
        request.setHotel(hotelSection);

        Quotation quotation = new Quotation();
        new QuotationMapper().applyRequest(request, quotation);

        assertThat(quotation.getHotels()).hasSize(1);
        QuotationHotel saved = quotation.getHotels().getFirst();
        assertThat(saved.getHotelMasterPublicId()).isEqualTo(tenantHotel);
        assertThat(saved.getRoomTypeMasterPublicId()).isEqualTo(tenantRoom);
        assertThat(saved.getMealPlanMasterPublicId()).isEqualTo(tenantMeal);
        assertThat(saved.getPlatformHotelPublicId()).isEqualTo(platformHotel);
        assertThat(saved.getPlatformRoomPublicId()).isEqualTo(platformRoom);
        assertThat(saved.getPlatformMealPlanPublicId()).isEqualTo(platformMeal);
    }
}
