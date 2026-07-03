package com.crm.travelcrm.fleet.repository;

import com.crm.travelcrm.booking.entity.Booking;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Fleet-local, read-only Booking lookup for linking trips to bookings. A second Spring Data
 * repository on the Booking entity — keeps the fleet module decoupled from the booking
 * module's own repository API and guarantees the tenant-scoped finder it needs exists.
 */
public interface FleetBookingLookupRepository extends Repository<Booking, Long> {

    Optional<Booking> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);
}