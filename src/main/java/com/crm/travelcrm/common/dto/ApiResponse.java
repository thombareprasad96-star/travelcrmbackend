// com/crm/travelcrm/common/dto/ApiResponse.java
package com.crm.travelcrm.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private boolean       success;
    private String        message;
    private T             data;
    private Object        errors;
    private int           statusCode;

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    // ── Success (no data) ────────────────────────────────────────────────────
    public static <T> ApiResponse<T> success(String message) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .statusCode(200)
                .build();
    }

    // ── Success (with data) ──────────────────────────────────────────────────
    public static <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .statusCode(200)
                .build();
    }

    // ── Success (with data + custom status code) ─────────────────────────────
    public static <T> ApiResponse<T> success(String message, T data, int statusCode) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .statusCode(statusCode)
                .build();
    }

    // ── Failure (message only) ───────────────────────────────────────────────
    public static <T> ApiResponse<T> failure(String message) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .statusCode(500)
                .build();
    }

    // ── Failure (with errors payload) ────────────────────────────────────────
    public static <T> ApiResponse<T> failure(String message, Object errors) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .errors(errors)
                .statusCode(500)
                .build();
    }

    // ── Failure (with errors + custom status code) ───────────────────────────
    public static <T> ApiResponse<T> failure(String message, Object errors, int statusCode) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .errors(errors)
                .statusCode(statusCode)
                .build();
    }
}