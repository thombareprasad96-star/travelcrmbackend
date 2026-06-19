package com.crm.travelcrm.reminder.service;

import com.crm.travelcrm.reminder.dto.CreateReminderRequestDto;
import com.crm.travelcrm.reminder.dto.ReminderResponseDto;
import com.crm.travelcrm.reminder.dto.ReminderStatsDto;
import com.crm.travelcrm.reminder.dto.UpdateReminderRequestDto;

import java.time.Instant;
import java.util.List;

public interface ReminderService {

    ReminderResponseDto create(CreateReminderRequestDto request);

    /** Raw list (frontend reads {@code res.data} as an array). Filters are optional. */
    List<ReminderResponseDto> getAll(String status, String priority, String type);

    ReminderResponseDto getById(Long id);

    ReminderResponseDto update(Long id, UpdateReminderRequestDto request);

    void delete(Long id);

    ReminderResponseDto markComplete(Long id);

    ReminderResponseDto dismiss(Long id);

    ReminderResponseDto snooze(Long id, Instant snoozedUntil);

    ReminderResponseDto addLog(Long id, String log);

    int completeAllOverdue();

    List<ReminderResponseDto> getOverdue();

    List<ReminderResponseDto> getDueToday();

    List<ReminderResponseDto> getByLeadName(String leadName);

    ReminderStatsDto getStats();

    byte[] exportCsv();
}