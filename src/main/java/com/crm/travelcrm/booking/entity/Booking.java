package com.crm.travelcrm.booking.entity;

import com.crm.travelcrm.booking.enums.BookingStatus;
import com.crm.travelcrm.booking.enums.PaymentStatus;
import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Audited
@Entity
@Table(
        name = "bookings",
        indexes = {
                @Index(name = "idx_booking_tenant",      columnList = "tenant_id"),
                @Index(name = "idx_booking_code",        columnList = "tenant_id,booking_code"),
                @Index(name = "idx_booking_customer",    columnList = "customer_id"),
                @Index(name = "idx_booking_status",      columnList = "tenant_id,status"),
                @Index(name = "idx_booking_travel_date", columnList = "tenant_id,travel_date"),
                @Index(name = "idx_booking_deleted",     columnList = "tenant_id,deleted_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Booking extends BaseTenantEntity {

    // ───────────────── Identity ─────────────────

    @Column(name = "booking_code", nullable = false, length = 20)
    private String bookingCode;

    // ───────────────── Relationships ─────────────────

    // No DB-level FK — cross-aggregate reference to customers.id, enforced at the application layer.
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "customer_name_snapshot", nullable = false, length = 255)
    private String customerNameSnapshot;

    // No DB-level FK — cross-aggregate reference to destination_master.destination_id, enforced at the application layer.
    @Column(name = "destination_id")
    private Long destinationId;

    @Column(name = "destination_snapshot", nullable = false, length = 255)
    private String destinationSnapshot;

    // No DB-level FK — cross-aggregate reference to leads.id, enforced at the application layer.
    @Column(name = "lead_id")
    private Long leadId;

    // ───────────────── Financials ─────────────────

    @Builder.Default
    @Column(name = "customer_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal customerAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "vendor_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal vendorCost = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "gst", nullable = false, precision = 12, scale = 2)
    private BigDecimal gst = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "tcs", nullable = false, precision = 12, scale = 2)
    private BigDecimal tcs = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "total_payable", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalPayable = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "paid_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "net_profit", nullable = false, precision = 12, scale = 2)
    private BigDecimal netProfit = BigDecimal.ZERO;

    // ───────────────── Status ─────────────────

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BookingStatus status = BookingStatus.PENDING;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    private PaymentStatus paymentStatus = PaymentStatus.UNPAID;

    // ───────────────── Dates ─────────────────

    @Column(name = "booking_date", nullable = false)
    private LocalDate bookingDate;

    @Column(name = "travel_date", nullable = false)
    private LocalDate travelDate;

    // ───────────────── Services ─────────────────

    @NotAudited
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "booking_services",
            joinColumns = @JoinColumn(name = "booking_id")
    )
    @Column(name = "service_name", length = 100)
    @Builder.Default
    private List<String> services = new ArrayList<>();

    // ───────────────── Soft Delete ─────────────────

    @Builder.Default
    @Column(name = "active", nullable = false)
    private Boolean active = Boolean.TRUE;

    // ───────────────── Derived Fields ─────────────────

    @Transient
    public BigDecimal getPendingAmount() {
        if (totalPayable == null || paidAmount == null) return BigDecimal.ZERO;
        return totalPayable.subtract(paidAmount);
    }
}