package com.crm.travelcrm.otp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class OtpResponseDTO {
    private String message;
    private String otp;
}