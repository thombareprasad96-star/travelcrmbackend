package com.crm.travelcrm.hotelmarketplace.voucher.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * A voucher PDF the hotel supplied, held as bytes.
 *
 * <h3>Why a side table</h3>
 * {@code PlatformHotelBooking} is read on every SuperAdmin queue page, every tenant list and every
 * scheduler sweep. A {@code bytea} column on it would drag a multi-megabyte PDF through all of them,
 * and Hibernate's lazy-basic fetch does not reliably avoid that without bytecode enhancement. One
 * row per booking, loaded only when somebody actually downloads, is the version that stays cheap.
 *
 * <h3>Why Postgres and not Cloudinary</h3>
 * The document names the guest, their stay dates and their confirmation number. Cloudinary URLs are
 * public and unauthenticated — the same reason traveler documents are {@code bytea} and booking
 * invoices are rendered on the fly. There is no signed-URL story here, so there is no CDN story.
 */
@Entity
@Table(
        name = "platform_hotel_voucher_files",
        indexes = @Index(name = "idx_phvf_booking", columnList = "hotel_booking_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class PlatformHotelVoucherFile extends BaseEntity {

    /** Logical FK to the booking. One current file per booking; a re-upload replaces it. */
    @Column(name = "hotel_booking_id", nullable = false)
    private Long hotelBookingId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    /**
     * The bytes.
     *
     * <p>{@code @Lob} is deliberately NOT used: on PostgreSQL it maps to the {@code oid} large-object
     * API, which stores a pointer into {@code pg_largeobject} and leaks the object when the row is
     * deleted. A plain {@code byte[]} against {@code bytea} keeps the data in the row, where the
     * ordinary delete and backup paths already handle it.
     */
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "content", nullable = false, columnDefinition = "bytea")
    private byte[] content;

    @Column(name = "uploaded_by_super_admin_id")
    private Long uploadedBySuperAdminId;
}
