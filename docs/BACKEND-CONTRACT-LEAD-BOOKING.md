# Backend contract — Lead / Customer / Booking

> **Status: the frontend is built and shipped; the backend has not followed.**
> This document is the backend work list, written against the FE as it exists on
> `travelcrmfe/travelcrmfrontend` today. Every payload quoted here is what the browser
> *already sends*.
>
> Verified against the working tree on 2026-08-01, backend branch `issue-fixes` @ `072fabf`.
> Supersedes the backend half of `docs/LEAD-CUSTOMER-BOOKING-FIELD-ANALYSIS.md`, which is
> wrong in six material places — see §1 before trusting anything in it.

---

## 1. Corrections to `LEAD-CUSTOMER-BOOKING-FIELD-ANALYSIS.md`

That document was written against an older tree. Six of its claims are false, and acting on
three of them breaks production.

| # | It claims | Truth | Consequence of believing it |
|---|---|---|---|
| 1 | `LeadType` is already `Fresh/Hot/Warm/Cold`; the FE list is "stale" | `LeadType.java` is `FRESH_LEAD("Fresh Lead")`, `REPEAT_CUSTOMER`, `CORPORATE`, `VIP`. **The FE matches the backend exactly.** | Changing the FE array 400s every lead save |
| 2 | Phase 0: "run `deploy/migrate-lead-types-hot-warm-cold.sql` **first**" | That script installs `CHECK (lead_type IN ('FRESH','HOT','WARM','COLD'))` while the app still writes `FRESH_LEAD`/… | **Every lead INSERT/UPDATE fails, and the lead list 500s on read** (`LeadType.fromValue("FRESH")` throws). Actively destructive. |
| 3 | `CreateLeadRequestDto.java:31` has `@NotBlank` on `email` | Lines 31–33 are blank. `email` is `@Email` + `@Size(max=150)` only. | Wasted work; the fix is already done |
| 4 | `ddl-auto=update`, so new columns are added automatically (§8) | `application.properties:64` and `application-prod.properties:47` are both **`validate`**; Flyway is `enabled=false` and blocked by a circular `flyway`↔`entityManagerFactory` dependency | **Adding an entity field without a hand-applied migration hard-fails the boot** |
| 5 | There is no Create Booking form; `bookingService.create` has zero call sites | `CreateBookingClean.jsx` is live, routed at `/CreateBooking`, and calls `create()` | Phase 4 is not greenfield |
| 6 | There is no Booking Details screen | `BookingDetails.jsx` (2136 lines) is live and routed at `/BookingDetails/:id` | §13 is not greenfield |

Two smaller drifts worth knowing: `deploy/migrate-lead-types-hot-warm-cold.sql` is **committed**,
not untracked; and `LeadStage` already has all 8 constants including `REOPENED`.

**File-shape traps.** Four frontend files carry a fully commented-out older copy of themselves at
the top, so every `grep` returns two hits and *the doc's line numbers all point at the dead copy*:
`leadService.js` (dead 1–129, live 140–293), `BookingDetails.jsx` (dead 1–1062, live 1070+),
`CreateLead.jsx`, `EditLead.jsx`, `TravelDetails.jsx`. **Always take the later hit.**
Likewise `features/bookings/pages/CreateBooking.jsx` is parked dead code — the live create screen
is `CreateBookingClean.jsx`, exported *under the name* `CreateBooking` (`index.js:13`).

---

## 2. What shipped on the frontend this session

