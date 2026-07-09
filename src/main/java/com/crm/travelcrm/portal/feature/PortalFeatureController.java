package com.crm.travelcrm.portal.feature;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.portal.feature.dto.NotifyResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Traveler-facing "Coming Soon" interest API. Ownership is implicit — every call resolves the
 * authenticated traveler and scopes to their own customer. An unknown feature key → 404 (never a
 * dead 200), so the FE can trust the key list.
 */
@RestController
@RequestMapping("/api/portal/features")
@RequiredArgsConstructor
public class PortalFeatureController {

    private final PortalFeatureService service;

    @PostMapping("/{featureKey}/notify")
    public ResponseEntity<ApiResponse<NotifyResponse>> notify(@PathVariable String featureKey) {
        return ResponseEntity.ok(
                ApiResponse.success("Notify registered", service.register(parseKey(featureKey))));
    }

    @GetMapping("/interest")
    public ResponseEntity<ApiResponse<Map<String, Object>>> interest() {
        return ResponseEntity.ok(ApiResponse.success(
                "Feature interest fetched",
                Map.of("registeredKeys", service.myInterests())));
    }

    private PortalFeatureKey parseKey(String raw) {
        try {
            return PortalFeatureKey.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResourceNotFoundException("Unknown feature: " + raw);
        }
    }
}