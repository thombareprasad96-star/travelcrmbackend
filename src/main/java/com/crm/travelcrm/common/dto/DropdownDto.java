package com.crm.travelcrm.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Generic value/label pair used by every cascading dropdown in the UI.
 *
 * {@code value} — the database PK sent back on form submit.
 * {@code label} — the human-readable name rendered in the select element.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DropdownDto {
    private Long   value;
    private String label;
}