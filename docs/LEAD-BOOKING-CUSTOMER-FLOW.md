# Lead ↔ Booking ↔ Customer — Current Flow (as-is)

> Report only — no code was changed. Findings from reading the entities plus
> `BookingServiceImpl`, `LeadServiceImpl`, `CustomerServiceImpl`, the conversion
> controller/DTO, and repositories.

## 1. How the three are connected today

There are **no JPA relationships** (`@ManyToOne`/`@OneToOne`) between Lead, Booking,
and Customer. Every link is a **logical FK** — a plain `Long`/`UUID` column with **no DB
constraint**, validated tenant-scoped in the service layer (the codebase-wide
cross-aggregate convention).

| From → To | Column(s) | Type | Notes |
|---|---|---|---|
| `Booking` → `Customer` | `customer_id` (`Booking.java:45`) | logical, **NOT NULL** | Plus `customer_name_snapshot`. Every booking must point at a customer. |
| `Booking` → `Lead` | `lead_id` (`Booking.java:60`) | logical, **nullable** | A booking can exist with no lead (manual booking). |
| `Booking` → `Destination` | `destination_id` + `destination_snapshot` | logical, nullable | Destination stored mostly as free-text snapshot. |
| `Booking` → source `Lead`/`Quotation` | `source_lead_public_id`, `source_quotation_public_id` (UUIDs) | logical traceability | Set **only** on conversion, never on manual create. |
| `Lead` → `Booking` | `converted_booking_public_id` (UUID) + `converted_at` (`Lead.java:124-128`) | logical back-link | Stamped when the lead is converted. |
| `Customer` → `Lead` | **none** | — | `Customer` has **zero** reference back to a lead. |# Lead ↔ Booking ↔ Customer — Current Flow (as-is)

> Report only — no code was changed. Findings from reading the entities plus
> `BookingServiceImpl`, `LeadServiceImpl`, `CustomerServiceImpl`, the conversion
> controller/DTO, and repositories.

## 1. How the three are connected today

There are **no JPA relationships** (`@ManyToOne`/`@OneToOne`) between Lead, Booking,
and Customer. Every link is a **logical FK** — a plain `Long`/`UUID` column with **no DB
constraint**, validated tenant-scoped in the service layer (the codebase-wide
cross-aggregate convention).

| From → To | Column(s) | Type | Notes |
|---|---|---|---|
| `Booking` → `Customer` | `customer_id` (`Booking.java:45`) | logical, **NOT NULL** | Plus `customer_name_snapshot`. Every booking must point at a customer. |
| `Booking` → `Lead` | `lead_id` (`Booking.java:60`) | logical, **nullable** | A booking can exist with no lead (manual booking). |
| `Booking` → `Destination` | `destination_id` + `destination_snapshot` | logical, nullable | Destination stored mostly as free-text snapshot. |
| `Booking` → source `Lead`/`Quotation` | `source_lead_public_id`, `source_quotation_public_id` (UUIDs) | logical traceability | Set **only** on conversion, never on manual create. |
| `Lead` → `Booking` | `converted_booking_public_id` (UUID) + `converted_at` (`Lead.java:124-128`) | logical back-link | Stamped when the lead is converted. |
| `Customer` → `Lead` | **none** | — | `Customer` has **zero** reference back to a lead. |

The **only real JPA FK** among them is `Lead.assignedUser` → `User`
(`Lead.java:67`, `fk_lead_assigned_user`). Customer ↔ Booking ↔ Lead are otherwise
wired purely by id columns.

## 2. What triggers a Booking creation

Two entry points, both in the booking module:

**A. Manual / direct create** — `POST /api/bookings` → `BookingServiceImpl.create()` (`:88`)
- Requires an **existing `customerId`** (`CreateBookingRequestDTO.customerId`, `@NotNull`).
  The customer must already exist; it is looked up and its name snapshotted (`:99-104`).
- `leadId` is **optional**; if present it is only validated to exist (`:109-114`) — no stage change.
- **Does not create a customer, does not touch any lead's stage.**

**B. Lead → Booking conversion** — `POST /api/leads/{publicId}/convert-to-booking`
→ `BookingServiceImpl.convertLeadToBooking()` (`:131`)
- The only path that links all three and mutates the lead.

Both paths generate the booking code (`BKG-YY-NNNN`), set status `PENDING`, and derive
financials server-side (GST 5% + TCS 5%, `netProfit = customerAmount − vendorCost`, `:667-682`).


## 3. Does a Lead ever become a Customer today?

**Yes — but only during conversion, and only lazily.**
`resolveOrCreateCustomer()` (`BookingServiceImpl.java:211`):

