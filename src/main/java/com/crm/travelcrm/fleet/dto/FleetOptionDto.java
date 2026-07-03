package com.crm.travelcrm.fleet.dto;

import java.util.UUID;

/**
 * Slim dropdown option for fleet forms. Unlike the master {@code DropdownDto} (whose
 * {@code value} is an internal Long id), fleet options are keyed by {@code publicId}.
 */
public record FleetOptionDto(UUID publicId, String label, String status) {
}