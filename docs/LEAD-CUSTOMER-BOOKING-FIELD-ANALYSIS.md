# Lead / Customer / Booking — Field Ownership Analysis & Implementation Plan

> **Analysis only — no code was changed.** All line references verified against the
> working tree on 2026-07-29 (branch `master`).
>
> Supersedes `docs/LEAD-BOOKING-CUSTOMER-FLOW.md`, whose line references have drifted
> (it cites `Booking.java:45` for `customer_id`, which is now line 65; `BookingServiceImpl.create()`
> at `:88`, now `:134`) and whose body content is accidentally duplicated. Delete or
> regenerate that file.

---

## 0. Headline findings

Five things materially change the shape of the work you asked about.

1. **`Lead.birthDate` is collected and then thrown away.** Nothing reads it. It is written
   (`LeadMapper.java:75`, `LeadServiceImpl.java:294`) and echoed back (`LeadMapper.java:129`),
   but the lead→booking conversion does **not** copy it into `Customer.birthday`
   (`BookingServiceImpl.java:370-377`). The birthday/anniversary marketing automations read
   **only** `Customer.getBirthday()` / `getAnniversary()` (`AutomationRunnerService.java:51`,
   `AutomationServiceImpl.java:138-140`) and segments filter only `Customer.anniversary`
   (`SegmentEvaluator.java:78`). **Adding `anniversary` to Lead without also fixing the
   conversion copy would just duplicate an existing dead end.**

2. **Conversion copies exactly three fields into Customer** — `name`, `phone`, `email`
   (`BookingServiceImpl.java:370-377`). Every other lead field is lost.

3. **When a customer already exists, conversion syncs nothing.** `resolveOrCreateCustomer`
   returns the existing row untouched (`BookingServiceImpl.java:351-355`) — even the customer
   name the agent just edited in the convert form is discarded. This is precisely the business
   rule you want to introduce.

4. **There is no Create Booking form in the frontend.** `bookingService.create` exists
   (`bookingService.js:27`) but has **zero call sites**. The sidebar's "Add New Booking" links
   to `/allleads` (`Sidebar.jsx:339`). Lead conversion is the only path that creates a booking
   in the UI. Any "Create Booking supports inline customer" work is **greenfield UI**, not a
   modification.

5. **`LeadType` is already `Fresh / Hot / Warm / Cold`** (`LeadType.java:8-11`, plus the
   uncommitted `deploy/migrate-lead-types-hot-warm-cold.sql`). That **is** lead priority.
   Adding a separate `priority` field would create two competing concepts. Recommendation:
   **do not add `priority`.**

---

## 1. Create Lead — current flow

| Layer | File |
|---|---|
| Entity | `lead/entity/Lead.java` (179 lines) |
| Request DTO | `lead/dto/CreateLeadRequestDto.java` |
| Response DTO | `lead/dto/LeadResponseDto.java` |
| Mapper | `lead/mapper/LeadMapper.java` — **hand-written `@Component`, not MapStruct** |
| Controller | `lead/controller/LeadController.java` |
| Service | `lead/service/LeadServiceImpl.java` (765 lines) |

`LeadMapper.toEntity(dto, actor)` (`LeadMapper.java:53`) is the **single construction point**
for a Lead — both the human path and the machine ingest path go through it, which is what keeps
`origin`, `sourceIntegrationId` and `phoneNormalized` impossible to forget. Any new Lead field
must be added there or it will silently never persist.

### Current Lead fields

| Field | Type | Column | Null | Notes |
|---|---|---|---|---|
| `customerName` | String | `customer_name` | NO | len 150 |
| `phone` | String | `phone` | NO | len 20 |
| `phoneNormalized` | String | `phone_normalized` | yes | E.164 shadow key, write-only today |
| `email` | String | `email` | yes | NOT NULL dropped by hand (`indexes.sql:469`) |
| `leadSource` | enum | `lead_source` | NO | 25 constants |
| `origin` | enum | `origin` | yes | MANUAL/INTEGRATION/SYSTEM, from actor not request |
| `sourceIntegrationId` | Long | `source_integration_id` | yes | logical FK |
| `leadType` | enum | `lead_type` | NO | **Fresh/Hot/Warm/Cold** = priority |
| `leadStage` | enum | `lead_stage` | NO | 8 constants |
| `assignedUser` | `@ManyToOne User` | `assigned_user_id` | NO | the only real FK on Lead |
| `birthDate` | LocalDate | `birth_date` | yes | **zero consumers** |
| `travelDate` | LocalDate | `travel_date` | yes | |
| `budget` | BigDecimal | `budget` | yes | 15,2 |
| `departCountry` | String | `depart_country` | yes | len 100 |
| `departCity` | String | `depart_city` | yes | len 100 |
| `rooms/adults/children/infants/extraBeds` | Integer | — | yes | pax block |
| `services` | List\<String\> | `lead_services` join table | — | `@ElementCollection` EAGER |
| `notes` | String | `notes` TEXT | yes | |
| `convertedAt` | LocalDateTime | `converted_at` | yes | conversion traceability |
| `convertedBookingPublicId` | UUID | `converted_booking_public_id` | yes | |
| `itinerary` | List\<LeadItinerary\> | `lead_itinerary` | — | destination/city/nights/dayNumber |

