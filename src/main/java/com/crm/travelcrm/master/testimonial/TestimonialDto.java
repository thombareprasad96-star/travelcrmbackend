package com.crm.travelcrm.master.testimonial;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.UUID;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TestimonialDto {

    Long testimonialId;
    UUID publicId;

    String clientName;
    String destination;
    String description;
    String imagePath;
    boolean active;
    boolean visible;

    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}