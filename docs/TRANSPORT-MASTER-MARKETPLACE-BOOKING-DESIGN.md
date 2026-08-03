# Transport Master Sync + Superadmin-approved Booking

**Status:** Proposed detailed design  
**Project:** TravelCRM Backend  
**Updated:** 2026-08-02  
**Scope:** Optional Transport Marketplace subscription, Vehicle Master sync, quotation, tenant request, Superadmin approval, assignment, voucher and platform earning

---

## 1. Requirement

TravelCRM must support two transport sources.

### Tenant private Vehicle Master

- Tenant adds Sedan, SUV, Tempo Traveller, Bus or other quotation options.
- The data belongs only to that tenant.
- Tenant enters its own price.
- Platform approval and commission do not apply automatically.

### Superadmin Transport Marketplace

- Superadmin controls products, routes, service areas, rates, suppliers and availability.
- Subscribed tenants see eligible products under **Book via Platform**.
- Customer accepts a quotation and final transport option.
- Tenant sends a booking request; tenant cannot self-confirm.
- Superadmin verifies, approves/revises/rejects, assigns vehicle/driver and issues voucher.
- Platform earning is stored in a separate ledger.
- Selected product synchronizes with tenant Vehicle Master without exposing internal cost.

~~~text
Catalog -> Quotation -> Customer Acceptance -> Tenant Request
                                             |
                                      Superadmin Review
                                      /      |       \
                                  Approve  Revise   Reject
                                     |
                          Assignment + Voucher + CRM Link
~~~

Golden rules:

~~~text
Quotation accepted != Transport confirmed
Tenant request submitted != Superadmin approved
Catalog product != Actual assigned vehicle
Vehicle Master != Fleet vehicle
~~~

---

## 2. Existing backend and boundaries

### Vehicle Master

Current master.vehicle.VehicleEntity and vehicle_master are tenant quotation master data.

- Entity extends BaseTenantEntity.
- Database tenant_id is NOT NULL.
- Optional City is tenant-scoped.
- Current data: name, type, capacity, description, image and city.
- APIs: /api/vehicles.

The current code contains a global-null-tenant idea, but the schema/base contract requires a tenant. Do not insert null tenant IDs and do not create a fake platform tenant.

### Quotation vehicle

Current quotation snapshot contains type, pickup/drop, dates, price, per-vehicle price, quantity, notes and image. It lacks stable master/platform references, service/rate source, route legs, occupancy, availability and request state.

### CRM Booking Service Item

BookingServiceItem can store a generic TRANSPORT service, but needs logical marketplace booking and Vehicle Master links.

### Fleet

The fleet package is a tenant operational diary for actual vehicles, drivers, trips, fuel, maintenance and compliance. Marketplace commercial booking remains separate. Fleet integration is optional and happens only after approval through a tenant-safe command.

---

## 3. Ownership

| Area | Superadmin | Tenant |
|---|---|---|
| Platform product/rate/route | Full control | Eligible search/read |
| Supplier cost/platform earning | Full control | Hidden |
| Private Vehicle Master | No routine edit | Tenant controls |
| Synced master source fields | Platform controls | Read/use |
| Customer selling price | Support/audit if authorized | Tenant controls |
| Booking request | Cross-tenant review | Own request only |
| Revised price | Propose | Accept/decline |
| Final confirmation | Approve/reject | Cannot approve |
| Vehicle/driver assignment | Manage | Operational view |
| Voucher | Generate/upload/issue | Secure download |
| Earning ledger | Full control | Optional summary |

Suggested permissions:

~~~text
TRANSPORT_MARKETPLACE_VIEW
TRANSPORT_MARKETPLACE_REQUEST
TRANSPORT_MARKETPLACE_ACCEPT_REVISION
TRANSPORT_MARKETPLACE_CANCEL
TRANSPORT_MARKETPLACE_SYNC_MASTER
TRANSPORT_MARKETPLACE_VIEW_FINANCIALS
~~~

Permissions work only after module entitlement.

---

## 4. Supported services and rates

Services:

