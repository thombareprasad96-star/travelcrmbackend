package com.crm.travelcrm.booking.service;

import com.crm.travelcrm.booking.dto.request.AssignVendorRequest;
import com.crm.travelcrm.booking.dto.request.CreateBookingServiceItemRequest;
import com.crm.travelcrm.booking.dto.request.UpdateBookingServiceItemRequest;
import com.crm.travelcrm.booking.dto.response.BookingServiceItemResponse;
import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.entity.BookingServiceItem;
import com.crm.travelcrm.booking.enums.ServiceItemStatus;
import com.crm.travelcrm.booking.exception.BookingNotFoundException;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.booking.repository.BookingServiceItemRepository;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.vendor.entity.Vendor;
import com.crm.travelcrm.vendor.repository.VendorRepository;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BookingServiceItemServiceImpl implements BookingServiceItemService {

    private static final Logger log = LogManager.getLogger(BookingServiceItemServiceImpl.class);

    private final BookingRepository            bookingRepository;
    private final BookingServiceItemRepository serviceItemRepository;
    private final VendorRepository             vendorRepository;

    @Override
    @Transactional(readOnly = true)
    public List<BookingServiceItemResponse> getServiceItems(UUID bookingPublicId) {
        Booking booking = findActiveBooking(bookingPublicId);
        return serviceItemRepository.findByBookingIdAndDeletedAtIsNullOrderByIdAsc(booking.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public BookingServiceItemResponse addServiceItem(UUID bookingPublicId,
                                                     CreateBookingServiceItemRequest request) {
        Booking booking = findActiveBooking(bookingPublicId);

        BookingServiceItem item = BookingServiceItem.builder()
                .bookingId(booking.getId())
                .serviceType(request.getServiceType())
                .title(request.getTitle())
                .description(request.getDescription())
                .serviceDate(request.getServiceDate())
                .endDate(request.getEndDate())
                .status(request.getStatus() != null ? request.getStatus() : ServiceItemStatus.PENDING)
                .cost(request.getCost())
                .vendorCost(request.getVendorCost())
                .confirmationNumber(request.getConfirmationNumber())
                .notes(request.getNotes())
                .build();

        BookingServiceItem saved = serviceItemRepository.save(item);
        log.info("Service line {} ('{}') added to booking {}",
                saved.getPublicId(), saved.getTitle(), booking.getBookingCode());
        return toResponse(saved);
    }

    @Override
    @Transactional
    public BookingServiceItemResponse updateServiceItem(UUID bookingPublicId, UUID itemPublicId,
                                                        UpdateBookingServiceItemRequest request) {
        Booking booking = findActiveBooking(bookingPublicId);
        BookingServiceItem item = findItem(booking, itemPublicId);

        if (request.getServiceType() != null)        item.setServiceType(request.getServiceType());
        if (request.getTitle() != null)              item.setTitle(request.getTitle());
        if (request.getDescription() != null)        item.setDescription(request.getDescription());
        if (request.getServiceDate() != null)        item.setServiceDate(request.getServiceDate());
        if (request.getEndDate() != null)            item.setEndDate(request.getEndDate());
        if (request.getStatus() != null)             item.setStatus(request.getStatus());
        if (request.getCost() != null)               item.setCost(request.getCost());
        if (request.getVendorCost() != null)         item.setVendorCost(request.getVendorCost());
        if (request.getConfirmationNumber() != null) item.setConfirmationNumber(request.getConfirmationNumber());
        if (request.getNotes() != null)              item.setNotes(request.getNotes());

        BookingServiceItem saved = serviceItemRepository.save(item);
        log.info("Service line {} updated on booking {}", itemPublicId, booking.getBookingCode());
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void deleteServiceItem(UUID bookingPublicId, UUID itemPublicId) {
        Booking booking = findActiveBooking(bookingPublicId);
        BookingServiceItem item = findItem(booking, itemPublicId);
        item.softDelete(currentUserEmail());
        serviceItemRepository.save(item);
        log.info("Service line {} removed from booking {}", itemPublicId, booking.getBookingCode());
    }

    @Override
    @Transactional
    public BookingServiceItemResponse assignVendor(UUID bookingPublicId, UUID itemPublicId,
                                                   AssignVendorRequest request) {
        Long tenantId = requireTenantId();
        Booking booking = findActiveBooking(bookingPublicId);
        BookingServiceItem item = findItem(booking, itemPublicId);

        Vendor vendor = vendorRepository
                .findByPublicIdAndTenantId(request.getVendorPublicId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Vendor not found: " + request.getVendorPublicId()));

        item.setVendorId(vendor.getId());
        item.setVendorPublicId(vendor.getPublicId());
        item.setVendorNameSnapshot(vendor.getVendorName());
        if (request.getVendorCost() != null)         item.setVendorCost(request.getVendorCost());
        if (request.getConfirmationNumber() != null) item.setConfirmationNumber(request.getConfirmationNumber());

        BookingServiceItem saved = serviceItemRepository.save(item);
        log.info("Vendor {} ('{}') assigned to service line {} of booking {}",
                vendor.getPublicId(), vendor.getVendorName(), itemPublicId, booking.getBookingCode());
        return toResponse(saved);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Booking findActiveBooking(UUID bookingPublicId) {
        return bookingRepository.findByPublicIdAndDeletedAtIsNull(bookingPublicId)
                .orElseThrow(() -> new BookingNotFoundException(bookingPublicId));
    }

    private BookingServiceItem findItem(Booking booking, UUID itemPublicId) {
        return serviceItemRepository
                .findByPublicIdAndBookingIdAndDeletedAtIsNull(itemPublicId, booking.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Service line not found on this booking: " + itemPublicId));
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("TenantContext is empty. Ensure JwtAuthFilter is running.");
        }
        return tenantId;
    }

    private String currentUserEmail() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    private BookingServiceItemResponse toResponse(BookingServiceItem i) {
        return BookingServiceItemResponse.builder()
                .publicId(i.getPublicId())
                .serviceType(i.getServiceType())
                .title(i.getTitle())
                .description(i.getDescription())
                .serviceDate(i.getServiceDate())
                .endDate(i.getEndDate())
                .status(i.getStatus())
                .cost(i.getCost())
                .vendorCost(i.getVendorCost())
                .vendorPublicId(i.getVendorPublicId())
                .vendorName(i.getVendorNameSnapshot())
                .confirmationNumber(i.getConfirmationNumber())
                .notes(i.getNotes())
                .createdAt(i.getCreatedAt())
                .updatedAt(i.getUpdatedAt())
                .build();
    }
}