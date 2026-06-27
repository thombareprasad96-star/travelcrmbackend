package com.crm.travelcrm.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SendMessageRequest {

    @NotBlank(message = "message is required")
    @Size(max = 4000, message = "message must be at most 4000 characters")
    private String message;
}