~~~text
AIRPORT_TRANSFER
RAILWAY_TRANSFER
POINT_TO_POINT
LOCAL_PACKAGE
OUTSTATION_ONE_WAY
OUTSTATION_ROUND_TRIP
MULTI_DAY_TOUR
HOURLY_RENTAL
CUSTOM
~~~

Rate models:

~~~text
FLAT_PER_TRANSFER
FLAT_PER_VEHICLE
PER_KILOMETRE
PER_DAY
PER_HOUR
PACKAGE
ROUTE_FIXED
CUSTOM_QUOTE
~~~

Every offer identifies whether fuel, toll, parking, interstate permit/tax, driver allowance, night halt, waiting, extra km/hour and GST are included, excluded, fixed, estimated or payable on actuals.

Examples:

- Airport transfer: fixed per vehicle.
- Local package: 8 hours/80 km plus extra-km/hour.
- Outstation: per km with minimum billable km/day.
- Multi-day: day/km plus driver allowance/night halt.
- Bus: route-fixed or custom quote.

---

## 5. Architecture

~~~text
Platform Transport Catalog
        |
        | one-way versioned projection
        v
Tenant Vehicle Master

Quotation Vehicle Snapshot
        |
        v
Platform Transport Booking Request
        |
        | Superadmin approval
        v
Confirmed Booking + Assignment + Voucher
        |
        +-- CRM Booking Service Item
        +-- Platform Earning Ledger
        +-- Optional Fleet Trip
~~~

Suggested package:

~~~text
com.crm.travelcrm.transportmarketplace
  catalog
  pricing
  availability
  sync
  booking
  assignment
  voucher
  commission
  integration
  audit
~~~

Platform entities extend BaseEntity. Cross-tenant transaction rows contain explicit tenant_id. Tenant repositories always scope by tenant.

---

## 6. Data model

Use BigDecimal/numeric for amounts. Pickup/reporting timestamps must carry a defined service timezone.

### 6.1 platform_transport_products

Fields:

~~~text
id/public_id
name and vehicle_type
status: DRAFT | ACTIVE | INACTIVE | SUSPENDED
passenger and luggage capacity
air-conditioned/public amenities
description and image
confirmation_mode: SUPERADMIN_APPROVAL
catalog_version
audit/soft-delete
~~~

The product is normally a category, not a registration-number vehicle.

### 6.2 platform_transport_service_areas

~~~text
product_id
country/state/city/zone codes or normalized names
airport/station code
service radius
pickup/drop allowed
active
~~~

### 6.3 platform_transport_routes

~~~text
route_code
origin type/code/name/coordinates
destination type/code/name/coordinates
estimated distance/duration
one-way/round-trip flags
active
~~~

Guest-facing addresses are snapshotted even with a canonical route.

### 6.4 platform_transport_rate_cards

~~~text
product and supplier references
service_type and rate_model
route/service-area scope
currency and supplier base
included km/hours
minimum billable km/day
extra-km/hour
driver/night/waiting charges
validity and days
inclusion/exclusion flags
active and version
~~~

### 6.5 platform_transport_commercial_rules

~~~text
model: NET_RATE_MARKUP | SUPPLIER_PAID_COMMISSION
calculation: PERCENTAGE | FIXED
value, validity, priority
product/service/supplier filters
active
~~~

Resolve one rule and snapshot it.

### 6.6 platform_transport_availability

Category availability:

~~~text
product_id
service date/time window
total, held and confirmed units
stop_sell
version
~~~

Actual vehicle/driver assignment rejects overlapping reporting-to-release windows including buffer time. If availability is manual, product displays **On Request** until Superadmin approval.

### 6.7 Vehicle Master sync

Add to vehicle_master:

~~~text
origin: TENANT | PLATFORM_SYNC
platform_transport_product_public_id
platform_catalog_version
sync_status: SYNCED | STALE | LOCATION_MAPPING_REQUIRED | SOURCE_INACTIVE
last_synced_at
marketplace_bookable
~~~

Unique linked product per tenant for non-deleted rows.

