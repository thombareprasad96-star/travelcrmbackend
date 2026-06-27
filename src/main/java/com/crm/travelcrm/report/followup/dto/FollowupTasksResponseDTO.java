package com.crm.travelcrm.report.followup.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/** Paginated follow-up tasks — read as {@code res.data.tasks} / {@code res.data.total} by the FE. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FollowupTasksResponseDTO {
    private List<FollowupTaskDTO> tasks;
    private long total;
    private int  page;
    private int  perPage;
    private int  totalPages;
}