| File | Change |
|---|---|
| `leads/components/LeadInformation.jsx` | `LEAD_STAGES` gained `"Reopened"` (backend had it, the form didn't) + a cyan `stageColors` entry |
| `leads/pages/AllLeads.jsx` | matching `Reopened` pill in `STAGE_PILL`, so the new stage no longer falls to the orange default |
| `leads/api/leadService.js` | blank email now sends `null`, not `""` (safe: `LeadMapper.java:64` and `LeadServiceImpl.java:287` are both explicitly null-guarded) |
| `bookings/pages/BookingDetails.jsx` | cancel routed to `CancelBookingModal`; lead link uses `sourceLeadPublicId`; `netProfit` honours a server-sent `0`; Quotation/Itinerary + Reminders sections wired to real endpoints; margin masking added |
| `bookings/pages/CreateBookingClean.jsx` | Vendor Cost made required (`> 0`); matched-customer reuse card; indicative financial preview |
| `reminders/index.js` | exports `bookingReminderService` (was deep-import-only) |

`LEAD_TYPES` was deliberately **not** touched — see §4.

---

## 3. Work item 1 — Lead drops 20 of the 41 fields the form sends

`CreateLeadRequestDto` binds 20 fields. `transformFormData` (`leadService.js:143-218`) sends 41.
Jackson's `FAIL_ON_UNKNOWN_PROPERTIES` is **disabled** (confirmed by exhaustive negative search: no
`spring.jackson.*`, no `ObjectMapper` bean, no `Jackson2ObjectMapperBuilderCustomizer` anywhere), so
this is **silent data loss, not a 400**. Agents type into these fields and the values vanish.

**Dropped (20 real + 1 harmless):**

| Group | Fields | Sev |
|---|---|---|
| Dates | `anniversaryDate`, `followUpDate` | P2 |
| Pax split | `male`, `female` | P2 |
| Transport | `departureMode`, `departureAirport`, `airportCode`, `preferredFlightTime`, `railwayStation`, `trainClass`, `preferredTrainTime`, `pickupAddress`, `pickupDateTime`, `vehiclePreference` | P2/P3 |
| Accessibility | `specialAssistanceRequired`, `specialAssistanceTypes`, `assistancePassengerCount`, `specialAssistanceNotes` | P2 |
| Preferences | `preferredCommunication`, `packageType` | P3 |
| *Harmless* | `totalAdults` — a deliberate duplicate of the accepted `adults`; **no BE work needed** | — |

`departureMode` is the worst of these: it is the discriminator that gates the whole transport
sub-form, so because it never returns, **EditLead reopens with the transport section unset and every
transport field below it orphaned.**

### What to add

All plain scalars except `specialAssistanceTypes`, which wants an `@ElementCollection` mirroring
`services` (`Lead.java:163-168`).

1. **`lead/enums/DepartureMode.java`** — new. Copy the `@JsonValue`/`@JsonCreator` shape from
   `LeadType.java:19-33` verbatim (wire format is the `displayName`; `fromValue` accepts either form
   case-insensitively). **The FE sends `"Flight / Airport"`, `"Train / Rail"`, `"Car / Road"`** —
   match those display strings exactly or the discriminator silently fails to round-trip.
2. **`Lead.java`** — insert at line 131, between `birthDate` (`:129-130`) and `travelDate` (`:132-133`).
3. **`CreateLeadRequestDto.java`** — add all 20. This one DTO is bound with `@Valid` on **both**
   `POST /api/leads` (`LeadController.java:43-46`) and `PUT /api/leads/{publicId}` (`:112-116`);
   there is no separate update DTO.
4. **`LeadResponseDto.java`** — add all 20, **plus `destinationId`/`cityId` on the nested
   `ItineraryItem`** (`:63-71`). EditLead hydrates
   `row.destinationId ?? row.destinationPublicId ?? row.destination?.id ?? ""`, gets nothing, and the
   cascading Destination→City dropdowns reopen unlinked.
5. **`LeadMapper.java`** — **three** touchpoints, not two:
   - `toEntity` builder chain (`:53-104`, `birthDate` at `:75`)
   - `toResponse` builder chain (`:106-152`, `birthDate` at **`:130`**, not `:129`)
   - `LeadServiceImpl.updateLead` (`:272-350`) **sets fields manually and bypasses the mapper
     entirely** — `setBirthDate` is at **`:302`**. Miss this one and the field saves on create then
     silently reverts to null on every edit. A mapper-only round-trip test will not catch it.
