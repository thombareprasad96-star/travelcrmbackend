# Fleet Module — Redesign

**Date:** 2 August 2026
**Supersedes:** `docs/FLEET_STANDALONE_PRODUCT_IMPLEMENTATION_PLAN.md` (kept for reference; its
domain decomposition survives, its audit and its delivery order do not)
**Status:** implementation in progress — static implementation audit completed 3 August 2026;
not production-ready until the release gates in §0.2 are closed

---

## 0. The product goal

**Fleet must do both: sell as an independent product, AND keep running as a module inside the CRM.**
Both are required — neither replaces the other. That is a stated business decision, and §4.12 and §6
are ordered around it: the CRM boundary is extracted in week 1, before the ledger, because every
table written without it adds coupling that has to be unpicked later.

**One codebase, one JAR, two product modes** — selected by `APP_PRODUCT_MODE`. Ports are not a step
away from the CRM; they are the mechanism that lets the same build serve both surfaces.

Nothing a CRM tenant has today is lost:

| CRM-mode Fleet today | After the redesign |
|---|---|
| Trip ↔ Booking link (`bookingPublicId`, `GET /trips?bookingId=`) | **Kept** — the CRM adapter resolves `Booking` exactly as `FleetBookingLookupRepository` does now. Same behaviour, different wiring |
| Vehicle ↔ Vendor snapshot | **Kept** — CRM adapter behind `FleetPartyPort` |
| Notifications, Trash, storage quota, permissions, tenant isolation | **Shared** — platform capabilities, not CRM business logic |
| Fleet cost inside the tenant's P&L | **New**, CRM-only (phase 6, behind the same port boundary) |

What §4.10 drops — Vehicle Master linking/sync, the booking dispatch subsystem,
`booking_transport_details`, `fleet_booking_requirements` — is **net-new functionality that no
tenant has today**. Dropping it is a zero-regression decision for CRM users, and it is what keeps
the standalone build small.

It is worth being clear that this makes the build *smaller*, not larger. Most of the weight in the
previous plan's "make Fleet CRM-independent" chapter is CRM-suite **integration** — Vehicle Master
linking, booking dispatch, transport requirements — which a Fleet-only customer never runs. The
independence itself costs about two and a half weeks.

## 0.1 The one-paragraph verdict

The existing plan gets the *decomposition* right — operations, money, legal validity and
settlement are genuinely four different records — and is right to refuse `BookingExpense` as the
fleet money source (`booking_expenses.booking_id` is `NOT NULL`, so a fleet expense with no
booking cannot physically exist there). But it rests on a stale audit with six factually wrong
claims, two of which delete its entire migration chapter; it front-loads ~55% of the effort onto
work its own §8.1 calls optional; and it is missing the single thing every practitioner named
first — **the driver's cash**. This redesign inverts the order, deletes roughly half the scope,
and puts the *duty slip and the driver's cash account* at the centre instead of a generic expense
table with a five-state approval workflow that nobody in the field will use.

## 0.2 Implementation audit — 3 August 2026

This section records the state of the **current working tree**, not merely the last commit. The
redesign is now substantially implemented: the Fleet package contains 143 Java files, 15 entities,
12 controllers and 74 mapped API operations. The implemented domain spine is:

```text
Vehicle / Driver -> Trip -> Trip Legs -> Expenses + Driver Cash
                                  -> Settlement -> Period Close

Compliance Documents -> Expiry Alerts
Expenses / Documents / Settlements -> Private Attachments
CRM Suite <-> Fleet ports <-> Standalone adapters
```

Implemented areas include vehicle/driver/party management, trip lifecycle and mid-trip handover,
ledger expenses and reversals, cash recording and trip settlement, period close/reopen, normalized
compliance documents, private attachments, duty slips, settlement sheets, expiry alerts, module
entitlements and CRM/standalone integration ports.

### 0.2.1 What is strong and must be preserved

- Tenant-scoped `publicId` lookups are used consistently across Fleet service boundaries.
- CRM business dependencies are isolated behind `FleetJobReferencePort` and `FleetPartyPort`, with
  `FleetBoundaryArchTest` enforcing the package boundary.
- FX conversion, rounding and settlement arithmetic have one implementation each and are covered by
  pure unit tests.
- Reversal rows, separate document/posting dates, settlement row locks and human-controlled period
  close express the correct accounting intent.
- Compliance renewal inserts history rather than overwriting it.
- Attachments are tenant-scoped Postgres bytes, quota-metered, checksummed and protected once their
  owning money record freezes.
- Operational permissions and money permissions are deliberately separate.

### 0.2.2 Release gates — must close before pilot or production

#### RG-1 — restore Flyway migration immutability

The current working tree appends roughly 2,021 lines to the already-existing
`V2__lead_code.sql`. The file itself records that the local database already has V2 stamped and
requires manually applying SQL, deleting the Flyway history row and restamping it. That is not a
repeatable deployment path: any database that has received the earlier V2 will fail checksum
validation or will not receive the appended schema.

Required correction:

1. Restore V2 to its previously shipped contents.
2. Move every later delta to immutable V3+ migrations in dependency order.
3. Test both a fresh V1 database and an upgrade database already stamped with the original V2.

Evidence: `src/main/resources/db/migration/V2__lead_code.sql:492-524`.

#### RG-2 — make trip deletion and trash purge FK-safe

Every newly created trip receives a `FleetTripLeg`. `FLEET_TRIP` is registered in
`TrashableType`, but trip legs are neither cascaded by `FleetTrip` nor registered ahead of their
parent, and the database FK has no `ON DELETE CASCADE`. Consequently, `delete-now` and the scheduled
hard purge fail for every trip with a leg. Because one transaction purges every trashable type for a
tenant, one old trip can roll back that tenant's entire purge indefinitely.

Required correction: choose an explicit retention rule for trips and legs, then either cascade the
leg lifecycle safely or remove trips from the 30-day hard-purge mechanism. Retained expense, cash,
settlement, document and attachment evidence must remain intact.

