# Standalone Fleet Diary Product — Current State and Implementation Plan

**Audit date:** 31 July 2026  
**Repository audited:** `travelcrmbackend`  
**Target:** Existing Fleet / Vehicle Diary capability ko aise product me badalna jo:

1. Travel CRM ke saath optional module ke roop me chale; aur
2. CRM kharide bina independently sell aur deploy kiya ja sake.

This document is an implementation plan, not a claim that all listed features already exist.
Section 2 and Section 3 explicitly separate **present**, **partial**, and **missing** behavior.

> Terminology: user ke “Bansar” ko product/API me `BHANSAR_NEPAL` code aur UI me
> **Bhansar / Bansar (Nepal Border)** label diya jayega. Toll aur Parking alag expense types
> rahenge; “Toll Parking” ko ek combined type nahi banaya jayega, warna reporting inaccurate hogi.

---

## 1. Recommended product decision

### 1.1 One Fleet bounded context, two product surfaces

Do not create a second copied codebase. Existing backend me ek self-contained Fleet bounded
context rakhein aur usi APIs ko do product surfaces se expose karein:

```text
Fleet-only Web/PWA ───────┐
                          ├── /api/fleet/** ── Fleet domain ── PostgreSQL
Travel CRM Fleet screens ─┘                         │
                                                   ├── optional CRM booking adapter
                                                   ├── optional CRM vendor adapter
                                                   ├── optional Vehicle Master adapter
                                                   ├── notification adapter
                                                   └── private file-storage adapter
```

Recommended commercial modes:

| Mode | Customer sees | Required modules | CRM dependency |
|---|---|---|---|
| `FLEET_STANDALONE` | Fleet dashboard, vehicles, drivers, diary, expenses, compliance, reports, users and settings | `FLEET` | None |
| `CRM_SUITE` | Existing CRM plus optional Fleet navigation | Plan-selected CRM modules + `FLEET` | Booking/vendor adapters may be enabled |

This is plug-and-play commercially without prematurely extracting a microservice. A separate
service/JAR can be considered later only if a white-label customer requires an independently
versioned binary.

### 1.2 What “plug-and-play” must mean at launch

A Fleet-only customer must be able to:

- receive a tenant/company account without buying Leads, Booking, Quotation or Vendor modules;
- log in through a Fleet-branded URL and see no CRM navigation;
- complete a setup wizard, add vehicles/drivers and start a diary without creating a booking;
- record all requested costs with receipts and approval history;
- receive compliance-expiry alerts;
- invite staff with Fleet-specific roles and permissions;
- import starting data from CSV and export reports;
- use the hosted SaaS immediately, or start the self-hosted package with one documented Compose
  deployment;
- later upgrade to the CRM suite without migrating Fleet data.

---

## 2. Current backend state

### 2.1 Already present

| Capability | Current implementation | Evidence |
|---|---|---|
| Tenant isolation | Fleet entities extend `BaseTenantEntity`; APIs expose UUID `publicId` | `fleet/entity/*`, `docs/FLEET_MODULE.md` |
| Vehicle master | Number, type, make/model/year, seats, owner type, optional vendor snapshot, status and odometer | `FleetVehicle.java` |
| Driver master | Driver profile, licence expiry and active/inactive state | `FleetDriver.java` |
| Vehicle diary/trips | Planned, ongoing, completed and cancelled lifecycle; vehicle and driver assignment; odometer/distance; route/purpose | `FleetTrip.java`, `FleetTripServiceImpl.java` |
| Trip start/close | Start and close APIs synchronize vehicle status and validate odometers/dates | `FleetTripController.java`, `FleetTripServiceImpl.java` |
| Fuel diary | Per-vehicle fuel logs with date, litres, cost, odometer and notes | `FleetFuelLog.java`, `FleetFuelLogController.java` |
| Maintenance diary | Service date/type/cost/vendor/odometer and next-service fields | `FleetMaintenanceLog.java`, `FleetMaintenanceLogController.java` |
| Basic documents | Vehicle insurance, RC, one generic permit and PUC expiry dates; driver licence expiry | `FleetVehicle.java`, `FleetDocumentType.java` |
| Alerts | Daily expiry scan and in-app notifications | `FleetDocumentExpiryScheduler.java`, `FleetExpiryScanService.java` |
| Dashboard | Vehicle/driver counts, trips, expiring documents, service due, monthly fuel + maintenance spend | `FleetDashboardDto.java`, `FleetDashboardServiceImpl.java` |
| Fleet permissions | `FLEET_READ`, `FLEET_CREATE`, `FLEET_UPDATE`, `FLEET_DELETE` | `permission/enums/Permission.java` |
| Module entitlement | `/api/fleet/**` requires tenant module key `FLEET` | `platform/entitlement/filter/ModuleAccessFilter.java` |
| CRM plan availability | `FLEET` is currently included in Pro and Enterprise seed plans | `PlanCatalogueInitializer.java` |
| Optional booking link | Trip can snapshot a CRM booking ID/code | `FleetTrip.booking*`, `FleetBookingLookupRepository.java` |
| Optional vehicle owner link | Vendor/rented vehicles can snapshot CRM vendor details | `FleetVehicle.vendor*`, `FleetVendorLookupRepository.java` |
| Vehicle Master catalogue | CRM has a separate `vehicle_master` catalogue with name, type, capacity, description and image; global + tenant rows are supported | `master/vehicle/VehicleEntity.java` |
| Fleet ↔ Vehicle Master link | **Not present**; operational `FleetVehicle` has no master vehicle ID or sync workflow | `FleetVehicle.java`, `docs/FLEET_MODULE.md` extension notes |
| Own/Rented source on Vehicle Master | **Not present**; current master DTO/entity has no ownership/source field and saving a master never creates a Fleet asset | `VehicleEntity.java`, `VehicleRequestDTO.java`, `VehicleServiceImpl.java` |
| Booking transport service | Generic `BookingServiceItem` can represent Transport/Vehicle with service dates and optional vendor, but has no master-template or Fleet assignment | `BookingServiceItem.java` |
| Booking ↔ Fleet assignment | Fleet trip can snapshot the whole booking, but not a specific transport service; vehicle and driver are required when a Fleet trip is created | `FleetTrip.java`, `FleetTripCreateDto.java` |
| Soft delete/trash | User-facing Fleet entities participate in existing trash lifecycle | `docs/FLEET_MODULE.md`, `TrashableType.java` |

Current APIs are under `/api/fleet/**` for vehicles, drivers, trips, fuel logs, maintenance logs,
dashboard and alerts. This is a strong starting point: the operational core does not need to be
rewritten.

### 2.2 Existing financial behavior

`FleetTrip` currently has only three scalar trip totals:

- `fuelCost`
- `tollCost`
- `driverAllowance`

`FleetTripResponseDto.totalExpense` is based on those trip-level fields. These values have no
individual receipt, location, currency, approval, payer, payment, compliance document or audit
workflow.

There is also a separate `BookingExpense` ledger with category, description, vendor name, amount,
paid amount, payment status, dates, reference number and notes. It cannot be reused as the Fleet
source of truth because it:

- requires a CRM booking;
- is protected by Booking permissions and routes;
- does not link to a Fleet trip/vehicle/driver;
- uses a free-text category, which is unreliable for statutory and vehicle-wise reporting.

The reusable ideas are its settlement calculator and amount validations—not its entity or API.

### 2.3 Requested feature coverage

