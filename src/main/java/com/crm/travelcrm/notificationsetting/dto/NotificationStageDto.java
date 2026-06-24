package com.crm.travelcrm.notificationsetting.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// One lead-stage automation rule. reminderType/priority are free-form config strings
// (the page's own vocabulary), not validated against the reminder ReminderType enum.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationStageDto {
    private String  key;
    private boolean enabled;
    private String  reminderType;
    private Double  hours;
    private String  priority;
    private String  titleTemplate;
    private String  descTemplate;
}