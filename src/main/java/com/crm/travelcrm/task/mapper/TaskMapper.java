package com.crm.travelcrm.task.mapper;

import com.crm.travelcrm.task.dto.CreateTaskRequest;
import com.crm.travelcrm.task.dto.TaskResponse;
import com.crm.travelcrm.task.dto.UpdateTaskRequest;
import com.crm.travelcrm.task.entity.Task;
import org.mapstruct.*;

/**
 * MapStruct mapper for {@link Task}. Assignee / lead references, owner, {@code allDay},
 * {@code completedAt} and {@code logs} are all service-managed and deliberately NOT auto-mapped
 * from the request body (cross-aggregate FKs are resolved + validated in the service; see
 * {@code TaskServiceImpl}). {@code overdue} on the response is derived from {@code Task.isOverdue()}.
 */
@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface TaskMapper {

    // The grid columns are stored under storage-oriented names and exposed under display-oriented
    // ones, so these two need an explicit source. Everything else matches by name.
    @Mapping(target = "customerName",  source = "customerNameSnapshot")
    @Mapping(target = "createdByName", source = "ownerName")
    TaskResponse toDto(Task task);

    /**
     * Slim variant for list / board / My-Tasks responses: omits {@code logs} so the LAZY
     * {@code task_logs} @ElementCollection is never initialized for every row (the activity log is
     * only needed on the detail fetch, {@link #toDto}).
     */
    @Mapping(target = "customerName",  source = "customerNameSnapshot")
    @Mapping(target = "createdByName", source = "ownerName")
    @Mapping(target = "logs", ignore = true)
    TaskResponse toListDto(Task task);

    @Mapping(target = "assignToUserId",   ignore = true)
    @Mapping(target = "assignToPublicId", ignore = true)
    @Mapping(target = "assignToName",     ignore = true)
    @Mapping(target = "ownerUserId",      ignore = true)
    @Mapping(target = "leadRefId",        ignore = true)
    @Mapping(target = "leadPublicId",     ignore = true)
    @Mapping(target = "leadName",         ignore = true)
    @Mapping(target = "completedAt",      ignore = true)
    @Mapping(target = "allDay",           ignore = true)
    @Mapping(target = "logs",             ignore = true)
    // Booking link + its display snapshots: bookingPublicId matches the request field by name and
    // type, so without this it would be copied straight from the body — bypassing the tenant check
    // in applyReferences and letting a caller stamp any UUID onto the row.
    @Mapping(target = "bookingRefId",           ignore = true)
    @Mapping(target = "bookingPublicId",        ignore = true)
    @Mapping(target = "bookingCode",            ignore = true)
    @Mapping(target = "customerNameSnapshot",   ignore = true)
    @Mapping(target = "tripSource",             ignore = true)
    @Mapping(target = "ownerName",              ignore = true)
    @Mapping(target = "overdueNotifiedAt",      ignore = true)
    Task toEntity(CreateTaskRequest request);

    /** Applies only the non-null fields of {@code request} onto {@code task} (partial patch). */
    @Mapping(target = "assignToUserId",   ignore = true)
    @Mapping(target = "assignToPublicId", ignore = true)
    @Mapping(target = "assignToName",     ignore = true)
    @Mapping(target = "ownerUserId",      ignore = true)
    @Mapping(target = "leadRefId",        ignore = true)
    @Mapping(target = "leadPublicId",     ignore = true)
    @Mapping(target = "leadName",         ignore = true)
    @Mapping(target = "completedAt",      ignore = true)
    @Mapping(target = "allDay",           ignore = true)
    @Mapping(target = "logs",             ignore = true)
    @Mapping(target = "bookingRefId",           ignore = true)
    @Mapping(target = "bookingPublicId",        ignore = true)
    @Mapping(target = "bookingCode",            ignore = true)
    @Mapping(target = "customerNameSnapshot",   ignore = true)
    @Mapping(target = "tripSource",             ignore = true)
    @Mapping(target = "ownerName",              ignore = true)
    @Mapping(target = "overdueNotifiedAt",      ignore = true)
    void updateEntity(UpdateTaskRequest request, @MappingTarget Task task);
}