# Implementation Plan — Kanban Leads, Vehicle Diary, AI Features

> Status: **PLAN ONLY — no code written yet.** Review and approve module by module.
> Date: 2026-07-03

---

## Part 1 — Discovery Report (what already exists)

### Tech stack

| Layer | Finding |
|---|---|
| Backend | Spring Boot 3.5.3, Java 21, package `com.crm.travelcrm`, feature-module layout (one folder per domain: `lead/`, `booking/`, `master/`, `reminder/`, `ai/`, …) |
| Database | PostgreSQL, schema managed by `spring.jpa.hibernate.ddl-auto=update` — **no Flyway/Liquibase**. There are no migration files to review; new tables appear from `@Entity` classes. |
| ORM | Hibernate/JPA + MapStruct mappers (`componentModel = "spring"`), Lombok |
| Auth | JWT (staff realm + separate Traveler Portal realm). `TenantContext` ThreadLocal + Hibernate `@Filter("tenantFilter")` |
| Multi-tenancy | **Already exists.** Every tenant entity extends `BaseTenantEntity` (`tenantId` auto-stamped, filter-scoped). API uses `publicId` (UUID), never internal `Long id`. |
| Responses | `ApiResponse<T>` / `PagedApiResponse<T>` envelopes, `GlobalExceptionHandler` |
| Frontend | `E:\CRM_PROJECT\travelcrmfrontend` — React **19** + Vite + Tailwind v4, react-router-dom v7, axios, react-hook-form, recharts, lucide-react. No TypeScript. Feature folders (`admin/`, `masters/`, `bookings/`, `services/` per-domain API clients). Token in `localStorage["token"]`. |
| RBAC | **Already exists** — `permission/` module: `Permission` enum catalog, `UserPermission`, `PermissionTemplate`, `EffectivePermissionResolver`, `ScopeResolver` (scope = own vs all). Plus `lead/service/LeadAccessGuard`. **Reuse this; do not build a parallel roles system.** |
| Audit/activity | `activity/` module (`ActivityLog`, `ActivityAuditAspect`), Hibernate Envers on `Booking` |
| Notifications | Plug-and-play `notification/` module (`NotifyEvent`, IN_APP + SSE) — use for all alerts |
| Reminders | `reminder/` module with scheduler — pattern to copy for document-expiry alerts |
| Soft delete | Universal Trash convention (`deletedAt`/`deletedBy`, 30-day purge) |

### Critical overlap findings — these change the requested scope

1. **Module A (Kanban Leads) already ~70% exists.** `lead/` module has:
   - `Lead`, `LeadLog` (activity log), `LeadItinerary` entities; `LeadStage` enum:
     `NEW_LEAD → CONTACTED → FOLLOW_UP → QUALIFIED → PROPOSAL_SENT → CONVERTED / REOPENED / LOST`
   - Endpoints already present: `GET /api/leads/board` (grouped by stage), `PATCH /api/leads/{publicId}/stage`, full CRUD, logs, per-user workload/stage stats.
   - **Gap:** stages are a fixed Java enum (not customizable per agency), no dedicated `stage_history` table (stage changes may only appear as logs), stale-lead ("no activity in X days") flagging, and the frontend Kanban drag-and-drop board.
   - **Decision needed (see Open Questions):** keep the existing enum stages (cheap) vs. migrate to per-tenant configurable stage table (expensive, touches board/stats/reports).

2. **Module B (Vehicle Diary):** `master/vehicle/` exists but is only a quotation *master* (name, type, capacity, image, city). The Vehicle Diary is operationally different → build a **new `fleet/` module**, keep the existing master untouched; optionally link a fleet vehicle to the master by ID.

3. **Module C (AI) already has infrastructure** — `ai/` module ("Disha"): Spring AI with **Ollama** (`qwen3:8b`, local), chat sessions, audit log (`AiAuditLog`), tool-calling over Leads/Bookings/Quotations/Reminders/Dashboard, pgvector starter present.
   - **Decision needed:** the request says "Anthropic API (Claude)", but the project standard is Spring AI + local Ollama. Spring AI has an Anthropic starter, so the clean path is: keep the `ChatModel` abstraction and add features provider-agnostically; switching Ollama → Anthropic is then a dependency + properties change.


---

## Part 2 — Module A: Kanban Lead Pipeline (extend `lead/`, don't rebuild)

### Backend work

1. **`LeadStageHistory` entity** (new table `lead_stage_history`, extends `BaseTenantEntity`)
   - `leadId` (logical FK), `fromStage`, `toStage`, `movedByUserId`, `movedAt`, `note`
   - Written inside the existing `PATCH /{publicId}/stage` service path (transactional with the stage update).
   - `GET /api/leads/{publicId}/stage-history` endpoint.
