package com.crm.travelcrm.fleet.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What a driver is paid for being away from home: <em>bata</em> (daily allowance) and night halt.
 *
 * <p><b>Computed from policy, never typed.</b> Both the owner and the field supervisor were explicit
 * about this: "bata is calculated by a written rule, shown to the driver, and any deduction carries
 * a visible reason". A bata figure a driver types is a negotiation; a bata figure the system derives
 * from days out is a payslip. It also removes the single most common settlement argument.
 *
 * <p><b>Effective-dated, never edited in place.</b> Raising the rate in October must not restate what
 * a driver was owed for a trip in June — that trip was settled and signed against the rate that
 * applied then. So a rate change is a NEW row with a later {@code effectiveFrom}, and the settlement
 * picks the row in force on the trip's start date. Editing the existing row instead would silently
 * change history, which is the same defect as a status-flip void.
 *
 * <p>Optionally scoped by vehicle class ({@code FleetVehicle.type} — "Tempo Traveller", "Bus"), since
 * a bus driver and a Dzire driver are not on the same rate. The row with a null class is the tenant
 * default and is used when nothing more specific matches.
 */
@Entity
@Table(name = "fleet_allowance_policies", indexes = {
        @Index(name = "idx_fleet_allow_tenant", columnList = "tenant_id,effective_from")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class FleetAllowancePolicy extends BaseTenantEntity {

    /** Matches {@code FleetVehicle.type}. Null = the tenant-wide default. */
    @Column(name = "vehicle_class", length = 60)
    private String vehicleClass;

    /** The policy in force for a trip is the latest row with {@code effectiveFrom <= trip start}. */
    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** Per full day away. */
    @Column(name = "bata_per_day", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal bataPerDay = BigDecimal.ZERO;

    /** Per night spent away from base, paid on top of bata. */
    @Column(name = "night_halt_per_day", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal nightHaltPerDay = BigDecimal.ZERO;

    /**
     * Whether the day a trip starts and the day it ends each count as a full day. Operators differ,
     * and getting it wrong by one day on every trip is a real and constant source of dispute — so it
     * is a stated tenant policy rather than an assumption buried in a calculation.
     */
    @Column(name = "count_partial_days_as_full", nullable = false)
    @Builder.Default
    private boolean countPartialDaysAsFull = true;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
