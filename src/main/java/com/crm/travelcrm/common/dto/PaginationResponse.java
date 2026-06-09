package com.crm.travelcrm.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaginationResponse<T> {

    private List<T> content;
    private int      currentPage;
    private int      pageSize;
    private long     totalElements;
    private int      totalPages;
    private boolean  last;

    /**
     * Converts a raw Spring Data Page<T> directly.
     * Use when the entity type == the DTO type (already mapped).
     */
    public static <T> PaginationResponse<T> from(Page<T> page) {
        return PaginationResponse.<T>builder()
                .content(page.getContent())
                .currentPage(page.getNumber())
                .pageSize(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .last(page.isLast())
                .build();
    }

    /**
     * Converts Page<E> (entity) → PaginationResponse<D> (DTO)
     * using a provided mapper function.
     * Use this in ServiceImpl to avoid mapping in controller.
     *
     * Example:
     *   PaginationResponse.from(leadPage, leadMapper::toResponse)
     */
    public static <E, D> PaginationResponse<D> from(Page<E> page,
                                                    java.util.function.Function<E, D> mapper) {
        List<D> mapped = page.getContent()
                .stream()
                .map(mapper)
                .toList();                    // Java 21 immutable list
        return PaginationResponse.<D>builder()
                .content(mapped)
                .currentPage(page.getNumber())
                .pageSize(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .last(page.isLast())
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApiResponse<T> {

        private boolean success;
        private String message;
        private T data;

        @Builder.Default
        private LocalDateTime timestamp = LocalDateTime.now();

        public static <T> ApiResponse<T> success(String message, T data) {
            return ApiResponse.<T>builder()
                    .success(true)
                    .message(message)
                    .data(data)
                    .build();
        }

        public static <T> ApiResponse<T> error(String message) {
            return ApiResponse.<T>builder()
                    .success(false)
                    .message(message)
                    .build();
        }
    }
}