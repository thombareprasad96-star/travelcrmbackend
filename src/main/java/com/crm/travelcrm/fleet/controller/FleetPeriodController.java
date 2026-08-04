package com.crm.travelcrm.fleet.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.fleet.dto.FleetPeriodDto;
import com.crm.travelcrm.fleet.service.FleetPeriodService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fleet accounting periods.
 *
 * <p>Closing a month is what {@code FLEET_PERIOD_CLOSE} authorises — the permission shipped before
 * this endpoint did, which meant it authorised nothing at all and every period stayed open forever.
 *
 * <p>Reading the list only needs {@code FLEET_MONEY_READ}: a manager should be able to see which
 * months are still open, and how much unsquared driver cash is standing between them and a close,
 * without holding the authority to lock anything.
 */
@RestController
@RequestMapping("/api/fleet/periods")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('FLEET_MONEY_READ')")
public class FleetPeriodController {

    private final FleetPeriodService periodService;

    /** All twelve months of a financial year. Defaults to the current one. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<FleetPeriodDto>>> list(
            @RequestParam(required = false) Integer financialYear) {
        return ResponseEntity.ok(ApiResponse.success("Periods fetched",
                periodService.forFinancialYear(financialYear)));
    }

    @PostMapping("/close")
    @PreAuthorize("hasAuthority('FLEET_PERIOD_CLOSE')")
    public ResponseEntity<ApiResponse<FleetPeriodDto>> close(@RequestBody Map<String, Integer> body) {
        return ResponseEntity.ok(ApiResponse.success("Period closed",
                periodService.close(body.get("financialYear"), body.get("month"))));
    }

    /** Lifting a close is recorded with a reason — the month may already have been reported on. */
    @PostMapping("/{publicId}/reopen")
    @PreAuthorize("hasAuthority('FLEET_PERIOD_CLOSE')")
    public ResponseEntity<ApiResponse<FleetPeriodDto>> reopen(
            @PathVariable UUID publicId, @RequestBody(required = false) Map<String, String> body) {
        return ResponseEntity.ok(ApiResponse.success("Period reopened",
                periodService.reopen(publicId, body == null ? null : body.get("reason"))));
    }
}
