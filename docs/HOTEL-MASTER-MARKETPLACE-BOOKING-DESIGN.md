# Hotel Master Sync + Superadmin Hotel Marketplace Booking

**Document status:** Implemented (backend) — see §21 for what is built and what is deliberately not  
**Project:** TravelCRM Backend  
**Last updated:** 2026-08-03  
**Scope:** Optional subscription-based Superadmin hotel marketplace, tenant hotel search/booking, Hotel Master synchronization, manual approval/voucher, platform earning/commission, cancellation and audit

---

## 1. Business requirement

TravelCRM mein do tarah ke hotel use cases support karne hain:

1. **Tenant ka private Hotel Master**
   - Tenant apne quotation/CRM use ke liye hotel add kar sakta hai.
   - Ye hotel sirf us tenant ko dikhega.
   - Is hotel par platform commission automatically applicable nahi hoga.

2. **Superadmin ka platform hotel catalog**
   - Superadmin contracted/bookable hotel, room, meal plan, rate aur availability manage karega.
   - Sab eligible tenants is catalog ko search kar sakenge.
   - Tenant customer ke liye hotel **platform ke through** book karega.
   - Platform is transaction par markup/commission earn karega.
   - Selected/booked hotel tenant ke existing Hotel Master ke saath synchronized rahega.

In short:

```text
Superadmin Hotel Catalog
        |
        | publish rates + inventory
        v
Tenant Marketplace Search -----> Price Hold -----> Platform Hotel Booking
        |                                                  |
        | sync/import                                      | link
        v                                                  v
Tenant Hotel Master ------------------------------> CRM Booking Service Item
                                                           |
                                                           v
                                            Platform Commission Ledger
```

---

## 2. Confirmed ownership and control rules

| Area | Superadmin | Tenant |
|---|---|---|
| Platform hotel create/update | Full control | No direct edit |
| Platform hotel publish/unpublish | Full control | No access |
| Contracted rooms, rates and inventory | Full control | Read/search only |
| Platform commercial rule | Full control | No direct edit |
| Tenant private Hotel Master | No routine edit | Create/manage with existing permissions |
| Platform hotel local projection | Source fields control | Read/use; limited local preferences |
| Marketplace booking | View/manage all tenants | Create/view own tenant bookings only |
| Platform commission ledger | Full control | Optional summary only |
| Tenant customer selling price | No forced access except audit/support | Tenant decides, subject to configured rules |

### Important distinction

`Hotel Master` and `Bookable Hotel Product` same concept nahi hain:

- **Hotel Master** contains descriptive CRM data: hotel name, city, stars, images, amenities, rooms and meal-plan labels.
- **Bookable Product** contains commercial and operational data: nightly rate, availability, stop-sell, cancellation policy, price hold and confirmation state.
- **Booking Snapshot** contains the final accepted details and money. It must not change when a master record changes later.

Hotel Master ko directly inventory/booking ledger banana future mein data corruption aur historical price changes create karega. Isliye master, marketplace aur booking ko separate aggregates rakhna mandatory hai.

---

## 3. Current backend state

The existing code already has a tenant Hotel Master, but it is not a platform marketplace.

### Existing Hotel Master

- Entity: `master.hotel.Hotel`
- Table: `hotels`
- Base class: `BaseTenantEntity`
- Every row requires `tenant_id`.
- Reads are scoped through `TenantContext` and Hibernate `tenantFilter`.
- Hotel references tenant-scoped `City`; City may reference tenant-scoped Destination/Country.
- Existing write endpoints require `PLATFORM_ADMIN` or `MASTER_MANAGE`, but the service still calls the current tenant resolver.
- Superadmin has no tenant ID, so `PLATFORM_ADMIN` authority alone does not turn the current tenant Hotel Master into a global catalog.

### Existing room and meal data

- `hotel_room_types` stores descriptive room data.
- `hotel_meal_plans` stores meal-plan name/description and one price.
- There is no date-wise room inventory.
- There is no nightly/seasonal rate calendar.
- There is no price hold.
- There is no hotel booking confirmation lifecycle.

### Existing CRM booking integration

- `BookingServiceItem` supports a free-text `serviceType`, title, dates, customer-facing cost, vendor cost and confirmation number.
- It does not link to Hotel Master or a marketplace hotel booking.
- `QuotationHotel` stores hotel information as a snapshot and has no Hotel Master foreign key.
- Existing `Booking.netProfit` represents the **tenant agency's profit**. It must not be reused as the platform's hotel commission.

### Conclusion

The current `hotels` table must remain tenant-scoped. Making `tenant_id` nullable or bypassing `TenantContext` would weaken isolation and would still not solve tenant-scoped City/Destination relationships.

---

## 4. Recommended architecture

Use a two-layer hotel model with a controlled projection:

### Layer A: existing tenant Hotel Master

Keep these existing tables and APIs:

- `hotels`
- `hotel_room_types`
- `hotel_meal_plans`
- `/api/hotels/**`

Tenant-created rows continue working as private CRM master data.

### Layer B: new platform hotel marketplace

Create global platform-owned tables under a new `hotelmarketplace` module. These entities extend `BaseEntity`, not `BaseTenantEntity`, because Superadmin owns the catalog and requires cross-tenant operations.

Suggested package structure:

```text
com.crm.travelcrm.hotelmarketplace
  catalog/
    entity/
    repository/
    service/
    controller/
    dto/
  pricing/
  inventory/
  booking/
  commission/
  sync/
  audit/
```

### Layer C: tenant Hotel Master projection

When a tenant imports or books a platform hotel, create/update a tenant-scoped local Hotel Master row. The local row keeps the existing CRM and quotation screens compatible.

The projection is one-way for platform-owned descriptive fields:

```text
Platform Hotel (source of truth)
        |
        | one-way controlled sync
        v
Tenant Hotel Master projection
```

Tenant private hotels are not overwritten or automatically converted into platform hotels.

---

## 5. Proposed data model

All money columns must use `BigDecimal` and database `numeric(15,2)` or an explicitly agreed precision. Do not use `double` for prices, commission or tax.

### 5.1 `platform_hotels`

Global, Superadmin-owned catalog record.

| Column | Type | Purpose |
|---|---|---|
| `id` / `public_id` | bigint / UUID | Internal and API identity |
| `name` | varchar | Canonical hotel name |
| `status` | enum | `DRAFT`, `ACTIVE`, `INACTIVE`, `SUSPENDED` |
| `country_code` | varchar(3) | Stable ISO-based location match |
| `state_name` | varchar | Display/location snapshot |
| `city_name` | varchar | Canonical city name |
| `city_code` | varchar | Optional stable city/airport code |
| `address` | varchar | Public hotel address |
| `stars` / `rating` | integer / decimal | Classification and rating |
| `latitude` / `longitude` | decimal | Geo search |
| `website`, `map_url` | varchar | Public links |
| `overview` | text | Public content |
| `primary_image_url` | varchar | Main image |
| `supplier_vendor_public_id` | UUID | Logical link to supplier/vendor, if used |
| `confirmation_mode` | enum | `SUPERADMIN_APPROVAL` for the first release; `INSTANT` reserved for a future automated flow |
| `catalog_version` | bigint | Incremented on every sync-relevant change |
| audit/soft-delete columns | inherited | Traceability |

Recommended uniqueness is not only hotel name. Use a dedup key based on normalized name + city + country, and still allow Superadmin to resolve legitimate duplicates manually.

### 5.2 `platform_hotel_amenities`

Platform hotel amenity list. It should be included in the descriptive sync.

### 5.3 `platform_hotel_rooms`

| Column | Purpose |
|---|---|
| `platform_hotel_id` | Parent hotel |
| `public_id` | Stable room identifier exposed through API |
| `name` | Deluxe, Suite, etc. |
| `max_adults`, `max_children`, `max_occupancy` | Occupancy validation |
| `bed_type`, `size`, `description` | Descriptive details |
| `active` | Stops future sale without deleting history |

### 5.4 `platform_hotel_meal_plans`

Use a stable code and display name:

| Code | Meaning |
|---|---|
| `EP` | Room only |
| `CP` | Breakfast |
| `MAP` | Breakfast + one major meal |
| `AP` | All major meals |
| `CUSTOM` | Hotel-specific plan |

The meal plan's price must not be treated as the complete room rate. Final pricing belongs to the rate calendar.

### 5.5 `platform_hotel_rate_plans`

Defines a sellable room + meal + occupancy combination.

| Column | Purpose |
|---|---|
| `room_id` | Sellable room |
| `meal_plan_id` | Included meal plan |
| `rate_code` | Stable Superadmin reference |
| `refundable` | Refundability summary |
| `cancellation_policy_json` or policy reference | Terms shown before booking |
| `currency` | `INR` initially; designed for more currencies |
| `active` | Sale control |

### 5.6 `platform_hotel_rate_calendar`

One row per rate plan and stay date, or one row per date range if the service expands it safely.

| Column | Purpose |
|---|---|
| `rate_plan_id` | Parent rate plan |
| `stay_date` | Night being priced |
| `supplier_net_rate` | Amount payable to hotel/supplier |
| `tax_amount` / tax breakdown | Supplier tax snapshot/configuration |
| `platform_pricing_rule_id` | Applied commercial rule |
| `closed` | Rate not sellable on this date |
| `minimum_stay` | Stay restriction |

Unique constraint: `(rate_plan_id, stay_date)`.

