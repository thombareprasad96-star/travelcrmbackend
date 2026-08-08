package com.crm.travelcrm.lead.entity;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.entity.BaseEntity;
import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.customer.enums.CommunicationPreference;
import com.crm.travelcrm.lead.enums.DepartureMode;
import com.crm.travelcrm.lead.enums.LeadOrigin;
import com.crm.travelcrm.lead.enums.LeadSource;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.lead.enums.LeadType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
        name = "leads",
        indexes = {
                @Index(name = "idx_lead_email", columnList = "email"),
                @Index(name = "idx_lead_phone", columnList = "phone"),
                @Index(name = "idx_lead_code", columnList = "tenant_id,lead_code"),
                @Index(name = "idx_leads_assigned_user_id", columnList = "assigned_user_id")
        }
        // Uniqueness of (tenant_id, email) and (tenant_id, phone) is enforced by
        // soft-delete-aware, OPEN-lead-only PARTIAL unique indexes
        // (uq_leads_email_tenant_open / uq_leads_phone_tenant_open, see db/indexes.sql):
        //   WHERE deleted_at IS NULL AND lead_stage NOT IN ('CONVERTED','LOST').
        // An absolute constraint permanently blocked repeat business — once a lead was
        // CONVERTED (kept for history, never deleted) the same customer could never be
        // entered as a fresh lead again. The partial index keeps "one OPEN lead per
        // contact" while allowing new inquiries after the prior one closed.
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Lead extends BaseTenantEntity {

    /**
     * Human-readable per-tenant reference ({@code LD-26-0001}), issued by {@code LeadCodeGenerator}.
     * This is the DISPLAY identity — {@code publicId} remains the API/routing key and is what every
     * endpoint and FE link is still keyed by. Never renumber it: it is quoted to customers.
     *
     * <p>Nullable in the entity ON PURPOSE, same reasoning as {@link #origin}: {@code ddl-auto=update}
     * adds the column as NULL on every existing row, and {@code nullable = false} here would make
     * Hibernate try to add a NOT NULL to a column full of nulls and fail. {@code db/indexes.sql}
     * backfills existing rows and seeds the counter; the NOT NULL is a follow-up documented there.</p>
     */
    @Column(name = "lead_code", length = 20)
    private String leadCode;

    @Column(name = "customer_name", nullable = false, length = 150)
    private String customerName;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    /**
     * E.164-canonical form of {@link #phone}, derived on write. A SHADOW column, not a replacement:
     * {@code PhoneNormalizer} is deliberately trim-only so it cannot break matches against existing
     * rows, and rewriting the stored phone in place would do exactly that.
     *
     * <p>This exists because duplicate detection compares the RAW phone, so {@code +919812345678},
     * {@code 919812345678} and {@code 09812345678} are three different people to it. Inbound
     * channels deliver E.164; without a canonical column to match on, the append-on-repeat
     * behaviour never fires and every repeat caller becomes a new lead.
     */
    @Column(name = "phone_normalized", length = 20)
    private String phoneNormalized;

    /**
     * Nullable since an inbound enquiry (an IVR call, most obviously) has a phone and nothing else.
     * The NOT NULL drop is a hand-written {@code db/indexes.sql} block — {@code ddl-auto=update}
     * will not relax an existing NOT NULL. Safe against {@code uq_leads_email_tenant_open}: Postgres
     * treats NULLs as distinct in a unique index, so many email-less leads coexist.
     */
    @Column(name = "email", length = 150)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "lead_source", nullable = false, length = 50)
    private LeadSource leadSource;

    /**
     * How this lead got here. Nullable in the entity ON PURPOSE — {@code ddl-auto=update} adds the
     * column as NULL on every existing row, and a {@code nullable = false} here would make Hibernate
     * try to add a NOT NULL to a column full of nulls and fail. {@code db/indexes.sql} backfills
     * MANUAL first; do not "correct" this to non-nullable without checking that block.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "origin", length = 20)
    @Builder.Default
    private LeadOrigin origin = LeadOrigin.MANUAL;

    /**
     * Logical FK to {@code lead_source_integrations.id} — which connection delivered this lead.
     * Null for MANUAL and for SYSTEM (the sub-agent portal and repeat-customer automation are
     * machine-made but have no integration row), so <b>a null here does NOT mean "a human made
     * it"</b> — read {@link #origin} for that.
     */
    @Column(name = "source_integration_id")
    private Long sourceIntegrationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "lead_type", nullable = false, length = 50)
    private LeadType leadType;

    @Enumerated(EnumType.STRING)
    @Column(name = "lead_stage", nullable = false, length = 50)
    private LeadStage leadStage;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "assigned_user_id",
            referencedColumnName = "id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_lead_assigned_user")
    )
    private User assignedUser;

    // ── Claim window / contacted lock ─────────────────────────────────────────
    // A new lead is auto-assigned an owner, but stays CLAIMABLE by any eligible user until someone
    // marks it contacted. The three columns below are that window's entire state.

    /**
     * Compare-and-swap counter guarding the ownership transition. Bumped by every successful claim,
     * reassign and contact-stamp; a claim submits the value it saw and loses (0 rows updated) if
     * anyone moved first. See {@code LeadRepository.claimLead}.
     *
     * <p><b>Deliberately NOT a JPA {@code @Version}.</b> Optimistic locking fires when any two
     * writers touch the row, so a manager editing notes would 409 against a concurrent claim — and
     * every existing lead write path (edit, stage drag, convert-to-booking, ingest append) would
     * inherit that. A dedicated counter guards exactly the ownership transition and leaves every
     * other write path's semantics untouched. Same reasoning {@code FleetSettlementService} records
     * for choosing its lock.
     *
     * <p>{@code Integer}, not {@code int}: {@code ddl-auto=update} adds the column NULL on every
     * existing row and Hibernate cannot read a SQL NULL into a primitive. Readers coalesce — the
     * CAS query compares {@code COALESCE(claim_version, 0)} so a legacy row is claimable before the
     * backfill in {@code db/indexes.sql} has run, not after.
     */
    @Column(name = "claim_version")
    private Integer claimVersion;

    /**
     * When someone first engaged this lead — the moment the claim window CLOSES and the owner
     * becomes fixed. Null means still claimable.
     *
     * <p>This, not {@code leadStage}, is the lock: stage moves on past Contacted (Follow Up,
     * Qualified…) and a stage-based test would silently reopen the window every time it did.
     * Stamped exactly once, by the CAS in {@code LeadRepository.markContacted} — so two concurrent
     * "Mark Contacted" clicks cannot produce two different first-response times.
     */
    @Column(name = "first_contacted_at")
    private LocalDateTime firstContactedAt;

    /** Logical FK to {@code users.id} — who actually made first contact (may differ from the owner). */
    @Column(name = "first_contacted_by_user_id")
    private Long firstContactedByUserId;

    /**
     * {@code createdAt} → {@link #firstContactedAt} in seconds, denormalised at stamp time so SLA
     * reports never recompute it. Frozen: reopening the claim window (a manager undoing a mis-click)
     * deliberately does NOT clear this, or reopening would become a way to erase a missed SLA.
     */
    @Column(name = "first_response_seconds")
    private Long firstResponseSeconds;

    /**
     * The first-response target in force WHEN THIS LEAD WAS CREATED, in seconds. Snapshotted rather
     * than read live for the same reason a booking pins its cancellation policy: raising the target
     * next month must not silently un-breach last month's leads. Null on rows predating the column —
     * {@code LeadSlaPolicy} falls back to the configured default for those.
     */
    @Column(name = "sla_target_seconds")
    private Integer slaTargetSeconds;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "anniversary_date")
    private LocalDate anniversaryDate;

    /**
     * Preferred channel to reach this lead. Deliberately the SAME enum the customer master uses
     * rather than a lead-local copy: conversion copies this straight onto {@code Customer.commPref},
     * and two vocabularies that must map onto each other always drift.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_communication", length = 30)
    private CommunicationPreference preferredCommunication;

    /**
     * When the agent intends to call back. Stored on the lead so the value survives a reopen of the
     * form; the actual Reminder is raised separately by the follow-up log the create screen posts
     * after a successful save.
     */
    @Column(name = "follow_up_date")
    private LocalDate followUpDate;

    /** Free-text-ish trip category ("Honeymoon", "Family", …). Kept a String on purpose — see DDL. */
    @Column(name = "package_type", length = 50)
    private String packageType;

    @Column(name = "travel_date")
    private LocalDate travelDate;

    // ── Departure / transport ────────────────────────────────────────────────
    // departureMode is the discriminator: the trio of columns below it that applies is decided by
    // this value, and the other two trios stay null. It must round-trip or the edit form reopens
    // with the whole transport section unset and every field under it orphaned.

    @Enumerated(EnumType.STRING)
    @Column(name = "departure_mode", length = 20)
    private DepartureMode departureMode;

    @Column(name = "departure_airport", length = 150)
    private String departureAirport;

    /** IATA code, upper-cased by the frontend before send. */
    @Column(name = "airport_code", length = 10)
    private String airportCode;

    @Column(name = "preferred_flight_time")
    private LocalTime preferredFlightTime;

    @Column(name = "railway_station", length = 150)
    private String railwayStation;

    @Column(name = "train_class", length = 50)
    private String trainClass;

    @Column(name = "preferred_train_time")
    private LocalTime preferredTrainTime;

    @Column(name = "pickup_address", columnDefinition = "TEXT")
    private String pickupAddress;

    @Column(name = "pickup_date_time")
    private LocalDateTime pickupDateTime;

    @Column(name = "vehicle_preference", length = 150)
    private String vehiclePreference;

    // ── Accessibility ────────────────────────────────────────────────────────

    @Column(name = "special_assistance_required")
    private Boolean specialAssistanceRequired;

    @Column(name = "assistance_passenger_count")
    private Integer assistancePassengerCount;

    /**
     * Which assistances are needed ("Wheelchair Assistance", …). A join table exactly like
     * {@link #services} rather than a delimited string — the vocabulary is open (the form offers a
     * starting set and more will be added), and a delimited column cannot be queried or counted.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "lead_special_assistance_types",
            joinColumns = @JoinColumn(name = "lead_id"))
    @Column(name = "assistance_type")
    @BatchSize(size = 50)
    @Builder.Default
    private List<String> specialAssistanceTypes = new ArrayList<>();

    @Column(name = "special_assistance_notes", columnDefinition = "TEXT")
    private String specialAssistanceNotes;

    // Budget (₹) — the customer's planned spend; drives the Kanban column totals
    // and the active-pipeline figure. Nullable: not every fresh lead has a value yet.
    @Column(name = "budget", precision = 15, scale = 2)
    private BigDecimal budget;

    @Column(name = "depart_country", length = 100)
    private String departCountry;

    @Column(name = "depart_city", length = 100)
    private String departCity;

    @Column(name = "rooms")
    private Integer rooms;

    @Column(name = "adults")
    private Integer adults;

    // Gender split of the adults. The form derives `adults` as male + female, so these two are the
    // authored values and `adults` is the total — keep all three: every downstream reader
    // (quotation pax block, reports, the leads grid) reads `adults`.
    @Column(name = "male")
    private Integer male;

    @Column(name = "female")
    private Integer female;

    @Column(name = "children")
    private Integer children;

    @Column(name = "infants")
    private Integer infants;

    @Column(name = "extra_beds")
    private Integer extraBeds;

    // Stored as a proper join table (lead_services) via @ElementCollection —
    // one row per service, NOT a comma-separated string column.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "lead_services", joinColumns = @JoinColumn(name = "lead_id"))
    @Column(name = "service")
    @BatchSize(size = 50)
    @Builder.Default
    private List<String> services = new ArrayList<>();

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // ── Existing-customer link ────────────────────────────────────────────────
    /**
     * The customer master row this lead was matched to at creation, by phone or email.
     *
     * <p>A LOGICAL FK (plain Long, no {@code @ManyToOne}) for the same reason
     * {@code Booking.customerId} is one: the customer can be moved to Trash independently, and a
     * real association would either block that or cascade into the lead. Resolution is always
     * tenant-scoped through the repository, never a bare {@code findById}.
     *
     * <p>Null means "no existing customer had this phone or email when the lead was created" —
     * <b>not</b> "never checked". {@link #customerLinkedAt} distinguishes the two.
     */
    @Column(name = "customer_id")
    private Long customerId;

    /**
     * The same customer as {@link #customerId}, by publicId. Denormalised deliberately — the exact
     * idiom {@link #convertedBookingPublicId} already uses — so the mapper can return the UUID the
     * API is keyed by without a repository lookup on every single lead it maps.
     */
    @Column(name = "customer_public_id")
    private UUID customerPublicId;

    /**
     * When the customer probe last ran — set on a match AND on a no-match, so it records that the
     * lookup happened rather than that it succeeded. That is what lets {@link #customerId} being
     * null mean "checked, nobody matched" here and "never checked" on a row written before this
     * feature (where both columns are null).
     *
     * <p>Also re-stamped when a booking back-links its customer onto the lead, since that is the
     * same question being answered later by a different door.
     */
    @Column(name = "customer_linked_at")
    private LocalDateTime customerLinkedAt;

    // ── Conversion traceability (Lead → Booking) ──────────────────────────────
    // Stamped when the lead is converted to a booking. The lead is NEVER deleted on
    // conversion — it stays for history with its stage flipped to CONVERTED and these
    // two columns pointing at the resulting booking (by its publicId, never the Long id).
    @Column(name = "converted_at")
    private LocalDateTime convertedAt;

    @Column(name = "converted_booking_public_id")
    private UUID convertedBookingPublicId;

    @OneToMany(mappedBy = "lead", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @Builder.Default
    private List<LeadItinerary> itinerary = new ArrayList<>();

    @OneToMany(mappedBy = "lead", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @OrderBy("roomNumber ASC")
    @Builder.Default
    private List<LeadRoomAllocation> roomAllocations = new ArrayList<>();


    // Convenience method to wire up bi-directional relationship
    public void addItinerary(LeadItinerary item) {
        item.setLead(this);
        this.itinerary.add(item);
    }

    public void addRoomAllocation(LeadRoomAllocation allocation) {
        allocation.setLead(this);
        this.roomAllocations.add(allocation);
    }
}
