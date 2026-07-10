package com.crm.travelcrm.master.testimonial;

import lombok.Data;

@Data
public class UpdateTestimonialRequest {

    private String clientName;
    private String destination;
    private String description;
    private String imagePath;

    // Boxed so an omitted flag leaves the stored value alone (see NullValuePropertyMappingStrategy.IGNORE).
    private Boolean active;
    private Boolean visible;
}