### 5.7 `platform_hotel_inventory`

| Column | Purpose |
|---|---|
| `room_id` | Room category |
| `stay_date` | Inventory night |
| `total_allotment` | Rooms provided to platform |
| `held_quantity` | Temporary checkout holds |
| `booked_quantity` | Confirmed/committed quantity |
| `stop_sell` | Emergency sale stop |
| `version` | Optimistic concurrency control |

Derived availability:

```text
available = total_allotment - held_quantity - booked_quantity
```

Checkout date is exclusive. A booking from 10 August to 12 August consumes inventory for the nights of 10 and 11 August.

### 5.8 `platform_hotel_commercial_rules`

Support both common commercial models:

```text
commercial_model: NET_RATE_MARKUP | HOTEL_PAID_COMMISSION
calculation_type: PERCENTAGE | FIXED
value: BigDecimal
valid_from / valid_to
hotel_id (required)
room/rate-plan filters (optional)
```

Only one highest-priority active rule may resolve for one offer. The resolved rule and calculated amount are snapshotted at price-hold time.

### 5.9 Tenant Hotel Master sync columns

Add the following fields to existing `hotels`:

```text
origin: TENANT | PLATFORM_SYNC
platform_hotel_public_id: UUID nullable
platform_catalog_version: bigint nullable
sync_status: SYNCED | STALE | LOCATION_MAPPING_REQUIRED | SOURCE_INACTIVE
last_synced_at: timestamp nullable
marketplace_bookable: boolean
```

Add a partial unique index:

```text
(tenant_id, platform_hotel_public_id)
WHERE platform_hotel_public_id IS NOT NULL AND deleted_at IS NULL
```

Add stable platform-source IDs and `active` flags to `hotel_room_types` and `hotel_meal_plans` so the sync can upsert children instead of deleting and recreating them.

### 5.10 `platform_hotel_price_holds`

A short-lived immutable price offer generated for one tenant.

Required fields:

- tenant ID and tenant snapshot
- hotel, room and rate-plan IDs
- check-in/check-out, room count and occupancy
- per-night supplier rate snapshots
- supplier total
- platform earning
- tenant payable
- currency and tax breakdown
- cancellation terms snapshot
- `expires_at`
- status: `ACTIVE`, `CONSUMED`, `EXPIRED`, `RELEASED`
- opaque quote/hold token

Default expiry recommendation: 10 minutes, configurable.

### 5.11 `platform_hotel_bookings`

This is the operational hotel booking. It is platform-owned but references a tenant explicitly, following the existing platform payment/subscription pattern.

It should extend `BaseEntity` and contain a required `tenant_id`. Tenant endpoints must always use ownership-filtered repository methods.

Core fields:

```text
tenant_id
booking_code
idempotency_key
price_hold_id
platform_hotel_id
platform_hotel_public_id
tenant_hotel_master_public_id
crm_booking_public_id
crm_service_item_public_id
hotel_name_snapshot
address/city/country snapshot
room_name_snapshot
meal_plan_snapshot
check_in / check_out / nights
rooms / adults / children
lead_guest_name and contact snapshot
supplier_confirmation_number
platform_confirmation_number
confirmation_mode
status
voucher_status
approved_by_superadmin_id
approved_at
rejection_reason
price_revision_reason
voucher_document_id
voucher_issued_at
payment_status
supplier_total
platform_earning
tenant_payable
tenant_customer_selling_amount
tenant_margin
currency
cancellation_terms_snapshot
version
```

Recommended booking states:

```text
DRAFT
ON_HOLD
PENDING_PAYMENT
REQUESTED
UNDER_REVIEW
TENANT_APPROVAL_REQUIRED
TENANT_ACCEPTED
CONFIRMED
REJECTED
CANCEL_REQUESTED
CANCELLED
FAILED
EXPIRED
```

Voucher lifecycle is tracked separately so a document-generation/upload failure cannot change a genuinely confirmed hotel's booking state:

```text
NOT_ISSUED
ISSUED
REVOKED
```

For the first release, a tenant cannot move a request to `CONFIRMED`. Only a Superadmin approval action can perform that transition.

### 5.12 `platform_hotel_commission_ledger`

Never calculate the platform's historical earnings only from current master/rule data. Use an append-only ledger.

```text
hotel_booking_id
tenant_id
entry_type: ACCRUAL | REVERSAL | ADJUSTMENT | SETTLEMENT
amount
currency
status: PENDING | EARNED | REVERSED | SETTLED
effective_date
reason
reference/idempotency key
```

The ledger is the source for platform earning reports. `Booking.netProfit` remains the tenant agency's profit source and is not mixed with this ledger.

---

## 6. Hotel Master synchronization contract

### 6.1 When sync happens

Use lazy synchronization plus version reconciliation:

1. Tenant explicitly imports a platform hotel into Hotel Master; or
2. Tenant creates a price hold/booking and the platform hotel is not linked yet; or
3. A linked hotel is read and `platform_catalog_version` is behind; or
4. A scheduled reconciliation job processes stale links.

Do not eagerly copy every platform hotel into every tenant. That would create `number of hotels × number of tenants` rows even for hotels a tenant never uses.

### 6.2 Fields copied from platform to local master

- canonical name
- stars/rating
- public address and location
- website/map/coordinates
- overview
- amenities
- public images
- room descriptions/images
- meal-plan descriptions

### 6.3 Fields that must not be copied

- supplier net rates
- platform commission rule
- supplier settlement data
- internal hotel contract documents
- internal contact notes
- inventory counters
- other tenants' selling prices or bookings

### 6.4 Tenant-local fields

The following remain tenant-specific even on a synced record:

- `isDefault`
- tenant internal notes/tags, if added
- quotation-specific selling price
- customer-facing markup
- tenant's CRM ownership/audit data

For `origin = PLATFORM_SYNC`, existing `/api/hotels/{id}` update logic must reject edits to platform-owned source fields. Tenant-local preferences should use a dedicated update DTO so a normal update cannot silently break synchronization.

### 6.5 Location mapping

Platform geography cannot directly reference the existing `City` entity because City is tenant-scoped.

Sync algorithm:

1. Match tenant Country by ISO country code.
2. Match tenant City by country + normalized city code/name.
3. If a safe match exists, use it.
4. If no match exists, create a tenant geography projection through a dedicated `HotelGeoProjectionService`, or mark the hotel `LOCATION_MAPPING_REQUIRED` for tenant/admin resolution.
5. Never attach a hotel to the first city under a destination as a fallback during marketplace sync.

The existing `HotelService.resolveCity()` is intended for tenant UI creation and must not be reused blindly for platform synchronization.

### 6.6 Update and unpublish behavior

- Platform descriptive update increments `catalog_version`.
- Linked local masters become `STALE` until synchronized.
- A platform hotel being unpublished immediately removes it from marketplace search.
- Existing confirmed bookings and their snapshots remain valid.
- The tenant's synced Hotel Master record is not hard-deleted; it becomes `SOURCE_INACTIVE` and `marketplace_bookable = false`.
- Historical quotations/bookings must never be rewritten.

---

## 7. Pricing and earning model

Use separate amounts for each party. Do not store one ambiguous `price` field.

### Net-rate/markup example

```text
Supplier/hotel net total             Rs 4,000
Platform markup/earning              Rs   400
Tenant payable to platform           Rs 4,400
Tenant customer selling amount       Rs 4,800
Tenant margin                        Rs   400
```

Formula:

```text
tenantPayable = supplierTotal + platformEarning
tenantMargin = tenantCustomerSellingAmount - tenantPayable
```

### Hotel-paid commission example

If the hotel charges a gross amount and later pays commission, keep the economic event separate:

```text
Hotel gross amount                   Rs 4,500
Hotel-paid platform commission 10%   Rs   450
Platform receivable                  Rs   450
```

Do not force both models into the same arithmetic. The commercial model determines who owes the platform and how the ledger settles.

### Tax rule

The implementation must store tax components separately and snapshot the accepted breakdown. Existing CRM `gst`/`tcs` formulas must not automatically be reused for hotel marketplace settlement. Hotel GST, platform service fee GST, tenant invoice GST and TDS implications require an accountant-approved configuration before production settlement.

---

## 8. Quotation to Superadmin-approved hotel booking flow

The first release uses a manual approval model. Tenant hotel ko directly final-confirm nahi karega. Tenant an accepted quotation se booking request submit karega; Superadmin hotel availability and price verify karke approve/revise/reject karega and voucher issue karega.

Authoritative flow:

```text
Quotation hotel proposed
        |
Customer accepts quotation and final hotel option
        |
Tenant clicks "Send Hotel Booking Request"
        |
REQUESTED -> Superadmin queue
        |
UNDER_REVIEW
        |
        +---- price changed ----> TENANT_APPROVAL_REQUIRED
        |                              |
        |                         tenant accepts
        |                              |
        |                         TENANT_ACCEPTED
        |                              |
        +------------------------------+
        |
        +---- unavailable -----------> REJECTED
        |
        +---- approved --------------> CONFIRMED
                                           |
                                     Voucher ISSUED
                                           |
                              Tenant notified/downloads voucher
```

### Step 1: Hotel selection during quotation

Quotation hotel selector contains two sources:

- **My Hotels:** the current tenant's private Hotel Master rows (`origin = TENANT`). Tenant enters commercial values manually and platform booking/commission does not automatically apply.
- **Book via Platform:** Superadmin-published, active marketplace hotels. These use marketplace rate/availability and the manual Superadmin approval flow.

