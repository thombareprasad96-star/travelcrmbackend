package com.crm.travelcrm.fleet.service;

import com.crm.travelcrm.fleet.dto.FleetPeriodDto;

import java.util.List;
import java.util.UUID;

public interface FleetPeriodService {

    /** All twelve months of an Indian FY (April → March), closed or not. Defaults to the current FY. */
    List<FleetPeriodDto> forFinancialYear(Integer financialYear);

    /**
     * Lock a month. Refused while the month is still running, or while any driver settlement in it
     * is unsquared — locking then would make the cash return that squares it impossible to record.
     */
    FleetPeriodDto close(int financialYear, int month);

    /** Lift a close, with a recorded reason. The row is kept, never deleted. */
    FleetPeriodDto reopen(UUID publicId, String reason);
}
