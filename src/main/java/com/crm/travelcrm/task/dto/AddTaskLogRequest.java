package com.crm.travelcrm.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** Append one free-form activity-log line to a task. */
@Getter
@Setter
public class AddTaskLogRequest {

    @NotBlank(message = "Log text is required")
    @Size(max = 2000, message = "Log text must be at most 2000 characters")
    private String log;
}