Evidence: `TrashableType.java:73-81`, `TrashServiceImpl.java:136-157`,
`FleetTripServiceImpl.java:89`, `V2__lead_code.sql:1106-1114`.

#### RG-3 — finish the one-money-source cutover

Money currently has competing sources of truth:

- trip request/entity fields still accept `fuelCost`, `tollCost` and `driverAllowance`;
- fuel and maintenance logs still store independent cost values;
- the dashboard sums fuel/maintenance log costs rather than `fleet_expenses`;
- trip responses replace the legacy scalar total only when the ledger sum is non-zero.

The last rule is observably wrong after a full reversal: a ledger that correctly nets to zero falls
back to an obsolete positive scalar and displays a cost that was reversed. New trip requests can
also populate the legacy scalars without creating ledger rows, so settlement and trip screens can
disagree.

Required correction: backfill and reconcile all legacy money, link operational fuel/maintenance
facts to ledger expenses where required, remove legacy request writes, and make every dashboard,
trip total and report read only `base_amount` from the ledger. Zero is a valid ledger result and must
never mean "fall back".

Evidence: `FleetTripCreateDto.java:36-44`, `FleetTripMapper.java:24-51`,
`FleetTripServiceImpl.java:470-475`, `FleetDashboardServiceImpl.java:103-106`.

#### RG-4 — close the fleet-money authorization leak

`GET /api/fleet/trips`, `GET /api/fleet/trips/{id}` and `GET /api/fleet/dashboard` require only
`FLEET_READ`, but `FleetTripResponseDto` includes the three legacy money fields and `totalExpense`.
The service also calculates the ledger total for every returned trip. A Travel Agent intentionally
excluded from `FLEET_MONEY_READ` can therefore read fleet costs.

Required correction: return an operational projection with no money when the caller lacks
`FLEET_MONEY_READ`, or split operational and financial response models. Apply the same rule to
dashboard trip cards and PDFs.

Evidence: `FleetTripController.java:23`, `FleetDashboardController.java:16`,
`FleetTripResponseDto.java:38-43`, `FleetTripServiceImpl.java:470-475`.

#### RG-5 — enforce aggregate relationship consistency

The expense service resolves the submitted vehicle, trip and driver independently, then stores all
three without proving that they describe the same leg. A request can therefore charge vehicle A for
trip B and reduce driver C's settlement even when C never drove that trip. Cash recording similarly
opens a settlement for any tenant driver against any tenant trip. The database has individual FKs,
but no constraint can express this cross-row business invariant.

Foreign-currency entry has a related hole: every non-INR code uses the trip's rate without checking
that the requested code equals `trip.fxCurrency`; a USD row can be converted using an NPR rate.

Required correction:

- when a trip is supplied, derive or validate vehicle and driver from the resolved leg;
- require a cash-entry driver to participate in a trip leg before creating its settlement;
- require request currency to equal the trip's configured foreign currency;
- cover mismatched combinations with service and database integration tests.

Evidence: `FleetExpenseServiceImpl.java:103-136,279-312,320-327`,
`FleetSettlementServiceImpl.java:79-125,361-389`.

#### RG-6 — make expense update obey create invariants

Create rejects system-computed expense types, driver-cash rows without a driver, missing receipt
reasons, and ambiguous time-sensitive costs after a handover. Update runs none of those checks after
mapping the request. It can therefore turn a valid row into a manually entered bata/night-halt row,
a driver-cash expense without an account owner, or a receipt-less row without justification. The
association IDs present in the update body are silently ignored rather than validated or rejected.

Required correction: share one invariant-validation method between create and update and make
association mutability explicit in the API contract.

Evidence: `FleetExpenseServiceImpl.java:92-139,147-169`, `FleetExpenseMapper.java:39-56`.

#### RG-7 — make trip occupancy concurrency-safe

Trip start and handover use check-then-write availability tests. Neither vehicle nor driver is
locked, the trip has no version, and the schema has no partial unique constraint for ongoing
assignments. Two concurrent requests can both observe "available" and commit two ongoing trips for
the same vehicle or driver.

Required correction: serialize assignment on the vehicle/driver rows or introduce a database-backed
active-assignment invariant, then add a concurrent integration test. Application-level `exists...`
checks remain useful for friendly errors but cannot be the only guard.

Evidence: `FleetTripServiceImpl.java:97-125,222-255`, `FleetTripRepository.java:45-50`.

### 0.2.3 High-priority correctness gaps

1. **Compliance is advisory, not enforced.** Trip create/start never calls
   `FleetComplianceService.check`. A vehicle or driver with no document rows passes because the
   blocker list is empty. `isValidThrough` considers only `validUntil`, ignoring a future
   `validFrom` and Bhansar `exitDeadline`. Blocking categories therefore do not currently block an
   assignment. Evidence: `FleetComplianceServiceImpl.java:242-279`,
   `FleetComplianceDocument.java:181-184`.

2. **Compliance update bypasses create validation.** It can clear a required state/exit deadline or
   submit an invalid validity interval. Renewal also requires the caller to submit an otherwise
   irrelevant owner because it calls the generic builder before replacing owner/category from the
   original. Evidence: `FleetComplianceServiceImpl.java:88-114,141-164,289-360`.

3. **A reopened accounting month cannot be closed again.** Reopen retains the row with
   `deleted_at IS NULL`; the unique index is keyed only on `(tenant, FY, month)` for non-deleted rows;
   `close()` filters out the reopened row and inserts another, causing a uniqueness failure.
   Evidence: `FleetPeriodServiceImpl.java:107-131,147-179`, `V2__lead_code.sql:1327-1328`.

4. **Settlement can be signed before trip completion.** `settle()` validates mutability, cash zero
   and acknowledgement, but not `trip.status == COMPLETED`. `reconcile()` also returns the DTO built
   before changing status, so the response says `OPEN` while the database says `RECONCILED`.
   Evidence: `FleetSettlementServiceImpl.java:143-194`.

