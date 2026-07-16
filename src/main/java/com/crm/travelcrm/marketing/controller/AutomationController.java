package com.crm.travelcrm.marketing.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.marketing.dto.AutomationResponse;
import com.crm.travelcrm.marketing.dto.SendTestRequest;
import com.crm.travelcrm.marketing.dto.UpcomingCelebrationResponse;
import com.crm.travelcrm.marketing.dto.UpdateAutomationRequest;
import com.crm.travelcrm.marketing.enums.AutomationType;
import com.crm.travelcrm.marketing.service.AutomationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Birthday / anniversary auto-triggers — exactly one config row per {@link AutomationType},
 * auto-provisioned (disabled) on first read. The {@code {triggerType}} path value is
 * {@code birthday} | {@code anniversary} (case-insensitive). Firing is done daily by
 * {@code AutomationScheduler}. Class default {@code MARKETING_READ}; update needs UPDATE, test needs SEND.
 */
@RestController
@RequestMapping("/api/marketing/automations")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('MARKETING_READ')")   // class default; mutating methods override below
public class AutomationController {

    private final AutomationService automationService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<AutomationResponse>>> list() {
        return ResponseEntity.ok(automationService.list());
    }

    /** Literal path — declared before /{triggerType}. */
    @GetMapping("/upcoming")
    public ResponseEntity<ApiResponse<List<UpcomingCelebrationResponse>>> upcoming(
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(automationService.upcoming(days));
    }

    @GetMapping("/{triggerType}")
    public ResponseEntity<ApiResponse<AutomationResponse>> get(@PathVariable String triggerType) {
        return ResponseEntity.ok(automationService.get(parse(triggerType)));
    }

    @PutMapping("/{triggerType}")
    @PreAuthorize("hasAuthority('MARKETING_UPDATE')")
    public ResponseEntity<ApiResponse<AutomationResponse>> update(
            @PathVariable String triggerType, @Valid @RequestBody UpdateAutomationRequest request) {
        return ResponseEntity.ok(automationService.update(parse(triggerType), request));
    }

    @PostMapping("/{triggerType}/test")
    @PreAuthorize("hasAuthority('MARKETING_SEND')")
    public ResponseEntity<ApiResponse<Void>> test(
            @PathVariable String triggerType, @Valid @RequestBody SendTestRequest request) {
        return ResponseEntity.ok(automationService.test(parse(triggerType), request));
    }

    /** Case-insensitive {@code birthday|anniversary} → enum; unknown value is a 404. */
    private AutomationType parse(String triggerType) {
        try {
            return AutomationType.valueOf(triggerType.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResourceNotFoundException("Unknown automation trigger type: " + triggerType);
        }
    }
}