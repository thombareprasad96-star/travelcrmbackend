package com.crm.travelcrm.master.testimonial;

import org.mapstruct.*;

@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface TestimonialMapper {

    @Mapping(target = "testimonialId", source = "id")
    TestimonialDto toDto(Testimonial testimonial);

    Testimonial toEntity(CreateTestimonialRequest request);

    void updateEntity(UpdateTestimonialRequest request, @MappingTarget Testimonial testimonial);
}