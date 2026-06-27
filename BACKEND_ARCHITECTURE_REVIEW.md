# TravelCRM — Backend Architecture Review

**Scope:** Backend services layer (Spring Boot 3.5 / Hibernate 6 / PostgreSQL, multi-tenant SaaS).
**Mode:** Read-only audit. No code was modified. Every finding cites `file:line`; items that could not be confirmed from code are marked **UNVERIFIED**.
**Lens:** Prioritised by *blast radius* (how many tenants/records a bug can silently corrupt or expose), not code style.
**Frontend (Part A):** out of scope per instructions — not covered here.

---

## 1. Executive Summary — Top Risks by Blast Radius

> Each line: the risk + *what breaks if ignored*.

1. **Row-level ownership scoping exists for `Lead` only.** Every other aggregate — Booking, Quotation, Customer, Reminder — returns **all tenant records to every authenticated user** regardless of role. *What breaks: a TRAVEL_AGENT/STAFF user reads (and for Reminder, edits/deletes) every colleague's bookings, quotations, customers and reminders inside the tenant. Whole-table intra-tenant exposure across the entire CRM.* (`BookingServiceImpl.java:248`, `CustomerServiceImpl.java:141`, `ReminderServiceImpl.java:164`)
2. **Reminder API is keyed by the internal `Long id` and is not owner-scoped.** *What breaks: sequential-id enumeration (IDOR) — `GET/PUT/DELETE /api/reminders/{id}` lets any `REMINDER_*` holder read/modify/delete another staff member's reminders by guessing ids.* (`ReminderController.java:84-126`, `ReminderServiceImpl.java:260`)
3. **H2 — email-only login is not tenant-scoped.** *What breaks: if the same email exists in two tenants (the schema explicitly allows it), login either 500s on `NonUniqueResultException` or logs the user into the wrong tenant.* (`AuthServiceImpl.java:105`, `User.java:16-18`)
4. **Soft-delete cascade is partial.** Trashing a `Lead` cascades to `Quotation` but **not** to `Booking` or `Reminder`. *What breaks: active bookings/reminders left pointing at a dead lead — orphaned children, broken traceability, "ghost" rows in lists.* (`QuotationEventListener.java:32`, no equivalent in booking/reminder)
5. **Booking has no state machine.** `updateStatus` sets any status directly. *What breaks: invalid lifecycle jumps (e.g. `COMPLETED → PENDING`, `CANCELLED → CONFIRMED`) that the `cancel()` guard explicitly forbids, corrupting reporting and money state.* (`BookingServiceImpl.java:301-313` vs `:324-330`)
6. **Internal `Long` ids cross the API boundary.** Booking create accepts `Long customerId` / `Long leadId` in the request body; Reminder uses `Long id` in URLs; Reminder CSV exports raw `id`/`leadId`. *What breaks: violates the publicId/UUID-only contract; enables enumeration and couples clients to internal keys.* (`CreateBookingRequestDTO.java:20,55`, `ReminderController.java:84`, `ReminderServiceImpl.java:232-239`)
7. **Public quotation weblink is a permanent capability URL and may expose internal `markup`.** No expiry/revocation; `getPublicByPublicId` strips only `createdBy`/`leadId`. *What breaks: anyone with the link sees the quote forever; agent margin may leak to the customer.* (`QuotationServiceImpl.java:282-291`) — markup exposure **UNVERIFIED** (depends on `Totals` DTO fields).
8. **God services + unbounded queries.** `BookingServiceImpl` 688 LOC, `LeadServiceImpl` 565, `QuotationServiceImpl` 557; several Reminder list endpoints return unpaginated full-table reads. *What breaks: maintainability erosion and unbounded result sets as tenants grow.* (`ReminderServiceImpl.java:164,180,190,226`)

**The good news (verified strengths):** tenant isolation is solid (no `findById` bypass on tenant entities; filter re-applied per row in schedulers; `TenantContext` cleared in `finally` everywhere it is set on a pooled thread); money is `BigDecimal` end-to-end with `HALF_UP` rounding computed in one place; permissions are genuinely enforced (not saved-but-ignored); constructor injection is universal (zero field `@Autowired`).

---

## 2. Severity-Ranked Findings