### Missing from Lead

| Field | Status |
|---|---|
| **anniversary** | **Genuinely absent.** Customer has it; Lead does not. |
| **departureMode** (flight/train/bus/car) | **Genuinely absent** anywhere in the codebase. |
| departurePoint | **Present** as `departCountry` + `departCity` (free-text). |
| **followUpDate** | Absent on `Lead`; **exists on `LeadLog`** (`LeadLog.java:46-47`) and creates a `Reminder` (`LeadLogServiceImpl.java:78,193`). Only capturable *after* the lead exists, via Add Log. |
| **priority** | Absent — **and should stay absent**, `LeadType` already fills this role. | HOT,COLD,WARM,FRESH
| returnDate / total nights | Absent; nights exist per itinerary leg only. | -- DECISION PENDING

---

## 2. Create Customer — current flow

| Layer | File |
|---|---|
| Entity | `customer/entity/Customer.java` (142 lines) |
| DTOs | `CreateCustomerRequest.java`, `UpdateCustomerRequest.java`, `CustomerResponse.java` |
| Mapper | `customer/mapper/CustomerMapper.java` |
| Controller | `customer/controller/CustomerController.java` — **fully `UUID`/publicId keyed** |
| Service | `customer/service/CustomerServiceImpl.java` (484 lines) |

Profile fields: `name`, `phone`, `email`, `alternatePhone`, `type`, `commPref`, `tier`,
`status`, `city`, `state`, `address`, `pincode`, **`birthday`**, **`anniversary`**,
`passportNo`, `panNo`, `aadharNo`, `documents`, `notes`, plus `customerCode` and
`createdFromLeadId` provenance (`Customer.java:81-82`).

Booking metrics (`bookings`, `spent`, `lastBooking`) are **computed live** from the bookings
table and never persisted (`Customer.java:22-26`, `CustomerResponse.java:64-73`) — so a booking
write-back does **not** need to maintain counters.

**Duplicate rule:** phone is the per-tenant natural key. An active duplicate throws
`DuplicateCustomerException`; a trashed one throws a structured `RestoreAvailableException` so
the UI can offer Restore (`CustomerServiceImpl.create()`).

**Absent from Customer:** nationality, gender, country (only city/state/pincode), GST number,
company name, emergency contact, separate WhatsApp number.

---

## 3. Create Booking — current flow

| Layer | File |
|---|---|
| Entity | `booking/entity/Booking.java` (198 lines, `@Audited`) |
| Request DTO | `booking/dto/request/CreateBookingRequestDTO.java` |
| Response DTO | `booking/dto/response/BookingResponseDTO.java` |
| Mapper | `booking/mapper/BookingMapper.java` |
| Controller | `booking/controller/BookingController.java` |
| Service | `booking/service/BookingServiceImpl.java` (1199 lines) |

**It requires a pre-existing customer and cannot create one.**
`CreateBookingRequestDTO.customerId` is `@NotNull Long` (`:20-21`); `create()` looks it up and
404s if missing (`BookingServiceImpl.java:148-151`). It never writes to Customer.

**Customer data on Booking is a one-time snapshot:** `customerNameSnapshot`
(`Booking.java:68-69`, NOT NULL). Name only — no phone, no email. It is set from
`customer.getName()` on direct create (`:153`) but from `request.getCustomerName()` on
conversion (`:276`), so the two paths can disagree with the Customer row.

**Booking stores no traveller data at all** — no pax counts, no passenger names, no passport
data, no departure city/mode, no itinerary. Those live on Lead and Quotation only.

**Internal `Long` IDs are exposed** despite the comment at `BookingResponseDTO.java:22`
claiming otherwise: `customerId` (`:27`), `destinationId` (`:31`), `leadId` (`:34`).

---

## 4. Lead → Booking conversion

`POST /api/leads/{publicId}/convert-to-booking` → `BookingServiceImpl.convertLeadToBooking()`
(`:224-326`).

Customer resolution is `resolveOrCreateCustomer()` (`:340-382`), in strict order:

1. **Live customer with the normalised phone → reused as-is, nothing synced** (`:351-355`).
2. Trashed customer with that phone → restored and reused (`:358-367`).
3. Otherwise → **new Customer built from `name`, `phone`, `email` only** (`:370-377`),
   stamped with `createdFromLeadId` for cancel-cleanup.

A blank lead phone is rejected up front (`:342-347`).

### Data-loss table

| Lead field | → Customer | → Booking | Lost |
|---|---|---|---|
| customerName | ✅ (create only) | ✅ `customerNameSnapshot` | — |
| phone | ✅ (create only) | ❌ | — |
| email | ✅ (create only) | ❌ | — |
| **birthDate** | ❌ | ❌ | **LOST** |
| budget | ❌ | ❌ (replaced by `customerAmount`) | **LOST** |
| departCountry / departCity | ❌ | ❌ | **LOST** |
| adults / children / infants / rooms / extraBeds | ❌ | ❌ | **LOST** |
| notes | ❌ | ❌ | **LOST** |
| itinerary | ❌ | ❌ (destination flattened to a string) | **LOST** |
| services | ❌ | ✅ from request | — |
| leadSource / leadType | ❌ | ❌ | **LOST** |