| Requested item | Status now | What is missing |
|---|---|---|
| Toll | **Partial** | Only one `tollCost` total on a trip; no plaza, FASTag transaction, receipt, individual rows or approval |
| Parking | **Missing** | No field, ledger row, receipt or report |
| Bhansar/Bansar Nepal | **Missing** | No border post, receipt/document number, validity, NPR/INR or trip linkage |
| Permit India | **Partial** | One generic `permitExpiry` on vehicle; no permit type, state, number, validity history, payment or receipt |
| Permit Nepal | **Missing** | No separate Nepal permit or country-specific validity/payment record |
| Road Tax India | **Missing** | No state, tax period, receipt, validity or amount ledger |
| Fine / Challan | **Missing** | No challan, authority, offence, due/paid status, driver recovery or attachment |
| Receipt attachments | **Missing in Fleet** | No private receipt/document upload and download API |
| Expense approval | **Missing** | No draft/submit/approve/reject lifecycle |
| Expense settlement | **Missing** | No driver reimbursement, partial payment or payment reference ledger |
| INR/NPR support | **Missing** | Fleet cost fields carry no currency or historical FX snapshot |
| Category reports | **Missing** | Dashboard includes fuel + maintenance only; it does not include toll or other requested costs |

### 2.4 Current blockers to standalone sale

1. `FleetTripServiceImpl` and `FleetBookingLookupRepository` import the CRM `Booking` entity.
2. `FleetVehicleServiceImpl` and `FleetVendorLookupRepository` import the CRM `Vendor` entity.
3. Fleet expiry notifications directly use CRM staff `UserRepository` and the shared notification
   implementation instead of a Fleet-facing port.
4. Fleet is an entitlement, but there is no Fleet-only subscription plan or vehicle/driver usage
   limit. Current `STARTER/PRO/ENTERPRISE` plans are CRM bundles.
5. Current vehicle `permitExpiry` cannot represent multiple permits, states or countries.
6. Financial edits have only the four broad Fleet permissions; there is no separate approval or
   payout authority.
7. There are no Fleet-specific automated tests in `src/test`; only Booking expense tests are
   currently present.
8. The current workspace contains the backend only. Frontend Fleet screens must be audited before
   implementation; frontend behavior is not marked “present” in this document without that audit.
9. CRM Vehicle Master and operational Fleet Vehicle are currently separate and have no optional
   link. Fleet cannot use a master row as a creation template today.
10. Booking has no explicit optional dispatch/assignment model. Linking a booking directly to the
    current `FleetTrip` forces a physical vehicle/driver too early and cannot represent “vendor or
    customer will arrange the vehicle; no Fleet assignment needed”.

---

## 3. Target domain design

### 3.1 Keep operational, financial and legal records separate

| Record | Purpose | Examples |
|---|---|---|
| `FleetTrip` | Vehicle movement/duty diary | route, start/end, odometer, driver |
| `FleetExpense` | A money event | toll payment, parking fee, Bhansar, permit charge, road tax, fine |
| `FleetComplianceDocument` | Proof that the vehicle/driver is legally valid | RC, insurance, PUC, permit certificate, road-tax receipt/validity |
| `FleetExpenseSettlement` | Money paid/reimbursed against an expense | office paid vendor, driver reimbursement, recovery from driver |
| `FleetAttachment` | Private evidence | receipt image/PDF, permit scan, challan copy |

India/Nepal permit and road-tax payments may create both:

- an expense row, because money was paid; and
- a compliance document, because the resulting document has a validity period.

The two rows may link to each other, but neither should replace the other.

### 3.2 Expense type catalogue

Create a stable enum `FleetExpenseType`:

```java
TOLL,
PARKING,
BHANSAR_NEPAL,
PERMIT_INDIA,
PERMIT_NEPAL,
ROAD_TAX_INDIA,
TRAFFIC_FINE,
FUEL,
DRIVER_ALLOWANCE,
MAINTENANCE,
OTHER
```

Required launch types are the first seven. `FUEL`, `DRIVER_ALLOWANCE` and `MAINTENANCE` allow a
single future reporting model, but the first migration must avoid double-counting their existing
logs/fields. Expose a read-only category endpoint so frontend labels are not duplicated:

`GET /api/fleet/expense-types`

Use enum codes for storage/reporting and localized labels in the response. `OTHER` requires a
custom category label. Do not store the main type as arbitrary free text.

### 3.3 `fleet_expenses` table

Create `FleetExpense extends BaseTenantEntity` with these fields:

| Group | Fields | Rules |
|---|---|---|
| Ownership | `vehicle_id` required, `trip_id` optional, `driver_id` optional | All resolved inside current tenant; if trip is supplied its vehicle/driver must match |
| Classification | `expense_type`, `custom_type`, `source` | `custom_type` only for `OTHER`; source = `MANUAL`, `IMPORT`, `INTEGRATION`, `LEGACY_MIGRATION` |
| Time | `expense_date`, `occurred_at` | Date required; time optional |
| Place | `country_code`, `state_code`, `location_name`, `border_post` | ISO country code; India state code where relevant |
| Reference | `receipt_number`, `transaction_reference`, `document_number`, `challan_number` | Category-specific validation and duplicate checks |
| Validity | `valid_from`, `valid_until` | Used by permit, road-tax and Bhansar records; `valid_until >= valid_from` |
| Money | `original_amount`, `currency`, `fx_rate_to_base`, `base_amount`, `base_currency` | Default base currency INR; server calculates base amount |
| Payment context | `paid_by`, `payee_name`, `payment_mode` | `paid_by`: `OFFICE`, `DRIVER`, `OWNER`, `CUSTOMER`, `OTHER` |
| Recovery | `recover_from`, `recoverable_amount` | `NONE`, `DRIVER`, `CUSTOMER`, `VENDOR`, `OTHER` |
| Workflow | `approval_status`, `submitted_at/by`, `approved_at/by`, `rejected_at/by`, `rejection_reason` | Financial transitions are server-owned |
| Fine details | `violation_type`, `issuing_authority`, `fine_due_date` | Used only for `TRAFFIC_FINE` |
| Linkage | `compliance_document_id`, `external_reference`, `idempotency_key` | No direct CRM FK needed |
| Notes | `description`, `notes` | Description required, notes optional |

Recommended enums:

```text
FleetExpenseApprovalStatus = DRAFT | SUBMITTED | APPROVED | REJECTED | VOID
FleetExpenseSource         = MANUAL | IMPORT | INTEGRATION | LEGACY_MIGRATION
FleetPaidBy                = OFFICE | DRIVER | OWNER | CUSTOMER | OTHER
FleetRecoveryParty         = NONE | DRIVER | CUSTOMER | VENDOR | OTHER
FleetPaymentMode           = CASH | UPI | BANK_TRANSFER | CARD | FASTAG | CHEQUE | OTHER
```

Money columns use `numeric(14,2)`. FX rate uses at least `numeric(18,8)`. Historical rows retain
the rate used at entry/approval time; later exchange-rate changes must not rewrite past reports.

### 3.4 `fleet_expense_settlements` table

Do not put only a mutable `paidAmount` on the expense. A small settlement ledger gives correct
partial reimbursement and payment history:

| Field | Purpose |
|---|---|
| `expense_id` | Parent expense |
| `settlement_date` | Date money moved |
| `amount`, `currency`, `base_amount` | Settlement value and base snapshot |
| `direction` | `PAYMENT` or `RECOVERY` |
| `payment_mode` | Cash/UPI/bank/card/etc. |
| `reference_number` | UTR, cheque or transaction reference |
| `paid_to_or_received_from` | Counterparty snapshot |
| `notes`, audit fields | Traceability |

Derived settlement status:

```text
UNPAID -> PARTIAL -> PAID
```

For a driver-paid toll, the expense may be approved immediately but remain `UNPAID` from the
company-to-driver reimbursement perspective. Fine recovery from a driver is recorded as a
`RECOVERY`, not as a negative expense.

### 3.5 `fleet_compliance_documents` table

