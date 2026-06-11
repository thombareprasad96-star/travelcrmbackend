package com.crm.travelcrm.notification.web.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
public class SnoozeRequestDTO {

    /** UTC ISO-8601 timestamp; must be in the future. */
    @NotNull
    @Future
    private Instant snoozedUntil;
}