Another tenant's private hotel must never appear. A linked platform hotel appears only once, not once from Hotel Master and again from the platform catalog.

The quotation must snapshot the selected option and retain stable logical links:

```text
hotelSource: TENANT_MASTER | PLATFORM
hotelMasterPublicId
platformHotelPublicId
platformRoomPublicId
platformRatePlanPublicId
hotel/room/meal-plan display snapshots
checkIn / checkOut / rooms / occupancy
quotedTenantPayable
quotedCustomerSellingAmount
cancellationSummarySnapshot
quotedAt
```

Quotation creation does not permanently book inventory and does not earn platform commission. A quotation price is indicative until the booking request recheck.

### Step 2: Customer acceptance and final hotel choice

The customer accepts the quotation. If the quotation carries multiple hotel options, tenant/customer must select exactly one final hotel option before a request can be submitted.

`Quotation ACCEPTED` is not equal to `Hotel CONFIRMED`. UI must show these statuses independently.

### Step 3: Rate, availability and eligibility recheck

When tenant clicks `Send Hotel Booking Request`, the backend must recheck:

- platform hotel is still active and published;
- selected room/rate plan is active;
- check-in/check-out and occupancy are valid;
- requested rooms are available or can be requested from the hotel;
- current tenant-payable price;
- current cancellation terms; and
- the CRM booking/quotation belongs to the current tenant.

The backend never trusts a price posted by the client. It returns a server-calculated breakdown and creates a request/price token.

### Step 4: Tenant submits the booking request

Tenant supplies guest details and either links an existing CRM booking using `crmBookingPublicId` or uses a dedicated orchestration service to create one.

The button/action is `Send Hotel Booking Request`, not `Confirm Hotel`.

Request payload includes:

```text
quotationPublicId and selected quotationHotelPublicId
server-issued price/request token
crmBookingPublicId
hotel, room and rate-plan logical references
check-in/check-out, rooms and occupancy
lead guest name, phone and email
special requests
tenant customer selling amount
tenant-generated idempotency key
```

The server creates one `platform_hotel_bookings` row with `status = REQUESTED`. Repeated submission with the same idempotency key returns the original request.

Tenant receives:

```text
Booking request submitted. Waiting for Superadmin confirmation.
```

At this point:

- the hotel is not confirmed;
- no voucher is available;
- platform commission is not earned; and
- CRM Hotel Service status remains pending/requested.

If managed platform allotment exists, the system may place a configurable request hold. If confirmation depends on an external hotel with no controlled allotment, the request carries no availability guarantee until Superadmin approval.

### Step 5: Superadmin receives and reviews the request

The request appears in the Superadmin Hotel Booking Requests dashboard with these queues:

```text
New Requests
Under Review
Tenant Approval Required
Tenant Accepted
Confirmed
Voucher Pending
Voucher Issued
Rejected
Cancelled/Expired
```

Superadmin moves `REQUESTED -> UNDER_REVIEW` and verifies availability and commercial details with the hotel/supplier.

The review screen must show:

- tenant and CRM booking reference;
- hotel, room, meal plan and stay dates;
- guest and occupancy details;
- quoted versus current tenant-payable amount;
- supplier amount and platform earning (Superadmin-only);
- cancellation terms;
- payment/credit authorization state; and
- internal notes and audit history.

### Step 6: Superadmin decision

Superadmin has three primary decisions.

#### A. Approve

Before approval, Superadmin supplies or verifies:

```text
hotel/supplier confirmation number
confirmed hotel, room and meal plan
confirmed check-in/check-out and room count
final supplier amount
final tenant-payable amount
cancellation terms
internal notes
```

The server rechecks that the approved commercial values are internally consistent and that the request is in an approvable state. Where payment/tenant credit is mandatory, authorization must succeed before confirmation.

The approval transaction:

1. atomically commits controlled inventory, when inventory is maintained by the platform;
2. consumes/releases any request hold correctly;
3. moves the hotel booking to `CONFIRMED`;
4. records `approvedBySuperadminId` and `approvedAt`;
5. creates a `PENDING` platform earning accrual;
6. creates/reuses and synchronizes the tenant Hotel Master projection;
7. creates/updates the CRM `BookingServiceItem`; and
8. queues voucher generation/upload and tenant notification.

Only Superadmin can execute this approval transition.

#### B. Request tenant approval for a revised price

If hotel availability exists but price or material terms changed, Superadmin must not silently approve the higher amount.

```text
REQUESTED/UNDER_REVIEW
    -> TENANT_APPROVAL_REQUIRED
    -> TENANT_ACCEPTED
    -> Superadmin final approval
```

The revision stores old amount, new amount, difference, reason, revised cancellation summary and expiry. Tenant may accept or decline. A decline cancels/rejects the request and releases any hold/authorization.

If tenant's quoted customer price stays fixed, the tenant can either revise the customer quotation or absorb the difference from its own margin. The backend must not make that choice automatically.

#### C. Reject

Superadmin may reject for unavailable room, hotel refusal, invalid guest/occupancy, payment failure or another recorded reason.

Rejection must:

- set `status = REJECTED` and store a user-visible reason;
- release held inventory;
- reverse/release payment or credit authorization;
- keep CRM Hotel Service pending/rejected as appropriate;
- create no earned commission; and
- notify the tenant.

### Step 7: Voucher issuance

Voucher state is separate from booking state:

```text
bookingStatus = CONFIRMED
voucherStatus = NOT_ISSUED | ISSUED | REVOKED
```

Two voucher sources may be supported:

1. **Superadmin upload:** Superadmin uploads the hotel's supplied PDF/image voucher.
2. **System generated:** Backend generates a branded booking voucher from the confirmed snapshot.

The recommended Superadmin action is `Approve & Issue Voucher` when all confirmed data is already available. A two-step `Approve` then `Issue Voucher` flow remains available when the supplier sends the document later.

Customer/tenant voucher contains:

- platform booking number;
- hotel/supplier confirmation number;
- hotel address/contact intended for guests;
- lead guest and occupancy;
- check-in/check-out;
- confirmed room and meal plan;
- cancellation/usage instructions; and
- platform support contact.

Voucher must not expose supplier net amount, platform commission, internal notes or contract data.

After issue:

```text
voucherStatus = ISSUED
voucherIssuedAt = current timestamp
```

Tenant receives a confirmation notification and an authenticated voucher download link. Voucher upload/download must follow the existing storage authorization and tenant quota/security rules.

### Step 8: CRM service-item synchronization

On request creation, create or update the hotel service as pending. On Superadmin approval, synchronize it to confirmed:

```text
serviceType = "HOTEL"
title = hotel name snapshot
serviceDate = check-in
endDate = check-out
status = PENDING before approval; CONFIRMED after approval
cost = tenant's customer selling amount
vendorCost = tenant payable to platform
confirmationNumber = platform/hotel confirmation
marketplaceHotelBookingPublicId = logical link
hotelMasterPublicId = tenant master projection link
```

The platform's supplier net and platform earning must not be written into tenant-visible `vendorCost`. From the tenant's point of view, the platform is the payable counterparty.

### Step 9: Commission timing

```text
Request submitted    -> no commission
Request rejected     -> no commission
Superadmin approved  -> PENDING accrual
Voucher issued       -> booking document available; no duplicate accrual
Policy/check-in rule -> EARNED
Cancellation/refund  -> REVERSAL or ADJUSTMENT where applicable
```

The voucher action must never create commission a second time.

### Step 10: CRM Booking linkage — RESOLVED

This supersedes the open choice in Step 4 ("either links an existing CRM booking … or uses a dedicated orchestration service to create one"). Verified against the code at `issue-fixes`.

**Decision: LINK-OR-CREATE, resolved at SUBMIT on the tenant's own request thread — never at approval.**

When the tenant presses `Send Hotel Booking Request`:

- `crmBookingPublicId` supplied → resolve tenant-scoped, apply `SubAgentScope.assertVisible`, reject `TERMINAL_STATUSES` (`BookingServiceImpl.java:105-108`), link.
- omitted → `CrmBookingLinkPort.createForMarketplace(seed)` synthesizes a `CreateBookingRequestDTO` and calls the existing `bookingService.create()` (`BookingServiceImpl.java:147-239`), which joins the same transaction.

The SuperAdmin approval thread then **never creates a Booking**. It flips the platform row and idempotently upserts the hotel's `BookingServiceItem` + its `BookingExpense` inside `TenantScope.call(tenantId, …)`.

#### Why submit-time, not approval-time

Creating on the approval thread breaks on three counts, all because the principal there is a `SuperAdmin`, not a tenant `User`:

| Concern | Approval-time (rejected) | Submit-time (chosen) |
|---|---|---|
| `owner_user_id` | `OwnershipEntityListener` only stamps a `User` principal (`:30-40`) → stays **null** → `SubAgentScope.assertVisible` 404s the sub-agent out of its own booking (`SubAgentScope.java:51-57`) | stamped automatically to the submitting user, sub-agent included |
| `assigned_user_id` | no current user to default to | `BookingAssigneeResolver.resolveForCreate` defaults to the submitter (`:72-75`) |
| Booking quota | `enforceBookingQuota` 403 (`BookingServiceImpl.java:172,490`) strands an order the platform already approved | the 403 reaches the tenant, who can upgrade or link an existing booking |
| `createdBy` | a platform email lands in `bookings_aud` | the tenant user's email |