Replace the one-column-per-document limit with a normalized document history.

Recommended document types:

```text
INSURANCE
REGISTRATION_CERTIFICATE
PUC
FITNESS_CERTIFICATE
NATIONAL_PERMIT_INDIA
STATE_PERMIT_INDIA
ROAD_TAX_INDIA
PERMIT_NEPAL
BHANSAR_NEPAL
DRIVER_LICENSE
OTHER
```

Fields:

- exactly one of `vehicle_id` or `driver_id`;
- `document_type`, `document_number`, `issuing_authority`;
- `country_code`, optional `state_code`, optional `border_post`;
- `issued_on`, `valid_from`, `valid_until`;
- `status`: `ACTIVE`, `EXPIRING`, `EXPIRED`, `REPLACED`, `REVOKED`;
- optional `expense_id` that paid for this document;
- attachment metadata/count;
- reminder thresholds and `last_alerted_threshold` or a separate idempotent alert history.

Keep full history. Renewing a permit creates a new document and marks the previous one `REPLACED`;
it must not overwrite the old validity and receipt.

### 3.6 Private attachments

Create `fleet_attachments` rather than saving a public receipt URL on an expense:

- `owner_type`: `EXPENSE` or `COMPLIANCE_DOCUMENT`;
- `owner_public_id`/internal FK;
- storage provider/key, original filename, MIME type, byte size and checksum;
- uploaded-by/at and soft-delete fields.

Introduce a `FleetFileStorage` port with S3/MinIO/Cloudinary adapters. Files are downloaded through
an authenticated, tenant-scoped API; do not expose a permanent public URL. Allow PDF, JPEG and PNG,
validate both declared MIME and file signature, cap file size/count and count usage against the
tenant storage quota.

### 3.7 Optional Vehicle Master reference on `FleetVehicle`

The two vehicle concepts must stay distinct:

| Concept | Meaning |
|---|---|
| CRM `VehicleEntity` / `vehicle_master` | A reusable quotation/catalogue template such as “Toyota Crysta”, with type, capacity, description and image |
| `FleetVehicle` / `fleet_vehicles` | A physical operational asset with registration number, ownership, documents, odometer, status, trips and expenses |

Add a required source choice for every new tenant-owned Vehicle Master:

```text
MasterVehicleSource = OWN | RENTED
```

UI label: **Vehicle Source — Own / Rented**. Existing/global catalogue rows may temporarily carry
`UNSPECIFIED` during migration; when a tenant uses such a row in a quotation, the source must be
selected and snapshotted on that quotation line.

Implement the field end-to-end in `VehicleEntity`, `VehicleRequestDTO`, `VehicleResponseDTO`,
`VehicleMapper` and Vehicle Master filters/exports. New tenant-created rows reject a missing source;
legacy/global rows are handled by the migration compatibility rule above.

When Master/Fleet integration is enabled:

- `RENTED` creates only the quotation/master template; it does not automatically create an
  operational Fleet asset;
- `OWN` must create at least one linked physical `FleetVehicle` in the same transaction;
- the Own form therefore expands a **Fleet Unit** section. `vehicleNumber` is mandatory because
  current `FleetVehicle` cannot represent a valid physical asset without it;
- `FleetVehicle.ownerType` is forced to `OWN`; master capacity seeds seating capacity; the user may
  additionally enter make, model, year, opening odometer and compliance dates;
- one Master may have several own Fleet units. Initial create accepts one or more registration
  numbers; “Add another Fleet unit” adds more later.

Creating an own Fleet inventory asset is not Booking assignment. It only makes the vehicle
available in Fleet; no booking requirement, driver or `FleetTrip` is created.

One master template may be linked to many physical Fleet vehicles. Add optional snapshot linkage to
`FleetVehicle`:

- `master_vehicle_public_id` — nullable logical UUID; no direct Fleet-domain entity relationship;
- `master_vehicle_name_snapshot`;
- `master_vehicle_image_snapshot`;
- `master_linked_at` and `master_synced_at`.

On initial link, Fleet may copy `type` and `capacity -> seatingCapacity` when the user has not
entered explicit values. Master does not contain registration, make, model, year, owner, odometer
or compliance data, so those remain Fleet-owned.

Use **snapshot semantics by default**. Updating or deleting a Vehicle Master row must not silently
rewrite historical/operational Fleet assets. An explicit sync action can preview and copy selected
catalogue fields. Fleet vehicle deletion must never delete the master row, and master soft-delete
must not break the Fleet vehicle because its snapshot remains available.

### 3.8 Non-negotiable optional-assignment invariant

Vehicle Master selection and physical Fleet assignment are two independent optional choices:

```text
Booking only
Booking + Vehicle Master requirement
Booking + optional vendor transport
Booking + optional physical Fleet Vehicle/Driver assignment
Standalone Fleet trip with no Booking and no Vehicle Master
```

All five are valid. In particular:

- booking create, confirm, update, voucher, invoice, complete or cancel must never require a Fleet
  vehicle;
- choosing a Vehicle Master/category must not automatically allocate a physical vehicle;
- enabling the Fleet module must not automatically create a `FleetTrip` on booking confirmation;
- a booking may finish with no Fleet assignment when transport is external, self-arranged or not
  required;
- only starting an actual Fleet-operated trip requires a concrete Fleet vehicle and driver.

---

## 4. Category-specific behavior

| Type | Required data | Important validation / behavior |
|---|---|---|
| `TOLL` | vehicle, date, amount, location/plaza | payment mode usually FASTag/cash; FASTag transaction reference should be unique per tenant when supplied |
| `PARKING` | vehicle, date, amount, location | optional entry/exit time; receipt recommended above configurable amount |
| `BHANSAR_NEPAL` | vehicle, date, amount, border post, document/receipt no. | country fixed to `NP`; allow INR or NPR; optional validity and trip; generate cross-border report |
| `PERMIT_INDIA` | vehicle, amount, permit type, issuing state/authority, document no., validity | country fixed to `IN`; state required for state permit, optional for national permit; may create compliance document |
| `PERMIT_NEPAL` | vehicle, amount, issuing authority/border, document no., validity | country fixed to `NP`; allow INR/NPR; may create compliance document |
| `ROAD_TAX_INDIA` | vehicle, amount, state, receipt/document no., tax period/validity | country fixed to `IN`; prevent exact duplicate receipt for same vehicle/state |
| `TRAFFIC_FINE` | vehicle, amount, challan no., issuing authority, issue date | optional driver/trip, violation and due date; support `recover_from=DRIVER`; paid and recovery histories stay separate |
| `OTHER` | vehicle, custom type, description, amount | never silently use `OTHER` when a system category applies |

Rates and government rules change. Do not hardcode toll, Bhansar, permit, road-tax or fine amounts
in application code. The receipt is the actual transaction. If estimates are needed, add a
tenant-configurable `fleet_rate_cards` feature later with effective-from/to dates.

---

## 5. API contract to implement

All responses continue using existing `ApiResponse<T>` / `PagedApiResponse<T>` envelopes and
public UUIDs only.

