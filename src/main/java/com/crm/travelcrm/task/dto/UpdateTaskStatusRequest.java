package com.crm.travelcrm.task.dto;

import com.crm.travelcrm.task.enums.TaskStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/** Move a task across the board (drag-drop between columns). */
@Getter
@Setter
public class UpdateTaskStatusRequest {

    @NotNull(message = "Status is required")
    private TaskStatus status;
}