`TenantContext` is ambient from `JwtAuthFilter` on the submit thread, so no `TenantScope` gymnastics are needed for creation at all.

#### Money map

| Field | Value | Branch |
|---|---|---|
| `Booking.customerAmount` | the TENANT's selling price to their customer (from the submit payload) | auto-create only |
| `Booking.vendorCost` | **server-owned** on a marketplace-linked booking: `tenantTypedPortion + Σ(marketplace VENDOR expenses)` | both branches |
| `BookingExpense` `costType = VENDOR` | the itemised AP/settlement record for the payable — `amount` = final tenant-payable, `vendorName` free text, `marketplaceBookingPublicId` = link | both branches |
| `BookingServiceItem.cost` / `.vendorCost` | **NULL** | both |

The payable belongs **inside** `Booking.vendorCost`, not beside it. `ExpenseCostType`'s own javadoc states the invariant: *"`Booking.vendorCost` … already represents everything paid to suppliers"*, and VENDOR rows are *"already accounted for by `Booking.vendorCost`, so these rows are EXCLUDED from `totalInternalCosts` and never affect `netProfit`"* (`ExpenseCostType.java:7-8,30-33`). Writing a VENDOR expense without adding it to `vendorCost` therefore breaks a documented contract and silently overstates `netProfit` by exactly the payable. Reclassifying it as `INTERNAL` is equally wrong — that type means *"the agency's own cost over and above what it paid suppliers"*.

The field's known weakness — `updateEntity` applies the client's non-null `vendorCost` and `recomputeTotals` then re-derives from it (`BookingServiceImpl.java:800-802,826-827`) — is fixed by making it self-healing rather than by routing around it. See decision 3.

`BookingServiceItem.cost` must stay NULL: any item with `cost > 0` flips the GST tax invoice from a single `customerAmount` line to per-item lines and bills **only** the items (`InvoiceServiceImpl.java:371-403`) — a priced hotel line would under-bill GST on the whole package.

Never written by the marketplace: `gst`, `tcs`, `totalPayable`, `netProfit`, `paidAmount`, `status`, `paymentStatus`, and `booking_payments` (receipts are hard-capped at `totalPayable` with a 409, `BookingPaymentServiceImpl.java:63-69`).

**No new column on `bookings`** — the link lives on the two child tables, deliberately keeping `bookings_aud` untouched.

#### The port

New narrow-port package `com.crm.travelcrm.booking.api` (mirrors the existing `auth/api`). Dependency direction is marketplace → booking, never the reverse; only records and `publicId`s cross the boundary.

```java
public interface CrmBookingLinkPort {
    CrmBookingRef requireLinkable(UUID bookingPublicId, Long tenantId);
    CrmBookingRef createForMarketplace(MarketplaceBookingSeed seed);
    CrmMarketplaceProjection projectHotel(MarketplaceHotelProjectionCommand cmd);   // idempotent upsert
    void withdrawHotel(UUID marketplaceBookingPublicId, Long tenantId, String reason);
}
```

`serviceType` is the literal `"Hotel"`. The platform can never be the service line's Vendor (`assignVendor` resolves strictly against the tenant's own vendor master, `BookingServiceItemServiceImpl.java:115-118`), so `vendorId`/`vendorNameSnapshot` stay null and the payee name lives in `BookingExpense.vendorName`.

#### Transaction boundaries

- **Submit** — orchestrator NOT `@Transactional` (the supplier re-price is a network call); one method-level `@Transactional` writer does link-or-create + service item + expense + `platform_hotel_bookings` REQUESTED. Idempotency is a partial unique index on `idempotency_key`; a duplicate hits `DataIntegrityViolationException`, the caller re-reads and returns the original.
- **Approve** — TX-1 (platform only, atomic: status + inventory + accrual, guarded by a `findByPublicIdForUpdate` status precondition which *is* the approve-retry idempotency). Then, **outside** any transaction, `TenantScope.call(tenantId, … projectHotel …)` as TX-2 — `TenantScope` refuses to be entered inside an active transaction by design, so TX-1 and TX-2 **cannot** be one transaction. TX-3 stamps `crm_sync_state`.
- **Compensation, not rollback** — `crm_sync_state` + a replay scheduler over `PENDING`/`FAILED`, using the established per-tenant set → work → `finally` clear loop. A lagging projection degrades to "confirmed on the platform, not yet on the booking", never to a wrong confirmation.
- `NotifyEvent` is published **last, after the `TenantScope` block closes** — `NotifyEventListener` does an unconditional `TenantContext.clear()` in its `finally` and `@EventListener` is synchronous, so publishing mid-scope destroys the outer tenant value.
- **Reject/cancel** — service line → `ServiceItemStatus.CANCELLED` (enum already has it, no CHECK-constraint migration). The marketplace **never** cancels or deletes the CRM Booking: `BookingServiceImpl.cancel()` mints an immutable `BookingCancellation`, issues a numbered credit note, freezes a P&L and disposes of the lead — that is the customer cancelling their trip, not a supplier declining a room.

#### Blocking defects — fix before this ships

| # | Defect | Fix |
|---|---|---|
| 1 | `create()` has **no duplicate-lead guard** — only `convertLeadToBooking` does (`:529-536`). Passing `leadPublicId` for an already-converted lead mints a second booking, a second quota slot, and accrues sub-agent commission **twice** | run the same `findFirstByLeadIdAndTenantIdAndStatusNot…` guard in `createForMarketplace` and 409 |
| 2 | Cancellation P&L would be structurally blind to the payable — `CancellationCalculator` has only `sunkVendorCost` (from `Booking.vendorCost`) and `sunkInternalCosts` (INTERNAL rows only, `BookingExpenseRepository.java:54-60`), and `BookingProfitService` freezes the result permanently | **resolved by decision 3.** Keeping the payable inside `Booking.vendorCost` makes `sunkVendorCost` correct with **zero change** to `CancellationCalculator` or `BookingProfitService` — the two places least safe to touch |
| 3 | Restating `BookingExpense.amount` **downward** throws — `ExpenseSettlementCalculator` rejects `paid > amount` and `amount <= 0` (`:69-72,86-92`). A re-priced approval after part-settlement → 400 → `crm_sync_state = FAILED` → the scheduler replays the identical input forever | never restate downward past `paidAmount`; book the delta as a second credit line. Cap sync attempts → `ABANDONED` + alert |
| 4 | Nothing propagates a **CRM cancellation back to the platform**. After the customer cancels, `platform_hotel_bookings` stays CONFIRMED (room still held, accrual still PENDING) and `requireLinkable` now rejects the booking as terminal — no path left to restate the payable | add the inverse `onCrmBookingCancelled(...)`, and let `withdrawHotel` accept a terminal booking |
| 5 | The upsert is **not idempotent**: the `uq_bkpay_idem` idiom carries no `deleted_at` predicate, but the finder is `…AndDeletedAtIsNull`. A soft-deleted row keeps the key → later replay takes the INSERT branch → constraint violation | index `WHERE … IS NOT NULL AND deleted_at IS NULL`, and have the upsert see soft-deleted rows and un-delete |
| 6 | `PlatformHotelBooking extends BaseEntity` → no Hibernate `tenantFilter`, **and** `TenantIsolationArchTest` is scoped to `BaseTenantEntity` repositories, so a bare `findByPublicId` escapes both defences and leaks another tenant's negotiated payable | every tenant-facing read goes through `requireOwned(tenantId, publicId)` → 404, mirroring `UpgradeRequestServiceImpl:375-381`. `findByPublicIdForUpdate` reachable only from the approval path |
| 7 | `TRAVEL_AGENT` — the role that actually places marketplace orders — holds no `BOOKING_PROFIT_READ`, so it sees the hotel line but 403s on the payable, and `BookingTimelineServiceImpl:124` hides it from the timeline too | **resolved by decision 4.** New `MARKETPLACE_PAYABLE_READ`, gating *only* expense rows carrying `marketplace_booking_public_id`, applied at both `BookingExpenseController` and `BookingTimelineServiceImpl:124` |

Two pre-existing defects surface on this path and should be fixed independently:

- `InAppNotificationChannel.send` is `@Transactional(REQUIRED)` (`:46`). In an `afterCommit` callback the transaction is already committed but resources are still bound, so the save participates with **no commit following** — the notification row is dropped. `cancel()` already publishes via `publishBookingEventAfterCommit` (`:1009`), so `BOOKING_CANCELLED` in-app rows are being lost today. Needs `REQUIRES_NEW` before any further afterCommit publishing is adopted.
- `NotifyEventListener` does set/clear rather than save/restore on `TenantContext`, so any synchronous publish on a tenant thread leaves the context null for the rest of the request — `TenantFilterAspect` then fails **open**. `ReminderScheduler.java:60-63` already hand-rolls a workaround; give the listener `TenantScope` save/restore semantics instead.

#### Decisions — RESOLVED

Decided 2026-08-03, owner-delegated. These are binding for Phase 1; each records the reasoning so a future reader can tell a decision from an accident.

**1. Booking quota — an auto-created marketplace booking COUNTS, and is HARD-ENFORCED.**

A marketplace booking is a CRM Booking: it consumes the same row, invoice numbering, document storage, timeline and report surface as any other. The quota measures CRM consumption, so exempting it would make `TenantUsageResponse` lie to the SuperAdmin dashboard, not merely open a loophole. The commercial objection — "never let a subscription meter block a transaction the platform earns commission on" — is real but already answered twice over:

- **The LINK branch is not quota-gated at all**, and that falls out for free: no new `Booking` is created, so `enforceBookingQuota` never fires. A tenant at their cap can still transact through the marketplace against trips they are already managing. The cap only ever blocks *new CRM records* beyond the plan — which is exactly what it is for.
- The per-tenant quota **override + pin** already exists (`UsageServiceImpl.overrideQuota`) as the operator-controlled commercial escape hatch. No new machinery.

Refinement: run an **advisory** quota pre-check at the price-hold/offer step so a capped tenant learns before entering guest details. The authoritative gate stays at submit, inside the writer transaction. The 403 body must name the cap and offer both remedies (upgrade, or link to an existing booking).

**2. On REJECT — LEAVE the auto-created booking. Never auto-delete.**

By the time a rejection lands (hours later, sometimes a day), the tenant has seen `BKG-…` in their list and may have quoted that reference to the customer, attached notes, or — decisively — **taken a customer advance against it**. A system-initiated soft-delete of a booking carrying a payment ledger row is a data-integrity incident, and no automated path can rule that out beforehand. The general rule applies: *a system-initiated failure must never silently delete a record a human has already seen.*

So on reject: the hotel service line goes to `CANCELLED` carrying the rejection reason, so the otherwise-empty booking self-documents why. The tenant is notified with a deep link and two actions — **choose another hotel** (re-submit against the same booking, which is precisely why LEAVE beats delete) or **delete this booking** manually via the existing `delete()`, which permits `PENDING` and returns the quota slot (`countByTenantForMonth` filters `deletedAt IS NULL`). Control of the destructive act stays with the human.

**3. Price revision — restate the expense row; `Booking.vendorCost` follows automatically, because it is server-owned.**

This supersedes the earlier "never write `vendorCost` on the LINK branch" rule. That rule routed around the field's weakness instead of fixing it, and in doing so broke `ExpenseCostType`'s documented invariant (see the money map above) — leaving `netProfit` and the frozen cancellation P&L overstated by exactly the payable.

The rule is instead **one writer per field**, with `vendorCost` reclaimed as server-owned on marketplace-linked bookings:

- `projectHotel` writes/restates the VENDOR `BookingExpense.amount` only.
- `Booking.vendorCost` is always `tenantTypedPortion + Σ(marketplace VENDOR expenses)`, recomputed in `update()` between `bookingMapper.updateEntity` (`:802`) and `recomputeTotals` (`:827`) — the one clean seam. A new sibling to `sumInternalCosts` supplies the marketplace term; that query's own javadoc already accepts running *"on every expense write and every booking edit"* (`BookingExpenseRepository.java:50-52`), so the cost profile is established, not novel.
- API contract change: on a marketplace-linked booking, request `vendorCost` means the **tenant-typed portion**, and the response carries `marketplaceVendorCost` alongside. The UI renders `Your costs ₹40,000 + Platform ₹35,000 = ₹75,000` — strictly better than one opaque figure a background job keeps rewriting.
- `customerAmount` is **never** touched. Whether the tenant absorbs a price increase or re-quotes the customer is a commercial choice the backend must not make (§8 Step 6B).

Consequence: `CancellationCalculator` and `BookingProfitService` need no change at all, and the auto-create branch needs no DTO relaxation (`vendorCost` is already `> 0`).

**4. Payable visibility — GRANT to `TRAVEL_AGENT`, EXCLUDE `SUB_AGENT` from the marketplace entirely in v1.**

These two roles look similar in the permission table and are opposites commercially.

- `TRAVEL_AGENT` is tenant staff. The payable is not a margin — it is a **price they must know to quote the customer**. Hiding it makes the feature unusable. New `MARKETPLACE_PAYABLE_READ` (TENANT_ADMIN + TRAVEL_AGENT) gates **only** expense rows carrying `marketplace_booking_public_id`, at `BookingExpenseController` and `BookingTimelineServiceImpl:124`. Every other expense row stays behind `BOOKING_PROFIT_READ`. Margin stays hidden; price becomes visible.
- `SUB_AGENT` is a **broker external to the tenant's economics**. Under the confirmed commission-split model the partner sells the parent's package and earns a carve-out; the parent's cost base is exactly what that model conceals. Worse, letting a broker initiate a purchase the parent tenant is financially liable for — at a price the broker cannot see — is a governance defect, not a UX one. So `MARKETPLACE_READ` / `MARKETPLACE_BOOK` are **not** granted to `SUB_AGENT` in v1. Partners are unaffected in practice: they still build quotations from the parent's synced Hotel Master rows (the Layer C projection), which is read-only and already works.

Revisit only if partners are given their own credit relationship with the platform — a different product.

**5. A CRM Booking is MANDATORY. No standalone room bookings.**

Structural: `BookingExpense.bookingId` is NOT NULL, so the payable has nowhere to live; the calendar joins through the booking; the timeline is keyed on `bookingId`; the traveler portal reads bookings. A standalone order would be invisible to every CRM surface and would need its own AP, invoicing and reporting — a different product, not a variant of this one.

Commercial: this is a CRM-embedded marketplace, not an OTA. The proposition is *"book the hotel from inside the trip you are already managing."*

Mandatory costs the tenant nothing precisely because of link-or-create: if no trip exists yet, one is created in the same request. That is the whole reason link-or-create is the right shape — it makes "mandatory" free.

#### Files this slice touches

Ordering matters where noted; everything else is independent.

**Existing files — booking module**

| File | Change |
|---|---|
| `booking/entity/BookingServiceItem.java`, `booking/entity/BookingExpense.java` | add `marketplaceBookingPublicId` (UUID). Neither is `@Audited` — only `Booking.java:18` is — which is the whole reason the link lives here and not on `bookings` |
| `booking/repository/BookingRepository.java` | add `findByPublicIdAndTenantIdAndDeletedAtIsNull`. Today only the tenant-less `findByPublicIdAndDeletedAtIsNull` (`:24`) exists, relying entirely on the Hibernate filter — which fails **open** on a null context |
| `booking/repository/BookingServiceItemRepository.java`, `booking/repository/BookingExpenseRepository.java` | add `findByMarketplaceBookingPublicId(...)` for the idempotent upsert — **must see soft-deleted rows** (defect 5) |
| `booking/repository/BookingExpenseRepository.java` | add `sumMarketplacePayable(bookingId)` — sibling of `sumInternalCosts` (`:54-60`), VENDOR rows with a non-null `marketplaceBookingPublicId` (decision 3) |
| `booking/service/BookingServiceImpl.java` | `update()` — recompute `vendorCost = typedPortion + sumMarketplacePayable` between `:802` and `:827` (decision 3). `createForMarketplace` must run the same duplicate-lead guard `create()` lacks (defect 1) |
| `booking/controller/BookingExpenseController.java`, `booking/service/BookingTimelineServiceImpl.java:124` | widen the gate with `hasAuthority('MARKETPLACE_PAYABLE_READ')`, scoped to marketplace rows only (decision 4) |
| `permission/enums/Permission.java` | add `MARKETPLACE_READ`, `MARKETPLACE_BOOK`, `MARKETPLACE_PAYABLE_READ` + role defaults. **Not granted to `SUB_AGENT`** (decision 4) |

**New**

`booking/api/` — `CrmBookingLinkPort` + its records + `CrmBookingLinkAdapter` (per-method `@Transactional`; `TenantFilterAspect` matches `@annotation` only, so class-level annotation gets no tenant filter). Mirrors the existing narrow-port package `auth/api/`.

`marketplace/hotel/` — `PlatformHotelBooking` (extends `BaseEntity`, the `UpgradeRequest` convention) + repository (`findByTenantIdAndIdempotencyKey`, `findByPublicIdForUpdate` with `PESSIMISTIC_WRITE`, and a `requireOwned` chokepoint for every tenant-facing read — defect 6), `MarketplaceBookingRequestService` (**not** `@Transactional`), `MarketplaceBookingWriter` (`@Transactional`), `MarketplaceApprovalOrchestrator` (**not** `@Transactional` — `TenantScope` refuses an active transaction), `MarketplacePlatformWriter`, `MarketplaceCrmSyncScheduler` (attempt cap → `ABANDONED` + alert, defect 3), and the two controllers.

**Platform wiring — order is load-bearing**

1. `platform/subscription/config/PlanCatalogueInitializer.java` — backfill the `HOTEL_MARKETPLACE` module key onto plans. Module keys are **data** (the union of persisted `Plan.modules`), not an enum, and the entitlement cache is 30 s.
2. *then* `platform/entitlement/filter/ModuleAccessFilter.java` — `RULES.put("/api/marketplace", "HOTEL_MARKETPLACE")`. Shipping this first locks every tenant out. `ModuleAccessCoverageTest` fails the build on any `/api` prefix in neither `RULES` nor `ALWAYS_ALLOWED`; `/api/super-admin` is already allowed, so the admin controller needs nothing.
3. `platform/audit/entity/PlatformAuditAction.java` — `MARKETPLACE_HOTEL_BOOKING_APPROVED` / `_REJECTED` / `_REVISION_REQUESTED`.