| # | Sev | Category | Finding | file:line | Why it matters (CRM impact) | Fix direction |
|---|-----|----------|---------|-----------|------------------------------|---------------|
| 1 | **Critical** | Access control | Ownership scoping only on Lead; Booking/Quotation/Customer/Reminder reads are tenant-only | `BookingServiceImpl.java:248`, `CustomerServiceImpl.java:141`, `ReminderServiceImpl.java:164`, `QuotationServiceImpl` (no scope) | Every staff user sees all colleagues' records in the tenant | Extend existing `ScopeResolver.visibleUserIds` pattern (used by Lead) to all list/get/count; add owner column where missing |
| 2 | **Critical** | IDOR / Access | Reminder keyed by `Long id`, not owner-scoped | `ReminderController.java:84-126`, `ReminderServiceImpl.java:260` | Enumerable read/update/delete of other staff's reminders | Switch to `publicId`; add owner-scope guard returning 404 |
| 3 | **High** | Auth / Tenancy | H2: email-only login lookup not tenant-scoped | `AuthServiceImpl.java:105`, `UserDetailsServiceImpl.java:20`, `User.java:16-18` | Cross-tenant email reuse → 500 or wrong-tenant login | Disambiguate by tenant at login (tenant selector / subdomain / fail-closed on multiple matches) |
| 4 | **High** | Data integrity | Lead soft-delete cascades to Quotation only, not Booking/Reminder | `QuotationEventListener.java:32`; `LeadServiceImpl.java:252` | Orphaned active children referencing a trashed lead | Add booking/reminder listeners for `LeadSoftDeletedEvent`/`LeadRestoredEvent`, or block lead delete when active children exist |
| 5 | **High** | Lifecycle | Booking `updateStatus` has no transition guard | `BookingServiceImpl.java:301-313` | Invalid status jumps corrupt reporting & money | Introduce an explicit allowed-transition map; reject illegal jumps |
| 6 | **Med** | Identifier exposure | `Long customerId`/`leadId` in booking create payload | `CreateBookingRequestDTO.java:20,55`; `BookingServiceImpl.java:100,109` | Internal keys on the wire; enumeration | Accept `UUID` publicIds; resolve to internal id in service |
| 7 | **Med** | Lifecycle | Direct booking create does not check lead convertibility or prior conversion | `BookingServiceImpl.java:109-114` | Same lead converted to multiple bookings; bookings from lost/converted leads | Guard on lead stage + existing `convertedBookingPublicId` |
| 8 | **Med** | Data integrity | Reminder stores lead/assignee as deprecated free-text **and** Long FK **and** denormalized snapshot | `Reminder.java:61-103` | Drift: stale `assignToName`/`leadName` after rename | Drive reads from the Long FK; treat snapshots as display-only; plan retirement of `@Deprecated` String columns |
| 9 | **Med** | Money / Privacy | Public quotation may expose `markup`; permanent capability URL, no expiry | `QuotationServiceImpl.java:282-291`, `PublicQuotationController.java:40` | Agent margin leak; no revocation | Explicitly null margin fields on public DTO; add optional expiry/revoke |
| 10 | **Med** | Access control | `BookingReminderController` has no `@PreAuthorize` (only `authenticated()`) | `BookingReminderController.java` (no annotation) | Any authenticated tenant user can call it, no per-action key | Add `@PreAuthorize` keys consistent with siblings |
| 11 | **Low** | Convention | Reminder endpoints bypass `ApiResponse` envelope | `ReminderController.java:24-27` | Inconsistent client contract | Documented deliberate FE-compat deviation; track for future alignment |
| 12 | **Low** | Perf | Reminder list/overdue/due-today/CSV unbounded (no pagination) | `ReminderServiceImpl.java:164,180,190,226` | Unbounded payloads at scale | Add pagination/limit |
| 13 | **Low** | Maintainability | God services >400 LOC / >5 deps | `BookingServiceImpl` 688, `LeadServiceImpl` 565, `QuotationServiceImpl` 557, `CustomerServiceImpl` 466, `VendorServiceImpl` 414 | Hard to test/change safely | Split read/query services from command services |
| 14 | **Low** | Tenancy hygiene | `getByCode` uses non-tenant finder, relies on `@Transactional` filter | `BookingServiceImpl.java:240-243` | Safe only while filter stays enabled | Prefer an explicit `...AndTenantId` finder |