Sync only public product name, type, capacity, description, image and amenities. Never sync supplier rate, platform earning, inventory, actual registration/driver or another tenant price.

### 6.8 Quotation link

Add to quotation_vehicles:

~~~text
vehicle_source: TENANT_MASTER | PLATFORM
vehicle_master_public_id
platform product/rate/route public IDs
service_type
pickup_at / expected_release_at
passengers/luggage
quoted tenant payable
quoted customer selling amount
pricing and terms snapshots
quoted_at
~~~

Existing display fields remain historical snapshots.

### 6.9 Price hold

~~~text
tenant and product/rate/route references
pickup/drop/time/service/quantity
passenger/luggage
supplier amount and extras
platform earning and tenant payable
terms
expiry and status
opaque token
~~~

Statuses: ACTIVE, CONSUMED, EXPIRED, RELEASED. CUSTOM_QUOTE cannot confirm until tenant accepts the stored final price.

### 6.10 platform_transport_bookings

Core fields:

~~~text
tenant and request code
idempotency key
quotation/quotation-vehicle links
CRM booking/service links
Vehicle Master projection link
product/rate/route links
service, pickup/drop/time and route snapshots
passenger/luggage/guest/special requests
supplier amount
platform earning
tenant payable
customer selling amount and tenant margin
tax/extras/cancellation snapshots
status, payment and voucher status
approval/rejection/revision audit
version
~~~

States:

~~~text
DRAFT
REQUESTED
UNDER_REVIEW
TENANT_APPROVAL_REQUIRED
TENANT_ACCEPTED
CONFIRMED
REJECTED
CANCEL_REQUESTED
CANCELLED
IN_PROGRESS
COMPLETED
FAILED
EXPIRED
~~~

Only Superadmin confirms.

### 6.11 Booking legs

Each booking has one or more ordered legs with service date, pickup time/location, drop, distance and instructions. Multi-leg data supports multi-day tours and replacement without rewriting original booking.

### 6.12 Assignments

Append/version:

~~~text
booking/leg
supplier snapshot
specific vehicle reference
registration, make/model, colour
driver name/phone
assigned_at/by and validity
status and change reason
~~~

Breakdown/replacement closes old assignment and creates a new one.

### 6.13 Vouchers

~~~text
booking ID and document number
version
source: SYSTEM_GENERATED | SUPERADMIN_UPLOAD
private storage/checksum
status: DRAFT | ISSUED | REVOKED
issued audit
replaces voucher reference
~~~

Assignment change creates a new version.

### 6.14 Platform earning ledger

~~~text
booking and tenant
entry: ACCRUAL | REVERSAL | ADJUSTMENT | SETTLEMENT
amount/currency
status: PENDING | EARNED | REVERSED | SETTLED
effective date, reason and idempotency reference
~~~

Never use tenant Booking.netProfit as platform earning.

---

## 7. Vehicle Master synchronization

Lazy sync occurs when tenant imports/selects a product, submits request, approval lacks projection, or catalog version is stale. Do not copy every platform product to every tenant.

For PLATFORM_SYNC rows, platform controls public source fields. Tenant local defaults/tags/markup stay local. Tenant update rejects source-field edits.

Unpublish removes new sale, marks linked row SOURCE_INACTIVE and preserves quotation, confirmed booking, assignment and voucher.

Deduplicate by platform product UUID, not display name.

---

## 8. Quotation visibility

~~~text
Transportation
+-- My Vehicles
|   +-- current tenant private Vehicle Master
+-- Book via Platform
    +-- active eligible Superadmin products
~~~

**My Vehicles:** tenant-only, manually priced, no automatic platform earning.

**Book via Platform:** active/published, serviceable route, valid rate/custom quote, capacity fit, available or On Request, and tenant has active entitlement.

Never show another tenant private vehicle or DRAFT/INACTIVE/SUSPENDED product. Quotation acceptance is not confirmation.

---

## 9. Tenant request