### 5.1 Expense APIs

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/api/fleet/expense-types` | Stable codes, labels and required-field metadata |
| `POST` | `/api/fleet/expenses` | Create draft expense independent of a CRM booking |
| `GET` | `/api/fleet/expenses` | Filter by vehicle, driver, trip, type, status, date, country/state, payer and search |
| `GET` | `/api/fleet/expenses/{id}` | Detail with settlements and attachment metadata |
| `PUT` | `/api/fleet/expenses/{id}` | Edit draft/rejected expense |
| `DELETE` | `/api/fleet/expenses/{id}` | Soft-delete draft/rejected entry; approved items require void workflow |
| `POST` | `/api/fleet/expenses/{id}/submit` | Draft/rejected to submitted |
| `POST` | `/api/fleet/expenses/{id}/approve` | Submitted to approved |
| `POST` | `/api/fleet/expenses/{id}/reject` | Submitted to rejected with reason |
| `POST` | `/api/fleet/expenses/{id}/void` | Reversal-style void; preserve financial audit |
| `POST` | `/api/fleet/expenses/{id}/settlements` | Record payment/reimbursement/recovery |
| `DELETE` | `/api/fleet/expenses/{id}/settlements/{sid}` | Reverse erroneous settlement with audit; no hard silent edit |
| `POST` | `/api/fleet/expenses/{id}/attachments` | Multipart receipt upload |
| `GET` | `/api/fleet/expenses/{id}/attachments/{aid}/file` | Authorized download/stream |
| `POST` | `/api/fleet/expenses/import` | Validated CSV import with dry-run option |

Example create request:

```json
{
  "vehiclePublicId": "uuid",
  "tripPublicId": "uuid-or-null",
  "driverPublicId": "uuid-or-null",
  "expenseType": "BHANSAR_NEPAL",
  "expenseDate": "2026-07-31",
  "countryCode": "NP",
  "borderPost": "Sunauli",
  "documentNumber": "BH-12345",
  "description": "Nepal vehicle entry Bhansar",
  "originalAmount": 2500.00,
  "currency": "NPR",
  "fxRateToBase": 0.62500000,
  "paidBy": "DRIVER",
  "paymentMode": "CASH",
  "recoverFrom": "NONE",
  "idempotencyKey": "mobile-device-unique-key"
}
```

The server calculates `baseAmount`; it does not trust a client-supplied base total.

### 5.2 Compliance APIs

| Method | Endpoint | Purpose |
|---|---|---|
| `POST` | `/api/fleet/compliance-documents` | Add vehicle/driver document |
| `GET` | `/api/fleet/compliance-documents` | Filter by owner/type/country/status/expiry window |
| `GET/PUT` | `/api/fleet/compliance-documents/{id}` | View/update active record |
| `POST` | `/api/fleet/compliance-documents/{id}/renew` | Create replacement while preserving history |
| `POST` | `/api/fleet/compliance-documents/{id}/revoke` | Revoke with reason |
| `POST/GET` | `/api/fleet/compliance-documents/{id}/attachments` | Upload/list proof |
| `GET` | `/api/fleet/compliance-documents/{id}/attachments/{aid}/file` | Private download |

### 5.3 Diary and reporting APIs

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/api/fleet/diary/daily?date=&vehicleId=` | One daily view: trips, odometer, fuel, maintenance and itemized expenses |
| `GET` | `/api/fleet/trips/{id}/expense-summary` | Category totals, base total and pending reimbursements |
| `GET` | `/api/fleet/reports/expenses` | Category/vehicle/driver/trip/country/state/month aggregation |
| `GET` | `/api/fleet/reports/cost-per-km` | Approved operating cost divided by completed distance |
| `GET` | `/api/fleet/reports/compliance` | Expired/expiring/missing documents |
| `GET` | `/api/fleet/reports/fines` | Outstanding fines and driver recoveries |
| `GET` | `/api/fleet/reports/cross-border` | Bhansar + Nepal permit cost/validity history |
| `GET` | `/api/fleet/reports/expenses/export?format=csv` | Export current filtered dataset |

Dashboard monthly spend must use one documented source of truth:

```text
approved fleet expenses
+ fuel logs not represented as expense rows
+ maintenance logs not represented as expense rows
= total operating spend
```

Never count both a legacy trip `tollCost` and its migrated `TOLL` expense.

### 5.4 Optional Vehicle Master integration APIs

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/api/fleet/master-vehicle-options?q=` | CRM mode: list global + tenant-visible master templates; standalone mode: return integration-disabled capability |
| `POST` | `/api/fleet/vehicles/{id}/master-link` | Link a visible master template and take a snapshot |
| `GET` | `/api/fleet/vehicles/{id}/master-sync-preview` | Compare current Fleet values with latest master values without writing |
| `POST` | `/api/fleet/vehicles/{id}/master-sync` | Explicitly copy selected `type`, capacity, name/image snapshot fields |
| `DELETE` | `/api/fleet/vehicles/{id}/master-link` | Unlink while retaining physical Fleet data and audit history |
| `POST` | `/api/vehicle-onboarding` | CRM integration facade: atomically create Vehicle Master and required Fleet unit(s) when source is `OWN` |
| `POST` | `/api/fleet/vehicles/from-master/{masterId}` | Add another physical own unit under an existing Own master |

`FleetVehicleRequestDto` may accept optional `masterVehiclePublicId` on create. If integration is
disabled and a client supplies this field, return a clear `400 MASTER_INTEGRATION_DISABLED`; do not
silently ignore it. Manual Fleet vehicle creation remains available in both modes.

Example integrated Own onboarding request:

```json
{
  "master": {
    "name": "Toyota Crysta",
    "type": "SUV",
    "capacity": 7,
    "description": "AC tourist vehicle",
    "vehicleSource": "OWN"
  },
  "fleetUnits": [
    {
      "vehicleNumber": "UP32AB1234",
      "make": "Toyota",
      "model": "Innova Crysta",
      "year": 2025,
      "openingOdometer": 12000
    }
  ],
  "idempotencyKey": "vehicle-onboarding-unique-key"
}
```

The facade validates both halves first and uses one database transaction. If Fleet unit creation
fails—duplicate registration, plan vehicle limit, invalid tenant or validation error—the Vehicle
Master must also roll back. A partially-created Own master is not acceptable.

Permissions:

- listing/linking requires `FLEET_UPDATE` and, in CRM mode, `MASTER_READ`;
- atomic Own onboarding requires both `MASTER_MANAGE` and `FLEET_CREATE` plus both module
  entitlements;
- syncing never grants permission to edit the master itself;
- creating/updating a master continues to require `MASTER_MANAGE` through existing master APIs.

### 5.5 Optional Booking-to-Fleet assignment APIs

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/api/fleet/dispatch/booking-requirements` | Optional dispatch queue; filter unassigned/tentative/assigned/external items |
| `GET` | `/api/fleet/dispatch/availability` | Suggest compatible available vehicles/drivers for a time window; no assignment write |
| `POST` | `/api/fleet/dispatch/booking-requirements/{id}/assign` | User explicitly assigns physical vehicle + driver |
| `POST` | `/api/fleet/dispatch/assignments/{id}/confirm` | Convert tentative hold to confirmed assignment after availability recheck |
| `POST` | `/api/fleet/dispatch/assignments/{id}/reassign` | Change vehicle/driver before trip start with audit reason |
| `DELETE` | `/api/fleet/dispatch/assignments/{id}` | Unassign and release the vehicle before trip start |
| `POST` | `/api/fleet/dispatch/booking-requirements/{id}/external` | Mark vendor/self-arranged transport; no Fleet vehicle required |
| `POST` | `/api/fleet/dispatch/booking-requirements/{id}/undecided` | Return to optional unassigned state |
| `GET` | `/api/fleet/integrations/bookings/{bookingId}/assignments` | Booking screen reads Fleet assignment summary without owning Fleet entities |

`Assign Fleet` is an explicit button/action, never an automatic consequence of selecting Master or
confirming Booking. These endpoints exist only when Booking integration is enabled. Standalone
Fleet users create trips through the normal `/api/fleet/trips` flow.

---

## 6. Workflow and business rules

### 6.1 Expense lifecycle

```text
DRAFT ──submit──> SUBMITTED ──approve──> APPROVED
  ▲                    │                     │
  └──── edit <── REJECTED <──reject─────────┘
                                             └──void──> VOID
```