---

## 3. Section 1 — Service Inventory

> LOC from `wc -l`. "God service?" = >~400 LOC **or** >5 injected deps.

| Service | Domain / aggregate | Controller(s) | Repos used | Calls other services | LOC | God? |
|---|---|---|---|---|---|---|
| `BookingServiceImpl` | Booking | BookingController, LeadConversionController | Booking, Customer, Lead, Quotation | LeadAccessGuard, eventPublisher | **688** | **Yes (LOC + 9 deps)** |
| `LeadServiceImpl` | Lead | LeadController | Lead, User | QuotationService, ScopeResolver, LeadAccessGuard, eventPublisher | **565** | **Yes (LOC + 7 deps)** |
| `QuotationServiceImpl` | Quotation | QuotationController, PublicQuotationController | Quotation | LeadAccessGuard, QuotationPdfService, QuotationMapper | **557** | **Yes (LOC)** |
| `CustomerServiceImpl` | Customer | CustomerController | Customer | — | **466** | **Yes (LOC)** |
| `VendorServiceImpl` | Vendor | VendorController | Vendor | — | **414** | **Yes (LOC)** |
| `ReminderServiceImpl` | Reminder | ReminderController | Reminder, User | LeadAccessGuard | 345 | Borderline |
| `UserServiceImpl` | User | UserController | User, Tenant | permissions | 307 | No |
| `HotelServiceImpl` | Hotel (master) | HotelController | Hotel + nested | — | 250 | No |
| `TrashServiceImpl` | Trash | TrashController | (multi) | — | 247 | No |
| `CityServiceImpl` | Geography | CityController | City, Destination | — | 245 | No |
| `MasterDropdownServiceImpl` | Dropdowns | MasterDropdownController | (many) | — | 212 | No |
| `DestinationServiceImpl` | Geography | DestinationController | Destination | — | 196 | No |
| `BookingReminderService` | Booking reminders | BookingReminderController | BookingReminder | — | 185 | No |
| `AuthServiceImpl` | Auth | AuthController | User, SuperAdmin | JwtUtil, StaffIpService | 171 | No |
| `PermissionTemplateService` | Permissions | PermissionTemplateController | PermissionTemplate | — | 155 | No |
| `SightseeingServiceImpl` | Sightseeing | SightseeingController | Sightseeing, City | — | 143 | No |
| `CruiseServiceImpl` | Cruise | CruiseController | Cruise | — | 142 | No |
| `TenantServiceImpl` | Tenant | TenantController | Tenant | — | 133 | No |
| `TravelerAuthServiceImpl` | Portal auth | PortalAuthController | TravelerAccount, Customer | OtpService, PortalJwtUtil | 133 | No |
| `CompanyService` | Company | CompanyController | Company | — | 129 | No |
| `VehicleServiceImpl` | Vehicle (master) | VehicleController | Vehicle | — | 125 | No |
| `PermissionService` | Permissions | (internal) | UserPermission | — | 120 | No |
| `NotificationServiceImpl` | Notification | NotificationController | Notification | — | 119 | No |
| `CountryServiceImpl` | Geography | CountryController | Country | — | 118 | No |
| `WeblinkAnalyticsService` | Quotation analytics | PublicQuotationController | QuotationWeblinkView, Quotation | StaffIpService | 115 | No |
| `PortalDocumentService` | Portal docs | PortalDocumentController | TravelerDocument | — | 112 | No |
| `QuotationPdfService` | Quotation PDF | (internal) | — | — | 105 | No |
| Smaller: `AddonServiceImpl` 102, `AirlineServiceImpl` 99, `CloudinaryService` 96, `CsvExportService` 84, `OtpServiceImpl` 78, `NotificationSettingService` 74, `TaxRateService` 74, `DocumentExpiryReminderService` 70, `AuthApiService` 70, `PortalBookingService` 54, `PortalPaymentService` 52, `RateLimitService` 49 | — | — | — | — | <400 | No |

---

## 4. Section 2 — Domain & Data-Model Integrity

