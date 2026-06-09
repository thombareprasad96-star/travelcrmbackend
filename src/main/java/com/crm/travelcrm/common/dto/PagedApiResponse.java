package com.crm.travelcrm.common.dto;


import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"success", "message", "data", "pagination", "timestamp"})

public class PagedApiResponse<T> {

    private final boolean        success;
    private final String         message;
    private final List<T>        data;           // flat list, not nested
    private final PaginationMeta pagination;     // separate block

    @Builder.Default
    private final LocalDateTime  timestamp = LocalDateTime.now();

    public static <T> PagedApiResponse<T> of(String message,
                                             List<T> data,
                                             PaginationMeta pagination) {
        return PagedApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .pagination(pagination)
                .build();
    }
}