- Driver/operator creates a draft, optionally offline in the PWA.
- Receipt upload may be mandatory based on tenant policy and amount threshold.
- Submit freezes the submitted values.
- Manager approves or rejects with reason.
- Accountant/owner adds settlement or driver reimbursement.
- Approved financial rows cannot be silently edited/deleted; use void + replacement.
- A trip may be closed before every receipt arrives. Allow late expenses until the configurable
  accounting lock date (`app.fleet.expense-lock-days`, for example 7 days) or until a manager
  explicitly locks the period.

### 6.2 General validations

- Every referenced entity must belong to `TenantContext` and be non-deleted.
- Expense vehicle must equal trip vehicle; supplied driver must equal the trip driver unless an
  authorized manager records an override with reason.
- Amount must be positive; currency is required and limited to configured ISO codes.
- `INR -> INR` uses FX rate `1`; foreign currency requires a positive rate.
- `baseAmount = originalAmount * fxRateToBase`, rounded server-side using one documented rule.
- Required fields vary by `expenseType`; return field-level validation errors.
- Dates cannot contradict validity, trip or settlement chronology.
- `idempotencyKey` prevents duplicate mobile retries/imports.
- Exact receipt/FASTag/challan duplicates produce a conflict or manager-confirmed override, not a
  silent second charge.
- All financial state transitions record actor, timestamp and before/after values in the activity
  audit.

### 6.3 Fine handling

A fine is an expense/liability, while driver recovery is a separate settlement. Required behavior:

1. Record challan, vehicle, authority, issue date, amount and due date.
2. Optionally link driver and trip.
3. Manager chooses whether it is company cost or recoverable from driver.
4. Record government payment independently from employee recovery.
5. Report overdue unpaid fines and pending recoveries separately.

This avoids showing a fine as “zero cost” merely because the company later recovers it.

---

## 7. Make the Fleet package CRM-independent

### 7.1 Replace direct domain imports with ports

Create interfaces under `fleet/integration/spi`:

```java
public interface FleetWorkReferencePort {
    Optional<FleetWorkReference> resolve(UUID publicId, long tenantId);
}

public interface FleetPartyDirectoryPort {
    Optional<FleetPartySnapshot> resolve(UUID publicId, long tenantId);
}

public interface FleetVehicleCatalogPort {
    Optional<FleetVehicleTemplate> resolveVisible(UUID publicId, long tenantId);
    List<FleetVehicleTemplate> searchVisible(String query, long tenantId);
}

public interface FleetAssetOnboardingPort {
    List<FleetAssetReference> createOwnUnits(FleetVehicleTemplate master,
                                              List<NewFleetUnit> units,
                                              long tenantId,
                                              String idempotencyKey);
}

public interface FleetNotificationPort {
    void send(FleetNotification notification);
}

public interface FleetFileStorage {
    StoredFile store(...);
    FileContent load(...);
    void delete(...);
}
```

Adapters:

| Adapter | Mode | Behavior |
|---|---|---|
| `CrmBookingFleetWorkReferenceAdapter` | `CRM_SUITE` | Resolves booking and snapshots code |
| `StandaloneFleetWorkReferenceAdapter` | `FLEET_STANDALONE` | Accepts optional free-text external job/customer reference; no Booking import |
| `CrmVendorFleetPartyAdapter` | `CRM_SUITE` | Resolves existing Vendor master |
| `StandaloneFleetPartyAdapter` | `FLEET_STANDALONE` | Uses Fleet-owned party/owner snapshot or small Fleet party master |
| `CrmVehicleMasterCatalogAdapter` | `CRM_SUITE` + optional setting enabled | Resolves global or tenant-visible `vehicle_master` rows and returns a Fleet-owned template DTO |
| `CrmOwnVehicleOnboardingFacade` | `CRM_SUITE` + Master/Fleet integration enabled | Coordinates Master create and Fleet unit create in one transaction without putting Fleet imports in Master core |
| `NoOpFleetVehicleCatalogAdapter` | `FLEET_STANDALONE` or integration off | Advertises the integration as unavailable; Fleet manual entry continues normally |
| `NotifyEventFleetNotificationAdapter` | hosted modes | Uses existing in-app notification infrastructure |
| `LoggingFleetNotificationAdapter` | dev/fallback | Safe no-op/logging fallback |

Use `@ConditionalOnProperty` for CRM adapters and `@ConditionalOnMissingBean` for standalone/fallback
implementations. Nothing under Fleet core/service/entity packages should import `booking.*` or
`vendor.*` or `master.vehicle.*` after this refactor. Only the CRM adapter package may import the
Vehicle Master entity/repository.

### 7.2 Vehicle Master integration configuration and sync rules

Add a tenant setting:

```text
fleet.master-vehicle-integration = OFF | OPTIONAL
```

Default is `OFF` for Fleet-only tenants and `OPTIONAL` for CRM tenants that own both `FLEET` and
`MASTERS`. It must never be `REQUIRED`, because imported/owned/rented physical vehicles may not
have a catalogue template.

| Field | Initial link | Explicit re-sync | Source of truth |
|---|---|---|---|
| Master public ID | Link | Unchanged unless relinked | Integration link |
| Master vehicle source | Snapshot `OWN`/`RENTED` | Never silently changes physical ownership | Master/quotation snapshot |
| Master name snapshot | Copy | Refresh | Master snapshot |
| Master image snapshot | Copy | Refresh | Master snapshot |
| Fleet type | Copy only if blank, unless user selects overwrite | User-selected overwrite | Fleet after copy |
| Seating capacity | Copy only if blank, unless user selects overwrite | User-selected overwrite | Fleet after copy |
| Registration number | Never copy | Never copy | Fleet only |
| Make/model/year | Never copy (not present in current master) | Never copy | Fleet only |
| Owner/vendor, status, odometer | Never copy | Never copy | Fleet only |
| Documents, trips, expenses | Never copy | Never copy | Fleet only |

Store an audit event for link, relink, sync and unlink including changed fields. Never run automatic
bulk sync on master update; that could alter live operational records without a Fleet manager's
decision.

The integration itself remains optional at product/tenant level. Once it is enabled, however, an
`OWN` master save is atomic: Master + at least one Fleet asset either both succeed or both fail.
Changing an existing source follows guarded workflows:

- `RENTED -> OWN`: require Fleet unit details and create the first own asset atomically;
- `OWN -> RENTED`: never delete linked Fleet assets; require manager confirmation and explicit
  unlink/retire handling;
- a linked Fleet asset with trip history is retained and may only be retired using normal Fleet
  status rules;
- deleting/soft-deleting Master never deletes the physical Fleet asset.

### 7.3 Exact Booking → Master → Fleet assignment point

Recommended two-stage model:

```text
Quotation/Booking
  └─ optional Vehicle Master/category requirement (what customer needs)
       └─ optional dispatch action by user
            └─ physical FleetVehicle + Driver
                 └─ PLANNED FleetTrip -> ONGOING -> COMPLETED
```

The actual assignment point is **when a dispatcher/manager clicks `Assign Fleet`**, not when the
quotation or booking is created. The button may be used on a `PENDING` or `CONFIRMED` booking:

| Booking point | Fleet behavior |
|---|---|
| Quotation | Select only optional Vehicle Master/type/quantity; never reserve registration number |
| Booking `PENDING` | No assignment by default; user may create a `TENTATIVE` hold |
| Booking `CONFIRMED` | Still no automatic assignment; user may create/confirm an assignment now or closer to service date |
| Configured dispatch lead time, e.g. 24/48 hours | Dashboard raises “vehicle not assigned” reminder only; it does not block booking |
| Trip start | If this service is being operated through Fleet, concrete vehicle + driver are required |
| External/vendor/self-arranged service | No Fleet assignment or Fleet trip is required at any point |