### 4.1 Aggregate ownership / shared-write
- **Single-writer holds for most aggregates.** Each `*ServiceImpl` is the sole writer of its entity.
- **Cross-aggregate writes are event-driven, not direct.** `LeadServiceImpl` soft-delete publishes `LeadSoftDeletedEvent` (`LeadServiceImpl.java:252`); `QuotationEventListener` (`:32-44`) is the writer that trashes quotations. This keeps the quotation aggregate's writes inside its own module — good.
- **Booking writes the Lead aggregate during cancel** (`BookingServiceImpl.java:399` publishes `LeadSoftDeletedEvent`; `:336-343` moves lead back / trashes lead). This is a shared-write path on the Lead aggregate from the Booking module — acceptable (event-mediated) but worth noting as the one place two modules mutate lead lifecycle.

### 4.2 Lead → Quotation → Booking lifecycle (gaps)
- **Quotation from lead:** `QuotationServiceImpl` resolves the lead via `leadAccessGuard.requireVisible(leadPublicId,"LEAD_READ")` (`:363`) — tenant + row-scope enforced. ✅
- **Convert lead → booking:** `LeadConversionController` + `BookingServiceImpl` use `LeadAccessGuard`; lead stays as history with `convertedBookingPublicId` stamped (`Lead.java:124-128`). ✅
- **GAP — direct booking create bypasses lifecycle:** `BookingServiceImpl.create` (`:109-114`) only checks the lead **exists** in the tenant. It does **not** check the lead's stage, nor whether it was already converted. Two bookings can be created from one lead; a booking can be created from a lost/closed lead. **No state guard.**
- **GAP — booking status free-set:** `updateStatus` (`:301-313`) writes any `BookingStatus` with no allowed-transition check, while `cancel()` (`:324-330`) *does* guard `COMPLETED`/`CANCELLED`. Inconsistent — the guard is trivially bypassed via `updateStatus`.

### 4.3 Referential integrity: code vs DB
- Cross-aggregate references are **logical FKs only** (no DB constraint), enforced in Java: `Booking.customerId/leadId` (`Booking.java:45,60`), `Reminder.leadRefId/assignToUserId/ownerUserId` (`Reminder.java:70,95,121`), `Quotation.leadId` (`Quotation.java:50`). Validated at write-time via tenant-scoped finders (e.g. `BookingServiceImpl.java:99-113`). **Bypassable** by any code path that writes the column without the service guard (e.g. future native update). Real FKs exist only inside an aggregate (`Lead.assignedUser` → `fk_lead_assigned_user`, `Lead.java:72`).

### 4.4 Free-text where a relation/enum should exist
- **Reminder dual/triple representation** (`Reminder.java:61-103`): `@Deprecated String leadId` ("LD1042") + `Long leadRefId` + `UUID leadPublicId` + `String leadName`; same for assignee (`String assignTo` + `Long assignToUserId` + `UUID assignToPublicId` + `String assignToName`). The denormalized name snapshots **drift** when a user/lead is renamed. Reads/CSV use the snapshots (`ReminderServiceImpl.java:240-242`).
- **Booking destination is free-text snapshot** (`Booking.java:55` `destinationSnapshot`; create accepts a name string, `CreateBookingRequestDTO.java:27`) even though a `destinationId` logical FK exists (`Booking.java:52`). Acceptable as an intentional snapshot, but the name is not validated against the master.

### 4.5 Soft-delete correctness (orphans)
- **Quotation:** cascades on lead trash **and** restore (`QuotationEventListener.java:32,51`). ✅
- **Booking:** **no listener** for `LeadSoftDeletedEvent` → a trashed lead leaves its booking(s) active and pointing at a dead `leadId`. ❌
- **Reminder:** **no listener** → reminders referencing `leadRefId` survive the lead's deletion as orphans. ❌
- Net: deleting a lead silently produces orphaned active children in two of three child aggregates.

---

## 5. Section 3 — Multi-Tenant Safety (Critical) — mostly STRONG

### 5.1 `findById(Long)` / filter-bypass audit
Only **three** `findById(Long)` calls exist in the whole `src/main/java` tree, all platform-level and acceptable:

| file:line | Call | Verdict |
|---|---|---|
| `UserServiceImpl.java:246` | `tenantRepository.findById(tenantId)` | Platform-level (Tenant is not tenant-scoped). OK |
| `TenantServiceImpl.java:131` | `tenantRepository.findById(id)` | Platform-level. OK |
| `AuthApiService.java:55` | `userRepository.findById(userId)` | **Fallback only** when `TenantContext` is null (async/tenant-less email resolution); the tenant-scoped finder is used whenever a tenant is bound (`:53-54`). Low risk, but it is an unscoped read of a tenant-carrying entity — worth a comment/guard. |

No `getReferenceById`/`getOne` usage. No tenant-entity `findById` bypass. ✅

### 5.2 Entities not extending `BaseTenantEntity`
- `VehicleEntity extends BaseTenantEntity` — **confirmed** (`VehicleEntity.java:29`), PK overridden to `vehicle_id` per CLAUDE.md. ✅
- `QuotationWeblinkView` is intentionally **standalone** (not `BaseTenantEntity`) because the public write path has no tenant context; every query passes `tenantId` explicitly (`WeblinkAnalyticsService.java:48,86,90`). Documented and safe. ✅

### 5.3 `TenantContext` lifecycle (set/clear)
All set-sites pair with a `finally` clear on pooled threads:
- `JwtAuthFilter.java:84` set / `:99` `finally` clear. ✅
- `TravelerJwtAuthFilter.java:83` set / `:89` `finally` clear. ✅
- `NotifyEventListener.java:24` set / `:38` `finally` clear. ✅
- `ReminderScheduler.java:57,62,87` set / `:69,94` `finally` clear (per row). ✅
- `TrashPurgeScheduler.java:44` set / `:50` `finally` clear. ✅
- `DocumentExpiryReminderScheduler.java:51` set / `:57` `finally` clear. ✅
- `DevDataSeeder.java:130` set / `:156` clear (dev only). ✅
- **Intentional non-clear:** `NotificationController.java:139` (SSE async response keeps the thread open — documented). ✅
- **Watch:** `TravelerAuthServiceImpl.java:54,72` sets `TenantContext` without a local `finally`; relies on the portal filter's `finally` (`:89`) to clear. Correct for the request path but fragile if ever called off a portal request. Note only.

### 5.4 Background tasks & tenant context
- **Schedulers** run tenant-blind by design, then **re-stamp `TenantContext` per row** before any tenant-entity write so `TenantEntityListener`'s cross-tenant guard and the Hibernate filter both pass (`ReminderScheduler.java:55-95`). ✅
- **`@Async recordView`** (`WeblinkAnalyticsService.java:33`) never reads `TenantContext`; it resolves `tenantId` from the quotation and passes it explicitly to a standalone entity. Tenant-blind but safe. ✅ — but note it relies on the async executor; if that executor were ever swapped for one that inherits a stale `SecurityContext`/`TenantContext`, re-review.
- **`@Async notificationExecutor`** (`EmailNotificationChannel.java:54`) — delivery stub; **UNVERIFIED** whether it needs tenant context (current impl logs only).