6. **`db/indexes.sql`** — the `departure_mode` CHECK. Template is the `leads_origin_check` block at
   **`:473-481`** (the doc's `:428-436` has drifted).

```sql
ALTER TABLE leads DROP CONSTRAINT IF EXISTS leads_departure_mode_check;
ALTER TABLE leads ADD CONSTRAINT leads_departure_mode_check
        CHECK (departure_mode IN ('FLIGHT','TRAIN','CAR','CRUISE','OTHER'));
-- NULL passes a CHECK, so rows created before this runs are not rejected.
```

> **DDL warning.** `ddl-auto` is **`validate`**, not `update`. Adding these entity fields without
> first applying the `ALTER TABLE ... ADD COLUMN` statements by hand will **fail the application
> boot**, not silently work. Flyway cannot do it for you — it is disabled and its cutover is
> blocked by a circular `flyway`↔`entityManagerFactory` dependency. Write the DDL, apply it with
> `psql`, then deploy.

---

## 4. Work item 2 — `LeadType` → Fresh / Hot / Warm / Cold

**Decision taken: yes, move to the priority vocabulary. It must ship as one coordinated change.**

`deploy/migrate-lead-types-hot-warm-cold.sql` already exists and maps
`FRESH_LEAD→FRESH`, `REPEAT_CUSTOMER→WARM`, `CORPORATE|VIP→HOT`.

**Never run that script on its own.** Correct order, in a single deploy window:

1. Change `LeadType.java` constants to `FRESH("Fresh")`, `HOT("Hot")`, `WARM("Warm")`, `COLD("Cold")`.
2. Apply the migration SQL (it rewrites existing rows *and* installs the CHECK).
3. Ship the FE array `LEAD_TYPES` in `LeadInformation.jsx:13` in the same window.

Between steps the app is down for lead writes either way round, so keep the window short. Note the
business categories `Repeat Customer` / `Corporate` / `VIP` are **lost** by this mapping — if they
matter, they belong on `Customer.type`, not on `LeadType`.

---

## 5. Work item 3 — conversion must carry the customer profile

`BookingServiceImpl.resolveOrCreateCustomer()` copies exactly `name`, `phone`, `email`, and when the
customer already exists it syncs **nothing** — even the name the agent just corrected is discarded.

`Lead.birthDate` is collected today and thrown away: nothing reads it, and the marketing automations
read only `Customer.getBirthday()` / `getAnniversary()`. **Adding `anniversary` to Lead without this
change just duplicates an existing dead end.**

**On create** — also copy `birthDate`→`birthday`, `anniversary`, `departCity`→`city`.

**On the existing-customer branch** — apply this sync, and nothing looser:

| Field | Rule | Why |
|---|---|---|
| `name` | last-write-wins | the agent just confirmed it with the customer |
| `email`, `birthday`, `anniversary`, `city` | **fill-if-empty only** | a thin booking form must never erase a richer profile |
| `phone` | **never written** | per-tenant natural key; `uq_customers_phone_tenant` rejects a collision |

Also add **`customerEmail` to `LeadConversionRequestDTO`** — `ConvertToBookingModal.jsx:231` has
always sent it and the backend has always dropped it.

Related, from `BookingServiceImpl` itself: conversion sets `customerNameSnapshot` from the *request*
(`:276`) while direct create sets it from the *Customer row* (`:153`), so the two paths can already
disagree. Make both read the resolved Customer after sync.

---

## 6. Work item 4 — `POST /api/bookings` rejects the payload the FE already sends

**This is the highest-priority item: every Create Booking submit 400s today.**

`CreateBookingRequestDTO` binds a flat `@NotNull Long customerId` + `@NotBlank customerName` +
`Long leadId`. The live form posts a nested resolver block, a `tripSnapshot`, and a `leadPublicId`.

### The exact payload (`CreateBookingClean.jsx:475-533`)

```jsonc
{
  "customer": {
    // Mode A — matched by phone search. `id` on CustomerResponse IS the publicId UUID.
    "customerPublicId": "…uuid…",
    // present only when the agent ticked "Edit details"
    "sync": { "name": "…", "email": "…", "birthday": "yyyy-MM-dd", "anniversary": "yyyy-MM-dd" }
    // …OR Mode B, when the search 404s:
    // "newCustomer": { "name","phone","email","city","birthday","anniversary" }
  },
  "destination": "Goa",
  "travelDate": "yyyy-MM-dd",
  "tripSnapshot": {
    "packageType": "Honeymoon",
    "departure": {
      "country": "India", "city": "Pune", "mode": "Flight / Airport",
      // mode-dependent, exactly one of these three groups:
      "airport": "…", "airportCode": "PNQ", "preferredTime": "HH:MM",
      "railwayStation": "…", "trainClass": "…",
      "pickupAddress": "…", "pickupDateTime": "…", "vehiclePreference": "…"
    },
    "travellers": { "rooms":0,"male":0,"female":0,"totalAdults":0,"children":0,"infants":0,"extraBeds":0 },
    "specialAssistance": { "required": false, "types": [], "passengerCount": 0, "notes": null },
    "itinerary": [ { "destination": "…", "city": "…", "nights": 0, "dayNumber": 1 } ],
    "notes": "…"
  },
  "bookingDate": "yyyy-MM-dd",
  "customerAmount": 0, "vendorCost": 0, "paidAmount": 0,
  "services": ["Hotel","Flight"],
  "assignedUserId": "…uuid…",
  "leadPublicId": "…uuid…"
}
```

### Required backend changes

1. **`BookingCustomerRequest`** (new) — `customerPublicId` (UUID) | `newCustomer`
   (`CreateCustomerRequest`) | `sync` (`CustomerSyncRequest`). Class-level `@AssertTrue` so
   "exactly one mode" is a single clear 400.
2. **`CustomerSyncRequest`** (new) — `name`, `email`, `birthday`, `anniversary`. **No `phone` field
   at all** — that makes the "never overwrite phone" rule structural rather than a code comment.
3. **`CustomerService.resolveOrCreate(...)`** — put the logic in `CustomerServiceImpl`, not in
   booking, so the duplicate + trashed-restore rules stay in one place. Re-implementing them in
   booking will 500 against `uq_customers_phone_tenant`.
4. **`CreateBookingRequestDTO`** — replace `Long customerId`/`customerName` with the nested block;
   accept `leadPublicId` (UUID) instead of `leadId` (Long); accept `tripSnapshot`.
5. **`BookingResponseDTO`** — `customerId` (`:27`), `destinationId` (`:31`), `leadId` (`:34`) are
   internal `Long`s despite the comment at `:22` claiming otherwise. Replace with UUIDs.
   This also **unblocks the Booking Details customer card**, which currently cannot fetch the
   profile because `GET /api/customers/{id}` takes a UUID and the booking only exposes a Long.
6. **`tripSnapshot`** — decide where it lands (see §8, open decision 2). Simply accepting and
   ignoring it is acceptable for a first cut *provided* that is a deliberate, recorded choice.

All lookups tenant-scoped (`findByPublicIdAndTenantIdAndDeletedAtIsNull`), never bare `findById`.

---

## 7. Work item 5 — Booking Details

The screen currently fans out to 3 calls (`getById`, `getServices`, `getPayments`) plus on-demand
document/quotation/reminder reads. That works. `GET /api/bookings/{publicId}/detail` is a
consolidation, **not a blocker** — deprioritise it against §6.

Two things that *are* blockers:

**a) Margin masking does not exist anywhere.** Grep returns no runtime masking of
`vendorCost`/`netProfit`.

