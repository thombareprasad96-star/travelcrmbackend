package com.crm.travelcrm.customer.mapper;

import com.crm.travelcrm.customer.dto.request.CreateCustomerRequest;
import com.crm.travelcrm.customer.dto.request.UpdateCustomerRequest;
import com.crm.travelcrm.customer.dto.response.CustomerResponse;
import com.crm.travelcrm.customer.entity.Customer;
import org.mapstruct.*;

/**
 * MapStruct mapper for {@link Customer} ⇄ DTO conversions.
 *
 * <p>Generated as a Spring bean ({@code componentModel = "spring"}). Server-managed
 * fields (identifiers, tenant, audit, soft-delete, business code) are always
 * ignored on inbound mappings — they are owned by the persistence/service layer,
 * never the client. On the outbound mapping, {@code id} surfaces the {@code publicId}
 * and {@code customerId} surfaces the {@code customerCode}; the booking-derived
 * metrics are left untouched here and populated by the service.</p>
 *
 * <p>{@code NullValuePropertyMappingStrategy.IGNORE} on the update mapping means a
 * {@code null} field in the payload won't blank out an existing column.</p>
 */
@Mapper(
        componentModel = "spring",
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface CustomerMapper {

    // ── Entity → Response ───────────────────────────────────────────────────────

    @Mapping(target = "id",          source = "publicId")
    @Mapping(target = "customerId",  source = "customerCode")
    // Booking-derived; enriched in the service after mapping.
    @Mapping(target = "bookings",    ignore = true)
    @Mapping(target = "spent",       ignore = true)
    @Mapping(target = "lastBooking", ignore = true)
    CustomerResponse toResponse(Customer customer);

    // ── Create Request → Entity ─────────────────────────────────────────────────

    @Mapping(target = "id",           ignore = true)
    @Mapping(target = "publicId",     ignore = true)
    @Mapping(target = "tenantId",     ignore = true)
    @Mapping(target = "customerCode", ignore = true)
    @Mapping(target = "createdAt",    ignore = true)
    @Mapping(target = "updatedAt",    ignore = true)
    @Mapping(target = "createdBy",    ignore = true)
    @Mapping(target = "updatedBy",    ignore = true)
    @Mapping(target = "deletedAt",    ignore = true)
    @Mapping(target = "deletedBy",    ignore = true)
    Customer toEntity(CreateCustomerRequest request);

    // ── Update Request → existing Entity (in place) ─────────────────────────────

    @Mapping(target = "id",           ignore = true)
    @Mapping(target = "publicId",     ignore = true)
    @Mapping(target = "tenantId",     ignore = true)
    @Mapping(target = "customerCode", ignore = true)
    @Mapping(target = "createdAt",    ignore = true)
    @Mapping(target = "updatedAt",    ignore = true)
    @Mapping(target = "createdBy",    ignore = true)
    @Mapping(target = "updatedBy",    ignore = true)
    @Mapping(target = "deletedAt",    ignore = true)
    @Mapping(target = "deletedBy",    ignore = true)
    void updateEntity(UpdateCustomerRequest request, @MappingTarget Customer customer);
}