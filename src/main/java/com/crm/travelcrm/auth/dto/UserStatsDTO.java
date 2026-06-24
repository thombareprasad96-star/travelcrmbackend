package com.crm.travelcrm.auth.dto;

import lombok.Builder;
import lombok.Value;

// Aggregate counts for the Users page stat cards. Scoped to the caller's tenant,
// excludes soft-deleted users. "managers" is the count of the elevated MANAGER role.
@Value
@Builder
public class UserStatsDTO {
    long total;
    long active;
    long inactive;
    long managers;
}