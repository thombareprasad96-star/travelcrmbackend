package com.crm.travelcrm.master.testimonial;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;

/**
 * Client testimonial master. A flat, tenant-scoped leaf entity — {@code destination} is a
 * free-text trip label ("Bali Honeymoon Package"), NOT a FK to the Destination master, because
 * the frontend captures whatever the client wrote rather than a geography row.
 */
@Entity
@Table(
        name = "testimonials",
        indexes = {
                @Index(name = "idx_testimonial_tenant", columnList = "tenant_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
// Hide trashed rows from every read (see softDeleteFilter on BaseTenantEntity).
@Filter(name = "softDeleteFilter", condition = "deleted_at is null")
public class Testimonial extends BaseTenantEntity {

    @Column(name = "client_name", nullable = false, length = 200)
    private String clientName;

    @Column(length = 255)
    private String destination;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Cloudinary URL of the client's photo; null renders as initials on the frontend. */
    @Column(name = "image_path", length = 500)
    private String imagePath;

    /** Inactive testimonials are retained but excluded from public surfaces. */
    @Column(nullable = false)
    private boolean active = true;

    /** An active testimonial can still be hidden from the public site. */
    @Column(nullable = false)
    private boolean visible = true;
}