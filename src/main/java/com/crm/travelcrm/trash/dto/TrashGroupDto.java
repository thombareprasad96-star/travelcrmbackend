package com.crm.travelcrm.trash.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Trashed records for one module, so the Trash sidebar can render grouped sections
 * each with its own count.
 */
@Data
@Builder
public class TrashGroupDto {

    private String entityType;
    private String module;
    private int count;
    private List<TrashItemDto> items;
}