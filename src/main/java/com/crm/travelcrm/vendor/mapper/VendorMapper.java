package com.crm.travelcrm.vendor.mapper;

import com.crm.travelcrm.vendor.dto.request.VendorRequestDTO;
import com.crm.travelcrm.vendor.dto.response.VendorResponseDTO;
import com.crm.travelcrm.vendor.entity.Vendor;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.factory.Mappers;

@Mapper(componentModel = "spring")
public interface VendorMapper {

    VendorMapper INSTANCE = Mappers.getMapper(VendorMapper.class);

    // ── ENTITY TO RESPONSE ────────────────────────────────────────────────────

    @Mapping(source = "createdAt", target = "joinDate")
    VendorResponseDTO toResponse(Vendor vendor);

    // ── REQUEST TO ENTITY ────────────────────────────────────────────────────

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "vendorCode", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "payStatus", ignore = true)
    @Mapping(target = "totalBusiness", ignore = true)
    @Mapping(target = "totalPaid", ignore = true)
    @Mapping(target = "rating", ignore = true)
    @Mapping(target = "ratingCount", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "deletedBy", ignore = true)
    Vendor toEntity(VendorRequestDTO request);

    // ── UPDATE ENTITY FROM REQUEST ────────────────────────────────────────────

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "vendorCode", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "payStatus", ignore = true)
    @Mapping(target = "totalBusiness", ignore = true)
    @Mapping(target = "totalPaid", ignore = true)
    @Mapping(target = "rating", ignore = true)
    @Mapping(target = "ratingCount", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "deletedBy", ignore = true)
    void updateEntity(VendorRequestDTO request, @MappingTarget Vendor vendor);
}