1. Customer accepts one final transport option.
2. Tenant clicks **Send Transport Booking Request**, not Confirm Transport.
3. Backend rechecks ownership, product/rate, route/time, passenger/luggage, availability, price, terms and duplicates.
4. Client-posted price is never authoritative.
5. Create one idempotent REQUESTED row and pending CRM Transport service.
6. Notify: request submitted, awaiting Superadmin.

At request time no actual assignment/voucher or earned commission exists. Controlled capacity may be held with expiry.

---

## 10. Superadmin approval

Dashboard queues:

~~~text
New
Under Review
Tenant Approval Required
Tenant Accepted
Confirmed
Assignment Pending
Voucher Pending/Issued
Rejected
Cancelled/Expired
~~~

Review shows tenant/CRM reference, legs, passenger/luggage, quoted/current amounts, Superadmin-only supplier cost/earning, availability, payment, terms and audit.

### Revision

~~~text
UNDER_REVIEW
 -> TENANT_APPROVAL_REQUIRED
 -> TENANT_ACCEPTED or TENANT_DECLINED
~~~

Store old/new amount, difference, reason, changed inclusions/terms and deadline. Tenant acceptance still requires final Superadmin approval.

### Reject

Store reason, release capacity/payment authorization, create no earning and notify tenant.

### Approve

Verify supplier, product, legs/times, final amounts, inclusions, terms, confirmation and assignment details.

Approval transaction:

1. lock/recheck availability and overlaps;
2. commit capacity/assignment;
3. consume/release hold;
4. mark CONFIRMED with Superadmin audit;
5. create one PENDING earning accrual;
6. create/reuse Vehicle Master projection;
7. confirm CRM Transport service;
8. store assignment snapshots;
9. generate/upload voucher or mark pending.

---

## 11. Assignment and voucher

Assignment may occur at approval or by a strict pre-reporting deadline. Changes preserve history, validate overlap, store reason, avoid silent finance changes, reissue voucher and notify tenant.

Voucher may be Superadmin-uploaded supplier duty slip or system-generated branded document.

Recommended action:

~~~text
Approve & Issue Voucher
~~~

Voucher shows booking/confirmation, passenger, pickup/drop/legs, category, assigned registration/driver when allowed, guest-facing inclusions/terms and support. It hides supplier net, platform earning, internal notes and contracts.

~~~text
bookingStatus = CONFIRMED
voucherStatus = NOT_ISSUED | ISSUED | REVOKED
~~~

Use authenticated tenant-owned download.

---

## 12. CRM and optional Fleet integration

CRM service on request:

~~~text
serviceType = TRANSPORT
status = PENDING
cost = customer selling amount
vendorCost = tenant payable to platform
marketplaceTransportBookingPublicId
vehicleMasterPublicId
~~~

Approval updates status/confirmation.

Raw supplier cost is never tenant-visible vendorCost.

Do not auto-create FleetTrip. A future explicit command may create dispatch only when valid tenant FleetVehicle/FleetDriver ownership exists. Platform booking remains commercial truth; Fleet remains operational truth.

---

## 13. Pricing example

~~~text
Supplier amount             Rs 5,000
Platform earning            Rs   500
Tenant payable              Rs 5,500
Customer selling amount     Rs 6,200
Tenant margin               Rs   700
~~~

~~~text
tenantPayable = supplierAmount + platformEarning
tenantMargin = customerSellingAmount - tenantPayable
~~~

Timing:

~~~text
Request -> no commission
Reject -> no commission
Approve -> PENDING accrual
Voucher -> no duplicate accrual
Service milestone -> EARNED
Cancel/refund -> REVERSAL/ADJUSTMENT
Settlement -> SETTLED
~~~

GST/TDS/supplier settlement needs accountant-approved configuration.

---

## 14. Cancellation and amendment

Cancellation uses confirmed snapshot, releases capacity/assignment, records supplier/refund effects, appends ledger reversal/adjustment, cancels CRM service, revokes voucher and notifies tenant.

Material pickup/route/quantity/category/passenger changes create an amendment review and tenant approval if price/terms change. Preserve old/new snapshot and issue new voucher version.

No-show, overtime, extra km and actual toll/parking are adjustment events; do not rewrite original quote.