Lead side effects are correct: stage → `CONVERTED`, `convertedAt`, `convertedBookingPublicId`
(`:313-316`); the lead row is never deleted. A duplicate active booking per lead is blocked with
a 409 (`:240-247`).

---

## 5. Field ownership recommendation

| Concept | Lead today | Customer today | Booking today | **Recommended owner** |
|---|---|---|---|---|
| Name | ✅ `customerName` | ✅ `name` | snapshot | **Overlap — SYNC** |
| Phone | ✅ | ✅ (natural key) | ❌ | **Overlap — match key, never overwrite** |
| Email | ✅ | ✅ | ❌ | **Overlap — SYNC (fill-if-empty)** |
| Birthday | ✅ unused | ✅ used | ❌ | **Overlap — PREFILL at conversion** |
| **Anniversary** | ❌ | ✅ used | ❌ | **Add to Lead → PREFILL at conversion** |
| Address / city / state / pincode | ❌ | ✅ | ❌ | **Customer-only** |
| Passport / PAN / Aadhar | ❌ | ✅ | ❌ | **Customer-only** |
| Loyalty tier / status / type | ❌ | ✅ | ❌ | **Customer-only** |
| Budget | ✅ | ❌ | ❌ | **Lead-only** (an aspiration, not a fact) |
| Lead source / type / stage | ✅ | ❌ | ❌ | **Lead-only** |
| Pax counts | ✅ | ❌ | ❌ | **Lead-only now; Booking-only later** (per-trip) |
| Departure country / city | ✅ | ❌ | ❌ | **Lead → Booking** (per-trip) |
| **Departure mode** | ❌ | ❌ | ❌ | **Add to Lead** (per-trip enquiry fact) |
| Travel date | ✅ | ❌ | ✅ | **Overlap — PREFILL** |
| Follow-up date | ❌ (on `LeadLog`) | ❌ | ❌ | **Lead-only — surface the existing LeadLog mechanism** |
| Priority | via `LeadType` | ❌ | ❌ | **Lead-only — already exists, add nothing** |
| Amounts / GST / TCS / profit | ❌ | ❌ | ✅ | **Booking-only** |

**SYNC vs PREFILL.** Only three fields should ever write back to Customer from a booking, and
each needs an explicit conflict rule:

| Field | Rule | Why |
|---|---|---|
| `name` | last-write-wins | The agent has just confirmed it with the customer. |
| `email` | **fill-if-empty only** | A thin booking form must not erase a richer profile. |
| `birthday`, `anniversary` | **fill-if-empty only** | Same; these feed marketing automations. |

Phone must **never** be overwritten — it is the per-tenant natural key and the partial unique
index `uq_customers_phone_tenant` will reject a collision.

### Should Anniversary go on Create Lead?

**Yes — but only together with the conversion copy.** Justification: an anniversary is a *travel
trigger* ("planning our 10th anniversary"), so it is legitimate enquiry data, and it is exactly
symmetric with `birthDate`, which the form already collects. It stays a two-field addition, so
Lead does not drift toward a full profile form. **The value is entirely in the hand-off** — the
marketing automations that consume it read `Customer`, so without step 2 below you would be
adding a second field that nothing reads.

---

## 6. Required vs optional

### Create Lead

| Field | Now | Recommended |
|---|---|---|
| customerName | required | required |
| phone | required | required |
| **email** | **`@NotBlank` (BE) but optional (FE)** | **optional — fix the mismatch** |
| leadSource / leadType / leadStage | required | required (default stage server-side) |
| assignedUserId | required | required |
| birthDate, **anniversary**, travelDate, budget, departCountry/City, **departureMode**, pax, services, notes, itinerary | optional | optional |

> **Live bug.** `CreateLeadRequestDto.java:31` marks `email` `@NotBlank`, the form does not
> require it (`LeadInformation.jsx:283-290`), and `transformFormData` sends `""` when blank
> (`leadService.js:8`). A lead with no email is a guaranteed 400 on the human path. The entity
> and `indexes.sql:469` both deliberately allow NULL. **Fix by relaxing the DTO**, not the form.

### Create Customer
Required: `name`, `phone`. Everything else optional. **No change recommended.**

### Create Booking (recommended)
Required: customer reference (one of three forms, below), `destination`, `travelDate`,
`customerAmount`, `vendorCost`. Optional: `bookingDate`, `paidAmount`, `services`,
`assignedUserId`, `leadId`, `customerSync`.

---

## 6.1 Final form layouts — exactly which field goes on which form

One rule per form:

- **Create Lead** = *"who enquired, and what trip are they asking about"* — enquiry data only.
- **Create Customer** = *"who this person is"* — the full, durable profile.
- **Create Booking** = *"what was actually sold"* — per-trip commercial facts, plus a customer
  resolve/create/sync block.

Legend: `*` = required · **NEW** = does not exist today · ~~strikethrough~~ = must **not** be
added to this form.

---

### A. CREATE LEAD form

**Panel 1 — Lead Information** (`LeadInformation.jsx`)