Recommended data model:

#### CRM-side transport requirement

Preserve the customer's requested category before Fleet sees it:

- extend `QuotationVehicle` with optional `master_vehicle_public_id` plus name/type/capacity and
  `vehicle_source` snapshots;
- add a one-to-one `booking_transport_details` row for a Transport/Vehicle
  `BookingServiceItem` rather than adding transport-only columns to every generic service item;
- store optional master public ID/snapshots, `vehicle_source`, pickup/drop, scheduled start/end,
  passenger count, quantity and notes;
- when quotation converts to booking, copy quotation snapshots to the transport detail; never
  re-read current Master values to rewrite the agreed booking;
- direct booking entry can create transport detail without selecting any Master.

Suggested CRM APIs:

```text
GET/PUT /api/bookings/{bookingId}/services/{serviceItemId}/transport-details
```

This detail describes what was sold/requested. It does not hold a Fleet vehicle or driver and does
not make Fleet mandatory.

#### `fleet_booking_requirements`

Fleet-owned optional projection/work order; no database FK to Booking or Master:

- booking and booking-service-item public IDs/codes as logical references;
- optional master vehicle public ID and name/type/capacity snapshots;
- service date/time, pickup, drop, passenger count and required quantity;
- `fulfillment_mode`: `UNDECIDED`, `INTERNAL_FLEET`, `EXTERNAL_VENDOR`, `SELF_ARRANGED`,
  `NOT_REQUIRED`;
- `dispatch_status`: `UNASSIGNED`, `TENTATIVE`, `ASSIGNED`, `DISPATCHED`, `COMPLETED`,
  `CANCELLED`;
- idempotency key based on tenant + booking service-item public ID.

The projection can be created idempotently when a transport service item is added/updated, but it
is only a dispatch reminder. It is not a physical assignment and must not affect Booking status.

#### Physical assignment

When `Assign Fleet` is clicked, create a `PLANNED FleetTrip` linked to the requirement. Current
`FleetTrip` can remain strict—vehicle and driver required—because the optional/unassigned stage is
held by `fleet_booking_requirements`, not by a half-empty trip. If quantity is greater than one,
create one planned trip/assignment per physical vehicle.

Assignment checks:

- same tenant and non-deleted vehicle/driver;
- vehicle/driver availability for the service window;
- vehicle is not maintenance/out-of-service;
- required seat capacity and optional master/type compatibility;
- mandatory compliance documents valid through the trip end;
- no overlapping confirmed Fleet assignment;
- manager override requires a recorded reason where policy allows it.

Master matching is a suggestion, not a hard requirement. Prefer Fleet vehicles linked to the same
master template, then compatible type/capacity. Dispatcher may choose a different compatible vehicle
with an override reason.

Source affects suggestions only:

- `OWN` first suggests linked `FleetOwnerType.OWN` assets;
- `RENTED` suggests external vendor flow or Fleet assets registered as `VENDOR`/`RENTED`;
- neither source automatically assigns a physical vehicle to the Booking.

Lifecycle rules:

- pending-booking assignment is `TENTATIVE`; tenant policy decides whether it blocks the vehicle or
  only warns about overlap;
- booking confirmation revalidates but does not auto-assign; an existing tentative hold may be
  explicitly or policy-confirmed;
- rescheduling an unassigned requirement simply changes dates; an assigned trip requires conflict
  revalidation and dispatcher acknowledgement;
- cancellation releases tentative/planned assignments; an ongoing trip is never silently cancelled;
- reassignment is allowed and audited before trip start; after start use an explicit emergency
  vehicle-swap workflow rather than rewriting history;
- removing/unlinking Vehicle Master never removes a Fleet assignment;
- Booking and Fleet statuses remain independent and synchronize only through adapter events.

### 7.4 Keep platform-kernel dependencies

The standalone product can continue using these shared platform capabilities:

- `BaseTenantEntity`, `TenantContext`, API envelopes and error handling;
- tenant/company onboarding;
- JWT login and user management;
- effective permissions;
- subscription status/dunning;
- storage quota;
- activity audit and trash;
- notification infrastructure through a Fleet port.

These are product-platform dependencies, not Travel CRM business dependencies.

---

## 8. Standalone subscription and licensing

### 8.1 Immediate implementation

Extend the plan catalogue with dedicated plan codes such as:

```text
FLEET_BASIC
FLEET_PRO
```

Because `Plan.code` currently uses the `TenantPlan` enum, the immediate implementation must also
add `FLEET_BASIC` and `FLEET_PRO` to that enum. In a later catalogue refactor the plan code may be
moved to a validated string so adding a commercial plan does not require an application release.

Add `ProductFamily { CRM, FLEET }` to `Tenant` and `Plan`, defaulting existing rows to `CRM`.
Fleet-only plans grant `FLEET` and do not grant Leads/Bookings/Quotations/Customers/Vendors/CRM
Reports. Auth, users, company settings and `/api/me` remain platform capabilities.

Add plan limits:

- `max_fleet_vehicles`;
- `max_fleet_drivers` if commercially required;
- `max_users`;
- `max_storage_mb`;
- optional receipt-retention days.

Enforce vehicle limits in `FleetVehicleService.create`, not only in the frontend. Expose effective
usage and limits in the tenant feature/bootstrap response so the UI can show “8 of 10 vehicles”.

Suggested packaging—not final pricing:

| Capability | Fleet Basic | Fleet Pro |
|---|---|---|
| Vehicle/driver/trip diary | Yes | Yes |
| Fuel/maintenance | Yes | Yes |
| Basic expense ledger | Yes | Yes |
| Receipt attachments | Limited by storage | Higher storage |
| Approval/reimbursement workflow | Optional | Yes |
| Cross-border/compliance reports | Basic | Full |
| CSV import/export | Yes | Yes |
| API/webhooks/GPS integrations | No or add-on | Yes/add-on |

Vehicle counts, storage and prices must be configuration/catalogue data—not hardcoded business
logic.

Vehicle Master integration is not part of the Fleet-only product dependency graph. It is an
optional CRM Suite capability controlled independently from Fleet plan limits.

Booking-to-Fleet dispatch integration is also optional. A Fleet-only plan has no booking
requirement projection; a CRM tenant without Fleet continues using Booking service items and Vendor
assignment exactly as before.

### 8.2 Product-surface enforcement

- Tenant provisioning records `ProductFamily.FLEET` and selected Fleet plan.
- Backend `ModuleAccessFilter` continues enforcing `/api/fleet/** -> FLEET`.
- Add tests proving a Fleet-only token receives `403 MODULE_NOT_ENABLED` for CRM routes.
- Frontend bootstraps from `GET /api/me/features` and mounts the Fleet shell for Fleet tenants.
- For dedicated self-host deployments, `APP_PRODUCT_MODE=FLEET_STANDALONE` prevents provisioning a
  CRM tenant and applies Fleet branding/defaults.
- Hiding navigation is UX only; backend entitlement remains the security boundary.

---

## 9. Standalone UI/PWA scope

The Fleet-only frontend should contain only:

1. **Dashboard** — vehicle status, active trips, monthly spend, pending approvals, expiring docs,
   unpaid fines and reimbursements.
2. **Vehicles** — master, ownership, odometer, document completeness and vehicle timeline.
3. **Drivers** — profile, licence, availability, assigned/ongoing trip and driver expense balance.
4. **Trips / Daily Diary** — calendar/list, start/close, route, odometer and expenses.
5. **Expenses** — fast entry, category-specific form, receipt camera upload, approval inbox and
   settlement history.