```java
customerRepository.findByPhoneAndTenantIdAndDeletedAtIsNull(lead.getPhone(), tenantId)
    .orElseGet(() -> { /* build new Customer from the lead's name/phone/email */ });
```

- **Phone is the per-tenant natural key.** If a customer with the lead's phone already
  exists it is **reused**; otherwise a **new `Customer` is created**, snapshotting the
  lead's `name`/`phone`/`email` and generating a `customerCode` (e.g. `CUS10001`).
- Nowhere else — not in `LeadServiceImpl`, not in `CustomerServiceImpl` — does a lead
  turn into a customer. Creating a lead does **not** create a customer; only conversion does.

## 4. Existing conversion / linking logic

All in **`BookingServiceImpl.convertLeadToBooking()`**, one `@Transactional`:

1. **Auth & visibility** — endpoint gated by `BOOKING_CREATE`; inside,
   `leadAccessGuard.requireVisible(leadPublicId, "LEAD_UPDATE")` (`:137`) enforces
   row-level lead visibility and returns the managed `Lead`.
2. **Duplicate guard** (`:141-147`) — if the lead already has an active booking, throws
   **409** ("already converted to booking BKG-…"). Blocks double-submits.
3. **Optional quotation link** (`:150-163`) — if `quotationPublicId` is sent, it must
   belong to the same lead + tenant, else rejected; stored as `sourceQuotationPublicId`.
4. **Resolve/create customer** from lead phone (`:166`).
5. **Build the booking** (`:169-187`) carrying `customerId`, `leadId`,
   `sourceLeadPublicId`, `sourceQuotationPublicId`, reviewed amounts, services;
   financials derived (`:190`).
6. **Flip the lead** (`:195-198`) — `leadStage = CONVERTED`, `convertedAt = now`,
   `convertedBookingPublicId = booking.publicId`. **The lead is kept for history, never deleted.**
7. **Notify** — publishes `BOOKING_CREATED` to tenant admins (`:203`).

### Reverse / unlink logic (in `cancel()`, `:319`)

Cancellation undoes the links, driven by `CancelAction`:

- **`MOVE_TO_LEAD`** (`moveBackToLead`, `:361`) → lead set to `REOPENED`, clears
  `convertedAt`/`convertedBookingPublicId`, keeps the booking↔lead link.
- **`PERMANENT_DELETE_LEAD`** (`trashLeadOnCancel`, `:388`) → soft-deletes (Trash) the
  lead + cascade-trashes its quotations; requires extra `LEAD_PERMANENT_DELETE` authority.
- **Derived-customer cleanup** (`handleDerivedCustomerOnCancel`, `:413`) → if the
  booking's customer has **no other active booking**, the customer is soft-deleted (Trash);
  a repeat customer is preserved. Mirror of the lead→customer creation.
- The booking itself is **always retained** (status → `CANCELLED`); a `COMPLETED` booking
  cannot be cancelled.

### Read-side linkage (no persistence coupling)

`CustomerServiceImpl` reads `BookingRepository` purely to **aggregate** per-customer
metrics — `bookingCount`, `totalSpent`, `lastBookingDate` (`loadMetrics`, `:365`) and
booking history (`getBookingHistory`, `:275`) — all keyed by `customer_id`. Metrics are
computed on demand, never stored on the entity.

## 5. Summary

- **Model:** `Customer 1 —→ * Booking * ←— (0..1) Lead`, all via logical id columns; the
  only real FK is `Lead → User (assignedUser)`.
- **Booking creation:** manual (`customerId` required, no customer/lead mutation) **or**
  lead conversion (creates/links customer, flips lead to `CONVERTED`).
- **Lead → Customer:** happens **only** at conversion, matched on **phone** per tenant
  (reuse-or-create). Leads and customers are otherwise independent — a lead carries its own
  `customerName`/`phone`/`email` and is never auto-promoted to a customer.
- **Full lifecycle already implemented:** convert (link + stage flip + traceability
  back-links) and cancel (reopen/trash lead, trash derived customer, retain booking).

The **only real JPA FK** among them is `Lead.assignedUser` → `User`
(`Lead.java:67`, `fk_lead_assigned_user`). Customer ↔ Booking ↔ Lead are otherwise
wired purely by id columns.

## 2. What triggers a Booking creation

Two entry points, both in the booking module:

**A. Manual / direct create** — `POST /api/bookings` → `BookingServiceImpl.create()` (`:88`)
- Requires an **existing `customerId`** (`CreateBookingRequestDTO.customerId`, `@NotNull`).
  The customer must already exist; it is looked up and its name snapshotted (`:99-104`).
- `leadId` is **optional**; if present it is only validated to exist (`:109-114`) — no stage change.
- **Does not create a customer, does not touch any lead's stage.**

