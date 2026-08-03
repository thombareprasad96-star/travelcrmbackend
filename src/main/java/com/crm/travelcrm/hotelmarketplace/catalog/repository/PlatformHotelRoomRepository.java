package com.crm.travelcrm.hotelmarketplace.catalog.repository;

import com.crm.travelcrm.hotelmarketplace.catalog.entity.PlatformHotelRoom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Rooms of a catalog hotel. Always reached through the parent — never by bare id. */
public interface PlatformHotelRoomRepository extends JpaRepository<PlatformHotelRoom, Long> {

    List<PlatformHotelRoom> findByHotelIdAndDeletedAtIsNullOrderByNameAsc(long hotelId);

    List<PlatformHotelRoom> findByHotelIdAndActiveTrueAndDeletedAtIsNullOrderByNameAsc(long hotelId);

    /**
     * Scoped by parent as well as publicId: a room publicId that belongs to a different hotel is a
     * 404, not someone else's room silently edited under this hotel's audit trail.
     */
    Optional<PlatformHotelRoom> findByPublicIdAndHotelIdAndDeletedAtIsNull(UUID publicId, long hotelId);
}