6. **Compliance** — India/Nepal permits, road tax, RC/insurance/PUC/fitness and renewals.
7. **Fuel & Maintenance** — retain existing functionality.
8. **Reports** — cost by vehicle/category/trip, cost/km, fines, cross-border and expiry reports.
9. **Users & Roles** — Fleet users only.
10. **Settings** — company, base currency, approval policy, receipt threshold, alert days and
    storage/plan usage.

First-login wizard:

```text
Company & timezone -> base currency -> first vehicle -> first driver -> alert policy -> invite team
```

Driver/operator PWA must optimize for low connectivity: local draft, compressed receipt photo,
idempotent sync and clear sync status. Approval and payment actions remain online-only.

In CRM Suite mode, the Vehicle form shows an optional “Use Vehicle Master template” selector. The
form must display which fields will be copied, allow manual creation, show whether the linked row is
global or tenant-owned, and provide explicit Preview Sync / Unlink actions. Fleet-only mode does not
render this selector.

Vehicle Master form behavior:

- required `Vehicle Source` radio/select: `Own` or `Rented`;
- selecting `Own` expands Fleet Unit fields and requires at least one unique registration number;
- selecting `Rented` keeps optional vendor/source details but creates no Fleet asset automatically;
- successful Own save shows links to both the master template and created Fleet unit(s);
- changing Own/Rented displays the guarded consequences before save;
- adding an Own master to Fleet inventory does not show it as assigned on any Booking.

Booking UI behavior:

- Transport service shows optional Master selection and optional `Assign Fleet` button.
- `Assign Fleet` opens availability; it is never marked required.
- Summary badge displays `Unassigned`, `Tentative`, `Fleet Assigned`, `External Vendor`,
  `Self-arranged` or `Not required`.
- “Unassigned” is informational, not a Booking validation error.
- Booking save/confirm/voucher/invoice buttons remain enabled without Fleet assignment.

---

## 10. Permissions and roles

The current four permissions are too broad for money. Keep them for master/trip compatibility and
add:

```text
FLEET_EXPENSE_READ
FLEET_EXPENSE_CREATE
FLEET_EXPENSE_UPDATE_DRAFT
FLEET_EXPENSE_APPROVE
FLEET_EXPENSE_SETTLE
FLEET_EXPENSE_VOID
FLEET_COMPLIANCE_READ
FLEET_COMPLIANCE_MANAGE
FLEET_DISPATCH
FLEET_REPORT_READ
```

Recommended default roles:

| Role | Main access |
|---|---|
| Fleet Owner/Admin | All Fleet and user/settings permissions |
| Fleet Manager | Vehicles, drivers, trips, compliance, approve/reject; no tenant billing |
| Dispatcher | Vehicles/drivers/trips; view expenses; no approval/payment |
| Driver/Operator | Own assigned trips and create own draft expenses/receipts |
| Accountant | Read all, settle approved expenses, reports; no trip deletion |
| Viewer/Auditor | Read-only reports, documents and audit trail |

For the first release, avoid multiplying the global `Role` enum unnecessarily: map Fleet
Owner/Admin to `TENANT_ADMIN`, Fleet Manager to `MANAGER`, Accountant to `ACCOUNTANT`, and create
Dispatcher/Driver/Viewer as named permission templates on `STAFF`. Add an optional tenant-scoped
`user_id` link on `FleetDriver` (unique where non-null) so Driver row scope can be enforced.

Driver access needs row scope in addition to authority: a driver-linked user can only see assigned
trips and their own expense drafts unless explicitly elevated.

---

## 11. Reports and dashboard rules

Launch reports:

- total approved expenses by day/month/type;
- per vehicle, driver, trip and owner type;
- Toll vs Parking separated;
- India state-wise permit and road-tax spend;
- Nepal Bhansar and permit spend in original currency and INR base currency;
- unpaid/overdue fines;
- driver-paid expenses awaiting reimbursement;
- fine recoveries pending from drivers;
- cost per completed kilometre;
- document coverage matrix per vehicle;
- documents expiring in 30/15/7/0 days;
- vendor/rented vs owned vehicle operating cost.

Only `APPROVED` non-void expenses enter official cost reports. Draft/submitted rows appear as
“pending exposure”. Reports must clearly display original and base currency totals and never add
INR and NPR values directly without conversion.

---

## 12. Database migration and backward compatibility

### 12.1 Migration strategy

Current repository has Flyway baseline/migrations and production schema validation. Add a new
forward migration, for example:

`V3__fleet_expenses_compliance_and_product_family.sql`

Do not edit the already-promoted `V1__baseline_schema.sql` for this feature.

Migration order:

1. Add product-family/plan-limit columns with safe defaults for existing tenants/plans.
2. Add `vehicle_source` to `vehicle_master` and quotation/booking transport snapshots; add nullable
   Vehicle Master snapshot-link columns to `fleet_vehicles`.
3. Add optional master snapshots to quotation vehicles and create `booking_transport_details` plus
   `fleet_booking_requirements`.
4. Create expense, settlement, compliance-document and attachment tables.
5. Add tenant-scoped indexes and uniqueness constraints.
6. Seed Fleet-only plan rows/catalogue values idempotently.
7. Backfill legacy permit/toll data; do not invent master links or booking assignments for existing
   Fleet vehicles/bookings.
8. Deploy dual-read/dual-write compatibility code.
9. Switch dashboard and UI to the new ledger after reconciliation.
10. Remove legacy writes only in a later migration/release.

### 12.2 Toll backfill

For every non-deleted trip with `toll_cost > 0`, create one expense:

```text
type             = TOLL
source           = LEGACY_MIGRATION
approval_status  = APPROVED
original/base    = old toll_cost in INR
idempotency_key  = legacy-trip:{tripPublicId}:toll
description      = Migrated legacy trip toll total
```

Keep `fleet_trips.toll_cost` temporarily for old clients, but exclude it from totals once the
migrated expense exists. Add a reconciliation query comparing old values to migrated rows.

### 12.3 Permit backfill

For every vehicle with a legacy `permit_expiry`, create a compliance document:

```text
type          = NATIONAL_PERMIT_INDIA
valid_until   = old permit_expiry
status        = derived from date
source        = LEGACY_MIGRATION
```

Document number, issue date and jurisdiction remain unknown and must be flagged `NEEDS_REVIEW` in
the UI. Do not invent these values. Continue reading the old column during one compatibility
release, then make normalized documents the source of truth.

### 12.4 Indexes/constraints

At minimum:

- `(tenant_id, expense_date)`;
- `(tenant_id, vehicle_id, expense_date)`;
- `(tenant_id, trip_id)`;
- `(tenant_id, expense_type, approval_status)`;
- partial/unique idempotency key for non-deleted rows;
- `(tenant_id, challan_number)` where present;
- compliance `(tenant_id, vehicle_id, document_type, valid_until)`;
- attachment owner lookup;
- check constraints for positive amounts, FX rate, validity chronology and exactly one compliance
  owner.

---

## 13. Package/code layout

Recommended additions:

```text
fleet/
  expense/
    controller/
    dto/
    entity/
    enums/
    mapper/
    repository/
    service/
  compliance/
    controller/
    dto/
    entity/
    enums/
    repository/
    service/
  attachment/
  report/
  integration/
    spi/
    adapter/crm/
    adapter/standalone/
```

Keep controllers thin. Category rules should be strategies keyed by `FleetExpenseType` rather than
one long controller/service `if/else`. Calculations—base amount, settlement status and totals—must
be pure services with unit tests.

Publish internal events such as:

```text
FleetExpenseSubmitted
FleetExpenseApproved
FleetExpenseRejected
FleetExpenseSettled
FleetComplianceExpiring
```

CRM adapters may consume `FleetExpenseApproved` to update a booking cost view. Fleet core must not
call `BookingExpenseService` directly. Any cross-module sync uses an idempotent integration record
so a retry cannot duplicate a Booking expense.

