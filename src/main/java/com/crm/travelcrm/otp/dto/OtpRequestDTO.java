package com.crm.travelcrm.otp.dto;


import com.crm.travelcrm.auth.enums.OtpChannel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class OtpRequestDTO {
    private String destination; // email or phone
    private OtpChannel channel;
}