2. **Board filters** — extend existing `GET /api/leads/board` with query params: `assignedTo` (user publicId), `source`, `stage`, `fromDate`, `toDate`. Implement via JPA Specifications (same pattern as `booking/` and `ReminderSpecification`).
3. **Stale-lead flagging**
   - Tenant setting `lead.stale-after-days` (default 3) — store in existing settings mechanism (`notificationsetting`-style config entity or `tenent` settings; confirm at build time).
   - Computed field `stale: boolean` + `lastActivityAt` on `LeadResponseDto`/`LeadBoardColumnDto` (derived from latest `LeadLog`).
   - Optional scheduled job (copy `ReminderScheduler` pattern) raising a `NotifyEvent` to the assigned agent for stale leads.
4. **Card payload** — ensure board DTO carries: customer name, contact, destination/package interest, budget, assigned agent name, source, `lastActivityAt`, `nextFollowUpDate`.
5. **Stage customization (pending decision)** — Option 1 (recommended for now): keep enum, allow per-tenant *hiding/relabeling* via a small `tenant_stage_config` table consumed by the frontend. Option 2: full dynamic stage table — defer unless truly needed.

### Frontend work (`travelcrmfrontend`)

- New `src/leads/board/` — `LeadBoard.jsx` (columns from `/api/leads/board`), `LeadCard.jsx`, `BoardFilters.jsx`.
- Drag-and-drop: **`@hello-pangea/dnd`** (verify React 19 support at implementation time; fallback `@dnd-kit/core`). On drop → `PATCH /api/leads/{publicId}/stage`, optimistic UI with rollback on error.
- Stale leads get a visual flag (e.g. amber dot + "No activity for N days").
- API client `src/services/leadBoardService.js` using shared `axiosInstance`.

### RBAC
Reuse `LeadAccessGuard` + `ScopeResolver`: agents (scope=OWN) see only assigned leads on the board; admins see all. No new roles.

---

## Part 3 — Module B: Vehicle Diary (new `fleet/` module)

New backend package `com.crm.travelcrm.fleet/` — fully self-contained (controllers/services/repos/DTOs/mappers inside it), linked to the rest of the app **only by logical IDs** (`bookingId`, `customerId`), per the project's cross-aggregate convention (validated via `findByIdAndTenantId` before persist).

### Entities (all extend `BaseTenantEntity`; API by `publicId`; soft-delete/Trash applies)

| Entity | Table | Key fields |
|---|---|---|
| `FleetVehicle` | `fleet_vehicles` | `vehicleNumber` (unique per tenant), `type`, `make`, `model`, `year`, `seatingCapacity`, `ownerType` enum (OWN/VENDOR/RENTED), `vendorId` (nullable logical FK to `vendors`), `insuranceExpiry`, `rcExpiry`, `permitExpiry`, `pucExpiry`, `status` enum (AVAILABLE/ON_TRIP/MAINTENANCE/OUT_OF_SERVICE), optional `masterVehicleId` link |
| `Driver` | `fleet_drivers` | `name`, `phone`, `licenseNumber`, `licenseExpiry`, `status` enum |
| `Trip` | `fleet_trips` | `vehicleId`, `driverId`, `bookingId` (nullable — validated tenant-scoped), `startDatetime`, `endDatetime`, `startOdometer`, `endOdometer`, `distanceKm` (derived, persisted on close), `routeFrom`, `routeTo`, `purpose`, `fuelCost`, `tollCost`, `driverAllowance`, `remarks`, `status` (PLANNED/ONGOING/COMPLETED/CANCELLED) |
| `FuelLog` | `fleet_fuel_logs` | `vehicleId`, `date`, `liters`, `cost`, `odometer` |
| `MaintenanceLog` | `fleet_maintenance_logs` | `vehicleId`, `serviceDate`, `serviceType`, `cost`, `vendorName`, `nextServiceDueDate`, `nextServiceDueKm` |

**Document alerts:** do **not** persist a `document_alerts` table. Instead, copy the proven `DocumentExpiryReminderScheduler` pattern from `portal/`: per-tenant scheduled job, configurable thresholds (`app.fleet.expiry-reminder-days=30,15,7`), idempotent threshold markers, delivery via `NotifyEvent` to admins. Plus a live `GET /api/fleet/dashboard` that computes upcoming expiries on demand (no stale rows).

### Endpoints (namespace `/api/fleet/**`)

- `vehicles` CRUD + `PATCH /{id}/status`; `drivers` CRUD
- `trips` CRUD; `POST /trips/{id}/close` (sets endOdometer/end time, computes distance, flips vehicle back to AVAILABLE)
- `fuel-logs`, `maintenance-logs` CRUD (nested list by vehicle)
- `GET /dashboard` — expiring docs (30/15/7), vehicles on trip, vehicles in maintenance
- Booking integration: `POST /api/fleet/trips/from-booking/{bookingPublicId}` — creates a trip pre-linked to the booking (frontend adds an "Assign vehicle" action on the booking detail page)