**B. Lead → Booking conversion** — `POST /api/leads/{publicId}/convert-to-booking`
→ `BookingServiceImpl.convertLeadToBooking()` (`:131`)
- The only path that links all three and mutates the lead.

Both paths generate the booking code (`BKG-YY-NNNN`), set status `PENDING`, and derive
financials server-side (GST 5% + TCS 5%, `netProfit = customerAmount − vendorCost`, `:667-682`).


## 3. Does a Lead ever become a Customer today?

**Yes — but only during conversion, and only lazily.**
`resolveOrCreateCustomer()` (`BookingServiceImpl.java:211`):

```java
customerRepository.findByPhoneAndTenantIdAndDeletedAtIsNull(lead.getPhone(), tenantId)
    .orElseGet(() -> { /* build new Customer from the lead's name/phone/email */ });
```

- **Phone is the per-tenant natural key.** If a customer with the lead's phone already
  exists it is **reused**; otherwise a **new `Customer` is created**, snapshotting the
  lead's `name`/`phone`/`email` and generating a `customerCode` (e.g. `CUS10001`).
- Nowhere else — not in `LeadServiceImpl`, not in `CustomerServiceImpl` — does a lead
  turn into a customer. Creating a lead does **not** create a customer; only conversion does.

## 4. Existing conversion / linking logic

All in **`BookingServiceImpl.convertLeadToBooking()`**, one `@Transactional`:

1. **Auth & visibility** — endpoint gated by `BOOKING_CREATE`; inside,
   `leadAccessGuard.requireVisible(leadPublicId, "LEAD_UPDATE")` (`:137`) enforces
   row-level lead visibility and returns the managed `Lead`.
2. **Duplicate guard** (`:141-147`) — if the lead already has an active booking, throws
   **409** ("already converted to booking BKG-…"). Blocks double-submits.
3. **Optional quotation link** (`:150-163`) — if `quotationPublicId` is sent, it must
   belong to the same lead + tenant, else rejected; stored as `sourceQuotationPublicId`.
4. **Resolve/create customer** from lead phone (`:166`).
5. **Build the booking** (`:169-187`) carrying `customerId`, `leadId`,
   `sourceLeadPublicId`, `sourceQuotationPublicId`, reviewed amounts, services;
   financials derived (`:190`).
6. **Flip the lead** (`:195-198`) — `leadStage = CONVERTED`, `convertedAt = now`,
   `convertedBookingPublicId = booking.publicId`. **The lead is kept for history, never deleted.**
7. **Notify** — publishes `BOOKING_CREATED` to tenant admins (`:203`).

### Reverse / unlink logic (in `cancel()`, `:319`)

Cancellation undoes the links, driven by `CancelAction`:

- **`MOVE_TO_LEAD`** (`moveBackToLead`, `:361`) → lead set to `REOPENED`, clears
  `convertedAt`/`convertedBookingPublicId`, keeps the booking↔lead link.
- **`PERMANENT_DELETE_LEAD`** (`trashLeadOnCancel`, `:388`) → soft-deletes (Trash) the
  lead + cascade-trashes its quotations; requires extra `LEAD_PERMANENT_DELETE` authority.
- **Derived-customer cleanup** (`handleDerivedCustomerOnCancel`, `:413`) → if the
  booking's customer has **no other active booking**, the customer is soft-deleted (Trash);
  a repeat customer is preserved. Mirror of the lead→customer creation.
- The booking itself is **always retained** (status → `CANCELLED`); a `COMPLETED` booking
  cannot be cancelled.

### Read-side linkage (no persistence coupling)

`CustomerServiceImpl` reads `BookingRepository` purely to **aggregate** per-customer
metrics — `bookingCount`, `totalSpent`, `lastBookingDate` (`loadMetrics`, `:365`) and
booking history (`getBookingHistory`, `:275`) — all keyed by `customer_id`. Metrics are
computed on demand, never stored on the entity.

## 5. Summary

- **Model:** `Customer 1 —→ * Booking * ←— (0..1) Lead`, all via logical id columns; the
  only real FK is `Lead → User (assignedUser)`.
- **Booking creation:** manual (`customerId` required, no customer/lead mutation) **or**
  lead conversion (creates/links customer, flips lead to `CONVERTED`).
- **Lead → Customer:** happens **only** at conversion, matched on **phone** per tenant
  (reuse-or-create). Leads and customers are otherwise independent — a lead carries its own
  `customerName`/`phone`/`email` and is never auto-promoted to a customer.
- **Full lifecycle already implemented:** convert (link + stage flip + traceability
  back-links) and cancel (reopen/trash lead, trash derived customer, retain booking).