---

## 15. APIs

Superadmin catalog:

~~~http
POST/GET /api/platform/transport-catalog/products
PUT      /api/platform/transport-catalog/products/{id}
PATCH    /api/platform/transport-catalog/products/{id}/status
POST     /api/platform/transport-catalog/products/{id}/service-areas
POST     /api/platform/transport-catalog/routes
POST     /api/platform/transport-catalog/products/{id}/rate-cards
PUT      /api/platform/transport-catalog/products/{id}/availability
~~~

Superadmin request:

~~~http
GET  /api/platform/transport-bookings
POST /api/platform/transport-bookings/{id}/start-review
POST /api/platform/transport-bookings/{id}/request-tenant-approval
POST /api/platform/transport-bookings/{id}/approve
POST /api/platform/transport-bookings/{id}/reject
POST /api/platform/transport-bookings/{id}/assignments
POST /api/platform/transport-bookings/{id}/vouchers/generate
POST /api/platform/transport-bookings/{id}/vouchers/upload
POST /api/platform/transport-bookings/{id}/vouchers/{voucherId}/issue
~~~

Tenant active marketplace:

~~~http
GET  /api/transport-marketplace/search
POST /api/transport-marketplace/offers/recheck
POST /api/transport-marketplace/price-holds
POST /api/transport-marketplace/bookings
POST /api/transport-marketplace/bookings/{id}/accept-revision
POST /api/transport-marketplace/bookings/{id}/decline-revision
POST /api/transport-marketplace/bookings/{id}/cancel
POST /api/transport-marketplace/products/{id}/sync-to-master
~~~

Historical access:

~~~http
GET  /api/me/transport-bookings
GET  /api/me/transport-bookings/{id}
GET  /api/me/transport-bookings/{id}/voucher
POST /api/me/transport-bookings/{id}/cancellation-request
~~~

Use UUID public IDs only.

---

## 16. Plug-and-play optional subscription

Stable module key:

~~~text
TRANSPORT_MARKETPLACE
~~~

Private Vehicle Master remains under MASTERS. A tenant can use CRM quotation without buying platform transport.

Hard gate:

~~~text
/api/transport-marketplace/** -> TRANSPORT_MARKETPLACE
~~~

Frontend reads /api/me/entitlements; backend ModuleAccessFilter enforces access.

Effective modules should be base plan plus active paid add-ons and grants minus suspensions. Use explicit add-on product/subscription records with TRIAL, ACTIVE, PAST_DUE, SUSPENDED, CANCELLED and EXPIRED.

On expiry:

- block search/import/new request;
- block new revision acceptance outside grace;
- keep confirmed booking/voucher read-only access;
- allow cancellation/refund/support;
- continue operational notifications, assignments and settlement.

Historical access uses the tenant-owned /api/me path because active marketplace prefix is subscription-gated.

Optional global kill switch:

~~~properties
app.transport-marketplace.enabled=true
~~~

It is operational, not billing state.

---

## 17. Security, concurrency and idempotency

Tenant lookup always includes public ID + tenant ID + non-deleted condition. Tenant A gets 404 for Tenant B records.

Superadmin actions require PLATFORM_ADMIN and PlatformContext. Audit review, revision, approval, rejection, assignment, voucher and finance.

Separate tenant/Superadmin DTOs. Tenant never receives supplier cost, platform earning, contract/internal note or foreign tenant data.

Controls:

- atomic category capacity;
- overlapping actual vehicle/driver check;
- version/locks;
- centralized legal state transitions;
- tenant-scoped unique idempotency keys.

Idempotency required for request, approval, revision acceptance, payment, voucher, cancel/refund and ledger entries.

---

## 18. Notifications and reports

Tenant: submitted, review, revision, confirmed/rejected, assignment, voucher, cancellation/refund.

Superadmin: new/aging request, accepted revision, pickup approaching without assignment/voucher, conflict, payment failure and cancellation.

Superadmin reports: status, tenant/product/area, supplier/tenant payable, earning ledger, turnaround, rejection, assignment/voucher SLA and cancellations.

Tenant reports: own booking, customer price, payable/margin, assignment, voucher and cancellation.

---

## 19. Implementation phases

1. **Catalog, entitlement and sync:** module key/gate, product/service area CRUD, Vehicle Master projection, quotation source links.
2. **Pricing/search:** route/rate models, commercial resolver, recheck/hold, Custom Quote.
3. **Manual approval:** request state machine, queue, revision, approve/reject, CRM link, capacity.
4. **Assignment/voucher:** legs, versioned assignment, secure voucher and notifications.
5. **Finance/lifecycle:** earning ledger, cancellation/refund/extras, reports and tax/settlement.
6. **Optional Fleet:** explicit tenant-safe dispatch command; no fake/global Fleet rows.

---

## 20. Migration plan

1. Platform product/service-area/route/rate/commercial tables.
2. Vehicle Master sync fields; backfill origin TENANT.
3. Quotation source/logical links and snapshots.
4. Price hold, booking and leg tables.
5. Assignment and voucher tables.
6. Earning ledger.
7. BookingServiceItem logical links.
8. Indexes, partial uniqueness and idempotency constraints.
9. Add-on product/subscription catalogue.

Do not auto-convert tenant private masters into platform products.

---

## 21. Tests

Unit: all rate models/extras, earning, eligibility, state transitions, revision expiry, cancellation and sync version.

Integration:

- publish to eligible tenant quotation;
- foreign private vehicle invisible;
- one projection only;
- tenant cannot self-confirm;
- revision needs tenant acceptance plus Superadmin approval;
- approval creates one CRM service and one accrual;
- rejection releases holds;
- voucher unavailable before confirmation;
- assignment change versions voucher;
- later master/rate edit leaves snapshots;
- expired add-on blocks new sale but preserves voucher.

Concurrency: last unit confirms once, no overlapping assignment, duplicate calls create one effect, approve/reject race has one result.

Security: tenant cannot write catalog, cross-tenant request/voucher is 404, internal money hidden, approval requires platform context, voucher private.

---

## 22. Acceptance criteria

- Global catalog works without tenant context.
- TRANSPORT_MARKETPLACE is independently subscribable.
- Non-subscriber keeps private Vehicle Master/Quotation but cannot use marketplace.
- Quotation separates My Vehicles and Book via Platform.
- Sync creates/reuses one projection.
- Customer acceptance does not imply confirmation.
- Tenant requests but cannot approve.
- Superadmin can review/revise/reject/approve with audit.
- Revision needs tenant acceptance and final approval.
- Approval creates confirmed CRM service and exactly one accrual.
- Assignments are overlap-safe/versioned.
- Voucher is secure/versioned and hides margin.
- Expired subscriber retains confirmed voucher/cancellation support.
- Fleet stays separate until explicit integration.
- Historical snapshot/ledger does not change with current master edits.

---

## 23. First vertical slice

1. Superadmin publishes 6 Seater SUV.
2. Subscribed tenant sees Book via Platform.
3. Selection creates/reuses master projection.
4. Customer accepts quotation.
5. Tenant sends request.
6. Superadmin approves with supplier/vehicle/driver snapshots.
7. CRM service and one pending earning entry are created.
8. Superadmin issues voucher; tenant downloads securely.
9. Catalog edit syncs master but not confirmed snapshot.
10. Subscription suspension blocks new request but not issued voucher.

---

## 24. Decisions before production

- First rate models.
- Assignment mandatory at approval or later deadline.
- Wallet/credit/online/manual payment.
- Hold/revision expiry.
- Actual extras policy.
- Cancellation/no-show/overtime.
- GST/TDS and supplier settlement.
- Private vehicle onboarding.
- Platform fleet versus external suppliers.
- Monthly/yearly add-on price and grace.

Recommended: Superadmin approval for every request, category product with assignment by voucher deadline, hide platform earning, show On Request without controlled inventory, require payment/credit authorization, sell TRANSPORT_MARKETPLACE as separate add-on, and defer Fleet integration.

