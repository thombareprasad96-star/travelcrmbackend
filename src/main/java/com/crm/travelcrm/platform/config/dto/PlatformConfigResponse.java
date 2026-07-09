package com.crm.travelcrm.platform.config.dto;

import java.util.UUID;

/** A single platform setting for the console editor. */
public record PlatformConfigResponse(UUID publicId, String key, String value, String description) {
}