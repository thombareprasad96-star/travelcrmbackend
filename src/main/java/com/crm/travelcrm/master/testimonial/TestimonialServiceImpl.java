package com.crm.travelcrm.master.testimonial;

import com.crm.travelcrm.common.cloudinary.CloudinaryService;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.master.geography.support.GeographySupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@Slf4j
@RequiredArgsConstructor
public class TestimonialServiceImpl implements TestimonialService {

    private final TestimonialRepository testimonialRepository;
    private final TestimonialMapper testimonialMapper;
    private final CloudinaryService cloudinaryService;

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<TestimonialDto> getAll(int page, int size, String sortBy, String sortDir) {
        Long tenantId = GeographySupport.currentTenantId();
        Page<Testimonial> result = testimonialRepository.findByTenantId(
                tenantId, PageRequest.of(page, size, GeographySupport.buildSort(sortBy, sortDir)));
        return PagedApiResponse.of("Testimonials fetched",
                result.map(testimonialMapper::toDto).getContent(),
                PaginationMeta.from(result, sortBy, sortDir));
    }

    @Override
    @Transactional(readOnly = true)
    public TestimonialDto getById(Long id) {
        return testimonialMapper.toDto(findOrThrow(id));
    }

    @Override
    @Transactional
    public TestimonialDto create(CreateTestimonialRequest request) {
        Long tenantId = GeographySupport.currentTenantId();
        Testimonial testimonial = testimonialMapper.toEntity(request);
        testimonial.setTenantId(tenantId);
        Testimonial saved = testimonialRepository.save(testimonial);
        log.info("Testimonial created | id: {} | tenantId: {}", saved.getId(), tenantId);
        return testimonialMapper.toDto(saved);
    }

    @Override
    @Transactional
    public TestimonialDto update(Long id, UpdateTestimonialRequest request) {
        Testimonial testimonial = findOrThrow(id);
        if (request.getActive() != null) {
            testimonial.setActive(request.getActive());
        }
        if (request.getVisible() != null) {
            testimonial.setVisible(request.getVisible());
        }
        testimonialMapper.updateEntity(request, testimonial);
        return testimonialMapper.toDto(testimonialRepository.save(testimonial));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Testimonial testimonial = findOrThrow(id);
        // Leaf master — nothing references it by FK. Recoverable from Trash for 30 days.
        testimonial.softDelete(GeographySupport.currentUsername());
        testimonialRepository.save(testimonial);
        log.info("Testimonial moved to Trash | id: {}", id);
    }

    @Override
    public String uploadImage(MultipartFile file) {
        return cloudinaryService.uploadImage(file, "testimonials");
    }

    private Testimonial findOrThrow(Long id) {
        Long tenantId = GeographySupport.currentTenantId();
        return testimonialRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Testimonial not found: " + id));
    }
}