---

## 14. Testing plan

### Unit tests

- every category validator, including required India/Nepal fields;
- INR/NPR conversion and rounding;
- approval transition matrix;
- partial settlement and driver recovery calculations;
- cost summary excluding draft/rejected/void rows;
- receipt duplicate/idempotency rules;
- permit renewal/status calculation;
- legacy toll reconciliation.

### Repository/integration tests

- tenant A cannot read/change tenant B expenses, files or compliance records;
- trip/vehicle/driver mismatch is rejected;
- expired/deleted references are rejected;
- concurrent approve/settle requests do not double-pay;
- private attachments cannot be downloaded with another tenant/user token;
- Fleet-only tenant gets Fleet APIs but CRM APIs return module-disabled;
- CRM mode booking/vendor adapters work only when enabled;
- Vehicle Master link resolves only a global or same-tenant visible row;
- integrated Own master onboarding creates master + one or more linked `FleetOwnerType.OWN` assets
  atomically, and duplicate/limit failures roll back both;
- Rented master onboarding does not auto-create a Fleet asset;
- source change never deletes a linked Fleet asset or its trip history;
- standalone/integration-off mode supports manual vehicles and rejects supplied master IDs clearly;
- master update/delete never mutates or deletes linked physical Fleet vehicles;
- explicit master sync changes only selected catalogue-owned fields and writes audit history;
- Booking save/confirm/complete works with no Fleet assignment;
- PENDING booking may have a tentative assignment, but assignment remains optional;
- CONFIRMED booking does not auto-create a trip or require assignment;
- external/vendor/self-arranged fulfillment reaches completion without a Fleet vehicle;
- `Assign Fleet` creates a planned trip only after availability/compliance checks;
- cancellation/reschedule releases or revalidates assignments without corrupting trip history;
- Flyway V3 succeeds on empty and V2-populated shadow databases.

### API/UI acceptance tests

- create and approve each of the seven required expense types;
- upload/download JPEG, PNG and PDF receipts;
- record a Nepal expense in NPR and verify INR dashboard total;
- create fine, pay authority, recover from driver and verify both balances;
- renew India/Nepal permit and verify old history remains;
- offline driver draft retries only once after reconnect;
- CSV import dry run reports row-level errors without partial writes;
- export totals equal on-screen filtered totals.

Target: meaningful Fleet service/controller integration coverage before selling; the current absence
of Fleet tests is a release blocker for financial functionality.

---

## 15. Delivery phases

| Phase | Deliverable | Main work |
|---|---|---|
| 0 — Boundary | Standalone architecture ready | Product family/plans, Fleet shell contract, ports/adapters, remove direct Booking/Vendor/Master imports from Fleet core |
| 1 — Ledger | Requested costs work end-to-end | Expense model/APIs, seven types, INR/NPR, receipts, validation, migration |
| 2 — Compliance | Legal-document history | Normalized India/Nepal permits, road tax, renewal, alerts and document files |
| 3 — Control | Money workflow | Submit/approve/reject/void, settlements, reimbursements, fine recovery and audit |
| 4 — UI/PWA | Sellable user experience | Fleet-only layout, setup wizard, mobile receipt flow, approval inbox and plan usage |
| 5 — Reports/integration | Operational visibility | Dashboard totals, expense/compliance reports, CSV import/export, optional CRM sync |
| 6 — Hardening/launch | Production readiness | Migration rehearsal, security/tenant tests, backup/restore, monitoring, docs and demo tenant |

With one backend and one frontend developer, a realistic order is 4–6 focused development weeks
after UI designs and storage choice are approved. This is an effort range, not a release promise;
offline PWA, external GPS and accounting integrations should not block the first paid SaaS launch.

### MVP sellable cut

The first paid release should include:

- Fleet-only onboarding and navigation;
- vehicles, drivers, trips, fuel and maintenance;
- all seven requested expense types;
- INR/NPR and base-currency reporting;
- receipt upload;
- manager approval and driver reimbursement;
- normalized compliance documents and expiry alerts;
- dashboard, CSV export and basic reports;
- roles, tenant isolation, plan limit and audit trail;
- tested Flyway migration and backup procedure.

Defer GPS/live tracking, automatic FASTag imports, government portal integrations, advanced rate
cards and native mobile apps to later add-ons.

---

## 16. Definition of done for standalone sale

The product is not “standalone” merely because the sidebar hides CRM. It is ready to sell only when
all of the following pass:

- [ ] A Fleet-only tenant can be provisioned without CRM modules.
- [ ] No Fleet core class imports Booking or Vendor domain classes.
- [ ] Vehicle Master integration is optional and accessed only through `FleetVehicleCatalogPort`.
- [ ] New master captures Own/Rented source; integrated `OWN` creation also creates a linked Fleet
      asset in the same transaction.
- [ ] Own Fleet onboarding requires registration number and enforces plan/duplicate rules.
- [ ] `RENTED` creates no Fleet asset automatically.
- [ ] One master template can link to multiple physical Fleet vehicles without ownership crossover.
- [ ] Master sync is explicit, field-selective and audited; manual Fleet creation always works.
- [ ] `Assign Fleet` is optional on PENDING and CONFIRMED bookings and never blocks Booking lifecycle.
- [ ] No physical `FleetTrip` is created until the user explicitly assigns a vehicle/driver.
- [ ] External vendor, self-arranged and not-required transport need no Fleet vehicle.
- [ ] A trip can be created without `bookingPublicId` or CRM master data.
- [ ] Toll, Parking, Bhansar Nepal, India Permit, Nepal Permit, India Road Tax and Fine are separate,
      filterable expense codes.
- [ ] Receipts/documents are private and tenant-authorized.
- [ ] INR/NPR values reconcile to immutable base-currency snapshots.
- [ ] Manager approval, accountant settlement and driver recovery are independently authorized.
- [ ] Permit/road-tax renewals preserve history and trigger expiry alerts.
- [ ] Dashboard and exports use the same non-duplicated calculation source.
- [ ] Existing legacy toll and permit values migrate idempotently and reconcile.
- [ ] Fleet-only users cannot access CRM endpoints even by manually calling the API.
- [ ] Vehicle/user/storage limits are enforced server-side.
- [ ] Automated tenant-isolation, financial workflow and migration tests pass.
- [ ] Hosted onboarding and self-host deployment documentation are complete.
- [ ] Upgrade from Fleet-only to CRM Suite retains the same tenant and Fleet records.

---

## 17. Immediate implementation backlog

Execute in this order:

1. Add Fleet-only `ProductFamily` and plan catalogue entries/limits.
2. Introduce Fleet integration ports and replace Booking/Vendor/Vehicle Master direct imports.
3. Add Vehicle Master source, optional link/snapshots and atomic Own Master + Fleet Unit onboarding.
4. Add master adapter, link/sync/unlink APIs and tests.
5. Add optional Fleet booking requirement projection and explicit assign/reassign/unassign flow.
6. Add V3 migration and Fleet expense/compliance entities.
7. Implement category validators and base-currency calculator.
8. Implement expense CRUD, approval and settlement APIs.
9. Implement private attachment storage/download.
10. Normalize document history and adapt expiry scheduler/dashboard.
11. Backfill `toll_cost` and `permit_expiry` with reconciliation.
12. Add daily diary aggregation and Fleet reports.
13. Build Fleet-only frontend shell, setup wizard and driver-friendly expense flow.
14. Add CSV import/export and optional CRM integration adapters.
15. Complete tests, migration rehearsal, monitoring, backup/restore and launch checklist.

This order creates a sellable standalone boundary first, then adds the requested financial and
compliance functionality without forking the CRM codebase.
