package com.crm.travelcrm.ai.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateSessionRequest {

    /** Optional title; a default ("New chat") is applied when blank. */
    @Size(max = 200, message = "Title must be at most 200 characters")
    private String title;
}