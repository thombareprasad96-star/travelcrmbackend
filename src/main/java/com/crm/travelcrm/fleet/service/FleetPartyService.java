package com.crm.travelcrm.fleet.service;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.fleet.dto.FleetPartyRequestDto;
import com.crm.travelcrm.fleet.dto.FleetPartyResponseDto;

import java.util.UUID;

/**
 * The fleet's own directory of hired-vehicle owners, suppliers and garages.
 *
 * <p>Exists because a Fleet-only deployment has no Vendor master, and attached vehicles are the
 * majority of most Indian fleets. In CRM mode the directory shown on the vehicle form is the Vendor
 * master instead — {@code CrmVendorPartyAdapter} wins the {@code FleetPartyPort} binding — so these
 * endpoints simply go unused there rather than duplicating anything.
 */
public interface FleetPartyService {

    FleetPartyResponseDto create(FleetPartyRequestDto request);

    FleetPartyResponseDto update(UUID publicId, FleetPartyRequestDto request);

    FleetPartyResponseDto getByPublicId(UUID publicId);

    PagedApiResponse<FleetPartyResponseDto> list(String search, Boolean active, int page, int size);

    /**
     * Soft-delete. Refused while any vehicle still names this party: the vehicle snapshots the
     * owner's name, and removing the row underneath it leaves a name pointing at nothing and a
     * payout statement that can no longer be grouped.
     */
    void delete(UUID publicId);
}