**Migration** — `V3__hotel_marketplace.sql`. V1 and V2 are already stamped in `flyway_schema_history`, so V2 **cannot** be edited in place; this must be a new version. Creates `platform_hotel_bookings`, adds the two `marketplace_booking_public_id` columns with partial unique indexes `WHERE … IS NOT NULL AND deleted_at IS NULL` (defect 5), and refreshes the relevant `*_check` constraints by hand — `ddl-auto` will not.

**Independent pre-work** — the two pre-existing defects above (`InAppNotificationChannel` propagation, `NotifyEventListener` context save/restore). The afterCommit publishing this design relies on is unsafe until the first lands.

---

## 9. Cancellation and refund flow

Cancellation terms shown at price-hold time must be snapshotted into the booking.

Recommended flow:

1. Tenant requests a cancellation quote.
2. Server evaluates the booking's snapshotted policy, not today's Hotel Master policy.
3. Tenant confirms cancellation with an idempotency key.
4. Supplier cancellation/confirmation runs.
5. Inventory is released only when the business rule permits it.
6. Tenant refund/credit is recorded.
7. Platform earning is retained, reduced or reversed according to the snapshotted commercial/cancellation rule.
8. Append a `REVERSAL` or `ADJUSTMENT` ledger row; never mutate the original accrual into a different historical amount.
9. CRM service item status is synchronized to cancelled.

Failed or rejected supplier confirmations must release held inventory and must not leave an earned commission entry.

---

## 10. API design

All public API identifiers must be UUID `publicId`; internal Long IDs must not be exposed.

### 10.1 Superadmin catalog APIs

```http
POST   /api/platform/hotel-catalog/hotels
GET    /api/platform/hotel-catalog/hotels
GET    /api/platform/hotel-catalog/hotels/{hotelPublicId}
PUT    /api/platform/hotel-catalog/hotels/{hotelPublicId}
PATCH  /api/platform/hotel-catalog/hotels/{hotelPublicId}/status

POST   /api/platform/hotel-catalog/hotels/{hotelPublicId}/rooms
PUT    /api/platform/hotel-catalog/rooms/{roomPublicId}
POST   /api/platform/hotel-catalog/hotels/{hotelPublicId}/meal-plans
POST   /api/platform/hotel-catalog/hotels/{hotelPublicId}/rate-plans
PUT    /api/platform/hotel-catalog/rate-plans/{ratePlanPublicId}/calendar
PUT    /api/platform/hotel-catalog/rooms/{roomPublicId}/inventory
POST   /api/platform/hotel-catalog/hotels/{hotelPublicId}/commercial-rules

GET    /api/platform/hotel-bookings
GET    /api/platform/hotel-commissions
POST   /api/platform/hotel-bookings/{bookingPublicId}/confirm
POST   /api/platform/hotel-bookings/{bookingPublicId}/reject
```

All endpoints require `PLATFORM_ADMIN` and a valid `PlatformContext`.

### 10.2 Tenant marketplace APIs

```http
GET    /api/hotel-marketplace/search
GET    /api/hotel-marketplace/hotels/{hotelPublicId}
POST   /api/hotel-marketplace/offers/recheck
POST   /api/hotel-marketplace/price-holds
POST   /api/hotel-marketplace/bookings
GET    /api/hotel-marketplace/bookings
GET    /api/hotel-marketplace/bookings/{bookingPublicId}
POST   /api/hotel-marketplace/bookings/{bookingPublicId}/cancellation-quote
POST   /api/hotel-marketplace/bookings/{bookingPublicId}/cancel
POST   /api/hotel-marketplace/hotels/{hotelPublicId}/sync-to-master
```

Suggested tenant permission keys:

```text
HOTEL_MARKETPLACE_VIEW
HOTEL_MARKETPLACE_BOOK
HOTEL_MARKETPLACE_CANCEL
HOTEL_MARKETPLACE_SYNC_MASTER
HOTEL_MARKETPLACE_VIEW_FINANCIALS
```

### 10.3 Example booking request

```json
{
  "priceHoldToken": "opaque-server-issued-token",
  "crmBookingPublicId": "6bca17a5-1e61-4c88-8359-ea4b5a49fc7f",
  "leadGuest": {
    "name": "Guest Name",
    "phone": "+919999999999",
    "email": "guest@example.com"
  },
  "tenantCustomerSellingAmount": 4800.00,
  "paymentMode": "TENANT_CREDIT",
  "idempotencyKey": "tenant-generated-unique-key"
}
```

### 10.4 Example tenant price response

```json
{
  "currency": "INR",
  "checkIn": "2026-08-10",
  "checkOut": "2026-08-12",
  "nights": 2,
  "rooms": 1,
  "tenantPayable": 8800.00,
  "taxesAndFees": 0.00,
  "cancellationSummary": "Free until configured deadline",
  "holdExpiresAt": "2026-08-02T12:10:00+05:30",
  "priceHoldToken": "opaque-server-issued-token"
}
```

Raw supplier cost and platform earning are intentionally absent from the tenant DTO.

---

## 11. Authorization and tenant isolation

This module crosses platform and tenant boundaries, so it needs explicit ownership checks.

### Platform-owned entities

`PlatformHotel`, rates, inventory, marketplace bookings and commission ledger should extend `BaseEntity`. Marketplace booking/ledger still carries a required logical `tenant_id`.

### Tenant endpoint rule

Every tenant booking lookup must use both identifiers:

```text
findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, currentTenantId)
```

A tenant must receive 404 for another tenant's hotel booking public ID.

### Superadmin endpoint rule

Cross-tenant endpoints require both:

- `PLATFORM_ADMIN` authority; and
- `PlatformContext.isPlatform() == true`.

All Superadmin price, inventory, confirmation, reversal and adjustment actions must be sent to the platform audit log.

### Separate response DTOs

Use separate DTOs for tenant and Superadmin. Do not rely only on JSON annotations to hide supplier cost, commission or contract data.

---

## 12. Concurrency, idempotency and consistency

### Inventory race protection

Two tenants can attempt the last room simultaneously. Inventory reservation must use either:

- an atomic conditional update; or
- a pessimistic row lock for all required dates in stable date order.

Example condition:

```text
total_allotment - held_quantity - booked_quantity >= requested_rooms
```

If any stay date fails, the whole reservation transaction rolls back.

### Idempotency

The following write operations require tenant-scoped unique idempotency keys:

- booking create
- payment capture/credit debit
- cancellation confirm
- refund
- commission adjustment/settlement

Repeated requests must return the original result and must not consume inventory or money twice.

### Price consistency

- Search result is indicative.
- Recheck/price hold is authoritative until expiry.
- Booking rejects an expired or already-consumed hold.
- The backend never trusts a supplier total or commission amount posted by the client.

### Sync consistency

Use `catalog_version` for deterministic upsert. A retry of the same version must be harmless.

For the initial release, in-process after-commit events plus a scheduled reconciliation job are sufficient. If guaranteed multi-instance delivery is required, add a transactional outbox table and retry worker.

---

## 13. Audit, reporting and notifications

### Audit events

Record at least:

- platform hotel created/updated/published/unpublished
- rate/inventory bulk update
- commercial rule change
- tenant master import/sync failure
- price hold created/expired/consumed
- booking requested/confirmed/rejected/cancelled
- platform earning accrued/reversed/adjusted/settled
- payment/refund event

### Reports

Superadmin reports:

- gross tenant payable
- supplier payable
- accrued/earned/reversed/settled platform earning
- booking count by hotel/tenant/status
- cancellation/refund totals
- outstanding supplier and tenant balances

Tenant reports:

- own marketplace bookings
- tenant payable
- customer selling amount
- tenant margin
- payment/cancellation status

### Notifications

Use the existing notification/event system for:

- booking received
- supplier confirmation pending
- booking confirmed/rejected
- payment failed
- cancellation completed
- sync/location mapping failure
- low inventory/stop-sell alerts to Superadmin

---

## 14. Implementation phases

### Phase 1: platform catalog and Hotel Master sync

- Add platform hotel/room/meal entities and Superadmin APIs.
- Register the optional `HOTEL_MARKETPLACE` add-on and hard-gate tenant marketplace routes.
- Add `origin` and sync metadata to existing Hotel Master.
- Implement platform-to-tenant projection and version reconciliation.
- Add separate tenant/Superadmin DTOs and security tests.
- No live booking until rate and inventory controls exist.

### Phase 2: rates, inventory and tenant search

- Add rate plans, rate calendar and inventory.
- Add commercial-rule resolver.
- Implement tenant marketplace search and detail endpoints.
- Implement authoritative price recheck and expiring hold.

### Phase 3: booking and CRM linkage

- Add marketplace booking state machine.
- Add inventory hold/commit.
- Link/create CRM Booking and `BookingServiceItem`.
- Add confirmation/voucher and notifications.
- Add idempotency and concurrent last-room tests.

### Phase 4: commission, cancellation and settlement

- Add append-only platform earning ledger.
- Implement cancellation quote/confirm and ledger reversal.
- Implement tenant/platform settlement records.
- Add Superadmin and tenant reports.

### Phase 5: production hardening

- Scheduled reconciliation and expiry workers.
- Transactional outbox if deployment requires guaranteed event delivery.
- Metrics and alerts for inventory mismatch, stale sync and failed confirmations.
- Load tests, security tests and accounting reconciliation.

---

## 15. Database migration plan

The next migration should be additive and should not rewrite existing tenant hotels.

Recommended migration sequence:

1. Create platform catalog tables.
2. Add sync columns to `hotels`, `hotel_room_types` and `hotel_meal_plans` with safe defaults.
3. Add platform rate/inventory/commercial tables.
4. Add hold, marketplace booking and commission ledger tables.
5. Add the logical marketplace links to `booking_service_items`.
6. Add indexes and partial unique constraints.
7. Backfill existing hotels with `origin = TENANT` and `marketplace_bookable = false`.

Do not assign existing tenant hotels to the platform catalog automatically. Duplicate detection can produce Superadmin review candidates, but only an explicit approval may link/onboard an existing private hotel.

---

## 16. Minimum test plan

### Unit tests

- nightly price aggregation
- fixed and percentage platform earning
- occupancy validation
- checkout-exclusive night calculation
- cancellation calculation and earning reversal
- catalog version comparison
- state-transition validation

### Integration tests

- Superadmin creates/publishes a hotel and tenant can search it
- inactive hotel is not searchable
- tenant import creates exactly one Hotel Master projection
- repeated sync is idempotent
- platform update refreshes linked descriptive fields
- tenant local preferences survive platform sync
- booking creates marketplace booking, commission ledger and CRM service item atomically
- booking snapshots survive later hotel/rate edits
- failed booking releases inventory
- cancellation appends reversal and does not mutate original accrual

### Security tests

- tenant cannot create/update/publish platform hotels
- tenant A cannot read or cancel tenant B booking
- tenant API never returns supplier net/commission internals
- Superadmin catalog endpoint requires platform context
- synced source fields cannot be changed through tenant Hotel Master update API

### Concurrency tests

- two simultaneous requests for the final room result in one success only
- duplicate idempotency key produces one booking/one ledger effect
- price hold cannot be consumed twice
- cancel and confirm races produce one legal final state

---

## 17. Acceptance criteria

The feature is complete only when all conditions below are true:

- Superadmin can manage and publish a global hotel catalog without a tenant context.
- Tenant can search only active bookable platform inventory.
- Tenant can still create and use private Hotel Master entries.
- A platform hotel import/booking creates or reuses one linked tenant Hotel Master projection.
- Platform source updates sync without overwriting tenant-local preferences.
- A booking uses server-calculated held pricing and snapshots all accepted details.
- Inventory cannot oversell under concurrent requests.
- CRM Booking/Service Item links to the marketplace booking.
- Platform earning is stored in a separate append-only ledger.
- Tenant A cannot access tenant B's data.
- Cancellation/refund correctly reverses or retains earning according to the booking snapshot.
- Unpublishing a hotel blocks new sales but does not damage historical bookings.
- Supplier cost and internal platform commission are absent from tenant/customer-facing DTOs and documents.
- A tenant without an active `HOTEL_MARKETPLACE` add-on cannot search, import or submit new platform hotel requests.
- Subscription suspension blocks new marketplace business without hiding confirmed historical bookings or issued vouchers.

---

## 18. Recommended first implementation slice

Start with the smallest end-to-end vertical slice:

1. Superadmin creates one platform hotel with one room and one meal plan.
2. Superadmin publishes it.
3. Tenant searches and imports it.
4. The backend creates/reuses the tenant Hotel Master projection.
5. A later platform name/image/amenity update increments `catalog_version`.
6. Tenant read triggers safe resynchronization.

After this slice and its security tests pass, add rate/inventory and booking. This validates the most important architectural boundary—global platform ownership versus tenant-scoped CRM master—before money and inventory depend on it.

---

## 19. Decisions required before payment/settlement goes live

These choices do not block Phase 1 catalog/sync implementation, but must be finalized before production booking:

1. Default commercial model: net-rate markup, hotel-paid commission, or both.
2. Tenant payment method: prepaid wallet, credit limit, online payment, or manual approval.
3. Default confirmation mode: instant or on-request.
4. Whether tenant sees a separate platform fee or only a final tenant-payable price.
5. Hotel cancellation rule source and refund settlement timeline.
6. Accountant-approved GST/TDS treatment for supplier, platform and tenant invoices.
7. Whether a tenant-added private hotel can submit an onboarding request for Superadmin approval.

Recommended defaults for the first operational release are: support both commercial models internally, expose only final tenant payable, use `ON_REQUEST` where reliable inventory is unavailable, and never confirm until payment/credit authorization succeeds.

---

## 20. Plug-and-play optional subscription model

Hotel Marketplace base CRM ka mandatory module nahi hoga. It is an independently activatable paid add-on.

### 20.1 Module identity

Use one stable module key:

```text
HOTEL_MARKETPLACE
```

This key controls tenant-facing marketplace capabilities only. Existing private Hotel Master remains part of `MASTERS` and must continue working even when Hotel Marketplace is disabled.

```text
MASTERS
  -> tenant private hotels, rooms and meal-plan master

HOTEL_MARKETPLACE
  -> Superadmin catalog search/import
  -> live/platform offers
  -> booking request and revision acceptance
  -> marketplace cancellation
  -> active marketplace financial views
```

This separation lets a tenant buy normal CRM/Quotation without buying platform hotel distribution.

### 20.2 Existing entitlement integration

The backend already has `TenantEntitlementService`, `/api/me/entitlements`, tenant module overrides and `ModuleAccessFilter`. Hotel Marketplace must plug into that system instead of introducing controller-only checks.

Add a hard route rule:

```text
/api/hotel-marketplace/** -> HOTEL_MARKETPLACE
```

The frontend reads `/api/me/entitlements` and:

- shows Hotel Marketplace navigation/search when enabled;
- shows an upgrade/add-on prompt when disabled; and
- never treats UI hiding as authorization. Backend enforcement remains mandatory.

Superadmin endpoints under `/api/platform/hotel-*` run in `PlatformContext` and are not blocked by a tenant entitlement.

### 20.3 Add-on subscription versus base plan

This should be a true add-on, not automatically bundled into every CRM plan.

Recommended effective-access formula:

```text
effectiveModules
  = basePlanModules
  + activePaidAddOnModules
  + explicitSuperadminGrants
  - explicitSuspensions
```

The current entitlement implementation treats tenant modules as a complete override and derives the available module catalogue from the union of plan modules. That is sufficient for manual feature flags, but a recurring optional add-on needs an explicit lifecycle.

Recommended platform records:

```text
module_addon_products
  module_key = HOTEL_MARKETPLACE
  display_name
  monthly/yearly price
  currency
  trial_days
  active

tenant_module_subscriptions
  tenant_id
  module_key
  status
  period_start / period_end
  grace_until
  billing reference
  auto_renew
  activated_at / suspended_at / cancelled_at
```

Suggested status values:

```text
TRIAL
ACTIVE
PAST_DUE
SUSPENDED
CANCELLED
EXPIRED
```

Do not overload the current plan-only `Subscription.planCode` to represent an add-on without adding a product/module discriminator. A base plan renewal and a Hotel Marketplace add-on renewal are separate commercial contracts.

### 20.4 Activation flow

```text
Tenant requests Hotel Marketplace add-on
        |
Superadmin/payment approval
        |
tenant_module_subscription = ACTIVE
        |
Entitlement cache evicted
        |
HOTEL_MARKETPLACE becomes effective immediately
        |
Frontend refreshes entitlements/navigation
```

Activation must be idempotent and platform-audited. Payment success or Superadmin manual activation is the only writer that grants a paid add-on, apart from an explicit trial/grant workflow.

### 20.5 Disable, expiry and grace behavior

Disabling a subscription must not damage already purchased travel.

| Data/action | Active | Past due/grace | Suspended/expired |
|---|---:|---:|---:|
| Search platform hotels | Yes | Configurable, recommended yes | No |
| Import/sync new platform hotel | Yes | Configurable | No |
| Create new booking request | Yes | Configurable, recommended no after grace | No |
| Accept new revised price | Yes | Only if request remains eligible | No, unless Superadmin grants completion |
| View confirmed historical booking | Yes | Yes | Yes, read-only |
| Download already issued voucher | Yes | Yes | Yes |
| Cancel/refund confirmed booking | Yes | Yes | Yes |
| Receive operational notifications | Yes | Yes | Yes |
| Superadmin finish/support existing booking | Yes | Yes | Yes |

Confirmed booking, cancellation, refund and voucher duties survive subscription expiry. A tenant must not lose access to a hotel voucher required for an upcoming stay merely because the add-on renewal failed.

Because `ModuleAccessFilter` is path-prefix based, split active commerce from historical/operational access:

```text
/api/hotel-marketplace/**
  -> requires active HOTEL_MARKETPLACE

/api/me/hotel-bookings/**
  -> tenant-owned confirmed history, voucher and permitted cancellation support
  -> entitlement-neutral but object-level tenant secured
```

Alternatively, enhance the entitlement guard with an explicit read/lifecycle policy. Do not simply leave the whole marketplace prefix open after suspension.

### 20.6 Pending request behavior on suspension

Policy must be deterministic:

- New requests are rejected once entitlement is suspended/expired.
- Existing `REQUESTED` or `UNDER_REVIEW` rows are not deleted.
- Recommended default: Superadmin may reject/release them or complete them during a short paid/grace window; outside grace they cannot be newly confirmed without an audited override.
- Existing `CONFIRMED` rows continue to voucher/cancellation/refund completion.
- Commission ledger entries and supplier settlement remain valid regardless of later module status.

### 20.7 Background jobs

Job behavior must distinguish sales jobs from operational-duty jobs.

