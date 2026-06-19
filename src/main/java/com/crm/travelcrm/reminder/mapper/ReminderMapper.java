package com.crm.travelcrm.reminder.mapper;

import com.crm.travelcrm.reminder.dto.CreateReminderRequestDto;
import com.crm.travelcrm.reminder.dto.ReminderResponseDto;
import com.crm.travelcrm.reminder.dto.UpdateReminderRequestDto;
import com.crm.travelcrm.reminder.entity.Reminder;
import org.mapstruct.*;

/**
 * MapStruct mapper for {@link Reminder}. Tenant/owner/notified/logs are managed by the
 * service and never auto-mapped from the request body.
 */
@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface ReminderMapper {

    ReminderResponseDto toDto(Reminder reminder);

    Reminder toEntity(CreateReminderRequestDto request);

    /** Applies only the non-null fields of {@code request} onto {@code reminder}. */
    void updateEntity(UpdateReminderRequestDto request, @MappingTarget Reminder reminder);
}