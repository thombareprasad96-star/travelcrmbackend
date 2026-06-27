package com.crm.travelcrm.report.activity.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** One row of the Activity Reports table. External id is {@code publicId} (never the Long id). */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityLogDTO {
    private UUID   publicId;
    private String date;        // "Jun 22, 2026"
    private String time;        // "09:24:34"
    private String user;        // full name
    private String username;    // "@shreyash"
    private String type;        // Admin | Manager | Staff | Agent | Accountant | User
    private String action;      // Login | Logout | Create | Update | Delete | Settings | Export | View
    private String description;
    private String ip;
}