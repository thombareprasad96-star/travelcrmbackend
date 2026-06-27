package com.crm.travelcrm.report.activity.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** Full single-log payload for the Details modal — same fields as the row plus {@code userAgent}. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityLogDetailDTO {
    private UUID   publicId;
    private String date;
    private String time;
    private String user;
    private String username;
    private String type;
    private String action;
    private String description;
    private String ip;
    private String userAgent;
}