- `BookingMapper.toSummary()` / `BookingSummaryDTO` — the "AGENT, no sensitive financials" view —
  have **zero callers repo-wide**. The mapper's own comment instructs the service to choose between
  `toResponse()` and `toSummary()`; the service never does. Every read funnels through
  `BookingServiceImpl.toResponse()` (`:1023`), which always emits `vendorCost`,
  `totalInternalCosts` and `netProfit`.
- `BookingStatsResponseDTO:28` says fields are populated "only when caller has
  `booking:profit:read`" — **that permission does not exist anywhere in the codebase.**
- Net effect: **any role holding `BOOKING_READ` — including `SUB_AGENT` — currently sees full
  agency margin** on `GET /api/bookings` and `GET /api/bookings/{publicId}`, and a per-line
  `vendorCost` on `/services`.

The FE now hides these (`canSeeMargin` in `BookingDetails.jsx`), but **that is a UI courtesy, not a
security boundary** — the numbers are one Network tab away. Real fix: add a `BOOKING_PROFIT_READ`
constant to `permission/enums/Permission.java`, and a hand-written whitelist mapper following the
`portal/booking/PortalBookingMapper` precedent. `BookingSummaryDTO` cannot simply be "re-enabled" —
it was never wired and the permission it implies was never created.

**b) `BookingReminder` has no FK to `Booking`.** It matches on the `bookingCode` **string**, so a
reminder can exist for a code no booking has. Also `/api/booking-reminders/**` has **no
`@PreAuthorize` at all** and returns raw un-enveloped DTOs carrying the numeric `Long id` — the FE's
send-now action posts that Long back. Worth tightening independently of this work.

