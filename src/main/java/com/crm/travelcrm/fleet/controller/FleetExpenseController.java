package com.crm.travelcrm.fleet.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.fleet.dto.FleetExpenseRequestDto;
import com.crm.travelcrm.fleet.dto.FleetExpenseResponseDto;
import com.crm.travelcrm.fleet.dto.FleetExpenseTypeDto;
import com.crm.travelcrm.fleet.service.FleetExpenseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The fleet cost ledger.
 *
 * <p><b>On the permissions.</b> Recording a cost is an operational act, not a financial authority —
 * a dispatcher enters forty rows a day off a bundle of receipts — so create/update ride
 * {@code FLEET_CREATE}/{@code FLEET_UPDATE} like the rest of the diary. Only <em>seeing</em> the
 * money (amounts, cost structure, who owes what) needs {@code FLEET_MONEY_READ}, and only correcting
 * a frozen row needs {@code FLEET_MONEY_SETTLE}.
 *
 * <p>Deliberately NO per-expense approve/reject endpoints. Approval happens once, on the trip
 * settlement — see {@code FleetSettlementStatus}.
 */
@RestController
@RequestMapping("/api/fleet")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('FLEET_MONEY_READ')")   // class default; mutating methods override below
public class FleetExpenseController {

    private final FleetExpenseService expenseService;

    /**
     * The category catalogue and its per-type form metadata. Gated on plain {@code FLEET_READ}: it
     * carries no money, and the entry form needs it before the user has typed anything.
     */
    @GetMapping("/expense-types")
    @PreAuthorize("hasAuthority('FLEET_READ')")
    public ResponseEntity<ApiResponse<List<FleetExpenseTypeDto>>> expenseTypes() {
        return ResponseEntity.ok(
                ApiResponse.success("Expense types fetched successfully", expenseService.expenseTypes()));
    }

    @PostMapping("/expenses")
    @PreAuthorize("hasAuthority('FLEET_CREATE')")
    public ResponseEntity<ApiResponse<FleetExpenseResponseDto>> create(
            @Valid @RequestBody FleetExpenseRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Expense recorded successfully",
                        expenseService.create(request), 201));
    }

    @GetMapping("/expenses")
    public ResponseEntity<PagedApiResponse<FleetExpenseResponseDto>> list(
            @RequestParam(required = false) UUID vehicleId,
            @RequestParam(required = false) UUID tripId,
            @RequestParam(required = false) UUID driverId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String paidBy,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Boolean missingReceipt,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(expenseService.list(vehicleId, tripId, driverId, type, paidBy,
                fromDate, toDate, missingReceipt, search, page, size));
    }

    @GetMapping("/expenses/{publicId}")
    public ResponseEntity<ApiResponse<FleetExpenseResponseDto>> get(@PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResponse.success("Expense fetched successfully",
                expenseService.getByPublicId(publicId)));
    }

    @PutMapping("/expenses/{publicId}")
    @PreAuthorize("hasAuthority('FLEET_UPDATE')")
    public ResponseEntity<ApiResponse<FleetExpenseResponseDto>> update(
            @PathVariable UUID publicId, @Valid @RequestBody FleetExpenseRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success("Expense updated successfully",
                expenseService.update(publicId, request)));
    }

    /**
     * Correct a row that can no longer be edited. Needs {@code FLEET_MONEY_SETTLE}: by definition the
     * row is settled or its period is closed, so this restates a figure someone has already signed.
     */
    @PostMapping("/expenses/{publicId}/reverse")
    @PreAuthorize("hasAuthority('FLEET_MONEY_SETTLE')")
    public ResponseEntity<ApiResponse<FleetExpenseResponseDto>> reverse(
            @PathVariable UUID publicId, @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        return ResponseEntity.ok(ApiResponse.success("Expense reversed",
                expenseService.reverse(publicId, reason)));
    }

    @DeleteMapping("/expenses/{publicId}")
    @PreAuthorize("hasAuthority('FLEET_DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID publicId) {
        expenseService.delete(publicId);
        return ResponseEntity.ok(ApiResponse.success("Expense moved to Trash"));
    }
}