Skip for disabled tenants:

- catalog recommendation/preload;
- new master import and optional sync refresh;
- sales/search cache warming; and
- marketing nudges for marketplace offers.

Continue regardless of current subscription for affected existing bookings:

- hold/payment cleanup;
- approved voucher generation/delivery;
- stay reminders;
- cancellation/refund processing;
- commission reversal/settlement;
- audit retention; and
- security/storage cleanup.

### 20.8 Plug-and-play code boundary

Hotel Marketplace should depend on CRM through narrow application ports, not by allowing catalog code to modify Quotation/Booking tables from multiple services.

Suggested ports:

```text
HotelMarketplaceEntitlementPort
HotelMasterProjectionPort
QuotationHotelSelectionPort
CrmBookingServiceItemPort
MarketplacePaymentOrCreditPort
MarketplaceNotificationPort
VoucherStoragePort
```

One orchestration service owns the transaction for approval -> Hotel Master projection -> CRM service item -> commission accrual -> voucher request. This prevents partial activation and makes the module removable/replaceable without rewriting the core Hotel Master.

An optional deployment-wide property may stop all marketplace workers/controllers when the product is not sold on a deployment:

```properties
app.hotel-marketplace.enabled=true
```

Tenant subscription remains the primary per-customer gate. The global property is an operational kill switch, not billing state.

### 20.9 Subscription acceptance tests

- disabled tenant receives `MODULE_NOT_ENABLED` from active marketplace endpoints;
- private `/api/hotels` Hotel Master still works with `MASTERS` entitlement;
- active add-on appears in `/api/me/entitlements` without changing the tenant's base plan;
- activation/suspension evicts the entitlement cache immediately;
- frontend navigation follows entitlement but direct API calls remain blocked;
- expired tenant can read its own confirmed booking and download its issued voucher;
- expired tenant cannot search/import/create a new request;
- cancellation/refund for a confirmed booking still completes after expiry;
- Superadmin catalog and support queue remain available in platform context;
- tenant A can never use archive access to read tenant B's booking/voucher;
- subscription webhook/manual activation is idempotent and platform-audited; and
- disabling the global property stops new marketplace operations without corrupting existing records.

---

## 21. Implementation status — 2026-08-03

Backend is complete for the ON_REQUEST release. `mvnw test` is green (408 tests), the Spring context
boots against the migrated schema with `ddl-auto=validate`, and the two pre-existing defects §8
Step 10 flagged as blocking pre-work are fixed.

### Built

| Area | Where | Notes |
|---|---|---|
| Platform catalog + publish/unpublish | `hotelmarketplace/catalog/` | §5.1–5.4, §10.1 |
| Hotel Master projection + geo mapping | `hotelmarketplace/sync/` | §6; refuses to guess a city (§6.5) |
| **Version reconciliation** | `MarketplaceCatalogReconciliationScheduler` | Every catalog-version bump now marks linked projections STALE — previously only `publish()` did, so a corrected address reached a tenant only if somebody opened that hotel's detail page. A `LOCATION_MAPPING_REQUIRED` or `SOURCE_INACTIVE` projection is never overwritten with STALE: those are unresolved problems, not lag |
| Search / detail / import | `MarketplaceCatalogController` | §10.2 |
| Booking request + link-or-create | `MarketplaceBookingWriter`, `booking/api/CrmBookingLinkPort` | §8 Step 10, decisions 1–5 |
| SuperAdmin approve / reject / review | `MarketplaceApprovalOrchestrator` | Two-transaction shape + compensating scheduler |
| **Price revision** | `MarketplacePlatformWriter.requestRevision/acceptRevision/declineRevision/expireRevision` | §8 Step 6B, now reachable end to end |
| **Cancellation** | `settleCancellation`, `withdraw`, `requestCancellation` | §9 |
| **Inverse CRM→platform propagation** | `MarketplaceCrmCancellationListener` | Blocking defect #4, closed |
| **Voucher lifecycle + PDF** | `hotelmarketplace/voucher/` | §7; rendered on the fly, no Cloudinary, no money on the document |
| **Commission ledger** | `hotelmarketplace/commission/` | §5.12; append-only, signed amounts, idempotent on `reference_key`. `MarketplaceCommissionEarningScheduler` drives PENDING → EARNED (§8 Step 9) on **checkout passing**, not check-in — the conservative reading, so a no-show or an early departure never leaves an EARNED accrual needing a clawback |
| **Entitlement-neutral history** | `/api/me/hotel-bookings` | §20.5 — a lapsed add-on no longer hides a confirmed booking or its voucher |
| Notifications | `hotelmarketplace/notification/` | §13, both realms |
| Migration | `V2__lead_code.sql` PART 15 | Owner's one-file rule; re-stamped |
| Tests | `MarketplaceBookingStateMachineTest`, `MarketplaceMoneyVisibilityTest` | 26 tests over the transition guards, ownership and the money split |

### Three guards worth knowing about

1. **A tenant who accepted a revision accepted an amount.** `confirm()` 409s if the approved
   `tenantPayable` differs from what the tenant accepted. Without it the revision round-trip is
   decorative: the platform could put 4,400 to the tenant, take the yes, and confirm at 5,000.
2. **Retained commission can never exceed the cancellation charge.** Otherwise the platform pays its
   own margin out of the tenant's refund.
3. **`TENANT_APPROVAL_REQUIRED` is not approvable.** An unanswered offer cannot be confirmed — the
   whole point of §8 Step 6B.

### Deliberately NOT built

**Rate calendar, inventory/allotment and price holds (§5.6, §5.7, §5.10, Phase 2).**

This is a scope decision, not an omission. The release is ON_REQUEST throughout: there is no rate
source and no allotment, because a system that confirms automatically against inventory nobody
maintains sells rooms that do not exist (§19). A "price hold" over a rate that does not exist would
hold nothing, and `platform_hotel_price_holds` would be a table of tokens referencing no price.

Everything downstream is already shaped for it — the money fields are separate and snapshotted, the
approval is a guarded transition, and the ledger is append-only — so adding rates and inventory later
is additive. The concurrency work of §12 (last-room races, conditional inventory decrement) belongs
with it and is not needed until then.

Also outstanding, and genuinely optional: the add-on subscription lifecycle of §20.3
(`module_addon_products` / `tenant_module_subscriptions`). `HOTEL_MARKETPLACE` works today as a plan
module through the existing entitlement system; the separate product/renewal records matter only
when the add-on is sold on its own billing cycle.

### 21.1 Audit against this document — 2026-08-03

Every concrete checklist in this doc was walked against the code. Results:

**§8 blocking defects 1-7 — all seven fixed and verified.**
1. duplicate-lead guard runs in `createForMarketplace` (`CrmBookingLinkAdapter:118`);
2. resolved structurally by decision 3 — the payable lives inside `Booking.vendorCost`, so
   `CancellationCalculator` needed no change;
3. a downward restatement holds the floor at `paidAmount` and records the overpayment instead of
   throwing (`:264-272`), plus the attempt cap → `ABANDONED`;
4. `MarketplaceCrmCancellationListener`;
5. the upsert finders see soft-deleted rows and `restore()` them (`:209`, `:249`);
6. every tenant-facing read is ownership-scoped, and `lockOwned` covers the transitions;
7. `MARKETPLACE_PAYABLE_READ` enforced at BOTH sites — `BookingExpenseController:46` and
   `BookingTimelineServiceImpl:133`.

**One real gap the audit found, now fixed.** §6.1 trigger 2, §8 Step 6A item 6 and the §17 acceptance
criterion all require a *booking* — not just an explicit import — to create or reuse the tenant's
Hotel Master projection. It did not. A tenant who booked a catalog hotel without importing it first
got a confirmed stay and no Hotel Master row, leaving the hotel absent from the quotation builder,
the hotel dropdown and the itinerary — the whole purpose of Layer C.
`MarketplaceApprovalOrchestrator.projectHotelMaster` closes it: best-effort and last, because
`importOrSync` legitimately refuses a first import whose geography it cannot resolve (§6.5 forbids
guessing a city) and a convenience projection must never undo a confirmation.

**Deliberate divergences from this document — code is right, the text above is stale.**

| This doc says | Code does | Why |
|---|---|---|
| `/api/platform/hotel-catalog/**`, `/api/platform/hotel-bookings` | `/api/super-admin/hotel-catalog`, `/api/super-admin/marketplace/bookings`, `/api/super-admin/marketplace/commissions` | `/api/super-admin` is the established console realm and is already in `ModuleAccessFilter`'s `ALWAYS_ALLOWED`; a second platform prefix would need its own coverage entry for no gain |
| `GET /api/hotel-marketplace/search` | `GET /api/hotel-marketplace/hotels` | consistent with the detail route beneath it |
| `POST /hotels/{id}/sync-to-master` | `POST /hotels/{id}/import` | same operation, and `import` is what the FE and the service already call it |
| permission `HOTEL_MARKETPLACE_VIEW_FINANCIALS` | `MARKETPLACE_PAYABLE_READ` | superseded by §8 decision 4, which scopes it to marketplace expense rows only |
| `POST /bookings/{id}/cancellation-quote` | absent | needs a machine-readable cancellation policy; this release snapshots terms as free text, so a "quote" would be invented. The SuperAdmin enters the charge they actually agreed with the hotel |
| `POST /offers/recheck`, `POST /price-holds` | absent | Phase 2 — see the deferred list above |
