package com.crm.travelcrm.booking.dto.response;

import java.util.UUID;

/**
 * One selectable option in the booking "Assigned To" dropdown.
 *
 * <p>Exposes the publicId only — never the internal Long id. Deliberately carries NO workload
 * number: booking assignment is relationship-based (who owns this customer), not load-balanced the
 * way inbound lead assignment is, so a load figure here would suggest a decision rule that does not
 * exist.
 *
 * @param id   the user's publicId — what {@code assignedUserId} on the create/convert request takes
 * @param name display name
 * @param email shown as the disambiguator when two staff share a name
 * @param role  the user's role, for the dropdown's secondary line
 */
public record BookingAssigneeDto(UUID id, String name, String email, String role) {
}
