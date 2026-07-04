package com.crm.travelcrm.settings.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TestWhatsAppResponse {
    private boolean success;
    private String  message;
    private String  error;      // provider error string, or null on success
    private String  testedAt;
}