### 5.5 Native/JPQL filter-skipping
- The Hibernate `tenantFilter` is enabled only inside `@Transactional` methods when `TenantContext` is set (`TenantFilterAspect.java:22-41`). Any repository call **outside** a `@Transactional` method, or any **native query**, does not get the filter. No tenant-entity native query was found that omits an explicit `tenantId`; the JPQL `UserRepository.searchInTenant` (`UserRepository.java:39-47`) carries `tenantId` explicitly. ✅
- **Blast-radius ranking of tenant leaks:** none confirmed cross-tenant. The dominant exposure is **intra-tenant** (Section 1 #1/#2), not cross-tenant. Tenant isolation itself rates **Strong**.

---

## 6. Section 4 — Security & Access Control

### 6.1 H1 — inactive / soft-deleted users authenticating → **MITIGATED**
- Login blocks soft-deleted (`findByEmailAndDeletedAtIsNull`, `AuthServiceImpl.java:105`) and inactive (`:118`).
- The JWT filter **re-checks on every request**: loaders use `...DeletedAtIsNull` (`UserDetailsServiceImpl.java:29`) so a deleted user can't resolve, and `JwtAuthFilter.java:72` rejects `!isEnabled()` where `User.isEnabled()` == `isActive` (`User.java:95`). Deactivation/deletion therefore takes effect on the next request rather than only at token expiry. ✅

### 6.2 H2 — email-only login → cross-tenant collision → **CONFIRMED OPEN**
- `AuthServiceImpl.userLogin` calls `userRepository.findByEmailAndDeletedAtIsNull(email)` with **no tenant** (`:105`); the `UserDetailsService` fallback does the same (`UserDetailsServiceImpl.java:20`).
- The schema's uniqueness is `(email, tenant_id)` (`User.java:16-18`), so the same email **can** exist in multiple tenants. With ≥2 matches the `Optional` query throws `NonUniqueResultException` (HTTP 500); with the data ordered differently it could bind the wrong tenant. Login cannot disambiguate by email alone.

### 6.3 Permission enforcement → **REAL, not saved-but-ignored**
- `EffectivePermissionResolver.resolve` (`:47-85`) builds authorities from the **persisted `user_permissions` map** via `permissionService.savedMapOrNull` (`:67-68`); an empty saved map means "no grants" (not a silent role-default fallback) — correct. TENANT_ADMIN bypass is explicit (`:61-65`); SUPERADMIN deliberately excluded from tenant keys.
- `@PreAuthorize` uses **real per-action keys**: `BOOKING_CREATE/UPDATE/CANCEL/DELETE` (`BookingController.java:52-145`), `VENDOR_READ/CREATE/UPDATE/DELETE` (`VendorController.java:47-187`), `REMINDER_*` (`ReminderController.java:41-130`), `MASTER_MANAGE`, `TRASH_*`. Legacy `CRM_FULL`/role authorities are kept **additive on purpose** during migration (documented in the resolver) — not a coarse-only gate.
- **Two-dimensional model:** access (method-level `@PreAuthorize`) + visibility (`ScopeResolver.visibleUserIds` / `canSee`, `ScopeResolver.java:43-94`). **Visibility is wired only in the Lead module** (`LeadServiceImpl.java:99-108`, `LeadAccessGuard`). That is the core gap in Section 1 #1.
- **Coarse spots:** master read endpoints use `isAuthenticated()` (`HotelController.java:24`, `SightseeingController.java:25`) — acceptable for shared master data. `BookingReminderController` has **no** `@PreAuthorize` (relies on `anyRequest().authenticated()`).

### 6.4 Identifier exposure (`Long id` reaching API)
- `CreateBookingRequestDTO.customerId` / `leadId` are `Long` (`:20,55`) — internal ids accepted from clients.
- Reminder uses `Long id` in every path variable (`ReminderController.java:84-126`) and writes raw `id`/`leadId` to CSV (`ReminderServiceImpl.java:232-239`).
- Everywhere else correctly uses `publicId`/UUID (Booking/Quotation/Customer/Lead mutating endpoints, portal). `BaseEntity.publicId` is the external key (`BaseEntity.java:30-32`).

### 6.5 Tenant-admin / org-creation flow
- Not modified (read-only review). `TenantController` is `@PreAuthorize("hasRole('SUPER_ADMIN')")` at class level (`TenantController.java:25`) — tenant lifecycle is SuperAdmin-only and **not reachable by tenant roles**. Verified by inspection; per instructions this flow was not touched.

---

## 7. Section 5 — Money & Quotation Correctness → **STRONG**

- **No `double`/`float` for currency.** Quotation money fields are all `BigDecimal` (`Quotation.java:134-278` and child entities); Booking money is `BigDecimal precision=12 scale=2` (`Booking.java:74-100`); Lead `estimatedValue` is `BigDecimal precision=15 scale=2` (`Lead.java:84-85`).
- **Single computation site.** Quotation totals are computed once in `QuotationMapper.computeTotals` (`QuotationMapper.java:503-541`): subtotal = sum of six sections; percent/flat discount; `+ markup`; tax; `grandTotal` with `setScale(2, HALF_UP)`; per-person divide with `HALF_UP` (`:515,519,525,529`). No duplicate total logic found in the service. ✅
- **Booking financials** computed once in `BookingServiceImpl.calculateAndApplyFinancials` using `BigDecimal` rate constants `GST_RATE`/`TCS_RATE = 0.05` (`:71-72`) and `RoundingMode` import (`:56`). ✅
- **Public weblink:** capability-URL model keyed by unguessable `publicId`; 404 (`ResourceNotFoundException`) on bad/unknown id — **not** 403 (`QuotationServiceImpl.java:272-273,284-285`). No `tenant_id` request param; tenant resolved from the row. ✅
- **Watch (Med):** `getPublicByPublicId` nulls only `createdBy` and `leadId` (`:288-289`); the returned `Totals` includes `markup` in the math and **may** surface `markup` as a field — **UNVERIFIED** (depends on `QuotationResponseDto.Totals` fields). If exposed, agent margin leaks to the customer-facing link. Also no link expiry/revocation.

---

## 8. Section 6 — Transaction & Consistency

- **Writes are `@Transactional`, reads `@Transactional(readOnly = true)`** consistently (e.g. `BookingServiceImpl.java:87,249,274,302`, `ReminderServiceImpl.java:57,163`, `QuotationServiceImpl.java:281`). ✅
- **Cascade runs in-transaction.** `QuotationEventListener.onLeadSoftDeleted` is a synchronous `@EventListener` `@Transactional` (`:32-34`) — it joins the publisher's transaction, so a lead trash + quotation cascade commit/rollback atomically. ✅
- **Atomicity gaps:**
  - Booking `cancel()` performs derived-customer cleanup, then lead cascade, then status flip across several saves (`BookingServiceImpl.java:334-348`) — all within one `@Transactional` method, so atomic. ✅
  - Lead delete → `LeadSoftDeletedEvent` cascades quotations synchronously; **but** Booking/Reminder are simply *not* handled (Section 4.5), so the "incomplete cascade" is a *design* gap, not a partial-write-on-failure gap.
- **Swallowed exceptions:** `WeblinkAnalyticsService.recordView` catches and logs (`:71-74`) — **intentional** best-effort on a public path (documented); the data is non-critical analytics. Schedulers catch-log-continue per row (`ReminderScheduler.java:66-70`) so one bad row doesn't stall the batch — acceptable. No silent catch found that hides core CRM data corruption.
- **`@Async` not assumed transactional:** `recordView` is annotated **both** `@Async` and `@Transactional` (`:33-34`) so it opens its own transaction on the async thread — correct. ✅

---

## 9. Section 7 — Conventions Compliance (PASS/FAIL)

| Convention | Status | Evidence |
|---|---|---|
| Constructor injection (no field `@Autowired`) | **PASS (all)** | Zero `@Autowired` matches in `src/main/java`; `@RequiredArgsConstructor` throughout |
| `@Transactional` on writes, `readOnly` on reads | **PASS** | See Section 8 |
| Thin controllers | **PASS (mostly)** | Controllers delegate to services; logic lives in `*ServiceImpl`. Minor: `BookingServiceImpl.cancel` re-reads authority (`requireAuthority`) in service — acceptable defense-in-depth |
| Hand-written `@Component` mappers (NO MapStruct) | **PASS** | `QuotationMapper`, `BookingMapper`, `VehicleMapper`, `LeadMapper` are hand-written; no MapStruct on these paths (note: project pom still configures MapStruct processor per CLAUDE.md, but service mappers are hand-written) |
| `ApiResponse` / `PagedApiResponse` envelope | **PASS except Reminder** | `PagedApiResponse.of(...)` in Booking/Customer; **Reminder deliberately returns raw DTOs** (`ReminderController.java:24-27`) |
| Soft-delete pattern (`deletedAt`/`deletedBy`) | **PASS** | `BaseEntity.softDelete/restore` (`:63-71`); services use `...DeletedAtIsNull` finders |
| No Flyway / `ddl-auto=update` only | **PASS** | No migration directory; logical FKs in code per convention |
| `publicId`/UUID in API | **FAIL (Booking payload, Reminder)** | Section 6.4 |

---

## 10. Section 8 — Performance / Scale

- **N+1 — handled in Lead:** `LeadServiceImpl.getAllLeads` batch-loads latest quotation via `enrichWithLatestQuotation` (`:113-114`) instead of per-row; `Lead.itinerary`/`services` use `@BatchSize(50)` (`Lead.java:113,131`). ✅
- **Missing pagination:** `ReminderServiceImpl.getAll` (`:164-170`), `getOverdue` (`:180`), `getDueToday` (`:190`), `getByLeadName` (`:202`), `exportCsv` (`:226-229`) all return unbounded lists (no `Pageable`). At scale these are full-table-per-tenant reads.
- **Vendor list cost:** loading a `Vendor` joins `vendor_bank_details` + `vendor_financials` secondary tables (per CLAUDE.md); list views should use a projection. `VendorServiceImpl` (414 LOC) — **UNVERIFIED** whether the list path uses a projection; flag for review.
- **Indexing:** hot paths are indexed — `bookings` (`Booking.java:21-28`: tenant, code, customer, status, travel_date, deleted), `leads` (`Lead.java:24-28`), `reminders` (`Reminder.java:23-27`), `users` (`User.java:19-23`). Adequate. Ownership scoping (Section 1 #1) will add `owner_id IN (...)` predicates — an index on the new owner column will be needed for Booking/Quotation/Customer.

---

## 11. Remediation Roadmap

### Fix now — data-corruption / data-exposure (intra-tenant)
1. **Roll out row-level ownership scoping beyond Lead** (Finding #1). Reuse the existing `ScopeResolver`/`LeadAccessGuard` pattern across Booking, Quotation, Customer, Reminder list/get/count/update/delete. *Depends on:* adding an owner column to Booking/Quotation/Customer (they have none today) and deciding owner semantics — see the separate **Phase-1 ownership-scoping scan** delivered alongside this report.
2. **Reminder: switch to `publicId` + owner-scope** (Findings #2, #6). Eliminates IDOR and Long-id exposure together. Do this as part of (1).
3. **Complete the soft-delete cascade** (Finding #4): add Booking + Reminder listeners for `LeadSoftDeletedEvent`/`LeadRestoredEvent`, or block lead deletion while active children exist.

### Fix soon — security / correctness
4. **H2 tenant-disambiguated login** (Finding #3) — fail-closed on multiple email matches; introduce a tenant discriminator (subdomain/selector).
5. **Booking state machine** (Finding #5) — allowed-transition map enforced in `updateStatus`; guard direct create against non-convertible/already-converted leads (Finding #7).
6. **Public quotation hardening** (Finding #9) — null margin fields on the public DTO; consider link expiry/revocation.
7. **`BookingReminderController` authorization** (Finding #10).

### Hardening — perf / structure
8. Paginate Reminder list endpoints (Finding #12).
9. Split the god services (Finding #13) — separate query services from command services for Booking/Lead/Quotation.
10. Retire Reminder's `@Deprecated` String lead/assignee columns once the FE is fully on publicId; treat snapshots as display-only to bound drift (Finding #8).

**Dependency note:** (1) is the keystone — (2) folds into it, and the new owner columns it introduces are prerequisites for the scoping predicates. (4)/(5) are independent and can proceed in parallel.

---

## 12. Architecture Maturity Scorecard

| Area | Rating | One-line justification |
|---|---|---|
| **Tenant isolation** | **Strong** | No `findById` bypass on tenant entities; filter on `@Transactional`; schedulers re-stamp per row; `TenantContext` cleared in `finally` everywhere it's set on a pooled thread. |
| **Data integrity** | **Weak→Adequate** | Money/aggregate ownership clean, but partial soft-delete cascade (Booking/Reminder orphans), no booking state machine, and Reminder's triple lead/assignee representation invites drift. |
| **Security / access control** | **Adequate** | H1 fixed and permissions genuinely enforced with per-action keys, but H2 open, row-level visibility wired only for Lead, Reminder IDOR via Long id, one ungated controller. |
| **Money correctness** | **Strong** | `BigDecimal` end-to-end, `HALF_UP`, single `computeTotals` site, 404-not-403 public path; only a possible `markup` exposure to verify. |
| **Transaction safety** | **Strong** | Correct `@Transactional`/`readOnly`; synchronous in-tx cascade; `@Async` path opens its own tx; no corruption-hiding catches. |
| **Maintainability** | **Adequate** | Constructor injection, hand-written mappers, clean module boundaries, but five >400-LOC god services and Reminder's documented envelope/identifier deviations. |

---

*Generated read-only. No source files were modified. `UNVERIFIED` items: `EmailNotificationChannel` async tenant need; `QuotationResponseDto.Totals` markup field exposure; `VendorServiceImpl` list projection usage.*