package com.crm.travelcrm.notificationsetting.controller;

import com.crm.travelcrm.notificationsetting.dto.NotificationStageDto;
import com.crm.travelcrm.notificationsetting.service.NotificationSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Org-wide automatic-reminder settings. Returns the raw stage list (no ApiResponse
 * envelope) to match the reminders feature area / notificationSettingsService.js.
 * Reads are open to any tenant user; saving is tenant-admin only (USER_UPDATE).
 */
@RestController
@RequestMapping("/api/notification-settings")
@RequiredArgsConstructor
public class NotificationSettingController {

    private final NotificationSettingService service;

    @GetMapping
    public ResponseEntity<List<NotificationStageDto>> get() {
        return ResponseEntity.ok(service.get());
    }

    // Body is the raw stage array (matches notificationSettingsService.updateAll).
    @PutMapping
    @PreAuthorize("hasAuthority('USER_UPDATE')")
    public ResponseEntity<List<NotificationStageDto>> update(
            @RequestBody List<NotificationStageDto> stages) {
        return ResponseEntity.ok(service.save(stages));
    }
}