### Business rules
- Vehicle status transitions: starting a trip → ON_TRIP; closing → AVAILABLE; open maintenance log without end → MAINTENANCE.
- `endOdometer >= startOdometer`; reject overlapping ONGOING trips for same vehicle or driver.

### Frontend
New `src/fleet/`: `VehicleList.jsx` / `VehicleDetail.jsx` (tabs: trips, fuel, maintenance, documents), `TripForm.jsx`, `FuelLogForm.jsx`, `MaintenanceForm.jsx`, `FleetDashboard.jsx` (recharts + expiry alert cards), `src/services/fleetService.js`. "Assign vehicle" button on booking detail.

### RBAC
Add `FLEET_*` entries to the existing `Permission` enum catalog; agents with scope=OWN see trips where they are the creator/assignee, admins see all.

---

## Part 4 — Module C: AI Features (extend `ai/`, provider-agnostic)

Keep everything behind the existing `ai/` module and Spring AI `ChatModel` abstraction. Add a new `AiActionService` (single entry point beside `ChatOrchestrationService`) so provider/model/keys stay in one config (`AiConfig`). All four features are **explicit user-triggered endpoints** (no background calls), all logged to the existing `AiAuditLog`.

| Feature | Endpoint | Inputs | Notes |
|---|---|---|---|
| 1. Itinerary draft | `POST /api/ai/actions/itinerary-draft` | destination, days, budget, travelers | Returns structured day-wise draft; agent can copy into `LeadItinerary` |
| 2. Follow-up message draft | `POST /api/ai/actions/leads/{publicId}/follow-up-draft` | tone (WHATSAPP/EMAIL) | Context = lead stage + latest `LeadLog` entries (tenant-scoped fetch through `LeadAccessGuard`) |
| 3. Lead scoring | `GET /api/ai/actions/leads/{publicId}/score` | — | **Rule-based score computed in Java** (response speed from log timestamps, budget filled?, stage progression velocity from `LeadStageHistory`) + LLM writes only the human-readable explanation. Not a black box. |
| 4. Lead history summary | `POST /api/ai/actions/leads/{publicId}/summarize` | — | One-paragraph handoff brief from `LeadLog` + stage history |

**Provider decision (Open Question #2):** default plan is to add `spring-ai-starter-model-anthropic` alongside Ollama, selected by property (`app.ai.provider=anthropic|ollama`), key via env var `ANTHROPIC_API_KEY` — never hardcoded. Model configurable (`spring.ai.anthropic.chat.options.model`).

**Frontend:** buttons ("Draft itinerary", "Suggest follow-up", "Score lead", "Summarize") on the lead detail/board card menu; each with loading spinner, error toast, and an editable preview before the agent uses the text. `src/services/aiActionService.js`.

---

## Part 5 — Cross-cutting / Step 3 items

1. **RBAC** — reuse `permission/` module everywhere (new `Permission` enum entries: `FLEET_VIEW/MANAGE`, `AI_ACTIONS`, existing lead perms cover Module A). No parallel roles system.
2. **README/docs** — add a short data-model + extension section per module in `docs/` and update `CLAUDE.md` module layout table.
3. **New environment variables / properties**
   - `ANTHROPIC_API_KEY` (if Anthropic chosen), `app.ai.provider`
   - `app.fleet.expiry-reminder-days=30,15,7`
   - `lead.stale-after-days` default (tenant-overridable)
4. **Migrations** — N/A: project intentionally uses `ddl-auto=update` and forbids Flyway (per CLAUDE.md). New tables are created from entities on startup. If you want review-before-apply, I can generate the DDL preview (`javax.persistence.schema-generation.scripts`) for inspection without applying.

## Build order & checkpoints

1. **Module A** (backend gaps → frontend board) → pause for review
2. **Module B** (`fleet/` backend → frontend) → pause for review
3. **Module C** (AI actions) → pause for review
4. Step-3 wrap-up (perms audit, docs, env list)

## Open questions (please answer before Step 2)

1. **Lead stages:** keep the existing fixed enum (`NEW_LEAD…LOST`) with per-tenant relabel/hide config, or fully dynamic per-agency stage tables? (Recommended: keep enum for now.)
2. **AI provider:** Anthropic API as requested, existing local Ollama, or both switchable by property? (Recommended: both, Anthropic default.)
3. **Frontend location:** confirm the frontend to modify is `E:\CRM_PROJECT\travelcrmfrontend` (CLAUDE.md still points at the old `D:\CRM PROJECT\frontend`).
4. **Kanban DnD library:** `@hello-pangea/dnd` vs `@dnd-kit` (React 19 project — dnd-kit is the safer bet if hello-pangea lags on React 19).