---

## 8. Open decisions

1. **Risk #2 — GST invoices snapshot customer identity.** `POST /{id}/gst-invoices` issues a filed
   tax document. Editing the customer afterwards can desync it. Before building §6's `sync` path,
   choose: **(a)** block customer sync on bookings with an issued GST invoice, or **(b)** snapshot
   invoice-time identity onto the invoice so later edits cannot touch it. (b) is the better model;
   (a) is faster. **This needs an answer before §6 ships.**
2. **Where does `tripSnapshot` live?** `Booking` stores no traveller data at all today — no pax, no
   departure, no itinerary. Options: a `booking_trip_snapshot` secondary table (mirrors the `Vendor`
   pattern), a JSONB column, or accept-and-ignore for now. Note the same facts also want to exist on
   `Lead` (§3), so pick one home and have conversion copy it.
3. **`assignedUserId` is only a suggestion on create.** `leadAssignmentService.assignForCreate()`
   (`LeadServiceImpl:121-132`) force-assigns agents/staff/sub-agents to themselves regardless of the
   posted value, while `updateLead` (`:301`) honours it verbatim. Intentional? The FE dropdown
   implies otherwise.
4. **Blank `leadSource`/`leadType` produce an unusable 400.** Neither FE `register()` carries a
   `required` rule, and `LeadSource.fromValue("")` throws `IllegalArgumentException` →
   `HttpMessageNotReadableException` *before* bean validation runs. So `@NotNull("Lead source is
   required")` can never fire and the response carries no `fieldErrors` for the FE to place inline.
   Fix on either side — returning `null` from `fromValue` on blank input is the cleaner one.

---

## 9. Tests to add

- **`LeadMapperTest`** — the 20 new fields survive `toEntity` → `toResponse`.
- **`LeadServiceImplTest`** — *separately*, that `updateLead` writes all 20. The mapper test cannot
  catch the update path because `updateLead` bypasses the mapper (§3.5).
- **`BookingServiceImplTest`** — conversion copies `birthday`/`anniversary` on create; does **not**
  overwrite a non-empty customer field on reuse; **never** writes `phone`.
- **`CustomerServiceImplTest`** — `resolveOrCreate` across all three modes, including trashed-restore.
- **A guard test for `FAIL_ON_UNKNOWN_PROPERTIES`.** It is disabled today, which is what makes §3 a
  silent loss rather than an outage. If anyone ever sets
  `spring.jackson.deserialization.fail-on-unknown-properties=true`, 21 keys turn every lead
  create/update into a 400 instantly.

---

## 10. Suggested order

1. **§6** — unbreak Create Booking (it is 400ing in production), after settling decision 1.
2. **§3** — stop the silent lead data loss. Write the DDL by hand; `ddl-auto` is `validate`.
3. **§5** — conversion carries the profile. Independent of everything else, high value.
4. **§7a** — margin masking; the FE gate is cosmetic until this lands.
5. **§4** — the `LeadType` vocabulary change, in its own deploy window.
6. **§7** aggregate endpoint — last; the screen works without it.

Backend before frontend in every step. The FE is already ahead in all six.