| Field | Req | Status |
|---|---|---|
| Customer Name | `*` | exists |
| Phone | `*` | exists |
| Email | optional | exists — **make optional in the DTO** (`CreateLeadRequestDto.java:31`) |
| Budget (₹) | optional | exists |
| Lead Source | `*` | exists — server-driven list |
| Lead Type (Fresh/Hot/Warm/Cold) | `*` | exists — **this is the priority field**; refresh the stale options at `LeadInformation.jsx:13` |
| Lead Stage | `*` | exists — **add `Reopened`** (`:14-17`) |
| Assign To | `*` | exists |
| Birth Date | optional | exists |
| **Anniversary** | optional | **NEW** |

**Panel 2 — Travel Details** (`TravelDetails.jsx`)

| Field | Req | Status |
|---|---|---|
| Travel Date | optional | exists |
| Departure Country | optional | exists |
| Departure City | optional | exists |
| **Departure Mode** (Flight/Train/Bus/Own Car/Cruise/Other) | optional | **NEW** |
| Rooms · Adults · Children · Infants · Extra Beds | optional | exists |
| Services (chips) | optional | exists |
| Itinerary legs (destination/city/nights) | optional | exists |
| Notes | optional | exists |

**Deliberately NOT on Create Lead** — these belong to the profile, and adding them turns the
enquiry form into a customer form:
~~Address · City · State · Pincode~~ · ~~Passport No~~ · ~~PAN~~ · ~~Aadhar~~ ·
~~Alternate Phone~~ · ~~Customer Type~~ · ~~Loyalty Tier~~ · ~~Communication Preference~~ ·
~~Documents~~ · ~~GST No~~ · ~~Customer Amount / Vendor Cost / GST / TCS~~ ·
~~a separate Priority field~~ (Lead Type already is one).

**Follow-up date** stays off this form — it is captured through Add Lead Log, which already
creates the Reminder (`LeadLog.java:46-47`, `LeadLogServiceImpl.java:193`). See Phase 5.

---

### B. CREATE CUSTOMER form

Already complete — **no field changes recommended.** Listed so the three forms can be compared.

| Group | Fields | Req |
|---|---|---|
| Identity | Name | `*` |
| Contact | Phone `*` · Email · Alternate Phone | phone only |
| Classification | Customer Type · Loyalty Tier · Status · Communication Preference | all optional (service defaults INDIVIDUAL / BRONZE / ACTIVE) |
| Address | Address · City · State · Pincode | optional |
| Important dates | **Birthday · Anniversary** | optional — these two drive the marketing automations |
| Documents | Passport No · PAN No · Aadhar No · Documents (free text) | optional |
| Free text | Notes | optional |

**Deliberately NOT on Create Customer:** ~~Budget~~ · ~~Lead Source / Type / Stage~~ ·
~~Travel Date~~ · ~~pax counts~~ · ~~Departure Mode / City~~ · ~~any booking amount~~ —
all of these are per-enquiry or per-trip, not per-person.

*Optional future additions (not required for this work):* GST No / company name for corporate
customers, nationality, gender.

---

### C. CREATE BOOKING form — **new page**, does not exist today

**Block 1 — Customer** (this block is the whole point of the change)

| Step | Field | Req | Behaviour |
|---|---|---|---|
| 1 | **Search customer by phone** | `*` | Calls the existing `GET /api/customers/search?phone=` |
| 2a | *Found* → read-only card (name, code, city, tier, past bookings) | — | Plus an **"Edit details"** toggle |
| 2b | Edit-details fields: Name · Email · Birthday · Anniversary | optional | Sent as `sync`. **Name = last-write-wins; the rest fill-if-empty; phone is never overwritten** |
| 2c | *Not found* → **inline create**: Name `*` · Phone `*` · Email · City | name+phone | Sent as `newCustomer`; reuses the duplicate / trashed-restore rules |

**Block 2 — Booking details**

| Field | Req | Notes |
|---|---|---|
| Destination | `*` | free text + snapshot |
| Travel Date | `*` | `@FutureOrPresent` |
| Booking Date | optional | defaults to today server-side |
| Customer Amount (₹) | `*` | must be > 0 |
| Vendor Cost (₹) | `*` | must be > 0 |
| Advance Collected (₹) | optional | defaults 0; writes a ledger row |
| Services (chips) | optional | |
| Assigned To | optional | defaults to current user |
| Link to Lead | optional | when started from a lead |

**Read-only preview** (never an input — the server computes every rupee): GST · TCS ·
Total Payable · Net Profit · Payment Status.

**Deliberately NOT on Create Booking:** ~~Budget~~ · ~~Lead Source / Type / Stage~~ ·
~~Passport / PAN / Aadhar~~ · ~~Loyalty Tier~~ · ~~full address~~ · ~~GST / TCS / Total /
Net Profit as inputs~~ · ~~internal `customerId`~~ (use `customerPublicId` or phone).

---

### D. Side-by-side summary

