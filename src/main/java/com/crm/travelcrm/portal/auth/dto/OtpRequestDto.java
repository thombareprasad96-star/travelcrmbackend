package com.crm.travelcrm.portal.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Step 1 of portal login: the phone or email the traveler is registered with. */
@Data
public class OtpRequestDto {
    @NotBlank(message = "Phone or email is required")
    private String identifier;
}
