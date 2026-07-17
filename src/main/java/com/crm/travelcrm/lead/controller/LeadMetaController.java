package com.crm.travelcrm.lead.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.lead.dto.LeadSourceOptionDTO;
import com.crm.travelcrm.lead.enums.LeadSource;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * Reference data for the lead forms — the enum catalogs the frontend must not hardcode.
 *
 * <p>Separate from {@link LeadController} because this serves <em>static</em> vocabulary, not tenant
 * data: nothing here touches the database, and the authority is deliberately wider than
 * {@code LEAD_READ} (a user who may create a lead but not browse the list still needs the dropdown).
 *
 * <p>Mapped under {@code /api/leads/meta} — two path segments, so it cannot collide with
 * {@code LeadController}'s {@code GET /api/leads/{publicId}}.
 */
@RestController
@RequestMapping("/api/leads/meta")
@RequiredArgsConstructor
public class LeadMetaController {

    /**
     * Every {@link LeadSource}, derived from {@code values()} — including MACHINE_ONLY and
     * LEGACY_READ_ONLY ones.
     *
     * <p><b>The full list is served on purpose.</b> The frontend needs the non-selectable entries to
     * <em>label</em> a lead that already carries one (a JustDial lead must read "JustDial", not a
     * blank select); it filters to {@code selectability === 'MANUAL_SELECTABLE'} for what a human may
     * pick. Filtering here instead would make an existing lead's source unrenderable — the same class
     * of bug as the hardcoded list, moved to the server.
     */
    @GetMapping("/sources")
    @PreAuthorize("hasAnyAuthority('LEAD_READ','LEAD_CREATE','LEAD_UPDATE')")
    public ResponseEntity<ApiResponse<List<LeadSourceOptionDTO>>> getLeadSources() {
        List<LeadSourceOptionDTO> options = Arrays.stream(LeadSource.values())
                .map(s -> new LeadSourceOptionDTO(
                        s.getDisplayName(),                 // value — the wire vocabulary (@JsonValue)
                        s.getDisplayName(),                 // label — identical by design, not a typo
                        s.name(),                           // code  — stable FE key, never an <option value>
                        s.getSelectability().name()))
                .toList();

        return ResponseEntity.ok(ApiResponse.success("Lead sources fetched", options));
    }
}
