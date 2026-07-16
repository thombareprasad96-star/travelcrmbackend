package com.crm.travelcrm.accounting.tds.entity;

import com.crm.travelcrm.accounting.tds.enums.TdsSection;
import com.crm.travelcrm.accounting.tds.enums.VendorBillStatus;
import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A payable the agency owes a vendor — the net-new purchase ledger (no such ledger existed before;
 * {@code VendorServiceImpl} only kept a {@code totalPaid} running total). Raising a bill records the
 * gross cost, computes the TDS to withhold (section rate, or the 206AA no-PAN rate) and the net
 * payable. Vendor payments post against it. {@code @Version} serialises concurrent payments so the
 * running {@code amountPaid} can't be corrupted (same pattern as {@code Booking}).
 */
@Entity
@Table(
        name = "vendor_bills",
        indexes = {
                @Index(name = "idx_vbill_tenant",  columnList = "tenant_id"),
                @Index(name = "idx_vbill_vendor",  columnList = "tenant_id,vendor_id"),
                @Index(name = "idx_vbill_booking", columnList = "tenant_id,booking_id"),
                @Index(name = "idx_vbill_status",  columnList = "tenant_id,status"),
                @Index(name = "idx_vbill_deleted", columnList = "tenant_id,deleted_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class VendorBill extends BaseTenantEntity {

    @Version
    @Column(name = "row_version")
    private Long rowVersion;

    // ── Vendor (snapshot) ─────────────────────────────────────────────────────
    @Column(name = "vendor_id", nullable = false)
    private Long vendorId;

    @Column(name = "vendor_public_id")
    private UUID vendorPublicId;

    @Column(name = "vendor_name_snapshot", length = 200)
    private String vendorNameSnapshot;

    /** Vendor PAN as-of bill date — its absence drives the 206AA higher-rate TDS. */
    @Column(name = "pan_snapshot", length = 12)
    private String panSnapshot;

    @Column(name = "gstin_snapshot", length = 15)
    private String gstinSnapshot;

    // ── Source (optional links) ───────────────────────────────────────────────
    @Column(name = "booking_id")
    private Long bookingId;

    @Column(name = "booking_code", length = 20)
    private String bookingCode;

    @Column(name = "service_item_id")
    private Long serviceItemId;

    /** Vendor's own invoice/bill reference. */
    @Column(name = "bill_number", length = 60)
    private String billNumber;

    @Column(name = "bill_date", nullable = false)
    private LocalDate billDate;

    @Column(name = "description", length = 300)
    private String description;

    // ── Money ─────────────────────────────────────────────────────────────────
    /** Total the vendor charges (incl. their GST, if any). */
    @Builder.Default
    @Column(name = "gross_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal grossAmount = BigDecimal.ZERO;

    /** Input GST embedded in the bill (recoverable as ITC when the tenant is eligible). */
    @Builder.Default
    @Column(name = "gst_input", nullable = false, precision = 14, scale = 2)
    private BigDecimal gstInput = BigDecimal.ZERO;

    /** Amount TDS is computed on (usually the value excluding GST). */
    @Builder.Default
    @Column(name = "tds_base", nullable = false, precision = 14, scale = 2)
    private BigDecimal tdsBase = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "tds_section", length = 10)
    private TdsSection tdsSection;

    @Builder.Default
    @Column(name = "tds_rate_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal tdsRatePct = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "tds_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal tdsAmount = BigDecimal.ZERO;

    /** grossAmount − tdsAmount — what actually gets disbursed to the vendor. */
    @Builder.Default
    @Column(name = "net_payable", nullable = false, precision = 14, scale = 2)
    private BigDecimal netPayable = BigDecimal.ZERO;

    /** Running total disbursed (sum of vendor payments); maintained by the service under @Version. */
    @Builder.Default
    @Column(name = "amount_paid", nullable = false, precision = 14, scale = 2)
    private BigDecimal amountPaid = BigDecimal.ZERO;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private VendorBillStatus status = VendorBillStatus.UNPAID;

    @Column(name = "cancelled_at")
    private java.time.LocalDateTime cancelledAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @Transient
    public BigDecimal getBalancePayable() {
        BigDecimal net = netPayable != null ? netPayable : BigDecimal.ZERO;
        BigDecimal paid = amountPaid != null ? amountPaid : BigDecimal.ZERO;
        return net.subtract(paid);
    }
}