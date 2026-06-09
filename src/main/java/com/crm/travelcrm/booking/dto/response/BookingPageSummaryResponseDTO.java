// ── BookingPageSummaryResponseDTO ─────────────────────────────────────────────
//
// Summary of financial aggregates for the currently visible page of bookings.
// Used for the "Tax & Profit Summary" panel in the UI.

package com.crm.travelcrm.booking.dto.response;

import lombok.*;
import java.math.BigDecimal;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingPageSummaryResponseDTO {

    // ── Safe for all roles ────────────────────────────────────────────────────
    private BigDecimal totalRevenue;      // was: pageRevenue
    private BigDecimal totalPending;      // was: pagePendingAmount
    private BigDecimal gstCollected;      // was: pageGST
    private BigDecimal tcsCollected;      // was: pageTCS

    // ── Sensitive — null for AGENT role ───────────────────────────────────────
    private BigDecimal netProfit;         // was: pageProfit — null if no permission
}