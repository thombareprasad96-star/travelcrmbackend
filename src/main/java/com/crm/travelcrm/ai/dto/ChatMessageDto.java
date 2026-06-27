package com.crm.travelcrm.ai.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class ChatMessageDto {
    private UUID publicId;
    private String role;
    private String content;
    private String toolName;
    private LocalDateTime createdAt;
}