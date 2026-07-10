package com.crm.travelcrm.master.testimonial;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateTestimonialRequest {

    @NotBlank
    private String clientName;

    @NotBlank
    private String destination;

    @NotBlank
    private String description;

    /** Cloudinary URL returned by POST /api/testimonials/upload-image. */
    private String imagePath;

    private boolean active = true;
    private boolean visible = true;
}