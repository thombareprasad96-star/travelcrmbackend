package com.crm.travelcrm.customer.dto.response;

import com.crm.travelcrm.customer.enums.CommunicationPreference;
import com.crm.travelcrm.customer.enums.CustomerStatus;
import com.crm.travelcrm.customer.enums.CustomerType;
import com.crm.travelcrm.customer.enums.LoyaltyTier;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Immutable customer projection returned by every read/write endpoint.
 *
 * <p>Field names are aligned 1:1 with what {@code AllCustomers.jsx} renders
 * ({@code id, customerId, name, phone, email, city, state, type, tier, status,
 * bookings, spent, lastBooking, notes}) plus the extended profile fields used by
 * the edit form. {@code id} carries the {@code publicId} (UUID) — the internal
 * numeric id is never exposed.</p>
 *
 * <p>{@code bookings}, {@code spent} and {@code lastBooking} are derived live from
 * the bookings table and enriched by the service after mapping.</p>
 */
@Value
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CustomerResponse {

    /** Stable external identifier (publicId). Used as the path param for all ops. */
    UUID id;

    /** Human-friendly code, e.g. {@code CUS10001}. Read by the UI as {@code customerId}. */
    String customerId;

    String name;
    String phone;
    String email;
    String alternatePhone;

    CustomerType type;
    CommunicationPreference commPref;
    LoyaltyTier tier;
    CustomerStatus status;

    String city;
    String state;
    String address;
    String pincode;

    LocalDate birthday;
    LocalDate anniversary;

    String passportNo;
    String panNo;
    String aadharNo;
    String documents;

    String notes;

    // ── Booking-derived metrics (computed, never persisted on Customer) ─────────

    /** Lifetime number of active bookings. */
    long bookings;

    /** Lifetime customer spend across active bookings. */
    BigDecimal spent;

    /** Date of the most recent booking, or {@code null} if none. */
    LocalDate lastBooking;

    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}