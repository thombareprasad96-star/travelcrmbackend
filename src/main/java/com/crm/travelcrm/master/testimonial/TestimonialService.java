package com.crm.travelcrm.master.testimonial;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import org.springframework.web.multipart.MultipartFile;

public interface TestimonialService {

    PagedApiResponse<TestimonialDto> getAll(int page, int size, String sortBy, String sortDir);

    TestimonialDto getById(Long id);

    TestimonialDto create(CreateTestimonialRequest request);

    TestimonialDto update(Long id, UpdateTestimonialRequest request);

    void delete(Long id);

    String uploadImage(MultipartFile file);
}