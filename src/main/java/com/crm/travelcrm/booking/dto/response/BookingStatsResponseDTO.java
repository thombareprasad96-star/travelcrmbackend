// ── BookingStatsResponseDTO ───────────────────────────────────────────────────

package com.crm.travelcrm.booking.dto.response;

import lombok.*;
import java.math.BigDecimal;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingStatsResponseDTO {

    // ── Booking counts ────────────────────────────────────────────────────────
    private long totalBookings;
    private long confirmedBookings;
    private long pendingBookings;
    private long cancelledBookings;
    private long completedBookings;
    private long refundedBookings;

    // ── Revenue (safe for all roles) ──────────────────────────────────────────
    private BigDecimal totalRevenue;       // sum of customer_amount
    private BigDecimal totalCollected;     // sum of paid_amount
    private BigDecimal totalPending;       // sum of (total_payable - paid_amount)

    // ── Sensitive financials (ADMIN + MANAGER only) ───────────────────────────
    // Service populates these only when caller has booking:profit:read permission
    // When caller is AGENT these will be null — Jackson omits null fields
    private BigDecimal totalVendorCost;
    private BigDecimal netProfit;
    private BigDecimal totalRefundAmount;

    // ── Tax summary (ADMIN + MANAGER only) ────────────────────────────────────
    private BigDecimal gstCollected;
    private BigDecimal tcsCollected;
}