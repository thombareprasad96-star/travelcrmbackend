package com.crm.travelcrm.ai.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class ChatSessionDto {
    private UUID publicId;
    private String title;
    private LocalDateTime lastMessageAt;
    private LocalDateTime createdAt;
}