| Field | Lead | Customer | Booking |
|---|---|---|---|
| Name | `*` | `*` | via resolver (`*`) |
| Phone | `*` | `*` | via resolver (`*`) |
| Email | optional | optional | sync only |
| Birthday | optional | optional | sync only |
| **Anniversary** | **NEW** optional | optional | sync only |
| Budget | optional | ✗ | ✗ |
| Lead Source / Type / Stage | `*` | ✗ | ✗ |
| Travel Date | optional | ✗ | `*` |
| Departure Country / City | optional | ✗ | ✗ |
| **Departure Mode** | **NEW** optional | ✗ | ✗ |
| Pax (rooms/adults/children/infants) | optional | ✗ | ✗ |
| Services | optional | ✗ | optional |
| Itinerary | optional | ✗ | ✗ |
| Notes | optional | optional | ✗ |
| Address / City / State / Pincode | ✗ | optional | ✗ |
| Passport / PAN / Aadhar / Documents | ✗ | optional | ✗ |
| Customer Type / Tier / Status / Comm. Pref | ✗ | optional | ✗ |
| Alternate Phone | ✗ | optional | ✗ |
| Customer Amount / Vendor Cost | ✗ | ✗ | `*` |
| Advance Paid | ✗ | ✗ | optional |
| GST / TCS / Total / Net Profit | ✗ | ✗ | read-only |
| Assigned To | `*` | ✗ | optional |

---

## 7. Recommended Create Booking API

Replace `CreateBookingRequestDTO.customerId` with a nested resolver block. Precedence:
`customerPublicId` → `customerPhone` → `newCustomer`; exactly one must be present.

```java
package com.crm.travelcrm.booking.dto.request;

/** Resolves the booking's customer. Exactly one of the three modes must be supplied. */
@Getter @Setter
public class BookingCustomerRequest {

    /** Mode A — an existing customer, by publicId. Never the internal Long id. */
    private UUID customerPublicId;

    /** Mode B — an existing customer, by phone (the per-tenant natural key). */
    @Pattern(regexp = "^\\+?[0-9\\s()-]{7,20}$", message = "Invalid phone number")
    private String customerPhone;

    /** Mode C — create a customer inline. Ignored when A or B resolves a row. */
    @Valid
    private CreateCustomerRequest newCustomer;

    /**
     * Optional profile corrections to apply to the resolved customer before the booking is
     * saved. Applied under fill-if-empty rules except `name` (last-write-wins).
     * `phone` is never overwritten.
     */
    @Valid
    private CustomerSyncRequest sync;
}
```

Validation rules:
- Exactly one of `customerPublicId` / `customerPhone` / `newCustomer` — enforce with a
  class-level `@AssertTrue` so the 400 is a single clear message.
- Mode B that finds no live customer: **fall through to `newCustomer` if present, else 404.**
- All lookups tenant-scoped (`findByPublicIdAndTenantIdAndDeletedAtIsNull` /
  `findByPhoneAndTenantIdAndDeletedAtIsNull`) — never bare `findById`.
- Mode C must reuse `CustomerServiceImpl`'s duplicate + trashed-restore rules rather than
  re-implementing them, or it will 500 against `uq_customers_phone_tenant`.

Also fix `BookingResponseDTO`: replace `customerId`/`destinationId`/`leadId` `Long`s with
`customerPublicId`/`leadPublicId` UUIDs (`:27,31,34`).

---

## 8. Database / migration needs

`ddl-auto=update` in both dev and prod (`application.properties:57`,
`application-prod.properties:47`). It **adds** columns automatically but **never** relaxes a
NOT NULL and **never** alters an existing CHECK constraint — that is what `db/indexes.sql` is
for. The `Lead.origin` block (`indexes.sql:428-436`) is the exact template to copy.

| Table | Column | Type | Null | Handled by ddl-auto? | Extra |
|---|---|---|---|---|---|
| `leads` | `anniversary` | `date` | yes | ✅ yes | none — no index, no CHECK |
| `leads` | `departure_mode` | `varchar(20)` | yes | ✅ adds column | ⚠️ **CHECK constraint required** |

```sql
-- db/indexes.sql — new block, mirrors the LeadOrigin pattern at :428-436
ALTER TABLE leads DROP CONSTRAINT IF EXISTS leads_departure_mode_check;
ALTER TABLE leads ADD CONSTRAINT leads_departure_mode_check
        CHECK (departure_mode IN ('FLIGHT','TRAIN','BUS','OWN_CAR','CRUISE','OTHER'));
-- Deliberately allows NULL: a CHECK passes on NULL, so rows created between the column
-- arriving and this file running are not rejected.
```

No index is needed on either column — neither is a filter or join key. No `anniversary`
constraint is needed (a bare `date`).

