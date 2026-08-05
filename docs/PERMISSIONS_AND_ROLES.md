# Permissions, Roles & Module Entitlements — Reference and Verification Guide

> **Status:** generated from the code at HEAD (branch `issue-fixes`) on **2026-08-04**.
> Every table below was extracted from the source, not written from memory. If you change the code,
> re-run the extraction described in [§11.4](#114-regenerating-this-document) rather than hand-editing.

This document answers three questions:

1. **What are the moving parts?** — role, permission, data scope, module entitlement are *four different things* and they are enforced at four different places.
2. **Who has what, exactly?** — the full role → permission matrix and the full endpoint → authority matrix.
3. **How do I keep it correct?** — the maintenance playbook, the checklists, and the tests that fail the build when a rule drifts.

---

## 1. The four gates — read this first

A request from a logged-in staff user passes through **four independent gates**. Failing any one of them denies the request, and they answer *different* questions. Confusing them is the single most common source of "why can't this user do X" tickets.

| # | Gate | Question it answers | Where it lives | Failure |
|---|---|---|---|---|
| 1 | **Authentication** | Is this a valid, live principal, and in which realm? | `JwtAuthFilter`, `SecurityConfig` | `401` |
| 2 | **Module entitlement** | Did this **organization** buy this module? | `ModuleAccessFilter` (servlet filter) | `403 MODULE_NOT_ENABLED` |
| 3 | **Permission (access)** | Is this **user** allowed to perform this action at all? | `@PreAuthorize` on the controller method | `403 PERMISSION_DENIED` |
| 4 | **Data scope (visibility)** | *Which rows* of that resource may they see/touch? | `ScopeResolver` / `SubAgentScope` in the service | `404` (never 403 — see below) |

Two rules that follow from the table and are easy to get wrong:

- **Gate 2 is a tenant/plan property, gate 3 is a user property.** A TENANT_ADMIN holds every permission but is still blocked by the module gate — buying is not a role. That is why `hasModule()` on the frontend has *no* admin bypass while `hasPermission()` does.
- **Gate 4 answers `404`, never `403`.** A `403` on a specific record confirms the record exists. For an inbox that means confirming a particular customer talks to this agency; for a lead it confirms a competitor's lead is in the system. `SubAgentScope.assertVisible` and `CommAccessGuard` both throw `ResourceNotFoundException` on purpose.

### 1.1 Filter order in the staff chain

`SecurityConfig.filterChain()` (`@Order(2)`; the traveler portal chain is `@Order(1)`):

```
RateLimitFilter
  → JwtAuthFilter                    (authenticate; populate TenantContext / PlatformContext)
    → SuperAdminSetupCompletionFilter
      → MaintenanceModeFilter        (after auth, so it can tell tenant from platform traffic)
        → ModuleAccessFilter         (403 MODULE_NOT_ENABLED)
          → @PreAuthorize            (method security, @EnableMethodSecurity)
            → controller → service → ScopeResolver / SubAgentScope
```

`ContextCleanupFilter` wraps every request and is what actually guarantees `TenantContext` is cleared — `JwtAuthFilter`'s own `finally` is **not** reached on its early-return paths (no `Bearer` header, invalid token).

---

## 2. Vocabulary and where each thing is defined

| Concept | Type | Source of truth | Notes |
|---|---|---|---|
| **Role** | `enum Role` (7 values) | `auth/enums/Role.java` | Stored on `users.role`. Also emits **coarse legacy authorities** (`CRM_FULL`, `USER_*`, `PLATFORM_ADMIN`). |
| **Permission** | `enum Permission` (78 keys) | `permission/enums/Permission.java` | The fine-grained keys. `name()` **is** the authority string in `@PreAuthorize`. |
| **Role default permissions** | `Permission.defaultsFor(Role)` | same file | Used **only** when a user has *no saved row* in `user_permissions`. |
| **Per-user override** | JSON map | table `user_permissions.permissions_json` | `{"LEAD_READ":{"access":true,"scope":"own"}, …}` |
| **Permission template** | row | table `permission_templates` | Reusable named permission maps per tenant. Not auto-applied — a convenience for the admin UI. |
| **Effective authorities** | computed per request | `EffectivePermissionResolver` | `role.authorities()` ∪ fine-grained keys. Loaded in `UserDetailsServiceImpl`. |
| **Data scope** | `OWN / TEAM / ALL / NONE` | `permission/service/ScopeResolver.java` | The `scope` field of each `PermissionEntry`. |
| **Sub-agent row scope** | boolean narrow rule | `permission/service/SubAgentScope.java` | Only ever active for `SUB_AGENT`. |
| **Module entitlement** | `Set<String>` of module keys | `plans.modules` → `tenant_modules` | Enforced by `ModuleAccessFilter`; served to the FE by `GET /api/me/entitlements`. |
| **FE mirror** | JS constants | `travelcrmfe/travelcrmfrontend/src/shared/lib/access.js` | UI hiding only. **Never a security boundary.** |

---

## 3. Roles

`auth/enums/Role.java`. Seven values; `SUPERADMIN` is a different entity class (`SuperAdmin`, not `User`) and only appears in this enum for exhaustiveness.

| Role | Product name | Coarse authorities emitted by `Role.authorities()` |
|---|---|---|
| `SUPERADMIN` | Platform owner | `PLATFORM_ADMIN` |
| `TENANT_ADMIN` | Organization Admin | `USER_CREATE`, `USER_READ`, `USER_UPDATE`, `USER_DELETE`, `CRM_FULL` |
| `MANAGER` | Manager | `CRM_FULL` |
| `TRAVEL_AGENT` | Travel Agent | `CRM_FULL` |
| `STAFF` | Staff | `CRM_FULL` |
| `ACCOUNTANT` | Account | `CRM_FULL` |
| `SUB_AGENT` | B2B franchise partner | *(none — deliberately empty)* |

### 3.1 `CRM_FULL` is legacy, and it is load-bearing today

`CRM_FULL` is **not** in the `Permission` enum and is **not** a per-user toggle. It is the coarse authority every operational tenant role carries while controllers migrate to fine-grained keys. **20 annotations gate on it alone** (8 of them class-level, so the endpoint count is higher), plus 12 more that accept it as one alternative in a `hasAnyAuthority(…)`. They fall into two groups:

- **Whole report controllers** — `/api/reports/**` (7 controllers) and `/api/dashboard`. These are class-level `@PreAuthorize("hasAuthority('CRM_FULL')")`.
- **Tenant-wide aggregates and bulk operations** deliberately kept away from sub-agents: `GET /api/bookings/stats`, `/api/bookings/summary`, `/api/bookings/export`, `/api/customers/stats`, `/api/customers/export`, `/api/reminders/stats`, `/api/reminders/export`, `POST /api/reminders/complete-all`, `GET /api/tasks/stats`, `/api/tasks/workload`, `GET /api/calendar/summary`, `GET /api/users/…`.

**The consequence:** `STAFF` — whose fine-grained default set is *empty* — still holds `CRM_FULL` and can therefore read **every report and the whole dashboard**. See [Finding F-1](#101-f-1-staff-reaches-every-report-through-crm_full).

`SUB_AGENT` holding **no** coarse authority is the deliberate mirror image: it is fail-closed, so a franchise partner cannot reach any not-yet-migrated controller at all.

---

## 4. The permission catalog and role defaults

78 keys, grouped into 15 catalog modules. `GET /api/permissions/catalog` serves this list straight off the enum, so the FE permission grid can never drift from the backend.

### 4.1 Role → default permission matrix

`Y` = granted by `Permission.defaultsFor(role)`. **This is the fallback only** — a user with a saved `user_permissions` row ignores this table entirely (see §5).

- **ADMIN** = `TENANT_ADMIN` (`EnumSet.allOf` — every key, plus a hard bypass in the resolver)
- **STAFF** and **SA** (`SUPERADMIN`) are `EnumSet.noneOf` — deny-by-default

| Permission key | Module | ADMIN | MGR | AGENT | ACCT | SUB | STAFF | SA |
|---|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| `LEAD_READ` | Leads | Y | Y | Y | x | Y | x | x |
| `LEAD_CREATE` | Leads | Y | Y | Y | x | Y | x | x |
| `LEAD_UPDATE` | Leads | Y | Y | Y | x | Y | x | x |
| `LEAD_DELETE` | Leads | Y | Y | x | x | Y | x | x |
| `LEAD_PERMANENT_DELETE` | Leads | Y | x | x | x | x | x | x |
| `LEAD_CLAIM` | Leads | Y | Y | Y | x | x | x | x |
| `LEAD_REASSIGN_LOCKED` | Leads | Y | Y | x | x | x | x | x |
| `BOOKING_READ` | Bookings | Y | Y | Y | Y | Y | x | x |
| `BOOKING_CREATE` | Bookings | Y | Y | Y | x | Y | x | x |
| `BOOKING_UPDATE` | Bookings | Y | Y | Y | Y | Y | x | x |
| `BOOKING_CANCEL` | Bookings | Y | Y | x | x | x | x | x |
| `BOOKING_DELETE` | Bookings | Y | Y | x | x | x | x | x |
| `BOOKING_PROFIT_READ` | Bookings | Y | Y | x | Y | x | x | x |
| `BOOKING_REFUND` | Bookings | Y | x | x | x | x | x | x |
| `CANCELLATION_POLICY_MANAGE` | Bookings | Y | x | x | x | x | x | x |
| `CUSTOMER_READ` | Customers | Y | Y | Y | Y | Y | x | x |
| `CUSTOMER_CREATE` | Customers | Y | Y | Y | x | Y | x | x |
| `CUSTOMER_UPDATE` | Customers | Y | Y | Y | x | Y | x | x |
| `CUSTOMER_DELETE` | Customers | Y | Y | x | x | x | x | x |
| `QUOTATION_READ` | Quotations | Y | Y | Y | Y | Y | x | x |
| `QUOTATION_CREATE` | Quotations | Y | Y | Y | x | Y | x | x |
| `QUOTATION_UPDATE` | Quotations | Y | Y | Y | x | Y | x | x |
| `QUOTATION_DELETE` | Quotations | Y | Y | x | x | Y | x | x |
| `VENDOR_READ` | Vendors | Y | Y | Y | Y | x | x | x |
| `VENDOR_CREATE` | Vendors | Y | Y | x | x | x | x | x |
| `VENDOR_UPDATE` | Vendors | Y | Y | x | Y | x | x | x |
| `VENDOR_DELETE` | Vendors | Y | x | x | x | x | x | x |
| `REMINDER_READ` | Reminders | Y | Y | Y | x | Y | x | x |
| `REMINDER_CREATE` | Reminders | Y | Y | Y | x | Y | x | x |
| `REMINDER_UPDATE` | Reminders | Y | Y | Y | x | Y | x | x |
| `REMINDER_DELETE` | Reminders | Y | Y | x | x | Y | x | x |
| `TASK_READ` | Tasks | Y | Y | Y | Y | Y | x | x |
| `TASK_CREATE` | Tasks | Y | Y | Y | Y | Y | x | x |
| `TASK_UPDATE` | Tasks | Y | Y | Y | Y | Y | x | x |
| `TASK_DELETE` | Tasks | Y | Y | x | x | Y | x | x |
| `FLEET_READ` | Fleet | Y | Y | Y | Y | x | x | x |
| `FLEET_CREATE` | Fleet | Y | Y | Y | x | x | x | x |
| `FLEET_UPDATE` | Fleet | Y | Y | Y | x | x | x | x |
| `FLEET_DELETE` | Fleet | Y | Y | x | x | x | x | x |
| `FLEET_MONEY_READ` | Fleet | Y | Y | x | Y | x | x | x |
| `FLEET_MONEY_SETTLE` | Fleet | Y | x | x | Y | x | x | x |
| `FLEET_PERIOD_CLOSE` | Fleet | Y | x | x | Y | x | x | x |
| `MASTER_READ` | Master Data | Y | Y | Y | Y | Y | x | x |
| `MASTER_MANAGE` | Master Data | Y | Y | x | x | x | x | x |
| `HOTEL_MARKETPLACE_VIEW` | Hotel Marketplace | Y | Y | Y | x | x | x | x |
| `HOTEL_MARKETPLACE_SYNC_MASTER` | Hotel Marketplace | Y | Y | x | x | x | x | x |
| `HOTEL_MARKETPLACE_BOOK` | Hotel Marketplace | Y | Y | Y | x | x | x | x |
| `HOTEL_MARKETPLACE_CANCEL` | Hotel Marketplace | Y | Y | Y | x | x | x | x |
| `MARKETPLACE_PAYABLE_READ` | Hotel Marketplace | Y | Y | Y | x | x | x | x |
| `USER_READ` | User Management | Y | Y | x | x | x | x | x |
| `USER_CREATE` | User Management | Y | x | x | x | x | x | x |
| `USER_UPDATE` | User Management | Y | x | x | x | x | x | x |
| `USER_DELETE` | User Management | Y | x | x | x | x | x | x |
| `REPORT_VIEW` | Reports | Y | Y | Y | Y | x | x | x |
| `MARKETING_READ` | Marketing | Y | Y | Y | x | x | x | x |
| `MARKETING_CREATE` | Marketing | Y | Y | x | x | x | x | x |
| `MARKETING_UPDATE` | Marketing | Y | Y | x | x | x | x | x |
| `MARKETING_DELETE` | Marketing | Y | Y | x | x | x | x | x |
| `MARKETING_SEND` | Marketing | Y | Y | x | x | x | x | x |
| `TRASH_VIEW` | Trash | Y | Y | Y | x | x | x | x |
| `TRASH_RESTORE` | Trash | Y | Y | x | x | x | x | x |
| `TRASH_DELETE` | Trash | Y | x | x | x | x | x | x |
| `ACCOUNTING_INVOICE_READ` | Accounting | Y | Y | x | Y | x | x | x |
| `ACCOUNTING_INVOICE_MANAGE` | Accounting | Y | x | x | Y | x | x | x |
| `ACCOUNTING_TDS_READ` | Accounting | Y | Y | x | Y | x | x | x |
| `ACCOUNTING_TDS_MANAGE` | Accounting | Y | x | x | Y | x | x | x |
| `ACCOUNTING_SETTINGS_MANAGE` | Accounting | Y | x | x | Y | x | x | x |
| `COMM_READ` | Communication | Y | Y | Y | Y | Y | x | x |
| `COMM_SEND` | Communication | Y | Y | Y | x | Y | x | x |
| `COMM_ASSIGN` | Communication | Y | Y | x | x | x | x | x |
| `COMM_CALL_LOG` | Communication | Y | Y | Y | x | x | x | x |
| `COMM_CHAT` | Communication | Y | Y | Y | Y | Y | x | x |
| `COMM_NOTE_PRIVATE_READ` | Communication | Y | x | x | x | x | x | x |
| `COMM_RECORDING_READ` | Communication | Y | x | x | x | x | x | x |
| `COMM_TEMPLATE_MANAGE` | Communication | Y | x | x | x | x | x | x |
| `COMM_WORKFLOW_MANAGE` | Communication | Y | x | x | x | x | x | x |
| `COMM_REPORT_VIEW` | Communication | Y | Y | x | x | x | x | x |
| `SETTINGS_MANAGE` | Settings | Y | x | x | x | x | x | x |


### 4.2 Keys granted to no role by design

Six keys are in **no** role default. `TENANT_ADMIN` reaches them through the resolver bypass; everyone else must be granted them explicitly, per user. This is the deliberate "high-privilege" tier:

| Key | Why it is per-user only |
|---|---|
| `LEAD_PERMANENT_DELETE` | Removes a lead when cancelling a booking (moves to Trash) |
| `BOOKING_REFUND` | Moves money and overrides a computed cancellation charge |
| `CANCELLATION_POLICY_MANAGE` | Rewrites the slabs every future refund is computed from |
| `COMM_NOTE_PRIVATE_READ` | Reading a colleague's private notes is a privacy decision, not a job title |
| `COMM_RECORDING_READ` | Same, for recorded customer calls |
| `COMM_TEMPLATE_MANAGE` / `COMM_WORKFLOW_MANAGE` | Configuration, not a per-conversation action |

`TRASH_DELETE` is granted to `TENANT_ADMIN` only for the same reason (irreversible hard delete before the 30-day auto-purge).

---

## 5. How effective permissions are actually resolved

`EffectivePermissionResolver.keysFor(role, savedMapOrNull)` — the single source of truth, used both per-login and by batch eligibility checks (`AssignableUserResolver`).

```
1. role == TENANT_ADMIN            → EVERY Permission key. Unconditional bypass.
                                     (An admin must not be able to lock itself out of its own tenant.)
2. saved map is NOT null           → exactly the keys with access==true. Unknown/stale keys dropped.
                                     An EMPTY saved map means NO GRANTS — it does NOT fall back.
3. saved map is null (never saved) → Permission.defaultsFor(role)
```

Then: `authorities = role.authorities() ∪ resolvedKeys`.

### 5.1 The two-grant-path hazard — the most important thing in this document

A permission has **two independent grant paths**:

| Path | Applies to | Lives in |
|---|---|---|
| `Permission.defaultsFor(Role)` | users whose permission screen was **never saved** (`user_permissions` row absent) | Java |
| The SQL backfill in `V2__lead_code.sql` | users whose permission screen **was** saved (row present) | migration |

Because step 2 above treats a non-null saved map as authoritative and **never merges defaults into it**, the two paths must agree exactly — otherwise *a user's access depends on whether anyone ever clicked Save on their profile*. That divergence has already shipped once (fleet money). Four tests now fail the build if it happens again:

| Test | Guards |
|---|---|
| `permission/FleetMoneyPermissionDefaultsTest` | `FLEET_MONEY_*` vs V2 **PART 6** |
| `permission/BookingProfitPermissionDefaultsTest` | `BOOKING_PROFIT_READ` vs V2 PART 6 |
| `permission/CommPermissionDefaultsTest` | every `COMM_*` key vs V2 **PART 17** |
| `permission/LeadClaimPermissionDefaultsTest` | `LEAD_CLAIM` / `LEAD_REASSIGN_LOCKED` vs V2 **PART 18** |

**If you add a key to an existing role's defaults, you owe a backfill and a test.** See the checklist in §11.1.

### 5.2 Where the map is stored

`user_permissions.permissions_json` is `TEXT` holding a JSON object:

```json
{
  "LEAD_READ":   { "access": true,  "scope": "own"  },
  "BOOKING_READ":{ "access": true,  "scope": "team" },
  "VENDOR_READ": { "access": false, "scope": "own"  }
}
```

`access` drives gate 3; `scope` drives gate 4. `PermissionEntry.scope` defaults to `"own"`.

---

## 6. Data scope — the second dimension

Access says *whether*; scope says *which rows*. Two mechanisms exist and they are **not** interchangeable.

### 6.1 `ScopeResolver` — the general OWN/TEAM/ALL model

```java
Set<Long> ids = scopeResolver.visibleUserIds(user, "LEAD_READ");
// null      → ALL   : caller SKIPS the owner predicate
// empty set → NONE  : caller returns nothing
// non-empty → OWN/TEAM : caller filters owner_id IN (ids)
boolean ok = scopeResolver.canSee(user, "LEAD_READ", record.getOwnerUserId());  // false ⇒ 404
```

Resolution order: **TENANT_ADMIN ⇒ ALL** → explicit `scope` on the saved `PermissionEntry` → role default below.

| Role | Default scope | Reason |
|---|---|---|
| `TENANT_ADMIN` | `ALL` | own tenant, everything |
| `MANAGER` | `TEAM` | self + users whose `User.managerId` is them |
| `ACCOUNTANT` | `ALL` | finance works across the tenant |
| `SUB_AGENT` | `OWN` | franchise partner, never team/all |
| `TRAVEL_AGENT`, `STAFF` | `OWN` | (the `default` branch) |

### 6.2 `SubAgentScope` — the narrow B2B rule

Deliberately narrower than `ScopeResolver`: **only** `SUB_AGENT` is confined (`owner_user_id = self`); every other role is untouched, so the B2B rollout changed nothing for existing roles. `ownerFilter()` returns `null` for everyone else; `assertVisible()` is a no-op for everyone else and throws **404** for a sub-agent touching a foreign row.

Migrate a module from `SubAgentScope` to `ScopeResolver` when you want per-user scope for *every* role in that module.

### 6.3 Where scope is enforced today

- **Leads** — the reference implementation (`LeadAccessGuard`).
- **Communication** — `CommAccessGuard` layers three scopes: tenant finder → `SubAgentScope` → `ScopeResolver` on `assignedUserId`. An **unassigned** thread is visible at any scope above `NONE` (a message from a new number belongs to nobody yet). `INTERNAL` conversations bypass the assignment axis entirely and are gated on **membership**.
- Everywhere else, the tenant filter plus `CRM_FULL` are what actually restrict data.

---

## 7. Module entitlements (the plan gate)

### 7.1 Plan → modules

Seeded by `platform/subscription/config/PlanCatalogueInitializer`. `seedPlans()` runs **only when the `plans` table is empty**, so every later capability arrives through an additive, idempotent `ensureModules()` backfill that runs on every startup.

| Plan | Modules |
|---|---|
| `STARTER` ("Basic") | `LEADS, BOOKINGS, QUOTATIONS, CUSTOMERS, MASTERS` + backfilled `TASKS, REMINDERS, DASHBOARD, COMMUNICATION` |
| `PRO` | Starter's + `VENDORS, REPORTS, FLEET, WHATSAPP, SUBAGENT` + backfilled `ACCOUNTING, MARKETING` |
| `ENTERPRISE` | Pro's + `DISHA_AI, PORTAL` + backfilled `HOTEL_MARKETPLACE` |
| `FLEET` ("Vehicle Diary") | `FLEET` **and nothing else** — the standalone-product boundary |

> **Granting a key to a PLAN does not reach an existing TENANT.** `TenantEntitlementService` returns the tenant's own `tenant_modules` snapshot whenever it is non-empty and never consults the plan. A new module needs *both* the `PlanCatalogueInitializer` grant **and** an `INSERT INTO tenant_modules` backfill (that is what V2 PART 17 does for `COMMUNICATION`).

### 7.2 Path → module (`ModuleAccessFilter.RULES`)

First match wins; matching is **exact-or-followed-by-slash**, so `/api/quotations` does *not* cover `/api/quotation-templates` (which is why that has its own rule).

| Prefix | Module | | Prefix | Module |
|---|---|---|---|---|
| `/api/leads` | `LEADS` | | `/api/accounting` | `ACCOUNTING` |
| `/api/lead-sources` | `LEADS` | | `/api/tax-rates` | `ACCOUNTING` |
| `/api/bookings` | `BOOKINGS` | | `/api/marketing` | `MARKETING` |
| `/api/booking-reminders` | `BOOKINGS` | | `/api/tasks` | `TASKS` |
| `/api/cancellation-policies` | `BOOKINGS` | | `/api/calendar` | `TASKS` |
| `/api/quotations` | `QUOTATIONS` | | `/api/reminders` | `REMINDERS` |
| `/api/quotation-templates` | `QUOTATIONS` | | `/api/dashboard` | `DASHBOARD` |
| `/api/customers` | `CUSTOMERS` | | `/api/communication` | `COMMUNICATION` |
| `/api/vendors` | `VENDORS` | | `/api/hotel-marketplace` | `HOTEL_MARKETPLACE` |
| `/api/reports` | `REPORTS` | | `/api/hotels`, `/api/airlines`, `/api/cruises`, `/api/vehicles`, `/api/addons`, `/api/sightseeings`, `/api/testimonials`, `/api/masters`, `/api/destinations`, `/api/countries`, `/api/cities`, `/api/v1` | `MASTERS` |
| `/api/fleet` | `FLEET` | | `/api/subagents` | `SUBAGENT` |
| `/api/settings/whatsapp` | `WHATSAPP` | | | |

**Special case:** `POST /api/leads/{id}/convert-to-booking` requires `BOOKINGS`, not `LEADS` — the discriminating segment sits after a path variable so it cannot be a prefix rule, and it is special-cased in `requiredModule()` *before* the generic `/api/leads` rule.

### 7.3 Never module-gated (`ALWAYS_ALLOWED`)

These are **platform capabilities** — a Fleet-only tenant needs every one. Gating any of them on a CRM module key breaks the standalone product.

`/api/auth`, `/api/me`, `/api/me/hotel-bookings`, `/api/users`, `/api/permissions`, `/api/permission-templates`, `/api/company`, `/api/settings/email`, `/api/notifications`, `/api/notification-settings`, `/api/trash`, `/api/impersonation`, `/api/masters/dropdown`, `/api/webhooks`, `/api/public`, `/api/portal`, `/api/portal-admin`, `/api/super-admin`

Two of these carry a trap worth knowing:
- **`/api/me/hotel-bookings`** is already covered by `/api/me` and is listed *anyway*, deliberately: a confirmed marketplace booking and its voucher must survive a lapsed `HOTEL_MARKETPLACE` add-on. The "tidy-up" of adding it to `RULES` would silently re-break that, so naming it here makes the edit collide in `ModuleAccessCoverageTest.rulesAndAllowlistDoNotOverlap()` instead of in production.
- **`/api/impersonation`** must never be gated, or a SuperAdmin impersonating a restricted tenant gets stuck inside that session.

### 7.4 Fail-open, and the test that compensates

`ModuleAccessFilter` is **fail-open at runtime**: any `/api` path in neither `RULES` nor `ALWAYS_ALLOWED` is allowed through. The real control is `arch/ModuleAccessCoverageTest`, which **fails the build** when a new controller appears in neither set. `app.entitlement.fail-closed=true` exists and is wired but is off by default.

---

## 8. The other realms

| Realm | Principal | Token | Gate |
|---|---|---|---|
| **Staff (tenant)** | `User implements UserDetails` | `jwt.secret`, claim `tenantId` | everything in this document |
| **Platform console** | `SuperAdmin` | `jwt.secret`, `role=SUPER_ADMIN`, **no** `tenantId` | `hasRole('SUPER_ADMIN')` — **28 endpoints**, all under `/api/super-admin/**` |
| **Traveler portal** | `TravelerPrincipal` (a `Principal`, not a `User`) | `portal.jwt.secret`, `typ=TRAVELER`/`aud=portal` | own `SecurityFilterChain` (`@Order(1)`); object-level ownership by `customerId`; a foreign `publicId` returns **404** |

`JwtAuthFilter` early-returns on `/api/portal/**` so a staff token can never act on the portal, and the distinct signing secret means tokens cannot cross realms in either direction.

**SuperAdmin holds no tenant permissions.** `defaultsFor(SUPERADMIN)` is `noneOf` and the resolver's tenant bypass explicitly excludes it. Cross-tenant reads happen through `PlatformContext` — a *named* god-mode marker, never inferred from "tenantId is null".

### 8.1 Non-`@PreAuthorize` enforcement points

Three places check authorities in code rather than by annotation. They are legitimate, but they will not show up in an annotation audit:

| Where | What it does |
|---|---|
| `ai/tool/AiToolAuthorizer.require(authority)` | Re-applies the REST layer's authority on the Disha AI tool path. `/ai/chat` itself is only `isAuthenticated()`, and the `@Tool` methods reach services directly — without this a low-privilege user could ask the chatbot for data the UI would 403. |
| `booking/service/BookingServiceImpl` | `requireAuthority("LEAD_PERMANENT_DELETE", …)` on the cancel-and-remove-lead path; and `hasAuthority("BOOKING_PROFIT_READ")` to decide whether profit fields are serialized at all. |
| `communication/service/CommAccessGuard.canReadPrivateNotes()` | `COMM_NOTE_PRIVATE_READ` is enforced as a **query predicate**, not a mapper filter — a private note must never be materialized inside a request that was not entitled to it. |

---

## 9. Verification playbook — "who can actually do what?"

### 9.1 Verify a live user's effective access (fastest, authoritative)

```bash
# 1. Log in as the user, then:
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/permissions/me
# → { "permissions": ["LEAD_READ", …], "platformAdmin": false }
```

This returns exactly what `EffectivePermissionResolver` put into the security context — role defaults *overlaid with* the saved per-user map. It is the ground truth; everything else is inference.

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/me/entitlements   # gate 2
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/permissions/catalog # the full key list
```

### 9.2 Verify from the database

```sql
-- Does this user have a saved map at all? (absent ⇒ role defaults apply, §4.1)
SELECT u.username, u.role, up.permissions_json
FROM   users u
LEFT   JOIN user_permissions up ON up.user_id = u.id AND up.deleted_at IS NULL
WHERE  u.tenant_id = :tenantId AND u.deleted_at IS NULL
ORDER  BY u.role, u.username;

-- Everyone who holds a specific key (saved maps only)
SELECT u.username, u.role,
       up.permissions_json::jsonb #>> '{BOOKING_REFUND,access}' AS access,
       up.permissions_json::jsonb #>> '{BOOKING_REFUND,scope}'  AS scope
FROM   user_permissions up JOIN users u ON u.id = up.user_id
WHERE  up.deleted_at IS NULL
  AND  up.permissions_json::jsonb ? 'BOOKING_REFUND';

-- What this tenant's plan actually unlocks (gate 2)
SELECT t.name, t.plan, tm.module
FROM   tenants t LEFT JOIN tenant_modules tm ON tm.tenant_id = t.id
ORDER  BY t.name, tm.module;
```

> `permissions_json` is `TEXT`, not `jsonb`. The V2 migration wraps every read in a `fleet_safe_jsonb()` helper because a truncated row would abort the whole migration under `ON_ERROR_STOP`. Use the same defensive cast in ad-hoc queries against production.

### 9.3 Verify from the code

- **"What does this endpoint need?"** → [Appendix A](#appendix-a--full-endpoint--authority-matrix).
- **"Which endpoints need this key?"** → `grep -rn "hasAuthority('KEY')" src/main/java`.
- **"Is this key enforced anywhere?"** → see [Finding F-2](#102-f-2-three-permission-keys-are-declared-but-enforce-nothing); two keys currently enforce nothing.
- **"Is this new controller module-gated?"** → run `mvnw.cmd test -Dtest=ModuleAccessCoverageTest`.

### 9.4 Manual verification matrix (what to actually click)

For each role, create one user and confirm:

| Check | Expected |
|---|---|
| `GET /api/permissions/me` | matches the row in §4.1 (assuming no saved map) |
| Sidebar contents | matches those keys — the FE mirrors, it does not decide |
| One denied action per role (e.g. `STAFF` → `POST /api/leads`) | `403` with `PERMISSION_DENIED` |
| One module the plan excludes | `403` with **`MODULE_NOT_ENABLED`** (different error code ⇒ different screen: upgrade prompt, not "ask your admin") |
| A `SUB_AGENT` opening another user's lead by `publicId` | **`404`**, not `403` |
| A `SUB_AGENT` hitting `GET /api/bookings/stats` | `403` (no `CRM_FULL`) |
| Turn **every** toggle off for a user and save | that user now has **zero** access — *not* a silent reset to role defaults |

That last one is the highest-value regression test in the list: it is the exact behaviour step 2 of §5 exists to produce.

---

## 10. Findings — current gaps and drift

Found while extracting the tables in this document. None of these is fixed by this document; each is a decision to take.

### 10.1 F-1 — `STAFF` reaches every report through `CRM_FULL`

`Permission.defaultsFor(STAFF)` is empty (deny-by-default, as designed), but `Role.authorities()` still gives `STAFF` the coarse `CRM_FULL`. All 7 report controllers and `/api/dashboard` gate on `CRM_FULL` at the **class** level, so a brand-new STAFF user with no permissions at all can read tenant-wide revenue, profit, follow-up and geographic reports.

*Fix:* migrate the report controllers to `REPORT_VIEW` (see F-2 — that key already exists and is already in the FE role defaults). Do it together with the `tenant-wide-aggregate` endpoints listed in §3.1, so the sub-agent block those `CRM_FULL` checks currently provide is replaced rather than dropped.

### 10.2 F-2 — three permission keys are declared but enforce nothing

| Key | Appears in `@PreAuthorize`? | Referenced anywhere in `src/main/java` outside the enum? |
|---|---|---|
| `MASTER_READ` | no | **no — zero references** |
| `REPORT_VIEW` | no | **no — zero references** |
| `COMM_*` (7 of 12: `SEND`, `ASSIGN`, `CALL_LOG`, `CHAT`, `REPORT_VIEW`, `TEMPLATE_MANAGE`, `WORKFLOW_MANAGE`) | no | no — Communication Phase 1 ships only `CommInboxController` (`COMM_READ`) |

`MASTER_READ` and `REPORT_VIEW` are the live ones: both are granted in role defaults **and used by the frontend to draw the sidebar**, so the UI hides master data / reports from a user the backend would happily serve. Master reads are actually gated on `isAuthenticated()`; reports on `CRM_FULL`.

The `COMM_*` keys are a *pending* gap, not a bug — the endpoints they gate do not exist yet (there is no `communication` feature in the frontend either). They must be wired as Phase 2 lands.

### 10.3 F-3 — frontend role-default mirror has drifted from the backend

`shared/lib/access.js` `ROLE_PERMISSIONS` is a pre-`/permissions/me` fallback only, so the blast radius is the first paint after login (and any session where that fetch failed). Still, it should mirror `defaultsFor` exactly:

| Role | In backend defaults, missing from FE |
|---|---|
| `MANAGER` | `TRASH_VIEW`, `TRASH_RESTORE`, `COMM_READ/SEND/ASSIGN/CALL_LOG/CHAT/REPORT_VIEW` |
| `TRAVEL_AGENT` | `TRASH_VIEW`, `COMM_READ/SEND/CALL_LOG/CHAT` |
| `ACCOUNTANT` | `COMM_READ`, `COMM_CHAT` |
| `SUB_AGENT` | `REMINDER_DELETE`, `TASK_DELETE`, `COMM_READ/SEND/CHAT` |

The `P` constant object is also missing every `COMM_*` key. The `TRASH_*` omissions are the ones that actually cost something today (the Trash menu is hidden from managers on first paint); the `COMM_*` ones are moot until the FE feature exists.

### 10.4 F-4 — `hasModule` is fail-open on the frontend *and* `ModuleAccessFilter` is fail-open on the backend

Both are deliberate, and together they mean an unmapped path on a malformed cache is fully visible. The compensating control is `ModuleAccessCoverageTest` (build-time), not runtime. Worth revisiting `app.entitlement.fail-closed=true` once the coverage test has been green across a few releases — the wiring already exists.

### 10.5 F-5 — 53 annotations are gated on `isAuthenticated()` only

Almost all are master-data **reads** (`/api/cities`, `/api/countries`, `/api/destinations`, `/api/hotels`, `/api/airlines`, `/api/cruises`, `/api/addons`, `/api/sightseeings`, `/api/vehicles`, `/api/masters/dropdown/**`) plus `/api/permissions/catalog`, `/api/permissions/me` and `/ai/chat`. Master **writes** are correctly `hasAnyAuthority('PLATFORM_ADMIN','MASTER_MANAGE')` (50 endpoints).

This is defensible — the tenant filter still scopes every row, and the dropdowns feed every form — but it is the same gap as F-2's `MASTER_READ`: read access to master data is currently "any logged-in user in the tenant", and the permission key that would express otherwise exists and is unused.

### 10.6 F-6 — `PLATFORM_ADMIN` and `ROLE_SUPER_ADMIN` are different strings, and only one of them is ever issued

Two distinct platform authorities exist, and they never meet:

| Authority | Issued by | Required by |
|---|---|---|
| `ROLE_SUPER_ADMIN` | `SuperAdmin.getAuthorities()` — the console entity | the 28 `hasRole('SUPER_ADMIN')` annotations under `/api/super-admin/**` (22 class-level) |
| `PLATFORM_ADMIN` | `Role.SUPERADMIN.authorities()` — a tenant **`User`** row whose role is `SUPERADMIN` | 63 tenant-side annotations: `hasAnyAuthority('PLATFORM_ADMIN','MASTER_MANAGE')` on master data, and the marketplace `hasAnyAuthority('PLATFORM_ADMIN','CRM_FULL',…)` set |

The console SuperAdmin logs in as a `SuperAdmin` **entity**, never as a `User`, so it carries `ROLE_SUPER_ADMIN` and **not** `PLATFORM_ADMIN`. `Permission.java` itself notes that the `Role.SUPERADMIN` branch "exists only for exhaustiveness" — meaning the `PLATFORM_ADMIN` alternative in all 63 of those annotations is a **dead branch** unless a `users` row with `role = 'SUPERADMIN'` exists.

Nothing is currently broken by this (the `MASTER_MANAGE` / `CRM_FULL` alternatives carry those endpoints), but it is worth writing down because it reads like a working platform override and is not one. If you actually want the console SuperAdmin to reach a tenant endpoint, `hasAnyAuthority('PLATFORM_ADMIN', …)` will **not** do it — impersonation is the supported path.

---

## 11. Maintenance playbook

### 11.1 Adding a new permission key

1. **`permission/enums/Permission.java`** — add the constant with its catalog module + label. Put it in the right group; the FE renders the grid grouped by that string.
2. **`Permission.defaultsFor(Role)`** — decide, per role, and *write the reason in a comment*. Every existing entry has one; that convention is what makes this file auditable.
   - High-privilege / privacy / irreversible / config ⇒ grant to **no** role (TENANT_ADMIN gets it via the bypass). See §4.2.
3. **Enforce it** — `@PreAuthorize("hasAuthority('NEW_KEY')")` on the controller method, or a class-level default plus per-method overrides (`LeadClaimController` is the reference: fail-closed class default, every method overrides explicitly).
4. **Backfill existing users** — append a new `PART n` to `src/main/resources/db/migration/V2__lead_code.sql` that adds the key to saved maps for the same roles `defaultsFor` grants it to. Copy the shape of PART 17/18 (`fleet_safe_jsonb`, `NOT (… ? 'KEY')` guard, verification queries in comments). **Skipping this makes access depend on whether Save was ever clicked.**
5. **Add a defaults test** modelled on `CommPermissionDefaultsTest` asserting the Java branch and the SQL role filter agree.
6. **Frontend** — add the key to `P` in `shared/lib/access.js` **and** to the affected `ROLE_PERMISSIONS` entries, then gate the sidebar item / button with `hasPermission(P.NEW_KEY)`.
7. Run `mvnw.cmd test -Dtest='*Permission*Test,ModuleAccessCoverageTest'`.

### 11.2 Adding a new module (plan entitlement)

1. `ModuleAccessFilter.RULES` — add the path prefix → module key. Remember exact-or-slash matching: register the *exact* prefix every controller in the module is mapped under.
2. `PlanCatalogueInitializer` — add an `ensureModules(...)` backfill (idempotent, additive) for the plans that should get it. A key **no plan has ever held is invisible** in the SuperAdmin console and cannot be granted through the UI at all — so register it even if you grant it to nobody (that is what `backfillHotelMarketplaceKey()` does).
3. **Backfill `tenant_modules`** in V2 — the plan grant alone does not reach existing tenants (§7.1). Deploy this *with or before* the filter rule, or every existing tenant is 403'd out of screens they use daily.
4. Frontend: gate the sidebar with `hasModule("KEY")` — same key, and note there is **no** TENANT_ADMIN bypass on module checks.
5. `mvnw.cmd test -Dtest=ModuleAccessCoverageTest`.

### 11.3 Adding a new role

Rare, and more invasive than it looks. Touch points:
`Role.java` (+ `authorities()` — decide `CRM_FULL` or not; fail-closed like `SUB_AGENT` is the safer default) → `Permission.defaultsFor` (the switch is exhaustive; it will not compile until you add the branch) → `ScopeResolver.resolveScope` default branch → the `users_role_check` constraint in `db/indexes.sql` → `ROLES` + `ROLE_PERMISSIONS` + the `getRole()` alias map in `shared/lib/access.js`.

> **Gotcha (from `usage-metering-quotas`):** a new `@Enumerated` value needs its `*_check` constraint refreshed in `db/indexes.sql`, or every insert fails at runtime with a constraint violation.

### 11.4 Regenerating this document

The role matrix and the endpoint matrix are extracted, not hand-written. The generator scripts used are throwaway PowerShell over `src/main/java`:

- **Role matrix** — parse the `defaultsFor` switch in `Permission.java` (one `case` per role; `EnumSet.allOf` ⇒ all, `noneOf` ⇒ none) and cross it with the enum constant list.
- **Endpoint matrix** — for every `.java` under `src/main/java`, skip comment lines, find `@PreAuthorize("…")`, decide class-level vs method-level by whether a `class` keyword or a method signature follows, then walk backwards for the `@Get/Post/Put/Patch/DeleteMapping` (or `@RequestMapping(method=…)`) and prepend the class-level `@RequestMapping` base path.

Two parsing traps if you rewrite it: `@PreAuthorize` also appears inside javadoc (`{@code @PreAuthorize(…)}`) and must be filtered out, and several controllers split `public class` across lines (`BookingController`, `ReportController`), which breaks a naive single-line class regex.

### 11.5 Review checklist for any PR that touches authorization

- [ ] Every new controller method has a `@PreAuthorize` (or a deliberate class-level default).
- [ ] New controller prefix is in `ModuleAccessFilter.RULES` **or** `ALWAYS_ALLOWED` — `ModuleAccessCoverageTest` is green.
- [ ] A new default grant has a matching V2 backfill **and** a defaults test.
- [ ] Row-level scope is applied for list endpoints (`visibleUserIds`) *and* single-record reads (`canSee` / `assertVisible`).
- [ ] Out-of-scope single-record access returns **404**, not 403.
- [ ] No `findById(Long)` / `getReferenceById` on a `BaseTenantEntity` — use `findByPublicIdAndTenantId`. (`TenantIsolationArchTest` guards this.)
- [ ] Sensitive fields (profit, cost, private notes, recordings) are filtered by a **query predicate or serialization guard**, not only by hiding the UI.
- [ ] The FE mirror in `shared/lib/access.js` was updated in the same change.

---

## Appendix A — full endpoint → authority matrix

Extracted from **427** `@PreAuthorize` annotations across **114** files. `CLASS default` rows apply to every method in that controller that does not declare its own; method-level annotations **override** the class-level one (they do not stack).

Legend: `hasAuthority('X')` = must hold X · `hasAnyAuthority(…)` = any one of them · `hasRole('SUPER_ADMIN')` = platform realm · `isAuthenticated()` = any logged-in principal.


#### `(no class @RequestMapping)`

<sub>`com/crm/travelcrm/master/geography/controller/CityController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `GET /api/cities` | `isAuthenticated()` |
| `POST /api/cities` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `PUT /api/cities/{cityId}` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `DELETE /api/cities/{cityId}` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `GET /api/cities/{cityId}` | `isAuthenticated()` |
| `GET /api/cities/destination/{destinationId}` | `isAuthenticated()` |
| `GET /api/cities/country/{countryId}` | `isAuthenticated()` |
| `GET /api/v1/countries/{countryId}/cities` | `isAuthenticated()` |
| `POST /api/v1/countries/{countryId}/cities` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `GET /api/v1/destinations/{destinationId}/cities` | `isAuthenticated()` |
| `POST /api/v1/destinations/{destinationId}/cities` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `GET /api/v1/cities/{cityId}` | `isAuthenticated()` |
| `PUT /api/v1/cities/{cityId}` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `DELETE /api/v1/cities/{cityId}` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |

#### `(no class @RequestMapping)`

<sub>`com/crm/travelcrm/platform/impersonation/controller/ImpersonationController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `POST /api/super-admin/users/{publicId}/impersonate` | `hasRole('SUPER_ADMIN')` |

#### `(no class @RequestMapping)`

<sub>`com/crm/travelcrm/auth/service/UserServiceImpl.java`</sub>

| Endpoint | Required authority |
|---|---|
| `? (see method)` | `hasAuthority('USER_CREATE')` |

#### `/ai/chat`

<sub>`com/crm/travelcrm/ai/controller/ChatController.java`</sub>

**Class default:** `isAuthenticated()` - applies to every method that does not declare its own.


#### `/api`

<sub>`com/crm/travelcrm/master/geography/controller/DestinationController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `GET /api/destinations` | `isAuthenticated()` |
| `GET /api/countries/{countryId}/destinations` | `isAuthenticated()` |
| `GET /api/destinations/{destinationId}` | `isAuthenticated()` |
| `POST /api/destinations` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `POST /api/countries/{countryId}/destinations` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `PUT /api/destinations/{destinationId}` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `GET /api/destinations/cities` | `isAuthenticated()` |
| `POST /api/destinations/upload-image` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `DELETE /api/destinations/{destinationId}` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |

#### `/api/accounting/export`

<sub>`com/crm/travelcrm/accounting/export/controller/LedgerExportController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `GET /api/accounting/export` | `hasAuthority('ACCOUNTING_INVOICE_READ')` |

#### `/api/accounting/hsn-rates`

<sub>`com/crm/travelcrm/accounting/tax/controller/HsnSacRateController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `GET /api/accounting/hsn-rates` | `hasAuthority('ACCOUNTING_INVOICE_READ')` |
| `POST /api/accounting/hsn-rates` | `hasAuthority('ACCOUNTING_SETTINGS_MANAGE')` |
| `PUT /api/accounting/hsn-rates/{publicId}` | `hasAuthority('ACCOUNTING_SETTINGS_MANAGE')` |
| `DELETE /api/accounting/hsn-rates/{publicId}` | `hasAuthority('ACCOUNTING_SETTINGS_MANAGE')` |

#### `/api/accounting/invoices`

<sub>`com/crm/travelcrm/accounting/invoice/controller/InvoiceController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `POST /api/accounting/invoices` | `hasAuthority('ACCOUNTING_INVOICE_MANAGE')` |
| `GET /api/accounting/invoices` | `hasAuthority('ACCOUNTING_INVOICE_READ')` |
| `GET /api/accounting/invoices/{publicId}` | `hasAuthority('ACCOUNTING_INVOICE_READ')` |
| `GET /api/accounting/invoices/booking/{bookingPublicId}` | `hasAuthority('ACCOUNTING_INVOICE_READ')` |
| `GET /api/accounting/invoices/{publicId}/pdf` | `hasAuthority('ACCOUNTING_INVOICE_READ')` |
| `POST /api/accounting/invoices/{publicId}/cancel` | `hasAuthority('ACCOUNTING_INVOICE_MANAGE')` |
| `POST /api/accounting/invoices/{publicId}/einvoice` | `hasAuthority('ACCOUNTING_INVOICE_MANAGE')` |

#### `/api/accounting/reports`

<sub>`com/crm/travelcrm/accounting/report/controller/AccountingReportController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `GET /api/accounting/reports/dashboard` | `hasAuthority('ACCOUNTING_INVOICE_READ')` |
| `GET /api/accounting/reports/pnl` | `hasAuthority('ACCOUNTING_INVOICE_READ')` |
| `GET /api/accounting/reports/gst-summary` | `hasAuthority('ACCOUNTING_INVOICE_READ')` |

#### `/api/accounting/settings`

<sub>`com/crm/travelcrm/accounting/settings/controller/AccountingSettingsController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `GET /api/accounting/settings` | `hasAuthority('ACCOUNTING_INVOICE_READ')` |
| `PUT /api/accounting/settings` | `hasAuthority('ACCOUNTING_SETTINGS_MANAGE')` |

#### `/api/accounting/vendor-bills`

<sub>`com/crm/travelcrm/accounting/tds/controller/VendorPayableController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `POST /api/accounting/vendor-bills` | `hasAuthority('ACCOUNTING_TDS_MANAGE')` |
| `GET /api/accounting/vendor-bills` | `hasAuthority('ACCOUNTING_TDS_READ')` |
| `GET /api/accounting/vendor-bills/{publicId}` | `hasAuthority('ACCOUNTING_TDS_READ')` |
| `POST /api/accounting/vendor-bills/{publicId}/payments` | `hasAuthority('ACCOUNTING_TDS_MANAGE')` |
| `POST /api/accounting/vendor-bills/{publicId}/cancel` | `hasAuthority('ACCOUNTING_TDS_MANAGE')` |
| `GET /api/accounting/vendor-bills/tds-summary` | `hasAuthority('ACCOUNTING_TDS_READ')` |

#### `/api/addons`

<sub>`com/crm/travelcrm/master/addon/AddonController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `GET /api/addons` | `isAuthenticated()` |
| `GET /api/addons/city/{cityId}` | `isAuthenticated()` |
| `GET /api/addons/{id}` | `isAuthenticated()` |
| `POST /api/addons` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `PUT /api/addons/{id}` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `DELETE /api/addons/{id}` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |

#### `/api/airlines`

<sub>`com/crm/travelcrm/master/airline/AirlineController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `GET /api/airlines` | `isAuthenticated()` |
| `GET /api/airlines/city/{cityId}` | `isAuthenticated()` |
| `GET /api/airlines/{id}` | `isAuthenticated()` |
| `POST /api/airlines` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `PUT /api/airlines/{id}` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `DELETE /api/airlines/{id}` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |

#### `/api/bookings`

<sub>`com/crm/travelcrm/booking/controller/BookingController.java`</sub>

**Class default:** `hasAuthority('BOOKING_READ')` - applies to every method that does not declare its own.

| Endpoint | Required authority |
|---|---|
| `POST /api/bookings` | `hasAuthority('BOOKING_CREATE')` |
| `POST /api/bookings/preview` | `hasAuthority('BOOKING_CREATE')` |
| `PUT /api/bookings/{publicId}` | `hasAuthority('BOOKING_UPDATE')` |
| `PATCH /api/bookings/{publicId}/status` | `hasAuthority('BOOKING_UPDATE')` |
| `POST /api/bookings/{publicId}/cancel` | `hasAuthority('BOOKING_CANCEL')` |
| `PATCH /api/bookings/{publicId}/payment` | `hasAuthority('BOOKING_UPDATE')` |
| `DELETE /api/bookings/{publicId}` | `hasAuthority('BOOKING_DELETE')` |
| `GET /api/bookings/stats` | `hasAuthority('CRM_FULL')` |
| `GET /api/bookings/page-summary` | `hasAuthority('CRM_FULL')` |
| `GET /api/bookings/export` | `hasAuthority('CRM_FULL')` |
| `POST /api/bookings/{publicId}/send-voucher` | `hasAuthority('BOOKING_UPDATE')` |

#### `/api/bookings/{bookingPublicId}/expenses`

<sub>`com/crm/travelcrm/booking/controller/BookingExpenseController.java`</sub>

**Class default:** `hasAnyAuthority('BOOKING_PROFIT_READ','MARKETPLACE_PAYABLE_READ')` - applies to every method that does not declare its own.

| Endpoint | Required authority |
|---|---|
| `GET /api/bookings/{bookingPublicId}/expenses/summary` | `hasAuthority('BOOKING_PROFIT_READ')` |
| `POST /api/bookings/{bookingPublicId}/expenses` | `hasAuthority('BOOKING_UPDATE') and hasAuthority('BOOKING_PROFIT_READ')` |
| `PUT /api/bookings/{bookingPublicId}/expenses/{expensePublicId}` | `hasAuthority('BOOKING_UPDATE') and hasAuthority('BOOKING_PROFIT_READ')` |
| `DELETE /api/bookings/{bookingPublicId}/expenses/{expensePublicId}` | `hasAuthority('BOOKING_UPDATE') and hasAuthority('BOOKING_PROFIT_READ')` |
| `POST /api/bookings/{bookingPublicId}/expenses/{expensePublicId}/restore` | `hasAuthority('BOOKING_UPDATE') and hasAuthority('BOOKING_PROFIT_READ')` |

#### `/api/bookings/{bookingPublicId}/gst-invoices`

<sub>`com/crm/travelcrm/booking/controller/BookingGstInvoiceController.java`</sub>

**Class default:** `hasAuthority('BOOKING_READ')` - applies to every method that does not declare its own.

| Endpoint | Required authority |
|---|---|
| `POST /api/bookings/{bookingPublicId}/gst-invoices` | `hasAuthority('ACCOUNTING_INVOICE_MANAGE')` |
| `POST /api/bookings/{bookingPublicId}/gst-invoices/{invoicePublicId}/cancel` | `hasAuthority('ACCOUNTING_INVOICE_MANAGE')` |

#### `/api/bookings/{bookingPublicId}/payments`

<sub>`com/crm/travelcrm/booking/controller/BookingPaymentController.java`</sub>

**Class default:** `hasAuthority('BOOKING_READ')` - applies to every method that does not declare its own.

| Endpoint | Required authority |
|---|---|
| `POST /api/bookings/{bookingPublicId}/payments` | `hasAuthority('BOOKING_UPDATE')` |
| `DELETE /api/bookings/{bookingPublicId}/payments/{paymentPublicId}` | `hasAuthority('BOOKING_UPDATE')` |

#### `/api/bookings/{bookingPublicId}/services`

<sub>`com/crm/travelcrm/booking/controller/BookingServiceItemController.java`</sub>

**Class default:** `hasAuthority('BOOKING_READ')` - applies to every method that does not declare its own.

| Endpoint | Required authority |
|---|---|
| `POST /api/bookings/{bookingPublicId}/services` | `hasAuthority('BOOKING_UPDATE')` |
| `PUT /api/bookings/{bookingPublicId}/services/{serviceItemPublicId}` | `hasAuthority('BOOKING_UPDATE')` |
| `DELETE /api/bookings/{bookingPublicId}/services/{serviceItemPublicId}` | `hasAuthority('BOOKING_UPDATE')` |
| `PUT /api/bookings/{bookingPublicId}/services/{serviceItemPublicId}/vendor` | `hasAuthority('BOOKING_UPDATE')` |

#### `/api/bookings/{publicId}`

<sub>`com/crm/travelcrm/booking/controller/BookingDocumentController.java`</sub>

**Class default:** `hasAuthority('BOOKING_READ')` - applies to every method that does not declare its own.


#### `/api/bookings/{publicId}/cancellation`

<sub>`com/crm/travelcrm/booking/cancellation/controller/BookingCancellationController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `GET /api/bookings/{publicId}/cancellation/preview` | `hasAuthority('BOOKING_CANCEL')` |
| `GET /api/bookings/{publicId}/cancellation` | `hasAuthority('BOOKING_READ')` |
| `GET /api/bookings/{publicId}/cancellation/credit-note` | `hasAuthority('BOOKING_READ')` |
| `GET /api/bookings/{publicId}/cancellation/refund-voucher` | `hasAuthority('BOOKING_READ')` |
| `POST /api/bookings/{publicId}/cancellation/refund` | `hasAuthority('BOOKING_REFUND')` |

#### `/api/bookings/assignment`

<sub>`com/crm/travelcrm/booking/controller/BookingAssignmentController.java`</sub>

**Class default:** `hasAuthority('BOOKING_CREATE')` - applies to every method that does not declare its own.


#### `/api/calendar`

<sub>`com/crm/travelcrm/calendar/controller/CalendarController.java`</sub>

**Class default:** `hasAuthority('TASK_READ')` - applies to every method that does not declare its own.

| Endpoint | Required authority |
|---|---|
| `GET /api/calendar/summary` | `hasAuthority('CRM_FULL')` |

#### `/api/cancellation-policies`

<sub>`com/crm/travelcrm/booking/cancellation/controller/CancellationPolicyController.java`</sub>

**Class default:** `hasAuthority('BOOKING_READ')` - applies to every method that does not declare its own.

| Endpoint | Required authority |
|---|---|
| `POST /api/cancellation-policies` | `hasAuthority('CANCELLATION_POLICY_MANAGE')` |
| `DELETE /api/cancellation-policies/{publicId}` | `hasAuthority('CANCELLATION_POLICY_MANAGE')` |

#### `/api/communication`

<sub>`com/crm/travelcrm/communication/controller/CommInboxController.java`</sub>

**Class default:** `hasAuthority('COMM_READ')` - applies to every method that does not declare its own.


#### `/api/company`

<sub>`com/crm/travelcrm/company/controller/CompanyController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `GET /api/company` | `isAuthenticated()` |
| `PUT /api/company` | `hasAuthority('SETTINGS_MANAGE')` |
| `POST /api/company/logo` | `hasAuthority('SETTINGS_MANAGE')` |
| `POST /api/company/favicon` | `hasAuthority('SETTINGS_MANAGE')` |
| `GET /api/company/subscription` | `isAuthenticated()` |
| `GET /api/company/ai-credits` | `isAuthenticated()` |

#### `/api/company/billing`

<sub>`com/crm/travelcrm/platform/payment/controller/TenantBillingPaymentController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `POST /api/company/billing/{invoicePublicId}/pay-intent` | `hasAuthority('SETTINGS_MANAGE')` |

#### `/api/company/subscription`

<sub>`com/crm/travelcrm/platform/subscription/self/MySubscriptionController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `GET /api/company/subscription/plans` | `isAuthenticated()` |
| `GET /api/company/subscription/invoices` | `isAuthenticated()` |
| `POST /api/company/subscription/change` | `hasAuthority('SETTINGS_MANAGE')` |

#### `/api/company/subscription/upgrade-requests`

<sub>`com/crm/travelcrm/platform/subscription/upgrade/controller/UpgradeRequestController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `POST /api/company/subscription/upgrade-requests` | `hasAuthority('SETTINGS_MANAGE')` |
| `GET /api/company/subscription/upgrade-requests` | `isAuthenticated()` |
| `POST /api/company/subscription/upgrade-requests/{publicId}/proof` | `hasAuthority('SETTINGS_MANAGE')` |
| `POST /api/company/subscription/upgrade-requests/{publicId}/cancel` | `hasAuthority('SETTINGS_MANAGE')` |

#### `/api/cruises`

<sub>`com/crm/travelcrm/master/cruise/CruiseController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `GET /api/cruises` | `isAuthenticated()` |
| `GET /api/cruises/city/{cityId}` | `isAuthenticated()` |
| `GET /api/cruises/{id}` | `isAuthenticated()` |
| `POST /api/cruises` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `PUT /api/cruises/{id}` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `DELETE /api/cruises/{id}` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `POST /api/cruises/{cruiseId}/room-types` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `PUT /api/cruises/{cruiseId}/room-types/{roomTypeId}` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `DELETE /api/cruises/{cruiseId}/room-types/{roomTypeId}` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |

#### `/api/customers`

<sub>`com/crm/travelcrm/customer/controller/CustomerController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `GET /api/customers` | `hasAuthority('CUSTOMER_READ')` |
| `GET /api/customers/search` | `hasAuthority('CUSTOMER_READ')` |
| `GET /api/customers/lookup` | `hasAuthority('CUSTOMER_READ')` |
| `GET /api/customers/search-name` | `hasAuthority('CUSTOMER_READ')` |
| `GET /api/customers/filter` | `hasAuthority('CUSTOMER_READ')` |
| `GET /api/customers/stats` | `hasAuthority('CRM_FULL')` |
| `GET /api/customers/export` | `hasAuthority('CRM_FULL')` |
| `GET /api/customers/{id}` | `hasAuthority('CUSTOMER_READ')` |
| `GET /api/customers/{id}/bookings` | `hasAuthority('CUSTOMER_READ')` |
| `POST /api/customers` | `hasAuthority('CUSTOMER_CREATE')` |
| `PUT /api/customers/{id}` | `hasAuthority('CUSTOMER_UPDATE')` |
| `PATCH /api/customers/{id}/status` | `hasAuthority('CUSTOMER_UPDATE')` |
| `PATCH /api/customers/{id}/tier` | `hasAuthority('CUSTOMER_UPDATE')` |
| `DELETE /api/customers/{id}` | `hasAuthority('CUSTOMER_DELETE')` |

#### `/api/dashboard`

<sub>`com/crm/travelcrm/report/dashboard/controller/DashboardAnalyticsController.java`</sub>

**Class default:** `hasAuthority('CRM_FULL')` - applies to every method that does not declare its own.


#### `/api/fleet`

<sub>`com/crm/travelcrm/fleet/controller/FleetDashboardController.java`</sub>

**Class default:** `hasAuthority('FLEET_READ')` - applies to every method that does not declare its own.


#### `/api/fleet`

<sub>`com/crm/travelcrm/fleet/controller/FleetSettlementController.java`</sub>

**Class default:** `hasAuthority('FLEET_MONEY_READ')` - applies to every method that does not declare its own.

| Endpoint | Required authority |
|---|---|
| `GET /api/fleet/cash-directions` | `hasAuthority('FLEET_READ')` |
| `POST /api/fleet/cash` | `hasAuthority('FLEET_UPDATE')` |
| `POST /api/fleet/trips/{tripPublicId}/settlements/{driverPublicId}/reconcile` | `hasAuthority('FLEET_UPDATE')` |
| `POST /api/fleet/trips/{tripPublicId}/settlements/{driverPublicId}/settle` | `hasAuthority('FLEET_MONEY_SETTLE')` |

#### `/api/fleet`

<sub>`com/crm/travelcrm/fleet/controller/FleetExpenseController.java`</sub>

**Class default:** `hasAuthority('FLEET_MONEY_READ')` - applies to every method that does not declare its own.

| Endpoint | Required authority |
|---|---|
| `GET /api/fleet/expense-types` | `hasAuthority('FLEET_READ')` |
| `POST /api/fleet/expenses` | `hasAuthority('FLEET_CREATE')` |
| `PUT /api/fleet/expenses/{publicId}` | `hasAuthority('FLEET_UPDATE')` |
| `POST /api/fleet/expenses/{publicId}/reverse` | `hasAuthority('FLEET_MONEY_SETTLE')` |
| `DELETE /api/fleet/expenses/{publicId}` | `hasAuthority('FLEET_DELETE')` |

#### `/api/fleet`

<sub>`com/crm/travelcrm/fleet/controller/FleetComplianceController.java`</sub>

**Class default:** `hasAuthority('FLEET_READ')` - applies to every method that does not declare its own.

| Endpoint | Required authority |
|---|---|
| `POST /api/fleet/documents` | `hasAuthority('FLEET_CREATE')` |
| `PUT /api/fleet/documents/{publicId}` | `hasAuthority('FLEET_UPDATE')` |
| `POST /api/fleet/documents/{publicId}/renew` | `hasAuthority('FLEET_UPDATE')` |
| `POST /api/fleet/documents/{publicId}/revoke` | `hasAuthority('FLEET_UPDATE')` |
| `DELETE /api/fleet/documents/{publicId}` | `hasAuthority('FLEET_DELETE')` |

#### `/api/fleet`

<sub>`com/crm/travelcrm/fleet/controller/FleetFuelLogController.java`</sub>

**Class default:** `hasAuthority('FLEET_READ')` - applies to every method that does not declare its own.

| Endpoint | Required authority |
|---|---|
| `POST /api/fleet/vehicles/{vehiclePublicId}/fuel-logs` | `hasAuthority('FLEET_CREATE')` |
| `PUT /api/fleet/fuel-logs/{publicId}` | `hasAuthority('FLEET_UPDATE')` |
| `DELETE /api/fleet/fuel-logs/{publicId}` | `hasAuthority('FLEET_DELETE')` |

#### `/api/fleet`

<sub>`com/crm/travelcrm/fleet/controller/FleetMaintenanceLogController.java`</sub>

**Class default:** `hasAuthority('FLEET_READ')` - applies to every method that does not declare its own.

| Endpoint | Required authority |
|---|---|
| `POST /api/fleet/vehicles/{vehiclePublicId}/maintenance-logs` | `hasAuthority('FLEET_CREATE')` |
| `PUT /api/fleet/maintenance-logs/{publicId}` | `hasAuthority('FLEET_UPDATE')` |
| `DELETE /api/fleet/maintenance-logs/{publicId}` | `hasAuthority('FLEET_DELETE')` |

#### `/api/fleet/attachments`

<sub>`com/crm/travelcrm/fleet/controller/FleetAttachmentController.java`</sub>

**Class default:** `hasAuthority('FLEET_READ')` - applies to every method that does not declare its own.

| Endpoint | Required authority |
|---|---|
| `POST /api/fleet/attachments` | `hasAnyAuthority('FLEET_CREATE','FLEET_UPDATE')` |
| `DELETE /api/fleet/attachments/{publicId}` | `hasAuthority('FLEET_DELETE')` |

#### `/api/fleet/drivers`

<sub>`com/crm/travelcrm/fleet/controller/FleetDriverController.java`</sub>

**Class default:** `hasAuthority('FLEET_READ')` - applies to every method that does not declare its own.

| Endpoint | Required authority |
|---|---|
| `POST /api/fleet/drivers` | `hasAuthority('FLEET_CREATE')` |
| `PUT /api/fleet/drivers/{publicId}` | `hasAuthority('FLEET_UPDATE')` |
| `PATCH /api/fleet/drivers/{publicId}/status` | `hasAuthority('FLEET_UPDATE')` |
| `DELETE /api/fleet/drivers/{publicId}` | `hasAuthority('FLEET_DELETE')` |

#### `/api/fleet/parties`

<sub>`com/crm/travelcrm/fleet/controller/FleetPartyController.java`</sub>

**Class default:** `hasAuthority('FLEET_READ')` - applies to every method that does not declare its own.

| Endpoint | Required authority |
|---|---|
| `POST /api/fleet/parties` | `hasAuthority('FLEET_CREATE')` |
| `PUT /api/fleet/parties/{publicId}` | `hasAuthority('FLEET_UPDATE')` |
| `DELETE /api/fleet/parties/{publicId}` | `hasAuthority('FLEET_DELETE')` |

#### `/api/fleet/periods`

<sub>`com/crm/travelcrm/fleet/controller/FleetPeriodController.java`</sub>

**Class default:** `hasAuthority('FLEET_MONEY_READ')` - applies to every method that does not declare its own.

| Endpoint | Required authority |
|---|---|
| `POST /api/fleet/periods/close` | `hasAuthority('FLEET_PERIOD_CLOSE')` |
| `POST /api/fleet/periods/{publicId}/reopen` | `hasAuthority('FLEET_PERIOD_CLOSE')` |

#### `/api/fleet/trips`

<sub>`com/crm/travelcrm/fleet/controller/FleetTripController.java`</sub>

**Class default:** `hasAuthority('FLEET_READ')` - applies to every method that does not declare its own.

| Endpoint | Required authority |
|---|---|
| `POST /api/fleet/trips` | `hasAuthority('FLEET_CREATE')` |
| `PUT /api/fleet/trips/{publicId}` | `hasAuthority('FLEET_UPDATE')` |
| `PATCH /api/fleet/trips/{publicId}/start` | `hasAuthority('FLEET_UPDATE')` |
| `PATCH /api/fleet/trips/{publicId}/close` | `hasAuthority('FLEET_UPDATE')` |
| `PATCH /api/fleet/trips/{publicId}/swap` | `hasAuthority('FLEET_UPDATE')` |
| `PATCH /api/fleet/trips/{publicId}/cancel` | `hasAuthority('FLEET_UPDATE')` |
| `DELETE /api/fleet/trips/{publicId}` | `hasAuthority('FLEET_DELETE')` |

#### `/api/fleet/vehicles`

<sub>`com/crm/travelcrm/fleet/controller/FleetVehicleController.java`</sub>

**Class default:** `hasAuthority('FLEET_READ')` - applies to every method that does not declare its own.

| Endpoint | Required authority |
|---|---|
| `POST /api/fleet/vehicles` | `hasAuthority('FLEET_CREATE')` |
| `PUT /api/fleet/vehicles/{publicId}` | `hasAuthority('FLEET_UPDATE')` |
| `PATCH /api/fleet/vehicles/{publicId}/status` | `hasAuthority('FLEET_UPDATE')` |
| `DELETE /api/fleet/vehicles/{publicId}` | `hasAuthority('FLEET_DELETE')` |

#### `/api/hotel-marketplace`

<sub>`com/crm/travelcrm/hotelmarketplace/catalog/controller/MarketplaceCatalogController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `GET /api/hotel-marketplace/hotels` | `hasAnyAuthority('PLATFORM_ADMIN','CRM_FULL','HOTEL_MARKETPLACE_VIEW')` |
| `GET /api/hotel-marketplace/hotels/{publicId}` | `hasAnyAuthority('PLATFORM_ADMIN','CRM_FULL','HOTEL_MARKETPLACE_VIEW')` |
| `POST /api/hotel-marketplace` | `hasAnyAuthority('PLATFORM_ADMIN','CRM_FULL','MASTER_MANAGE','HOTEL_MARKETPLACE_SYNC_MASTER')` |

#### `/api/hotel-marketplace/bookings`

<sub>`com/crm/travelcrm/hotelmarketplace/booking/controller/MarketplaceBookingController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `POST /api/hotel-marketplace/bookings` | `hasAnyAuthority('PLATFORM_ADMIN','CRM_FULL','HOTEL_MARKETPLACE_BOOK')` |
| `GET /api/hotel-marketplace/bookings` | `hasAnyAuthority('PLATFORM_ADMIN','CRM_FULL','HOTEL_MARKETPLACE_VIEW')` |
| `GET /api/hotel-marketplace/bookings/{publicId}` | `hasAnyAuthority('PLATFORM_ADMIN','CRM_FULL','HOTEL_MARKETPLACE_VIEW')` |
| `POST /api/hotel-marketplace/bookings/{publicId}/accept-revision` | `hasAnyAuthority('PLATFORM_ADMIN','CRM_FULL','HOTEL_MARKETPLACE_BOOK')` |
| `POST /api/hotel-marketplace/bookings/{publicId}/decline-revision` | `hasAnyAuthority('PLATFORM_ADMIN','CRM_FULL','HOTEL_MARKETPLACE_BOOK')` |
| `POST /api/hotel-marketplace/bookings/{publicId}/cancel` | `hasAnyAuthority('PLATFORM_ADMIN','CRM_FULL','HOTEL_MARKETPLACE_CANCEL')` |
| `POST /api/hotel-marketplace/bookings/{publicId}/accept-cancellation` | `hasAnyAuthority('PLATFORM_ADMIN','CRM_FULL','HOTEL_MARKETPLACE_CANCEL')` |
| `POST /api/hotel-marketplace/bookings/{publicId}/decline-cancellation` | `hasAnyAuthority('PLATFORM_ADMIN','CRM_FULL','HOTEL_MARKETPLACE_CANCEL')` |

#### `/api/hotels`

<sub>`com/crm/travelcrm/master/hotel/HotelController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `GET /api/hotels` | `isAuthenticated()` |
| `GET /api/hotels/destination/{destinationId}` | `isAuthenticated()` |
| `GET /api/hotels/city/{cityId}` | `isAuthenticated()` |
| `GET /api/hotels/{id}` | `isAuthenticated()` |
| `POST /api/hotels` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `PUT /api/hotels/{id}` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `DELETE /api/hotels/{id}` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `PATCH /api/hotels/{id}/set-default` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `POST /api/hotels/upload-image` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `POST /api/hotels/{hotelId}/room-types` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `PUT /api/hotels/{hotelId}/room-types/{roomTypeId}` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `DELETE /api/hotels/{hotelId}/room-types/{roomTypeId}` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `POST /api/hotels/{hotelId}/room-types/{roomTypeId}/images` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `POST /api/hotels/{hotelId}/meal-plans` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `PUT /api/hotels/{hotelId}/meal-plans/{mealPlanId}` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `DELETE /api/hotels/{hotelId}/meal-plans/{mealPlanId}` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |

#### `/api/leads`

<sub>`com/crm/travelcrm/lead/controller/LeadController.java`</sub>

**Class default:** `hasAuthority('LEAD_READ')` - applies to every method that does not declare its own.

| Endpoint | Required authority |
|---|---|
| `POST /api/leads` | `hasAuthority('LEAD_CREATE')` |
| `PATCH /api/leads/{publicId}/stage` | `hasAuthority('LEAD_UPDATE')` |
| `PUT /api/leads/{publicId}` | `hasAuthority('LEAD_UPDATE')` |
| `DELETE /api/leads/{publicId}` | `hasAuthority('LEAD_DELETE')` |
| `POST /api/leads/{publicId}/logs` | `hasAuthority('LEAD_UPDATE')` |
| `DELETE /api/leads/{publicId}/logs/{logId}` | `hasAuthority('LEAD_UPDATE')` |

#### `/api/leads`

<sub>`com/crm/travelcrm/booking/controller/LeadConversionController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `POST /api/leads/{publicId}/convert-to-booking` | `hasAuthority('BOOKING_CREATE')` |

#### `/api/leads`

<sub>`com/crm/travelcrm/lead/claim/controller/LeadClaimController.java`</sub>

**Class default:** `hasAuthority('LEAD_REASSIGN_LOCKED')` - applies to every method that does not declare its own.

| Endpoint | Required authority |
|---|---|
| `POST /api/leads/{publicId}/claim` | `hasAuthority('LEAD_CLAIM')` |
| `POST /api/leads/{publicId}/contacted` | `hasAuthority('LEAD_UPDATE')` |
| `POST /api/leads/{publicId}/reassign` | `hasAuthority('LEAD_REASSIGN_LOCKED')` |
| `POST /api/leads/{publicId}/reopen-claim` | `hasAuthority('LEAD_REASSIGN_LOCKED')` |
| `GET /api/leads/{publicId}/assignment-history` | `hasAuthority('LEAD_READ')` |

#### `/api/leads/alerts`

<sub>`com/crm/travelcrm/lead/alert/LeadAlertController.java`</sub>

**Class default:** `hasAuthority('LEAD_READ')` - applies to every method that does not declare its own.


#### `/api/leads/assignment`

<sub>`com/crm/travelcrm/lead/assignment/controller/LeadAssignmentController.java`</sub>

**Class default:** `hasAuthority('LEAD_CREATE')` - applies to every method that does not declare its own.


#### `/api/leads/import`

<sub>`com/crm/travelcrm/lead/bulkimport/LeadImportController.java`</sub>

**Class default:** `hasAuthority('LEAD_CREATE')` - applies to every method that does not declare its own.


#### `/api/leads/meta`

<sub>`com/crm/travelcrm/lead/controller/LeadMetaController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `GET /api/leads/meta/sources` | `hasAnyAuthority('LEAD_READ','LEAD_CREATE','LEAD_UPDATE')` |

#### `/api/lead-sources`

<sub>`com/crm/travelcrm/leadsource/web/LeadSourceIntegrationController.java`</sub>

**Class default:** `hasAuthority('SETTINGS_MANAGE')` - applies to every method that does not declare its own.


#### `/api/marketing`

<sub>`com/crm/travelcrm/marketing/controller/MarketingMetaController.java`</sub>

**Class default:** `hasAuthority('MARKETING_READ')` - applies to every method that does not declare its own.


#### `/api/marketing/automations`

<sub>`com/crm/travelcrm/marketing/controller/AutomationController.java`</sub>

**Class default:** `hasAuthority('MARKETING_READ')` - applies to every method that does not declare its own.

| Endpoint | Required authority |
|---|---|
| `PUT /api/marketing/automations/{triggerType}` | `hasAuthority('MARKETING_UPDATE')` |
| `POST /api/marketing/automations/{triggerType}/test` | `hasAuthority('MARKETING_SEND')` |

#### `/api/marketing/campaigns`

<sub>`com/crm/travelcrm/marketing/controller/CampaignController.java`</sub>

**Class default:** `hasAuthority('MARKETING_READ')` - applies to every method that does not declare its own.

| Endpoint | Required authority |
|---|---|
| `POST /api/marketing/campaigns` | `hasAuthority('MARKETING_CREATE')` |
| `PUT /api/marketing/campaigns/{publicId}` | `hasAuthority('MARKETING_UPDATE')` |
| `POST /api/marketing/campaigns/{publicId}/send` | `hasAuthority('MARKETING_SEND')` |
| `POST /api/marketing/campaigns/{publicId}/cancel` | `hasAuthority('MARKETING_SEND')` |
| `POST /api/marketing/campaigns/{publicId}/test` | `hasAuthority('MARKETING_SEND')` |
| `DELETE /api/marketing/campaigns/{publicId}` | `hasAuthority('MARKETING_DELETE')` |

#### `/api/marketing/drips`

<sub>`com/crm/travelcrm/marketing/controller/DripController.java`</sub>

**Class default:** `hasAuthority('MARKETING_READ')` - applies to every method that does not declare its own.

| Endpoint | Required authority |
|---|---|
| `POST /api/marketing/drips` | `hasAuthority('MARKETING_CREATE')` |
| `PUT /api/marketing/drips/{publicId}` | `hasAuthority('MARKETING_UPDATE')` |
| `POST /api/marketing/drips/{publicId}/activate` | `hasAuthority('MARKETING_SEND')` |
| `POST /api/marketing/drips/{publicId}/pause` | `hasAuthority('MARKETING_SEND')` |
| `DELETE /api/marketing/drips/{publicId}` | `hasAuthority('MARKETING_DELETE')` |

#### `/api/marketing/segments`

<sub>`com/crm/travelcrm/marketing/controller/SegmentController.java`</sub>

**Class default:** `hasAuthority('MARKETING_READ')` - applies to every method that does not declare its own.

| Endpoint | Required authority |
|---|---|
| `POST /api/marketing/segments` | `hasAuthority('MARKETING_CREATE')` |
| `PUT /api/marketing/segments/{publicId}` | `hasAuthority('MARKETING_UPDATE')` |
| `DELETE /api/marketing/segments/{publicId}` | `hasAuthority('MARKETING_DELETE')` |

#### `/api/masters/dropdown`

<sub>`com/crm/travelcrm/master/dropdown/MasterDropdownController.java`</sub>

**Class default:** `isAuthenticated()` - applies to every method that does not declare its own.


#### `/api/me`

<sub>`com/crm/travelcrm/subagent/controller/MyCommissionController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `GET /api/me/commissions` | `isAuthenticated()` |

#### `/api/me`

<sub>`com/crm/travelcrm/common/feature/DeploymentFeatureController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `GET /api/me/features` | `isAuthenticated()` |

#### `/api/me/hotel-bookings`

<sub>`com/crm/travelcrm/hotelmarketplace/history/controller/TenantHotelBookingHistoryController.java`</sub>

**Class default:** `hasAnyAuthority('PLATFORM_ADMIN','CRM_FULL','HOTEL_MARKETPLACE_VIEW')` - applies to every method that does not declare its own.


#### `/api/me/profile`

<sub>`com/crm/travelcrm/auth/controller/MeProfileController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `GET /api/me/profile` | `isAuthenticated()` |
| `PUT /api/me/profile` | `isAuthenticated()` |

#### `/api/notification-settings`

<sub>`com/crm/travelcrm/notificationsetting/controller/NotificationSettingController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `PUT /api/notification-settings` | `hasAuthority('SETTINGS_MANAGE')` |

#### `/api/permissions`

<sub>`com/crm/travelcrm/permission/controller/PermissionCatalogController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `GET /api/permissions/catalog` | `isAuthenticated()` |
| `GET /api/permissions/me` | `isAuthenticated()` |

#### `/api/permission-templates`

<sub>`com/crm/travelcrm/permission/controller/PermissionTemplateController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `GET /api/permission-templates` | `hasAuthority('USER_READ')` |
| `GET /api/permission-templates/{value}` | `hasAuthority('USER_READ')` |
| `POST /api/permission-templates` | `hasAuthority('USER_UPDATE')` |
| `PUT /api/permission-templates/{value}` | `hasAuthority('USER_UPDATE')` |
| `DELETE /api/permission-templates/{value}` | `hasAuthority('USER_UPDATE')` |

#### `/api/quotations`

<sub>`com/crm/travelcrm/quotation/controller/QuotationController.java`</sub>

**Class default:** `hasAuthority('QUOTATION_READ')` - applies to every method that does not declare its own.

| Endpoint | Required authority |
|---|---|
| `POST /api/quotations` | `hasAuthority('QUOTATION_CREATE')` |
| `PUT /api/quotations/{publicId}` | `hasAuthority('QUOTATION_UPDATE')` |
| `DELETE /api/quotations/{publicId}` | `hasAuthority('QUOTATION_DELETE')` |
| `PATCH /api/quotations/{publicId}/stage` | `hasAuthority('QUOTATION_UPDATE')` |
| `POST /api/quotations/{publicId}/duplicate` | `hasAuthority('QUOTATION_CREATE')` |
| `POST /api/quotations/{publicId}/new-version` | `hasAuthority('QUOTATION_CREATE')` |
| `PATCH /api/quotations/{publicId}/template-style` | `hasAuthority('QUOTATION_UPDATE')` |
| `POST /api/quotations/{publicId}/send-email` | `hasAuthority('QUOTATION_UPDATE')` |
| `POST /api/quotations/{publicId}/send-whatsapp` | `hasAuthority('QUOTATION_UPDATE')` |

#### `/api/quotation-templates`

<sub>`com/crm/travelcrm/quotationtemplate/controller/QuotationTemplateController.java`</sub>

**Class default:** `hasAuthority('QUOTATION_READ')` - applies to every method that does not declare its own.

| Endpoint | Required authority |
|---|---|
| `POST /api/quotation-templates` | `hasAuthority('QUOTATION_CREATE')` |
| `PUT /api/quotation-templates/{publicId}` | `hasAuthority('QUOTATION_UPDATE')` |
| `DELETE /api/quotation-templates/{publicId}` | `hasAuthority('QUOTATION_DELETE')` |
| `POST /api/quotation-templates/{publicId}/apply` | `hasAuthority('QUOTATION_CREATE')` |

#### `/api/reminders`

<sub>`com/crm/travelcrm/reminder/controller/ReminderController.java`</sub>

**Class default:** `hasAuthority('REMINDER_READ')` - applies to every method that does not declare its own.

| Endpoint | Required authority |
|---|---|
| `POST /api/reminders` | `hasAuthority('REMINDER_CREATE')` |
| `GET /api/reminders/stats` | `hasAuthority('CRM_FULL')` |
| `GET /api/reminders/export/csv` | `hasAuthority('CRM_FULL')` |
| `PUT /api/reminders/{id}` | `hasAuthority('REMINDER_UPDATE')` |
| `DELETE /api/reminders/{id}` | `hasAuthority('REMINDER_DELETE')` |
| `PATCH/PUT /api/reminders/{id}/complete` | `hasAuthority('REMINDER_UPDATE')` |
| `PATCH/PUT /api/reminders/{id}/dismiss` | `hasAuthority('REMINDER_UPDATE')` |
| `PATCH /api/reminders/{id}/snooze` | `hasAuthority('REMINDER_UPDATE')` |
| `POST /api/reminders/{id}/logs` | `hasAuthority('REMINDER_UPDATE')` |
| `PATCH /api/reminders/complete-all-overdue` | `hasAuthority('CRM_FULL')` |

#### `/api/reports`

<sub>`com/crm/travelcrm/report/dashboard/controller/ReportController.java`</sub>

**Class default:** `hasAuthority('CRM_FULL')` - applies to every method that does not declare its own.


#### `/api/reports/activity`

<sub>`com/crm/travelcrm/report/activity/controller/ActivityReportController.java`</sub>

**Class default:** `hasAuthority('CRM_FULL')` - applies to every method that does not declare its own.


#### `/api/reports/followup`

<sub>`com/crm/travelcrm/report/followup/controller/FollowupReportController.java`</sub>

**Class default:** `hasAuthority('CRM_FULL')` - applies to every method that does not declare its own.


#### `/api/reports/geographic`

<sub>`com/crm/travelcrm/report/geographic/controller/GeographicReportController.java`</sub>

**Class default:** `hasAuthority('CRM_FULL')` - applies to every method that does not declare its own.


#### `/api/reports/international-domestic`

<sub>`com/crm/travelcrm/report/intldomestic/controller/IntlDomesticController.java`</sub>

**Class default:** `hasAuthority('CRM_FULL')` - applies to every method that does not declare its own.


#### `/api/reports/revenue`

<sub>`com/crm/travelcrm/report/booking/controller/BookingRevenueController.java`</sub>

**Class default:** `hasAuthority('CRM_FULL')` - applies to every method that does not declare its own.


#### `/api/reports/travel-dates`

<sub>`com/crm/travelcrm/report/traveldate/controller/TravelDateController.java`</sub>

**Class default:** `hasAuthority('CRM_FULL')` - applies to every method that does not declare its own.


#### `/api/settings/email`

<sub>`com/crm/travelcrm/settings/controller/EmailConfigController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `GET /api/settings/email/config` | `hasAuthority('SETTINGS_MANAGE')` |
| `POST /api/settings/email/config` | `hasAuthority('SETTINGS_MANAGE')` |
| `POST /api/settings/email/test` | `hasAuthority('SETTINGS_MANAGE')` |
| `GET /api/settings/email/stats` | `hasAuthority('SETTINGS_MANAGE')` |

#### `/api/settings/whatsapp`

<sub>`com/crm/travelcrm/settings/controller/WhatsAppConfigController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `GET /api/settings/whatsapp/config` | `hasAuthority('SETTINGS_MANAGE')` |
| `POST /api/settings/whatsapp/config` | `hasAuthority('SETTINGS_MANAGE')` |
| `POST /api/settings/whatsapp/test` | `hasAuthority('SETTINGS_MANAGE')` |
| `GET /api/settings/whatsapp/stats` | `hasAuthority('SETTINGS_MANAGE')` |

#### `/api/sightseeings`

<sub>`com/crm/travelcrm/master/sightseeing/SightseeingController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `GET /api/sightseeings` | `isAuthenticated()` |
| `GET /api/sightseeings/city/{cityId}` | `isAuthenticated()` |
| `GET /api/sightseeings/destionation/{destionationId}` | `isAuthenticated()` |
| `GET /api/sightseeings/{id}` | `isAuthenticated()` |
| `POST /api/sightseeings` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `PUT /api/sightseeings/{id}` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `DELETE /api/sightseeings/{id}` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `POST /api/sightseeings/upload-image` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `GET /api/sightseeings/search` | `isAuthenticated()` |

#### `/api/subagents`

<sub>`com/crm/travelcrm/subagent/controller/SubAgentController.java`</sub>

**Class default:** `hasAuthority('USER_READ')` - applies to every method that does not declare its own.

| Endpoint | Required authority |
|---|---|
| `POST /api/subagents` | `hasAuthority('USER_CREATE')` |
| `PUT /api/subagents/{publicId}` | `hasAuthority('USER_UPDATE')` |
| `DELETE /api/subagents/{publicId}` | `hasAuthority('USER_DELETE')` |

#### `/api/subagents/license-requests`

<sub>`com/crm/travelcrm/subagent/license/controller/SubAgentLicenseController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `POST /api/subagents/license-requests` | `hasAuthority('USER_CREATE')` |
| `GET /api/subagents/license-requests` | `hasAuthority('USER_READ')` |
| `POST /api/subagents/license-requests/{publicId}/proof` | `hasAuthority('USER_CREATE')` |
| `POST /api/subagents/license-requests/{publicId}/cancel` | `hasAuthority('USER_UPDATE')` |

#### `/api/super-admin`

<sub>`com/crm/travelcrm/platform/billing/controller/BillingController.java`</sub>

**Class default:** `hasRole('SUPER_ADMIN')` - applies to every method that does not declare its own.


#### `/api/super-admin`

<sub>`com/crm/travelcrm/platform/console/SuperAdminMeController.java`</sub>

**Class default:** `hasRole('SUPER_ADMIN')` - applies to every method that does not declare its own.


#### `/api/super-admin`

<sub>`com/crm/travelcrm/platform/config/controller/PlatformConfigController.java`</sub>

**Class default:** `hasRole('SUPER_ADMIN')` - applies to every method that does not declare its own.


#### `/api/super-admin/accounts`

<sub>`com/crm/travelcrm/platform/console/SuperAdminAccountController.java`</sub>

**Class default:** `hasRole('SUPER_ADMIN')` - applies to every method that does not declare its own.


#### `/api/super-admin/analytics`

<sub>`com/crm/travelcrm/platform/analytics/controller/AnalyticsController.java`</sub>

**Class default:** `hasRole('SUPER_ADMIN')` - applies to every method that does not declare its own.


#### `/api/super-admin/announcements`

<sub>`com/crm/travelcrm/platform/announcement/controller/AnnouncementController.java`</sub>

**Class default:** `hasRole('SUPER_ADMIN')` - applies to every method that does not declare its own.


#### `/api/super-admin/audit-logs`

<sub>`com/crm/travelcrm/platform/audit/controller/PlatformAuditController.java`</sub>

**Class default:** `hasRole('SUPER_ADMIN')` - applies to every method that does not declare its own.


#### `/api/super-admin/export`

<sub>`com/crm/travelcrm/platform/ops/controller/DataExportController.java`</sub>

**Class default:** `hasRole('SUPER_ADMIN')` - applies to every method that does not declare its own.


#### `/api/super-admin/invites`

<sub>`com/crm/travelcrm/platform/console/SuperAdminInviteController.java`</sub>

**Class default:** `hasRole('SUPER_ADMIN')` - applies to every method that does not declare its own.


#### `/api/super-admin/marketplace`

<sub>`com/crm/travelcrm/hotelmarketplace/catalog/controller/PlatformHotelCatalogAdminController.java`</sub>

**Class default:** `hasRole('SUPER_ADMIN')` - applies to every method that does not declare its own.


#### `/api/super-admin/marketplace/bookings`

<sub>`com/crm/travelcrm/hotelmarketplace/voucher/controller/MarketplaceVoucherAdminController.java`</sub>

**Class default:** `hasRole('SUPER_ADMIN')` - applies to every method that does not declare its own.


#### `/api/super-admin/marketplace/bookings`

<sub>`com/crm/travelcrm/hotelmarketplace/booking/controller/MarketplaceBookingAdminController.java`</sub>

**Class default:** `hasRole('SUPER_ADMIN')` - applies to every method that does not declare its own.


#### `/api/super-admin/marketplace/commissions`

<sub>`com/crm/travelcrm/hotelmarketplace/commission/controller/MarketplaceCommissionAdminController.java`</sub>

**Class default:** `hasRole('SUPER_ADMIN')` - applies to every method that does not declare its own.


#### `/api/super-admin/me/mfa`

<sub>`com/crm/travelcrm/platform/console/SuperAdminMfaController.java`</sub>

**Class default:** `hasRole('SUPER_ADMIN')` - applies to every method that does not declare its own.


#### `/api/super-admin/notifications`

<sub>`com/crm/travelcrm/platform/notification/controller/PlatformNotificationController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `GET /api/super-admin/notifications` | `hasRole('SUPER_ADMIN')` |
| `GET /api/super-admin/notifications/unread-count` | `hasRole('SUPER_ADMIN')` |
| `PUT /api/super-admin/notifications/{publicId}/read` | `hasRole('SUPER_ADMIN')` |
| `PUT /api/super-admin/notifications` | `hasRole('SUPER_ADMIN')` |
| `DELETE /api/super-admin/notifications/{publicId}` | `hasRole('SUPER_ADMIN')` |

#### `/api/super-admin/plans`

<sub>`com/crm/travelcrm/platform/subscription/controller/PlanController.java`</sub>

**Class default:** `hasRole('SUPER_ADMIN')` - applies to every method that does not declare its own.


#### `/api/super-admin/subagent-license-requests`

<sub>`com/crm/travelcrm/subagent/license/controller/SubAgentLicenseAdminController.java`</sub>

**Class default:** `hasRole('SUPER_ADMIN')` - applies to every method that does not declare its own.


#### `/api/super-admin/subscriptions`

<sub>`com/crm/travelcrm/platform/subscription/controller/SubscriptionController.java`</sub>

**Class default:** `hasRole('SUPER_ADMIN')` - applies to every method that does not declare its own.


#### `/api/super-admin/tenants`

<sub>`com/crm/travelcrm/tenent/controller/TenantController.java`</sub>

**Class default:** `hasRole('SUPER_ADMIN')` - applies to every method that does not declare its own.


#### `/api/super-admin/tenants`

<sub>`com/crm/travelcrm/platform/entitlement/controller/FeatureFlagController.java`</sub>

**Class default:** `hasRole('SUPER_ADMIN')` - applies to every method that does not declare its own.


#### `/api/super-admin/upgrade-requests`

<sub>`com/crm/travelcrm/platform/subscription/upgrade/controller/UpgradeRequestAdminController.java`</sub>

**Class default:** `hasRole('SUPER_ADMIN')` - applies to every method that does not declare its own.


#### `/api/super-admin/usage`

<sub>`com/crm/travelcrm/platform/usage/controller/UsageController.java`</sub>

**Class default:** `hasRole('SUPER_ADMIN')` - applies to every method that does not declare its own.


#### `/api/super-admin/users`

<sub>`com/crm/travelcrm/platform/user/controller/PlatformUserController.java`</sub>

**Class default:** `hasRole('SUPER_ADMIN')` - applies to every method that does not declare its own.


#### `/api/tasks`

<sub>`com/crm/travelcrm/task/controller/TaskController.java`</sub>

**Class default:** `hasAuthority('TASK_READ')` - applies to every method that does not declare its own.

| Endpoint | Required authority |
|---|---|
| `POST /api/tasks` | `hasAuthority('TASK_CREATE')` |
| `GET /api/tasks/stats` | `hasAuthority('CRM_FULL')` |
| `GET /api/tasks/workload` | `hasAuthority('CRM_FULL')` |
| `PUT /api/tasks/{publicId}` | `hasAuthority('TASK_UPDATE')` |
| `PATCH/PUT /api/tasks/{publicId}/status` | `hasAuthority('TASK_UPDATE')` |
| `PATCH/PUT /api/tasks/{publicId}/complete` | `hasAuthority('TASK_UPDATE')` |
| `POST /api/tasks/{publicId}/logs` | `hasAuthority('TASK_UPDATE')` |
| `DELETE /api/tasks/{publicId}` | `hasAuthority('TASK_DELETE')` |

#### `/api/tax-rates`

<sub>`com/crm/travelcrm/company/controller/TaxRateController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `GET /api/tax-rates` | `isAuthenticated()` |
| `GET /api/tax-rates/active` | `isAuthenticated()` |
| `POST /api/tax-rates` | `hasAuthority('SETTINGS_MANAGE')` |
| `DELETE /api/tax-rates/{publicId}` | `hasAuthority('SETTINGS_MANAGE')` |

#### `/api/testimonials`

<sub>`com/crm/travelcrm/master/testimonial/TestimonialController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `GET /api/testimonials` | `isAuthenticated()` |
| `GET /api/testimonials/{id}` | `isAuthenticated()` |
| `POST /api/testimonials` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `PUT /api/testimonials/{id}` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `DELETE /api/testimonials/{id}` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `POST /api/testimonials/upload-image` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |

#### `/api/trash`

<sub>`com/crm/travelcrm/trash/TrashController.java`</sub>

**Class default:** `hasAuthority('TRASH_VIEW')` - applies to every method that does not declare its own.

| Endpoint | Required authority |
|---|---|
| `POST /api/trash/{type}/{publicId}/restore` | `hasAuthority('TRASH_RESTORE')` |
| `DELETE /api/trash/{type}/{publicId}` | `hasAuthority('TRASH_DELETE')` |

#### `/api/users`

<sub>`com/crm/travelcrm/auth/controller/UserController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `POST /api/users` | `hasAuthority('USER_CREATE')` |
| `GET /api/users` | `hasAuthority('USER_READ')` |
| `GET /api/users/stats` | `hasAuthority('USER_READ')` |
| `GET /api/users/search` | `hasAuthority('USER_READ')` |
| `GET /api/users/check-username` | `hasAuthority('USER_READ')` |
| `GET /api/users/dropdown` | `hasAuthority('USER_READ')` |
| `GET /api/users/{publicId}` | `hasAuthority('USER_READ')` |
| `PUT /api/users/{publicId}` | `hasAuthority('USER_UPDATE')` |
| `DELETE /api/users/{publicId}` | `hasAuthority('USER_DELETE')` |
| `PATCH /api/users/{publicId}/toggle-status` | `hasAuthority('USER_UPDATE')` |
| `POST /api/users/{publicId}/reset-password` | `hasAuthority('USER_UPDATE')` |
| `GET /api/users/available` | `hasAuthority('CRM_FULL')` |

#### `/api/users/{publicId}/permissions`

<sub>`com/crm/travelcrm/permission/controller/UserPermissionController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `GET /api/users/{publicId}/permissions` | `hasAuthority('USER_READ')` |
| `PUT /api/users/{publicId}/permissions` | `hasAuthority('USER_UPDATE')` |

#### `/api/v1/countries`

<sub>`com/crm/travelcrm/master/geography/controller/CountryController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `GET /api/v1/countries` | `isAuthenticated()` |
| `GET /api/v1/countries/{countryId}` | `isAuthenticated()` |
| `POST /api/v1/countries` | `hasAnyAuthority('PLATFORM_ADMIN')` |
| `PUT /api/v1/countries/{countryId}` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `DELETE /api/v1/countries/{countryId}` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |

#### `/api/vehicles`

<sub>`com/crm/travelcrm/master/vehicle/VehicleController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `POST /api/vehicles` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `GET /api/vehicles` | `isAuthenticated()` |
| `GET /api/vehicles/{publicId}` | `isAuthenticated()` |
| `PUT /api/vehicles/{publicId}` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `DELETE /api/vehicles/{publicId}` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `POST /api/vehicles/upload-image` | `hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')` |
| `GET /api/vehicles/filter` | `isAuthenticated()` |
| `GET /api/vehicles/search` | `isAuthenticated()` |

#### `/api/vendors`

<sub>`com/crm/travelcrm/vendor/controller/VendorController.java`</sub>

| Endpoint | Required authority |
|---|---|
| `GET /api/vendors/statuses` | `hasAuthority('VENDOR_READ')` |
| `GET /api/vendors` | `hasAuthority('VENDOR_READ')` |
| `GET /api/vendors/{id}` | `hasAuthority('VENDOR_READ')` |
| `GET /api/vendors/code/{code}` | `hasAuthority('VENDOR_READ')` |
| `POST /api/vendors` | `hasAuthority('VENDOR_CREATE')` |
| `PUT /api/vendors/{id}` | `hasAuthority('VENDOR_UPDATE')` |
| `PATCH /api/vendors/{id}/status` | `hasAuthority('VENDOR_UPDATE')` |
| `PATCH /api/vendors/{id}/payment` | `hasAuthority('VENDOR_UPDATE')` |
| `DELETE /api/vendors/{id}` | `hasAuthority('VENDOR_DELETE')` |
| `GET /api/vendors/filter` | `hasAuthority('VENDOR_READ')` |
| `GET /api/vendors/search` | `hasAuthority('VENDOR_READ')` |
| `GET /api/vendors/type/{type}` | `hasAuthority('VENDOR_READ')` |
| `GET /api/vendors/stats` | `hasAuthority('VENDOR_READ')` |
| `GET /api/vendors/{id}/bookings` | `hasAuthority('VENDOR_READ')` |
| `POST /api/vendors/{id}/rating` | `hasAuthority('VENDOR_READ')` |
| `GET /api/vendors/export` | `hasAuthority('VENDOR_READ')` |
| `POST /api/vendors/{id}/send-email` | `hasAuthority('VENDOR_UPDATE')` |


---

## Appendix B — permission key reference

The 78 keys as served by `GET /api/permissions/catalog`, in declaration order (which is the order the FE grid renders).

| Key | Module (catalog group) | Label shown in the UI |
|---|---|---|
| `LEAD_READ` | Leads | View leads |
| `LEAD_CREATE` | Leads | Create lead |
| `LEAD_UPDATE` | Leads | Edit lead |
| `LEAD_DELETE` | Leads | Delete lead |
| `LEAD_PERMANENT_DELETE` | Leads | Remove lead when cancelling a booking (moves to Trash) |
| `LEAD_CLAIM` | Leads | Claim a new lead / override its assigned owner |
| `LEAD_REASSIGN_LOCKED` | Leads | Reassign or reopen a lead after it has been contacted |
| `BOOKING_READ` | Bookings | View bookings |
| `BOOKING_CREATE` | Bookings | Create booking |
| `BOOKING_UPDATE` | Bookings | Edit booking |
| `BOOKING_CANCEL` | Bookings | Cancel booking |
| `BOOKING_DELETE` | Bookings | Delete booking |
| `BOOKING_PROFIT_READ` | Bookings | View supplier costs, expenses and booking profit |
| `BOOKING_REFUND` | Bookings | Refund a booking / override cancellation charges |
| `CANCELLATION_POLICY_MANAGE` | Bookings | Manage cancellation policies |
| `CUSTOMER_READ` | Customers | View customers |
| `CUSTOMER_CREATE` | Customers | Create customer |
| `CUSTOMER_UPDATE` | Customers | Edit customer |
| `CUSTOMER_DELETE` | Customers | Delete customer |
| `QUOTATION_READ` | Quotations | View quotations |
| `QUOTATION_CREATE` | Quotations | Create quotation |
| `QUOTATION_UPDATE` | Quotations | Edit quotation |
| `QUOTATION_DELETE` | Quotations | Delete quotation |
| `VENDOR_READ` | Vendors | View vendors |
| `VENDOR_CREATE` | Vendors | Create vendor |
| `VENDOR_UPDATE` | Vendors | Edit vendor |
| `VENDOR_DELETE` | Vendors | Delete vendor |
| `REMINDER_READ` | Reminders | View reminders |
| `REMINDER_CREATE` | Reminders | Create reminder |
| `REMINDER_UPDATE` | Reminders | Edit reminder |
| `REMINDER_DELETE` | Reminders | Delete reminder |
| `TASK_READ` | Tasks | View tasks & calendar |
| `TASK_CREATE` | Tasks | Create task / calendar event |
| `TASK_UPDATE` | Tasks | Edit task / move on board |
| `TASK_DELETE` | Tasks | Delete task |
| `FLEET_READ` | Fleet | View fleet vehicles, drivers, trips & logs |
| `FLEET_CREATE` | Fleet | Add fleet vehicles, drivers, trips & logs |
| `FLEET_UPDATE` | Fleet | Edit fleet records / trip lifecycle (start, close, cancel) |
| `FLEET_DELETE` | Fleet | Delete fleet records |
| `FLEET_MONEY_READ` | Fleet | View fleet expenses, driver cash balances & settlements |
| `FLEET_MONEY_SETTLE` | Fleet | Settle a trip: approve costs, reconcile driver cash, record payout |
| `FLEET_PERIOD_CLOSE` | Fleet | Lock a fleet accounting period (financial year / month) |
| `MASTER_READ` | Master Data | View master data |
| `MASTER_MANAGE` | Master Data | Create / edit / delete master data |
| `HOTEL_MARKETPLACE_VIEW` | Hotel Marketplace | Search the platform hotel catalog |
| `HOTEL_MARKETPLACE_SYNC_MASTER` | Hotel Marketplace | Import a platform hotel into your hotel master |
| `HOTEL_MARKETPLACE_BOOK` | Hotel Marketplace | Send a hotel booking request to the platform |
| `HOTEL_MARKETPLACE_CANCEL` | Hotel Marketplace | Cancel or withdraw a marketplace hotel booking |
| `MARKETPLACE_PAYABLE_READ` | Hotel Marketplace | View what you owe the platform for a marketplace booking |
| `USER_READ` | User Management | View users |
| `USER_CREATE` | User Management | Create user |
| `USER_UPDATE` | User Management | Edit user / manage permissions |
| `USER_DELETE` | User Management | Delete user |
| `REPORT_VIEW` | Reports | View reports |
| `MARKETING_READ` | Marketing | View segments, campaigns, drips & automations |
| `MARKETING_CREATE` | Marketing | Create segments, campaigns & drip sequences |
| `MARKETING_UPDATE` | Marketing | Edit segments, campaigns, drips & automations |
| `MARKETING_DELETE` | Marketing | Delete segments, campaigns & drip sequences |
| `MARKETING_SEND` | Marketing | Send campaigns, activate drips & send test messages |
| `TRASH_VIEW` | Trash | View trashed records |
| `TRASH_RESTORE` | Trash | Restore trashed records |
| `TRASH_DELETE` | Trash | Permanently delete a trashed record (irreversible) |
| `ACCOUNTING_INVOICE_READ` | Accounting | View GST invoices & tax masters |
| `ACCOUNTING_INVOICE_MANAGE` | Accounting | Issue / cancel GST invoices |
| `ACCOUNTING_TDS_READ` | Accounting | View vendor bills, TDS & TCS |
| `ACCOUNTING_TDS_MANAGE` | Accounting | Manage vendor bills, payments & TDS/TCS |
| `ACCOUNTING_SETTINGS_MANAGE` | Accounting | Manage GST settings & tax-rate masters |
| `COMM_READ` | Communication | View conversations, messages and call history |
| `COMM_SEND` | Communication | Send messages and reply on customer channels |
| `COMM_ASSIGN` | Communication | Assign, snooze and close conversations |
| `COMM_CALL_LOG` | Communication | Log a call and set its outcome / follow-up |
| `COMM_CHAT` | Communication | Use internal team chat |
| `COMM_NOTE_PRIVATE_READ` | Communication | Read other users' private notes |
| `COMM_RECORDING_READ` | Communication | Listen to call recordings |
| `COMM_TEMPLATE_MANAGE` | Communication | Create and edit message templates |
| `COMM_WORKFLOW_MANAGE` | Communication | Configure automated / scheduled messages |
| `COMM_REPORT_VIEW` | Communication | View communication reports & analytics |
| `SETTINGS_MANAGE` | Settings | Manage company settings |


