package com.crm.travelcrm.notification.web.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class ReminderRequestDTO {

    @NotBlank
    @Size(max = 255)
    private String title;

    @Size(max = 2000)
    private String notes;

    /** UTC ISO-8601, must be in the future. */
    @NotNull
    @Future
    private Instant remindAt;

    /** Optional back-reference, e.g. "LEAD". */
    @Size(max = 50)
    private String referenceType;

    /** Public UUID of the referenced entity. */
    private UUID referencePublicId;
}