**Enum touch-list for `DepartureMode`** (5 places): new enum class
`lead/enums/DepartureMode.java` (follow `LeadType`'s `@JsonValue`/`@JsonCreator` shape) →
`Lead` entity field → `CreateLeadRequestDto` → the `indexes.sql` CHECK block → the frontend
option array. Add it to `LeadMetaController` only if you want it server-driven like
`LeadSource`.

**Unrelated but pending:** `deploy/migrate-lead-types-hot-warm-cold.sql` is untracked and
**must run on the pilot DB before new lead writes**, or `leads_lead_type_check` will reject
`FRESH`/`HOT`/`WARM`/`COLD`.

---

## 9. Exact backend files to change

**Phase 1 — Lead enquiry fields**

| File | Change |
|---|---|
| `lead/enums/DepartureMode.java` | **NEW** — enum with `@JsonValue`/`@JsonCreator` |
| `lead/entity/Lead.java` | +`anniversary` (LocalDate), +`departureMode` (enum), after `birthDate` `:114` |
| `lead/dto/CreateLeadRequestDto.java` | +both fields; **relax `email` from `@NotBlank` to optional** `:31-34` |
| `lead/dto/LeadResponseDto.java` | +both fields |
| `lead/mapper/LeadMapper.java` | +both in `toEntity` `:75` and `toResponse` `:129` — **mandatory, or they never persist** |
| `lead/service/LeadServiceImpl.java` | +both in the update path near `:294` |
| `src/main/resources/db/indexes.sql` | + the `leads_departure_mode_check` block |

**Phase 2 — conversion carries profile data (the highest-value change)**

| File | Change |
|---|---|
| `booking/service/BookingServiceImpl.java` `:340-382` | `resolveOrCreateCustomer` — copy `birthDate`→`birthday`, `anniversary`, `departCity`→`city` on create; add **fill-if-empty sync** on the existing-customer branch `:351-355` |
| `booking/dto/request/LeadConversionRequestDTO.java` | +`customerEmail` — **the FE already sends it and it is silently dropped** (`ConvertToBookingModal.jsx:231`) |

**Phase 3 — Create Booking customer resolution**

| File | Change |
|---|---|
| `booking/dto/request/BookingCustomerRequest.java` | **NEW** |
| `booking/dto/request/CustomerSyncRequest.java` | **NEW** |
| `booking/dto/request/CreateBookingRequestDTO.java` | replace `Long customerId` `:20-21` with the nested block |
| `customer/service/CustomerService.java` + `Impl` | **NEW** `resolveOrCreate(BookingCustomerRequest)` — put the logic here, not in booking, so the duplicate/restore rules stay in one place |
| `booking/service/BookingServiceImpl.java` `:148-151` | delegate to the above |
| `booking/mapper/BookingMapper.java` | drop the `customerId` auto-map |
| `booking/dto/response/BookingResponseDTO.java` | `:27,31,34` Long → UUID |

---

## 10. Exact frontend changes

| File | Change |
|---|---|
| `features/leads/components/LeadInformation.jsx` | + Anniversary date input beside Birth Date (`:466-472`); **update `LEAD_TYPES` `:13`** — still the stale `["Fresh Lead","Repeat Customer","Corporate","VIP"]`; **add `"Reopened"` to `LEAD_STAGES` `:14-17`** |
| `features/leads/components/TravelDetails.jsx` | + Departure Mode select beside `departCity` (`:434`) |
| `features/leads/api/leadService.js` | + `anniversary`, `departureMode` to `transformFormData` `:4-36`; **send `email: undefined` not `""` when blank** `:8` |
| `features/leads/pages/CreateLead.jsx` | + both to `defaultValues` `:37` |
| `features/leads/pages/EditLead.jsx` | + both to `defaultValues` `:118` and the seed block `~:281` |
| `features/leads/components/ConvertToBookingModal.jsx` | surface birthday/anniversary read-only so the agent can confirm before they land on the Customer |
| **NEW** `features/bookings/pages/CreateBooking.jsx` | the greenfield form: customer search-by-phone → reuse / create inline / edit-and-sync. Wire `Navbar`'s `onNewBooking` and repoint `Sidebar.jsx:339` away from `/allleads` |

---

## 11. Risks

| # | Risk | Sev | Evidence | Mitigation |
|---|---|---|---|---|
| 1 | **A thin booking form overwrites a rich customer profile.** | **P1** | proposed sync path | Fill-if-empty for everything except `name`; never touch `phone`. |
| 2 | **GST invoices already issued snapshot customer data.** Editing a customer post-invoice can desync a filed tax document. | **P1** | `BookingGstInvoiceServiceImpl`, `booking/cancellation/` docs | Block customer sync on bookings with an issued GST invoice, or snapshot invoice-time identity. **Confirm before building Phase 3.** |
| 3 | Conversion writes `customerNameSnapshot` from the request `:276` but direct create uses the Customer row `:153` — the two paths can already disagree. | P2 | `BookingServiceImpl.java:153,276` | Make both read the resolved Customer after sync. |
| 4 | `TravelerAccount` links to `customerId`; a portal user's profile could change under them. | P2 | `portal/` module | Sync is additive under fill-if-empty, so low impact — but re-test portal login after Phase 3. |
| 5 | `leads_lead_type_check` will reject `FRESH/HOT/WARM/COLD` until the migration runs. | **P1 (pre-existing)** | `deploy/migrate-lead-types-hot-warm-cold.sql` | Run it on the pilot DB before any new lead write. |
| 6 | `DevDataSeeder` and `leadsource/gateway/LeadIngestService` construct Leads — both are already modified in the working tree. | P2 | git status | New nullable fields are safe, but recompile both. |
| 7 | Marketing segments query `Customer.anniversary`; back-filling it from leads changes who matches an existing segment. | P3 | `SegmentEvaluator.java:78` | Expected behaviour — mention it in the release note. |
| 8 | `AllLeads.jsx` fetches `size=100` and filters client-side; new fields do not change this, but the cap still silently truncates. | P3 | `leadService.js:45` | Out of scope; do not let it grow. |

**Deploy order: backend before frontend** in every phase — the FE sends new fields that an old
BE would reject or drop.

---

## 12. Step-by-step plan

Each phase ends at a compiling, testable state.

**Phase 0 — unblock (do first).** Run `deploy/migrate-lead-types-hot-warm-cold.sql` on the pilot
DB. Fix the `email` `@NotBlank`/form mismatch (`CreateLeadRequestDto.java:31` + `leadService.js:8`).
Refresh `LEAD_TYPES` and `LEAD_STAGES` in `LeadInformation.jsx:13-17`. *No schema change.*

**Phase 1 — Lead gains anniversary + departureMode.** Backend entity/DTO/mapper/service +
`indexes.sql` CHECK block; then the two form fields. Ship BE first.

**Phase 2 — conversion carries the profile.** Extend `resolveOrCreateCustomer` to copy
birthday/anniversary/city on create and fill-if-empty on reuse; add the missing `customerEmail`
to `LeadConversionRequestDTO`. **This is the highest-value change and is independent of Phase 3.**

**Phase 3 — Create Booking resolves/creates/syncs the customer.** New DTOs, `resolveOrCreate`
in `CustomerServiceImpl`, `BookingServiceImpl` delegation, `BookingResponseDTO` publicId
cleanup. Resolve risk #2 before starting.

**Phase 4 — the Create Booking UI.** New page, wire `onNewBooking`, repoint the sidebar link.

**Phase 5 (optional) — follow-up date at lead creation.** Reuse the existing `LeadLog` +
`Reminder` mechanism rather than adding a `Lead.followUpDate` column: on create, if a follow-up
date is supplied, write the first `LeadLog` and let `LeadLogServiceImpl:193` raise the Reminder.
Avoids a second source of truth for the same fact.

### Tests to add
- `LeadMapperTest` — the two new fields survive round-trip (the mapper is hand-written; nothing
  catches a forgotten line today).
- `BookingServiceImplTest` — conversion copies birthday/anniversary on create; **does not**
  overwrite a non-empty customer field on reuse; never overwrites phone.
- `CustomerServiceImplTest` — `resolveOrCreate` across all three modes incl. the
  trashed-restore path.

---

## 13. Booking Details screen — what to show and how to implement it

The Booking Details screen should be an **aggregate read model**, not just
`BookingResponseDTO`. A booking is the confirmed sale, but the useful staff/customer view spans
multiple sources:

- `Booking` = booking code, status, travel date, commercial totals and source quotation link.
- `Customer` = current customer profile and contact details.
- `Quotation` = accepted itinerary/package details when `sourceQuotationPublicId` exists.
- `BookingServiceItem` = post-booking operational service lines, vendors, PNR/confirmation
  numbers and per-service vouchers.
- `BookingPayment` = payment ledger.
- `BookingReminder` = booking follow-up/payment/travel reminders, currently keyed by booking code.
- Booking document services = invoice, voucher, service voucher, cancellation/refund documents.

### What should show on Booking Details

| Section | Fields | Source today |
|---|---|---|
| Booking summary | booking code, status, payment status, booking date, travel date, destination, assigned user, created by/created at | `Booking` / `BookingResponseDTO` |
| Source traceability | source lead public id, source quotation public id, quotation title/version/stage | `Booking.sourceLeadPublicId`, `Booking.sourceQuotationPublicId`, `Quotation` |
| Customer profile | name, customer code, phone, email, alternate phone, city, state, address, birthday, anniversary, customer type, tier, notes | `Customer`; fallback to `Booking.customerNameSnapshot` only if customer row is missing |
| Commercial summary | customer amount, GST, TCS, total payable, paid amount, pending amount, payment status | `Booking` |
| Internal finance | vendor cost, net profit, refunded amount | `Booking`; show only to roles that may see margins (`CRM_FULL` / manager/admin policy) |
| Quotation itinerary | title, destination, travel date, adults/children/infants, hotels, vehicles/transfers, sightseeing days, inclusions, exclusions, payment policies, cancellation policies | linked `Quotation` via `Booking.sourceQuotationPublicId` |
| Booking services | service type, title, description, service date/end date, status, vendor, confirmation/PNR, cost, vendor cost, notes | `BookingServiceItem` |
| Payments | payment date, amount, method, reference, payment type, receipt rows, refund rows | `BookingPayment` |
| Reminders | reminder type, message, travel date, reminder date, status, amount, phone, created at, send-now action | `BookingReminder` via `bookingCode` |
| Documents | invoice, voucher, service voucher per service item, GST invoice, cancellation note, refund voucher | existing booking/accounting document endpoints |
| Activity / audit | created by, created at, updated at; later status/payment history if exposed | `Booking` audit fields now; Envers/history later if needed |

### What should NOT be duplicated into Booking

Do **not** copy the full quotation itinerary into `Booking`. The current model already keeps the
correct link:

- `Booking.sourceQuotationPublicId` is stamped during lead conversion.
- The traveler portal itinerary already proves the pattern: it fetches the booking, loads the
  linked quotation, then maps a traveler-safe itinerary from quotation sections.
- Direct bookings without quotation should show `itinerary.available=false` and rely on
  `BookingServiceItem` rows for operational detail.

So the staff Booking Details page should **read itinerary from Quotation**, not add another
booking itinerary table unless the product later supports direct-booking itineraries without
quotation.

### Current gap

`GET /api/bookings/{publicId}` returns only `BookingResponseDTO`. It does not include:

- full customer profile,
- quotation itinerary,
- service item rows,
- payment ledger,
- document availability.

Those exist in separate modules/endpoints:

- `GET /api/bookings/{bookingPublicId}/services`
- `GET /api/bookings/{bookingPublicId}/payments`
- `GET /api/booking-reminders/booking/{bookingCode}`
- booking document endpoints for invoice/voucher/service voucher
- portal-only `GET /api/portal/bookings/{publicId}/itinerary`

The UI can call these separately, but a staff details page is cleaner and less fragile with one
aggregate endpoint.

### Recommended backend implementation

Add a staff-facing aggregate endpoint:

```text
GET /api/bookings/{publicId}/detail
```

Add these backend pieces:

| File | Change |
|---|---|
| `booking/dto/response/BookingDetailResponseDTO.java` | **NEW** aggregate DTO with nested booking, customer, itinerary, services, payments, documents |
| `booking/service/BookingDetailService.java` | **NEW** read-only service that assembles the aggregate |
| `booking/service/BookingDetailServiceImpl.java` | **NEW** fetches booking + customer + quotation + service items + payments |
| `booking/controller/BookingController.java` | add `GET /{publicId}/detail` under `BOOKING_READ` |
| `portal/itinerary/PortalItineraryMapper.java` | extract reusable quotation-itinerary mapping into a shared mapper, or create a staff mapper with the same no-pricing-default rule |
| `quotation/repository/QuotationRepository.java` | reuse `findByPublicIdAndTenantIdAndDeletedAtIsNull` for linked quotation lookup |
| `booking/repository/BookingServiceItemRepository.java` | reuse existing booking service item list query |
| `booking/repository/BookingPaymentRepository.java` | reuse existing payment ledger list query |
| `bookingreminder/repository/BookingReminderRepository.java` | reuse booking-code query for reminder list |
| `bookingreminder/service/BookingReminderService.java` | either expose a service method for aggregate reads or inject the repository directly into the detail service |

The service should do this:

```text
1. Load Booking by publicId with tenant + row-level visibility.
2. Load Customer by booking.customerId and tenantId.
3. If booking.sourceQuotationPublicId exists, load Quotation by publicId + tenantId.
4. Map quotation itinerary into a read-only itinerary block.
5. Load BookingServiceItem rows for operational confirmations/vendor work.
6. Load BookingPayment rows for the ledger.
7. Load BookingReminder rows by `booking.bookingCode`.
8. Build document links/availability from existing document endpoints.
9. Apply role-based masking:
   - agents/travelers: no vendorCost, no netProfit, no internal margin.
   - admin/manager/CRM_FULL: show internal finance.
```

### Suggested DTO shape

```java
public class BookingDetailResponseDTO {
    private BookingBlock booking;
    private CustomerBlock customer;
    private SourceBlock source;
    private FinancialBlock financials;
    private ItineraryBlock itinerary;
    private List<ServiceLineBlock> services;
    private List<PaymentBlock> payments;
    private List<ReminderBlock> reminders;
    private List<DocumentBlock> documents;
    private AuditBlock audit;
}
```

Important field rules:

- `customer` should come from `Customer`, not from booking snapshot, except fallback.
- `itinerary` should come from linked `Quotation`, not from `Booking`.
- `services` should come from `BookingServiceItem`, because these are post-booking operational
  confirmations.
- `reminders` should come from `BookingReminder` by `bookingCode` until that module gains a
  proper `bookingPublicId` link.
- `financials.vendorCost` and `financials.netProfit` must be hidden unless the caller has margin
  visibility.
- Do not expose internal `Long` ids in this new DTO. Use public UUIDs.

### Recommended frontend implementation

Create a real Booking Details page, for example:

```text
features/bookings/pages/BookingDetails.jsx
```

It should use tabs/sections:

| UI section | Data |
|---|---|
| Overview | booking summary, status, destination, dates, assignee |
| Customer | current customer contact/profile |
| Itinerary | quotation-derived hotels/vehicles/day-wise sightseeing |
| Services | booking service lines, vendors, PNR/confirmation, service vouchers |
| Payments | payment ledger, paid/pending, add payment action |
| Reminders | booking reminders, WhatsApp/send-now action, pending/sent/completed status |
| Documents | invoice/voucher/GST invoice/cancellation/refund downloads |
| Audit/Notes | created/updated data; future status history |
CC
Use this source order in the UI:

```text
Booking header = Booking
Customer card = Customer
Itinerary tab = Quotation via sourceQuotationPublicId
Operations tab = BookingServiceItem
Payments tab = BookingPayment
Reminders tab = BookingReminder
Documents tab = existing document endpoints
```

### Direct booking fallback

When `sourceQuotationPublicId == null`:

- show "No linked quotation itinerary";
- still show destination/travel date from `Booking`;
- show service tags from `Booking.services`;
- show detailed operational rows from `BookingServiceItem` if they exist.
- still show booking reminders by `bookingCode`.

This keeps direct booking usable without forcing a quotation.

### Why this is the right cut

Booking Details should be a view over the confirmed trip, not a new duplicate data store. The
existing code already separates the responsibilities correctly:

- Quotation owns the package/itinerary proposal.
- Booking owns the confirmed commercial record.
- Booking service items own operational fulfillment after booking.
- Booking reminders own booking follow-up communication.
- Customer owns durable profile data.

The implementation should connect these pieces in one details response and screen.