5. **Allowance policy is not operable.** The entity and repository exist, but no seed, service or
   controller lets a tenant configure a policy; a fresh installation silently pays zero. If one is
   inserted manually, allowance is summed independently per leg, so two same-driver legs on one
   calendar day pay two days and may add a false night halt. Evidence:
   `FleetSettlementServiceImpl.java:331-356`, `V2__lead_code.sql:1396-1403`.

6. **Cash corrections and the owner-level cash view are missing.** `FleetCashEntry` contains a
   reversal pointer and the repository can calculate a driver's overall signed balance, but the API
   exposes only `POST /cash`; there is no history/list, reversal or driver cash-position endpoint.

7. **Cancelling an ongoing trip leaves an open leg.** The vehicle is freed and trip status changes,
   but the current leg receives no end time. It can continue matching late expense timestamps
   forever. Evidence: `FleetTripServiceImpl.java:188-199`.

8. **Swap notes are discarded.** `FleetTripSwapDto.notes` is accepted but never passed to
   `FleetTripLegManager`, and the new leg's `notes` remains null. Evidence:
   `FleetTripSwapDto.java:45-48`, `FleetTripLegManager.java:186-194`.

9. **Tenant timezone adoption is incomplete.** The dashboard and expiry scheduler still use bare
   `LocalDate.now()` / `LocalDateTime.now()`, so date windows depend on the server timezone rather
   than the tenant timezone. Evidence: `FleetDashboardServiceImpl.java:70,93`,
   `FleetDocumentExpiryScheduler.java:43`.

10. **List performance regressed to N+1 aggregation.** Trip list loads one page and then executes
    `sumTripCost` once per trip. Replace this with an aggregate projection, grouped join or batch
    query before increasing page sizes. Evidence: `FleetTripServiceImpl.java:331-340,470-475`.

### 0.2.4 Product scope still missing from this redesign

The current backend does not yet implement:

- the dispatcher board or temporary `fleet_holds`;
- vehicle cost/km, utilization, idle-day, statutory and owner-payout reports;
- driver-wide live cash position and cash history;
- allowance-policy management;
- cash-entry reversal/correction;
- driver signed-link/PWA workflow.

These remain delivery work, not documentation-only gaps. In particular, the standalone runbook must
not claim an operable bata policy or owner cash view until those APIs exist.

### 0.2.5 Standalone boundary — exact meaning

Standalone is currently **runtime product-surface isolation**, not a physically smaller backend.
The same full CRM JAR and baseline schema are deployed. `APP_PRODUCT_MODE=FLEET_STANDALONE` selects
standalone Fleet adapters; the tenant's `FLEET` module entitlement denies CRM API prefixes. This is
a valid one-codebase strategy, but "does not expose CRM to a Fleet tenant" is the accurate claim;
"the CRM modules do not exist in the artifact or database" is not.

`ModuleAccessFilter` remains fail-open for unclassified runtime paths, and its `failClosed` property
is declared but unused. The build-time coverage test is therefore part of the security boundary and
must run on every release.

### 0.2.6 Verification state

Fleet currently has 41 focused tests across money calculators, PDF rendering/visibility, permission
defaults and architecture boundaries. There are no service/API/database tests for trip lifecycle,
concurrent assignment, settlement orchestration, relationship consistency, compliance enforcement,
period reopen/reclose or trash retention.

The focused Maven run attempted on 3 August 2026 did not reach the tests because the current working
tree fails main compilation first at `BookingServiceImpl.java:1044` (`BookingCancelledEvent` cannot
be resolved). That error is outside Fleet, but it means the present implementation does not yet have
a green build result.

### 0.2.7 Recommended release order

1. Restore immutable migrations and prove fresh + upgrade database paths.
2. Fix trip/leg retention and hard-purge behavior.
3. Complete the one-ledger cutover and close the money-permission leak.
4. Enforce expense/cash/leg/currency consistency.
5. Add database-backed concurrency protection for assignment and settlement creation.
6. Fix compliance enforcement, period reclose and settlement/allowance correctness.
7. Add service, API and Postgres integration coverage for every invariant above.
8. Only then complete board, cash-position, reporting and driver-access surfaces.

---

## 1. What is actually there today

`com.crm.travelcrm.fleet` — ~1,768 lines, 6 entities, 6 controllers, 5 service impls, plus 4,793
lines of routed, permission-guarded React at `travelcrmfe/travelcrmfrontend/src/features/fleet/`.
(The plan's §2.4 item 8 claims the frontend has not been audited. It ships.)

| Entity | Reality |
|---|---|
| `FleetVehicle` | plate, type/make/model/year/seats, `ownerType OWN\|VENDOR\|RENTED`, vendor snapshot, **4 hardcoded document date columns**, status, `lastOdometer` (bump-up only), denormalized next-service |
| `FleetDriver` | name, phone, licence no + expiry, status. Explicitly *not* a `User` |
| `FleetTrip` | real FK vehicle + driver (`optional=false`), logical booking link, `PLANNED→ONGOING→COMPLETED\|CANCELLED`, odometers, **3 scalar money columns** |
| `FleetFuelLog` / `FleetMaintenanceLog` | per-vehicle diaries with cost + odometer |
| `FleetDocumentAlert` | fired-alert row doubling as idempotency marker |

**What is genuinely good and must be preserved verbatim:** the trip↔vehicle status sync, the
monotonic `bumpOdometer`, the delete-guard that refuses to trash a vehicle with diary history,
the trashed-collision `RestoreAvailableException` flow, and the per-tenant expiry scheduler with
`TenantContext` cleared in `finally`. This redesign does not rewrite the operational core.

**What is missing:** all money that matters. Three scalars on a trip, with no receipt, no
currency, no payer, no settlement, and no relationship to the driver's cash.

---

## 2. The plan's 28 defects — the nine that change the design

Full list in the appendix. These nine are the ones that force architectural change.

| # | Defect | Evidence |
|---|---|---|
| 1 | **§12.1's migration chapter has no executor.** Flyway is present but cannot boot — circular `flyway`↔`entityManagerFactory`. Every statement is hand-applied by psql on the live VPS. | `application.properties:71-93` |
| 2 | **`ddl-auto=validate` on *every* environment** — the moment an `@Entity` is committed, CI and both dev machines stop booting until SQL is applied by hand. | `application.properties:54-64` |
| 3 | **`FLEET_BASIC`/`FLEET_PRO` violate two named CHECK constraints** pinned to `('STARTER','PRO','ENTERPRISE')` in the promoted baseline. | `V1__baseline_schema.sql:91,141` |
| 4 | **No optimistic locking anywhere**, yet "concurrent approve/settle must not double-pay" is an acceptance test. `BaseEntity` has no version; it is opt-in here. | `VendorBill.java:18,39-40` |
| 5 | **"Void" is named 4×, defined 0×**, and both readings contradict the period lock the same plan specifies. | Plan §5.1:429 vs §6.1:573-588 |
| 6 | **Sequencing is inverted** — backlog items 1-5 are all shipped before the cost ledger, and §8.1 itself calls items 3-5 optional. | Plan §17 vs §8.1:886-891 |
| 7 | **No GST/ITC, no TDS.** `AccountingReportService` computes P&L as `TaxInvoice − VendorBill`, reading `inputGst` only from `VendorBill`. Every fleet rupee would be invisible to your own accounting module. | `AccountingReportService.java:56-68,156-158` |
| 8 | **Driver login** would consume hard-enforced `Tenant.maxUsers`, needs an `Ownable` row-scope retrofit that does not exist, and leaks every colleague's phone + licence. | `Tenant.java:67-69`, `OwnershipEntityListener.java:29-40` |
| 9 | **`vehicle_master.tenant_id` is `NOT NULL`** — the "global master row" that five plan sections and three of its tests depend on cannot exist. `VehicleEntity.isGlobal()` is dead code. | `V1__baseline_schema.sql:149` |

Two more worth flagging because they are silent failures rather than build failures:

- **`POST /api/vehicle-onboarding` bypasses the security model.** `ModuleAccessFilter` is
  fail-open on unmapped prefixes; a new top-level route is unmapped. It breaks the plan's own DoD
  line "Fleet-only users cannot access CRM endpoints even by manually calling the API."
- **`fleet_attachments` would be unmetered.** `StorageQuotaGuard` sums exactly two repositories.
  A new attachment table is invisible to both the quota and the SuperAdmin usage dashboard.

**Estimate:** 4-6 weeks is off ~3×. Ledger-first MVP 7-9 weeks; the full §16 definition of done
16-20 weeks at 1 BE + 1 FE.

---

## 3. Domain truth — what four practitioners said

Four independent 20-year lenses (owner/P&L, dispatcher, driver/field, compliance/accounts).

### 3.1 Rejected by all four

| Rejected | Because |
|---|---|
| `DRAFT→SUBMITTED→APPROVED→REJECTED→VOID` per expense | "for a Rs 640 parking charge" — four approvals per Rs 100 of a driver's own cash |
| The dispatch subsystem (§5.5, §7.3) | a second product; nine endpoints and six statuses before the chart exists |
| Vehicle Master link / sync / Own-Rented onboarding | "requiring me to pick a *Toyota Crysta* template before I can add a real vehicle" |
| ProductFamily + 6 SPI ports + 8 adapters | boundary work before any money feature exists |
| Client-supplied `fxRateToBase` | "nobody types an exchange rate on a phone" |
| `app.fleet.expense-lock-days = 7` | challans and pump bills do not arrive in 7 days |
| S3 / MinIO / Cloudinary storage adapters | one VPS; and `TravelerDocument` already solved this |
| CSV import as an MVP feature | not how data actually arrives |
| Cost-per-km as the headline metric | denominator is unreliable; excludes deadhead and fixed cost |
| Driver seats charged to `maxUsers` | a 20-vehicle operator cannot onboard its drivers |

### 3.2 Demanded by all four, and absent from the plan entirely

> **The driver cash loop.** Advance out (*peshgi*) → spends against it → cash back → reconciled
> and **signed** before he goes home (*hisaab*).

Every lens named this first. The plan has no advance concept at all — it frames driver money as
"reimbursement", which the owner lens rejected outright: *"I gave him Rs 8,000 before he left. I
am not reimbursing him, I am settling him."*

Other cross-lens musts the plan lacks:

- **Multi-leg trips** — vehicle/driver change mid-tour without destroying the record. Named by 3
  lenses; the plan defers it to an "emergency vehicle-swap workflow" mentioned once and specified
  nowhere.
- **A duty chart** — vehicle rows × 14-day timeline, the dispatcher's single screen.
- **A printable duty slip / trip sheet** the driver physically carries.
- **Document date is the accounting date**; entry date and approval date are separate and can
  never overwrite it.
- **"No receipt" is a first-class, honest entry with a reason** — not a validation failure.
- **8-year statutory retention** — no 30-day trash purge on any financial or compliance record.
- **Bata and night halt computed from a written policy**, shown to the driver, deductions visible.
- **A challan arrives weeks later** and must attribute back to whoever was on duty at that
  datetime.
- **Vehicle identity is the chassis number**; the registration plate is a dated attribute with
  history.

---

## 4. The redesign

### 4.1 Thesis

**The duty slip and the driver's cash account are the product.** Money attaches to a duty, not to
a free-floating expense row. Approval happens **once, on the trip settlement** — not four times
per Rs 100. Everything else — compliance, the board, reports — hangs off that spine.

```
FleetVehicle ──┐
               ├── FleetTrip (the duty / customer commitment)
FleetDriver ───┘        │
                        ├── FleetTripLeg        one vehicle+driver+odometer span   [NEW]
                        ├── FleetExpense        one money event, document-dated    [NEW]
                        ├── FleetCashEntry      driver imprest: advance / return   [NEW]
                        └── FleetTripSettlement one signed reconciliation          [NEW]

FleetDocument      compliance with renewal history, replaces 4 date columns        [NEW]
FleetAttachment    bytea, private, checksummed, quota-metered                      [NEW]
FleetHold          tentative reservation with an expiry clock                      [NEW]
FleetAllowancePolicy  effective-dated bata / night-halt rates                      [NEW]
```

Nine new tables — but sequenced so that **four of them ship in phase 1 and are sellable alone.**

### 4.2 Trip legs — the fix for the most common fleet exception

`FleetTrip.vehicle` / `.driver` are `@ManyToOne(optional = false)`. A breakdown 200 km into a
Nepal run currently has two representations: corrupt the trip, or cancel and re-create and lose
the odometer chain.

```
fleet_trip_legs
  trip_id, seq
  vehicle_id, driver_id
  start_datetime, end_datetime
  start_odometer, end_odometer, distance_km
  change_reason        BREAKDOWN | DRIVER_HANDOVER | REST_RULE | OWNER_DECISION | null (first leg)
```

- `FleetTrip.distanceKm` becomes **the sum of its legs**, not `end − start`.
- `FleetTrip.vehicle/driver` stay as a denormalized pointer to the **current** leg — existing
  reads, the FE and the specifications keep working unchanged.
- Migration is clean: every existing trip becomes exactly one leg.
- The duty chart, driver attribution for a late challan, and per-vehicle cost all read legs.

### 4.3 Money — one ledger, document-dated, no per-row workflow

```
fleet_expenses
  vehicle_id (req), trip_id (opt), leg_id (opt), driver_id (opt)
  expense_type          TOLL PARKING FUEL BHANSAR_NEPAL PERMIT_IN PERMIT_NP ROAD_TAX_IN
                        CHALLAN MAINTENANCE TYRE DRIVER_BATA NIGHT_HALT BORDER_AGENT_FEE OTHER
  document_date         DATE  NOT NULL   -- the accounting date. Never overwritten.
  entered_at/by, approved_at/by          -- separate, never conflated with document_date
  amount, currency, base_amount          -- numeric(14,2)
  fx_rate                                -- copied from the TRIP, never sent by a client
  paid_by               OFFICE_DIRECT | DRIVER_CASH | VENDOR_CREDIT
  has_receipt BOOLEAN, no_receipt_reason -- first-class, not an exception
  supplier_gstin, supplier_invoice_no, taxable_value, gst_rate, cgst, sgst, igst, itc_eligible
  tax_character         ALLOWABLE | DISALLOWABLE_37_1 | CAPITAL
  reversal_of_id        -- corrections are dated reversals, not edits
  row_version           -- @Version. Mandatory.
```

Six decisions that differ from the plan:

1. **`document_date` is the accounting date.** The plan conflates entry and document time. An
   accountant lens line: *"the date on the receipt matters more than when it was entered."*
2. **No per-expense approval.** Rows are recorded freely while the trip is `OPEN`. Approval is a
   single act on the settlement.
3. **FX rate lives on the trip, not the expense.** The office sets one rate per Nepal trip. The
   driver enters NPR as NPR and never sees a rate. This kills the plan's
   `"fxRateToBase": 0.62500000` from a device — which all four lenses rejected.
4. **Rounding is fixed and documented: `HALF_UP` at `numeric(14,2)`,** applied once at
   `base_amount` write. The plan names a rule and never picks one; after one month of data it is
   unfixable.
5. **GST + tax character on every row** — so fleet money reaches `AccountingReportService`
   instead of being invisible to your own P&L.
6. **`reversal_of_id` settles the "void" ambiguity.** A void is a *new row, dated today, negative,
   pointing at the original*. Both rows stay. Reports net them. A closed period never changes
   retroactively. There is no status flip.

### 4.4 The cash loop — what the plan is missing entirely

```
fleet_cash_entries
  driver_id (req), trip_id (opt)
  direction        ADVANCE_OUT | CASH_RETURN | CUSTOMER_COLLECTION | RECOVERY | ADJUSTMENT
  amount, currency, base_amount
  entry_date, reference, notes, row_version
```

A driver has a **running imprest balance**. `ADVANCE_OUT` increases it, `DRIVER_CASH` expenses and
`CASH_RETURN` decrease it. Customer cash collected on the road lands in the driver's float — the
owner lens was explicit that this must not become "a note on an invoice".

```
fleet_trip_settlements                     one per trip
  advance_total, spend_total, collected_total, returned_total
  bata_computed, night_halt_computed       -- from FleetAllowancePolicy, not typed
  net_due_to_driver / net_due_from_driver
  status      OPEN → RECONCILED → SETTLED → LOCKED
  settled_at/by, driver_acknowledged_at, signature_ref
  row_version
```

- `OPEN` — free edit, free entry, no ceremony.
- `RECONCILED` — numbers agree; the sheet is printable.
- `SETTLED` — signed by the driver. **From here, corrections are reversal entries only.**
- `LOCKED` — period close by financial year/month, **owner-controlled**. Not a rolling 7-day
  timer (rejected by all four lenses).

**A trip cannot be marked settled until the driver's cash is squared, and it stays visibly open
until it is.** That was the owner's line, and it is the single most valuable invariant in this
module.

### 4.5 Compliance — documents, not date columns

Replace `insuranceExpiry` / `rcExpiry` / `permitExpiry` / `pucExpiry` with:

```
fleet_documents
  owner_type VEHICLE|DRIVER, owner_id     -- exactly one, CHECK-enforced
  doc_type, doc_number, issuing_authority, country_code, state_code
  issued_on, valid_from, valid_until
  status      ACTIVE | EXPIRING | EXPIRED | SUPERSEDED | REVOKED
  supersedes_id                            -- renewal keeps full history
  expense_id                               -- what it cost, if anything
  is_blocking BOOLEAN                      -- tenant-configurable per type
```

Document types the practitioners actually named, which the plan omits:
**Uttarakhand Green Card**, **Trip Card**, **Fitness Certificate**, **VLTD / panic button**,
**speed governor**, and on the driver side **transport endorsement**, **PSV badge**, **medical**.

**Warn, never block — with one exception.** The dispatcher was emphatic ("compliance must warn
loudly and never block"); the accountant was equally emphatic that an expired PSV badge must
*refuse* assignment. Resolution: `is_blocking` per type, defaulting to warn, with an **owner-only
override carrying a typed reason** on the blocking ones. And the check runs against the **trip's
return date**, not today — a permit valid tomorrow but expired on day 6 of a Char Dham run is a
vehicle impounded at a barrier.

`fleet_documents` also answers *"what was valid on this past date"* — a scrutiny question, and
free once validity is an interval instead of a column.

### 4.6 Attachments

`fleet_attachments` — **Postgres `bytea`**, exactly the `TravelerDocument` precedent, served only
through an authenticated ownership-checked endpoint. Never Cloudinary: those URLs are public and
unauthenticated, and these are financial and identity documents.

Three things the plan missed:
- **Register with `StorageQuotaGuard`** — it currently sums two repositories and would not see
  this table, giving fleet tenants unmetered storage.
- **Checksum + freeze at settlement.**
- **Never block an upload on quota** for a compliance document (accountant lens) — meter and
  warn, block only on gross overage.

### 4.7 Retention — the 30-day purge must not touch fleet money

Fleet entities are currently registered in `TrashableType`, which means the 30-day purge applies.
For expenses, cash entries, settlements, compliance documents and attachments, **that is a
statutory violation** — the retention requirement is 8 years.

These five tables are **excluded from `TrashableType`**. Soft-delete still hides a row; nothing
ever purges it. This is a deliberate, documented divergence from the house convention.

### 4.8 The board — what the dispatcher actually asked for

Not the plan's nine-endpoint dispatch subsystem. One read endpoint and one small table.

```
GET /api/fleet/board?from=&to=      vehicle rows × day columns
```

Blocks on the board come from three sources: **trip legs** (committed), **`fleet_holds`**
(tentative, with an expiry clock, an owner's name and a release reason), and **workshop status**
(a soft, repeatedly-pushable expected-return date, settable mid-trip).

A conflict produces a **loud warning naming who promised it and when** — never a hard block.
Auto-assignment does not exist. Crucially, **a hold needs no Booking** — it carries a free-text
party name, with an *optional* booking reference. That is the entire coupling, and it means the
board works identically in standalone mode.

### 4.9 Driver access without seats

Do not create `User` rows for drivers. They consume hard-enforced `maxUsers`, need an `Ownable`
row-scope retrofit that does not exist, and leak colleagues' PII.

Instead: a **signed-link surface** (the pattern already used for the SSE token and the portal
realm). The driver receives a per-trip link by WhatsApp — his duty, his balance, his documents,
and a four-tap spend entry. No account, no password, no seat, no PII beyond his own.

**This is phase 3 and optional.** The dispatcher's requirement was explicit: *everything must be
enterable by one person at a desk with no driver login.* The desk grid is the primary path; the
driver link is an accelerator.

### 4.10 What we are NOT building

| Dropped | Why |
|---|---|
| ProductFamily on Tenant *and* Plan | `Plan.modules = {FLEET}` already expresses it with zero schema change; two columns that can disagree |
| 6 SPI ports + 8 adapters | real coupling is 4 files. 2 ports + 1 ArchUnit rule ≈ 2 days, whenever a Fleet-only contract is signed |
| `FleetFileStorage` × 3 backends | one VPS; `bytea` precedent exists |
| The dispatch subsystem + `booking_transport_details` + `fleet_booking_requirements` | rejected by all four lenses; also rests on `BookingServiceItem.serviceType` being free text, so "Transfer" or "Cab" silently produces no row |
| Vehicle Master link / sync / `POST /api/vehicle-onboarding` | rejected by all four; the global-master row it needs cannot exist; the endpoint bypasses `ModuleAccessFilter` |
| `FLEET_BASIC` / `FLEET_PRO` plans | violates two CHECK constraints; no Fleet-only customer exists yet |
| 10 new permission keys | 3 suffice: `FLEET_MONEY_READ`, `FLEET_MONEY_SETTLE`, `FLEET_PERIOD_CLOSE` — **plus a backfill**, or every user with a saved custom map silently loses access on deploy day |
| Offline PWA | no service worker, no manifest, no IndexedDB in the tree; 3-4 weeks alone |
| CSV import | not phase 1 |

### 4.11 Two platform fixes that must land *before* any ledger data

1. **`tenant.timezone`.** IST is +5:30, NPT +5:45. Every date boundary in the app uses
   `ZoneId.systemDefault()`, and `FleetTripServiceImpl:106,123` defaults times with bare
   `LocalDateTime.now()`. A Bhansar receipt at 23:50 NPT files into the previous day in every
   Indian report. Adding this after the ledger has data is a backfill, not a column.
2. **`@Version` on every money entity.** `BaseEntity` has none; it is opt-in. Without it two
   settlement posts race and the derived status is computed from a lost update.

### 4.12 Plug-and-play — what selling Fleet standalone actually requires

**The product goal is to sell Fleet as an independent product.** That is a business decision, and it
changes the sequencing: the boundary comes *first*, not last. Every feature written without it adds
new CRM coupling that has to be unpicked later, and the ledger is the biggest feature there is.

What it does **not** change is the scope. The standalone plan reads as though independence is
expensive; it is the opposite. Most of that plan's weight is **CRM-suite integration** — Vehicle
Master linking, booking dispatch, transport requirements. None of it exists in a Fleet-only
deployment. Dropping it serves the standalone goal rather than working against it.

Honest minimum for a sellable independent product:

| # | Requirement | Cost | Why it is genuinely required |
|---|---|---|---|
| 1 | **Fleet core imports nothing from `booking.*` / `vendor.*`** | ~2 days | Real coupling is **4 files**: `FleetTripServiceImpl`, `FleetBookingLookupRepository` (Booking), `FleetVehicleServiceImpl`, `FleetVendorLookupRepository` (Vendor) |
| 2 | **Two ports, not six** | included above | `FleetJobReferencePort` — CRM adapter resolves a `Booking`; standalone adapter takes a free-text job/party reference. `FleetPartyPort` — CRM adapter resolves a `Vendor`; standalone adapter reads a small `fleet_parties` table |
| 3 | **One ArchUnit rule** | ~2 hours | `LeadSourceAdapterPurityArchTest` is a working template. This is what keeps the boundary true after month three — a review checklist will not |
| 4 | **Fleet-only tenant provisioning** | ~1 day | `Plan.modules = {FLEET}`. **No `ProductFamily`** — the module set already expresses it. Requires widening the `plans_code_check` / `tenants_plan_check` constraints in a future migration |
| 5 | **Fleet-only frontend shell** | ~1 week | Conditional chrome selected from `GET /api/me/entitlements` — *not* `/api/me/features`, which returns a single deployment-wide boolean. Not a fork: same build, same login, different sidebar and landing route |
| 6 | **`APP_PRODUCT_MODE=FLEET_STANDALONE`** | ~0.5 day | Picks the standalone adapters, hides CRM provisioning, applies Fleet branding |
| 7 | **Deployment package** | ~2 days | One documented `docker-compose` — Postgres + JAR + nginx. Plus the hand-applied SQL runbook, since Flyway cannot boot |

**~2.5 weeks total**, against the original plan's implied months. Everything else it listed under
"make Fleet CRM-independent" is either indirection over a dependency §7.4 itself calls a keepable
platform capability (notifications), or integration that a Fleet-only customer will never run.

Two things stay shared on purpose, and they are platform capabilities rather than CRM business
logic: `BaseTenantEntity` / `TenantContext` / API envelopes / error handling, and auth + users +
permissions + subscription + storage quota + audit + trash. A Fleet-only product needs all of them
and gains nothing from reimplementing them.

**The invariant that makes it plug-and-play:** a fleet trip, expense, cash entry, settlement and
compliance document must all be creatable with **no booking, no vendor, no master vehicle and no CRM
row of any kind**. That is already almost true today — the booking and vendor links are optional
snapshots. Requirement 1 makes it true at compile time, and requirement 3 keeps it true.

**The matching invariant for CRM mode:** enabling the CRM adapters must add capability and change
nothing else. A trip created with a `bookingPublicId` resolves and snapshots the booking code
exactly as it does today; a trip created without one behaves identically in both modes. The adapter
decides *what a job reference resolves to*, never *whether fleet works*.

Concretely, the two ports and their two implementations each:

| Port | CRM adapter | Standalone adapter |
|---|---|---|
| `FleetJobReferencePort` | resolves `Booking` by publicId, tenant-scoped; snapshots `bookingCode` — i.e. today's `FleetBookingLookupRepository` behaviour, moved | accepts a free-text job / party reference and snapshots it verbatim |
| `FleetPartyPort` | resolves `Vendor` by publicId, tenant-scoped; snapshots name — i.e. today's `FleetVendorLookupRepository` behaviour, moved | reads a small tenant-owned `fleet_parties` table |

Wired with `@ConditionalOnProperty` for the CRM adapters and `@ConditionalOnMissingBean` for the
standalone fallbacks, so a misconfiguration degrades to standalone (no CRM resolution) rather than
failing open into cross-module reads.

---

## 5. Migration — written for the executor that actually exists

**Updated 2 Aug 2026 — Flyway now works.** This section originally argued that Flyway could not boot
(a circular `flyway`↔`entityManagerFactory` dependency) and that every statement had to be
hand-applied by psql. That is fixed: `FLYWAY_ENABLED=true` boots and runs `migrate()` to completion,
which removes what §2 called the single biggest reason the original estimate was 3× low.

**Where the fleet SQL lives:** `V2__lead_code.sql`, as **PARTS 6-8**. V2 has not reached production,
so it is still the right place — but it *is* stamped in the local `flyway_schema_history`, so every
edit to it breaks the next local boot with `Migration checksum mismatch for migration version 2`.
The fix is a re-stamp, not a `repair` (bare repair rewrites the checksum without running the new
SQL, and `ddl-auto=validate` then fails on the missing columns):

1. run V2 by hand with `psql -v ON_ERROR_STOP=1 -f …` — surfaces any SQL error with a line number
2. `DELETE FROM flyway_schema_history WHERE version = '2';`
3. boot — Flyway re-runs V2 (all no-ops, every statement is idempotent) and stamps it correctly

The moment V2 reaches the deployment database this stops being an option and the next change is a
genuine V3. `ddl-auto` stays `validate` everywhere, so a database missing a migration still fails
fast with `Schema-validation: missing column [...]` rather than drifting — and the ordering rule
still holds: SQL lands before the JAR that declares the entity.

| Step | Script | Gate |
|---|---|---|
| 0 | **V3 PART 6** — `tenants.timezone` + backfill `'Asia/Kolkata'`, `FLEET_MONEY_READ` backfill | before anything ✅ *done* |
| 1 | `fleet_trip_legs` + backfill one leg per existing trip | reconcile `SUM(leg.distance) = trip.distance_km` |
| 2 | `fleet_expenses`, `fleet_cash_entries`, `fleet_trip_settlements` + `@Version` columns | shadow-check on a V2-populated DB |
| 3 | Backfill **all three** legacy scalars — `toll_cost`, `fuel_cost`, `driver_allowance` — as expenses, idempotent on `(trip, type, LEGACY)` | reconcile totals per trip |
| 4 | Point `fleet_fuel_logs` / `fleet_maintenance_logs` at an `expense_id`; the log keeps litres/odometer, the expense owns the money | one money source |
| 5 | **Drop the legacy reads in the same release.** Dashboard, `totalExpense` and reports read only the ledger | no dual-write window |
| 6 | `fleet_documents` + backfill the 4 date columns, flagged `NEEDS_REVIEW` | old columns read-only for one release, then dropped |
| 7 | `fleet_attachments` + register with `StorageQuotaGuard` | quota visible in the SuperAdmin dashboard |
| 8 | Exclude the five financial/compliance tables from `TrashableType` | 8-year retention |

**No dual-write window.** The plan proposes one; the accountant lens rejected keeping
`toll_cost`/`fuel_cost`/`driver_allowance` alive beside the ledger, and the plan's own §12.2
migrates only one of the three — which would make trip totals and ledger totals disagree
permanently. Backfill all three, cut over in one release, reconcile, drop.

Note the pre-existing version of this same disease: `trip.fuelCost` and `FleetFuelLog.cost` are
already two unreconciled records of the same fuel, and the dashboard counts only the latter.
Step 4 fixes that too.

---

## 6. Delivery — inverted from the plan

Ordered for the stated business goal: **an independently sellable Fleet product.**

| Phase | Weeks | Ships | A customer can then… |
|---|---|---|---|
| **0 Platform** | 0.5 | tenant timezone, `@Version`, 3 permission keys + backfill | — ✅ *done* |
| **1 Boundary** | 1 | 2 ports + standalone/CRM adapters, ArchUnit purity rule, `APP_PRODUCT_MODE` | — but every later phase now stays clean by construction ✅ *done (ports + adapters + ArchUnit; APP_PRODUCT_MODE pending with phase 4)* |
| **2 Cash & duty slip** | 3 | legs, expenses, cash entries, settlement, bata policy, desk grid, printable trip sheet + settlement PDF, legacy cutover | **run a trip end-to-end and settle a driver's cash** ✅ *done — cutover, swap/hand-over UI, month-close screen, duty slip + settlement sheet PDFs* |
| **3 Compliance** | 2.5 | `fleet_documents`, renewal history, attachments (bytea, metered), return-date checks, expiry alerts | stop losing vehicles at barriers ✅ *done — register + renewal chains + alerts + attachments metered via StorageQuotaGuard* |
| **4 Fleet-only product** | 1.5 | Fleet plan + provisioning, Fleet FE shell, branding, compose package, deploy runbook | **buy Fleet on its own, with no CRM** — first sellable standalone release ✅ *done — TenantPlan.FLEET + ensureFleetPlan, fleet_parties directory, APP_PRODUCT_MODE surfaced via /api/me/entitlements, fleet-only sidebar + landing, docker-compose.fleet.yml + nginx-fleet.conf + docs/FLEET_STANDALONE_DEPLOY.md* |
| **5 Board** | 2 | duty chart, holds with expiry, conflict warnings, workshop status, WhatsApp publish, driver signed-link | run tomorrow's chart on one screen |
| **6 Money out** | 2 | challan + dispute + recovery, hired-vehicle bills, GST/TDS bridge into `AccountingReportService` *(CRM-suite only)* | see fleet cost in the real P&L |
| **7 Reports** | 2 | FY vehicle-wise annexure, km/L, utilisation & idle days, cost/km *with fixed cost* | answer the 10pm questions |

**First standalone release at end of phase 4 — ~8.5 weeks.** Full product ~14 weeks. Compare the
original plan: 4-6 weeks claimed, 16-20 weeks actual, and the thing a customer pays for arriving
sixth.

Two sequencing calls worth stating explicitly:

- **Boundary before ledger (phase 1 before 2).** Reversed from my first draft. With standalone as
  the business goal, writing ~8 tables of ledger code against CRM types and unpicking it afterwards
  is strictly more work than spending one week first. The ArchUnit rule is what makes this hold.
- **Money-out (phase 6) is CRM-suite only.** The GST/TDS bridge into `AccountingReportService`
  matters to a CRM tenant whose P&L must include fleet cost. A Fleet-only customer has no
  `AccountingReportService`, so this cannot gate the standalone release — it sits behind the same
  port boundary as everything else CRM.

Still dropped, and dropping them *helps* the standalone goal because a Fleet-only customer never
runs them: Vehicle Master linking/sync/onboarding, the booking dispatch subsystem,
`booking_transport_details`, `fleet_booking_requirements`, `ProductFamily`, three storage backends.

---

## 7. Open decisions — owner's call

| # | Question | Recommendation |
|---|---|---|
| 1 | Blocking vs warning on expired documents | `is_blocking` per type; default warn; **block** on DL / PSV badge / fitness / permit, with owner override + typed reason |
| 2 | Bata policy grain | per vehicle class + effective-dated. Per-driver overrides only if you actually pay differently |
| 3 | Does fleet cost post into booking profit? | **No auto-post.** Accountant lens rejected it. Fleet reaches P&L through the GST/TDS bridge, not through `Booking.netProfit` |
| 4 | Chassis-as-identity | architecturally right, phase 3+. Plate stays the display key until then |
| 5 | Period close grain | FY + month, owner-triggered. **No rolling day-count lock** |
| 6 | Driver link surface | phase 3, optional. Desk-first is non-negotiable |
| 7 | Nepal date | store the printed Bikram Sambat string verbatim alongside the Gregorian date — do not convert and discard |

---

## 8. Appendix — the remaining plan defects

Beyond the nine in §2: the frontend-not-audited claim (4,793 lines ship); `/api/me/features`
returns one deployment-wide boolean, not per-tenant modules (`/api/me/entitlements` does); the
idempotency key's "unique among non-deleted rows" predicate defeats its own retry test; rounding
named but never chosen; multi-day trips straddling month boundaries make per-trip and monthly
totals irreconcilable with no stated attribution rule; odometer has no correction path yet is the
denominator of the headline metric; 11 strategy classes for what §4 shows to be a table; §2.4
item 7 misses that 34 test classes and **three ArchUnit suites** already exist —
`LeadSourceAdapterPurityArchTest` is a working template for exactly the import rule §7.1 wants in
prose, and `TenantIsolationArchTest` already guards the `findById` rule.

**Zero fleet tests exist today.** For a module that moves money, that is the release blocker, not
the standalone boundary.
