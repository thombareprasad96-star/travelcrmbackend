# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## Commands

```bash
# Build
./mvnw clean package -DskipTests

# Run
./mvnw spring-boot:run

# Run tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=TravelcrmApplicationTests

# Compile only (fast check)
./mvnw compile
```

On Windows use `mvnw.cmd` instead of `./mvnw`.

Frontend lives at `D:\CRM PROJECT\travelcrmfrontend` (Vite + React + Tailwind).

```bash
# Frontend dev server
cd "D:\CRM PROJECT\travelcrmfrontend"
npm run dev        # runs on http://localhost:5173
```

---

## Backend Architecture

Spring Boot 3.5.3 / Java 21 REST API for a multi-tenant Travel CRM. PostgreSQL is the primary database; Redis is used for OTP storage (currently commented out). Logging is **Log4j2 only** — Logback is globally excluded from all starters.

### Multi-tenancy model

Every tenant-scoped entity extends `BaseTenantEntity` (which extends `BaseEntity`). Tenant isolation is enforced at two layers:

1. **`TenantEntityListener`** (`@PrePersist`/`@PreUpdate`) — reads from `TenantContext` (a `ThreadLocal<Long>`) and auto-stamps `tenantId` on persist, blocks cross-tenant updates.
2. **Hibernate `@Filter("tenantFilter")`** on `BaseTenantEntity` — when enabled on a session, all queries automatically add `WHERE tenant_id = :tenantId`.

`TenantContext` is populated by `JwtAuthFilter` from the JWT claim `tenantId` and **must** be cleared in the `finally` block to prevent thread-pool leaks.

### Authentication & JWT

Two distinct principal types share the same filter chain:
- **`SuperAdmin`** — single instance, no `tenantId` in JWT, loaded by `SuperAdminDetailsService`.
- **`User`** — tenant-scoped, `tenantId` embedded in JWT claims, loaded by `UserDetailsServiceImpl`. Implements `UserDetails` directly — cast `auth.getPrincipal()` to `User` to get `user.getId()` (Long).

`JwtAuthFilter` routes loading to the correct service based on the `role` claim. `SecurityConfig` registers both as separate `DaoAuthenticationProvider` beans.

CORS is configured to allow `http://localhost:5173` and `http://localhost:5174` (dev). Add the production URL in `SecurityConfig.corsConfigurationSource()` before deploying.

`GET /api/notifications/stream` is the only non-auth endpoint beyond `/api/auth/**` — permitted because `EventSource` cannot set headers; token is validated manually in `NotificationController`.

### Entity base classes

| Class | Purpose |
|---|---|
| `BaseEntity` | `id` (long primitive PK), `publicId` (UUID), audit fields, soft delete via `deletedAt`/`deletedBy` |
| `BaseTenantEntity` | extends `BaseEntity`, adds `tenantId`, carries the Hibernate `@Filter` |

**Always use `publicId` (UUID) in API responses — never expose the internal `Long id`.**

### Module layout

```
auth/             Login, registration, JWT, SecurityConfig, two UserDetailsService impls
booking/          Booking CRUD, CSV export, voucher send, filtering via JPA Specifications
lead/             Lead pipeline (LeadStage, LeadType, LeadSource), itineraries. Lead `services` are stored via @ElementCollection (lead_services join table), NOT a comma-separated string
fleet/            Vehicle Diary — operational fleet: vehicles, drivers, trips (PLANNED→ONGOING→COMPLETED lifecycle syncs vehicle status), fuel/maintenance logs, document-expiry scheduler + alerts. Separate from master/vehicle (see docs/FLEET_MODULE.md). Permissions: FLEET_*
master/
  geography/      Country → Destination → City (cascading hierarchy)
  hotel/          Hotel, RoomType, MealPlan (nested under hotel)
  sightseeing/    Sightseeing attractions (real @ManyToOne City FK: fk_sightseeing_city)
  vehicle/        Vehicle master (extends BaseTenantEntity: publicId + audit + soft-delete; endpoints keyed by publicId)
  airline/        Airline master
  cruise/         Cruise + CruiseRoomType
  addon/          Add-on services
  dropdown/       MasterDropdownController — unified /api/masters/dropdown/** endpoints
tenent/           Tenant lifecycle management (note: package is spelled "tenent")
notification/     Plug-and-play notification module (see full section below)
otp/              Shared plug-and-play OTP module — generate/store/verify/deliver (see OTP module)
portal/           Customer-facing Traveler Portal — SEPARATE auth realm (see Traveler Portal)
trash/            Universal soft-delete → Trash → 30-day auto-purge (see Trash convention notes)
common/           BaseEntity, BaseTenantEntity, TenantContext, ApiResponse wrappers, GlobalExceptionHandler
```

### Response envelope

All endpoints return `ApiResponse<T>` (single item) or `PagedApiResponse<T>` (paginated). Do not bypass these wrappers.

`ApiResponse.success(message)` / `ApiResponse.success(message, data)` / `ApiResponse.success(message, data, statusCode)`
`PagedApiResponse.of(message, List<T>, PaginationMeta)` — note: list goes in `data` field, not `content`.

### Annotation processor order

The Maven compiler plugin explicitly orders annotation processors: **MapStruct → Lombok → lombok-mapstruct-binding**. This order is required — do not change it. MapStruct mappers must use `@Mapper(componentModel = "spring")` to be Spring beans.

When MapStruct tries to auto-map a `String` field to an `@Entity` field, add `@Mapping(target = "fieldName", ignore = true)` to the mapper method and resolve the FK manually in the service.

### Hibernate Envers

`Booking` is annotated `@Audited`. Envers creates `bookings_aud` and `revinfo` audit tables automatically. Use `@NotAudited` on fields that should be excluded (e.g., `@ElementCollection` services list).

### OTP module (`otp/`)

Shared, **plug-and-play** OTP module (the old commented-out Redis/Twilio strategy code was removed). One facade — `OtpService`:

```java
otpService.request(OtpPurpose.PORTAL_LOGIN, identifier, OtpChannel.AUTO);   // generate+store(hashed)+deliver
OtpResult r = otpService.verify(OtpPurpose.PORTAL_LOGIN, identifier, code); // SUCCESS/INVALID/EXPIRED/TOO_MANY_ATTEMPTS/NOT_FOUND
```

SOLID collaborators, each independently swappable:
- `OtpGenerator` ← `NumericOtpGenerator` (default).
- `OtpStore` (SPI) ← `InMemoryOtpStore` (default, single-node). For multi-node add a Redis/DB-backed `OtpStore` bean marked `@Primary`.
- `OtpDeliverySender` strategy (`Sms`/`Email`/`WhatsApp`, **logging stubs**) routed by `OtpSenderResolver` (a factory; `AUTO` ⇒ EMAIL if the destination has `@`, else SMS). Wire a real provider by dropping in a bean — nothing else changes.

Codes are **hashed** in the store (BCrypt), attempt-capped, cooldown-guarded and one-time-use. Config: `app.otp.*` (`length`, `ttl-seconds`, `max-attempts`, `resend-cooldown-seconds`). Reuse for any new flow by adding an `OtpPurpose` constant.

### Traveler Portal (`portal/`) — customer-facing realm

A self-service portal for the **end customer** (a traveler), a **strictly separate auth realm from staff** — never put a traveler into the staff `User`/`Role` world.

- **Namespace** `/api/portal/**` with its own `SecurityFilterChain` (`PortalSecurityConfig`, `@Order(1)`); the staff chain is `@Order(2)` and its `JwtAuthFilter` early-skips `/api/portal/**`.
- **Distinct JWT** — `PortalJwtUtil` signs with `portal.jwt.secret` (never `jwt.secret`) and stamps `typ=TRAVELER`/`aud=portal`. A staff token fails signature validation on the portal chain and vice-versa, so tokens can never cross realms.
- **Identity** — `TravelerAccount` (extends `BaseTenantEntity`) links to a `Customer` by internal `customerId`; provisioned lazily on first OTP request from an existing customer (no self-registration). Login is **OTP to the registered phone/email** via the shared `otp/` module — no passwords.
- **Principal** — `TravelerPrincipal` (a `Principal`, NOT a `User`) is set in the context; read it via `CurrentTraveler.require()`. Every portal query scopes by `principal.customerId()` — **object-level ownership; a foreign `publicId` returns 404, never data**.
- **Traveler-safe DTOs only** — hand-written mappers whitelist fields; never `vendorCost`, `netProfit`, internal notes, or another customer's data.
- **PII documents** — `TravelerDocument` bytes are stored in Postgres (`bytea`), **never a public CDN URL**; retrieval is only through the authenticated, ownership-checked `GET /api/portal/documents/{publicId}/file`. List + the reminder job use projections so blobs never load.
- **Payment** — `PortalPaymentInitiation` SPI with a `@ConditionalOnMissingBean` stub (returns `UNAVAILABLE`); a real gateway bean takes over with no portal changes.
- **Document-expiry reminders** — `DocumentExpiryReminderScheduler` runs per-tenant (set `TenantContext`, clear in `finally`), idempotent via a per-document threshold marker (`app`: `portal.document.expiry-reminder-days=60,30,7`). Delivery is `DocumentExpiryReminderSender`; the `@Primary` impl raises a `NotifyEvent` to tenant admins/managers (logging stub is the fallback).

Endpoints: `POST /api/portal/auth/request-otp|verify-otp`; `GET /api/portal/bookings[/{id}[/payment]]`; `POST|GET|DELETE /api/portal/documents` + `GET /{id}/file`; `POST /api/portal/payments/bookings/{id}/intent`.

---

## Master Entity Hierarchy

```
Country
  └── Destination  (country FK; global=true rows visible to all tenants)
        └── City
              ├── Hotel       (resolved via destinationId + city name string)
              │     ├── RoomType   (name, size, occupancy, bedType, description)
              │     └── MealPlan   (name, description, price)
              └── Sightseeing (real @ManyToOne City FK: fk_sightseeing_city; DTO still exposes destination/city as name strings)

Vehicle     (flat, tenant-scoped — extends BaseTenantEntity; PK column kept as vehicle_id via @AttributeOverride. API uses publicId)
Airline     (flat, tenant-scoped)
Cruise      (flat, tenant-scoped)
  └── CruiseRoomType
Addon       (flat, tenant-scoped, has active boolean)
```

### Vendor entity — split across secondary tables

`Vendor` (table `vendors`, tenant-scoped) maps one entity across **three** tables via
`@SecondaryTable`, keyed 1:1 on the vendor PK (`id`). The Java API is flat — every getter
(`vendor.getBankName()`, `vendor.getTotalBusiness()`, the `@Transient getOutstanding()`)
reads through transparently, so mappers/services/DTO/CSV treat it as one object:

| Table | Columns |
|-------|---------|
| `vendors` (primary) | core profile, contact, address, status, ratings, commission, notes |
| `vendor_bank_details` (`vendor_id` FK → `vendors.id`) | `bank_name`, `account_name`, `account_number`, `ifsc_code`, `upi_id`, `gst_number`, `pan_number` |
| `vendor_financials` (`vendor_id` FK → `vendors.id`) | `credit_limit`, `opening_balance`, `total_business`, `total_paid` |

Notes:
- Loading a `Vendor` joins both secondary tables (Hibernate fetches the whole entity). For
  large vendor **list** views that only need core fields, use a projection/DTO query instead
  of loading the full entity.
- To add a bank/financial column, put it on the `Vendor` field with
  `@Column(name = "...", table = "vendor_bank_details")` (or `vendor_financials`).

### Key field name decisions (match frontend exactly)

| Entity | Field stored as | Why |
|--------|----------------|-----|
| Hotel | `destinationId` (Long) + `city` (String) | FE sends name not cityId |
| Hotel | `contactPerson` (column: `contact_person`) | FE sends `contact`, mapped to this |
| RoomType | `size`, `occupancy`, `bedType`, `description` | FE does NOT send `capacity` or `price` |
| MealPlan | `name`, `description`, `price` | No `type` or `pricePerPerson` |
| Sightseeing | `@ManyToOne City` FK (`city_id`, `fk_sightseeing_city`) | FE sends `destination`+`city` names; service resolves them to the City FK at save time |
| HotelDto | `city` (String, city.name) | FE reads `city` not `cityName` |
| SightseeingDto | `destination`, `city` (Strings) | FE reads these not `destinationName`/`cityName` |

### Cascading dropdown endpoints

All under `/api/masters/dropdown/` (`MasterDropdownController`). Return `List<DropdownDto>` (`{value: Long, label: String}`).

| Endpoint | Filter param | Notes |
|----------|-------------|-------|
| `GET /countries` | — | all tenant countries |
| `GET /destinations` | `?countryId=` | active destinations |
| `GET /cities` | `?destinationId=` | cities under destination |
| `GET /hotels` | `?destinationId=` (optional) | |
| `GET /room-types` | `?hotelId=` | |
| `GET /meal-plans` | `?hotelId=` | |
| `GET /sightseeings` | `?destinationId=` (optional) | label = sightseeing.title |
| `GET /vehicles` | — | global+tenant |
| `GET /addons` | — | active only |
| `GET /airlines` | — | label = "Name (IATA)" |
| `GET /cruises` | — | |
| `GET /cruise-room-types` | `?cruiseId=` | |

Also: `GET /api/destinations/cities?destination={name}` — used by SightseeingService.js to load city dropdown by destination name.

### CityRepository — important derived query methods

```java
findByTenantIdAndDestinationIdAndNameIgnoreCase(tenantId, destinationId, name)
findByTenantIdAndDestination_NameIgnoreCaseAndNameIgnoreCase(tenantId, destName, cityName)
findByTenantIdAndDestination_NameIgnoreCase(tenantId, destName)
findByTenantIdAndDestinationIdOrderByNameAsc(tenantId, destinationId)
```

`CityController` exposes three route families: flat `/api/cities` (**what the frontend uses**),
nested `/api/v1/{countries|destinations}/{id}/cities`, and `/api/v1/cities/{id}`. Don't delete
the flat family as "unused". There is **no** `/api/geography/cities` route — despite the package
being `master/geography`, no mapping uses that prefix.

---

## Notification Module

Fully plug-and-play. Other modules fire events; this module handles everything else.

### Publishing a notification (from any module)

```java
applicationEventPublisher.publishEvent(
    NotifyEvent.builder()
        .type("LEAD_ASSIGNED")          // free-form string, define in your module
        .tenantId(tenantId)
        .recipientUserIds(List.of(userId))
        .title("Lead assigned to you")
        .message("Lead ABC was assigned to you")
        .referenceType("LEAD")
        .referencePublicId(lead.getPublicId())
        .channels(Set.of(DeliveryChannel.IN_APP))
        .build());
```

`channels` defaults to `IN_APP` only. `IN_APP` automatically SSE-pushes to any live browser tab.

### REST endpoints

Base: `/api/notifications`

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/` | Paginated feed. `PagedApiResponse<NotificationResponseDTO>` |
| `GET` | `/unread-count` | `ApiResponse<{count: N}>` — for bell badge |
| `PUT` | `/{publicId}/read` | Mark single notification read |
| `PUT` | `/mark-all-read` | Bulk mark-all-read for current user |
| `GET` | `/stream?token=` | SSE stream (permitted without Authorization header) |

### Tenant isolation

Automatic — `Notification extends BaseTenantEntity`, so Hibernate filter scopes all queries to the current tenant. The service additionally scopes by `recipientUserId` (user-level isolation within tenant). No `tenant_id` is ever accepted as a request parameter.

### Delivery channels

| Channel | Class | Effect |
|---------|-------|--------|
| `IN_APP` | `InAppNotificationChannel` | Persists DB row + SSE push |
| `SSE` | `SseNotificationChannel` | SSE-only ephemeral push (no DB) |
| `EMAIL` | `EmailNotificationChannel` | Email (implementation stub) |

### Response DTO fields

`publicId` (UUID), `type`, `title`, `message`, `status` (UNREAD/READ), `referenceType`, `referencePublicId`, `readAt`, `createdAt`

---

## Key configuration properties

| Property | Notes |
|---|---|
| `jwt.secret` | Base64-encoded HMAC-SHA key |
| `jwt.expiry-ms` | Token TTL in milliseconds (default 24 h) |
| `superadmin.signup-secret` | Required header when registering the SuperAdmin |
| `spring.jpa.hibernate.ddl-auto=update` | Schema is auto-managed; use migrations before switching to `validate` in prod |

---

## Frontend Architecture

Project: `D:\CRM PROJECT\travelcrmfrontend`
Stack: **React 19 + Vite 8 + Tailwind v4**. No TypeScript. Routing via `react-router-dom` 7.

### Key directories

The old layout (`src/components`, `src/masters`, `src/services`, `src/admin`, `src/quotation`) is **gone** — the app is feature-based. See `FEATURE-STRUCTURE.md`.

```
src/
  main.jsx        Vite entry
  App.jsx         thin shell — <ToastHost/> + <AppRouter/>, nothing else
  app/            chrome & wiring: Layout.jsx, router.jsx, PageLoader.jsx,
                  RouteErrorBoundary.jsx, chrome/ (Navbar, Sidebar, AppFooter,
                  ImpersonationBanner, MaintenanceOverlay)
  features/       21 features — the whole product surface
  shared/         cross-cutting infra only:
                    api/  http.js (shared axios), apiError.js, authRealm.js, geographyService.js
                    lib/  access.js, cn.js, download.js
                    ui/   toast.jsx, gridTable.jsx, WhatsAppIcon.jsx
  console/        SuperAdmin platform console — SEPARATE realm, NOT a feature
  assets/         static images
```

Features: `accounting assistant auth bookings calendar customers dashboard fleet leads marketing masters portal profile quotation reminders reports settings subagents subscription trash vendors`.

Each feature:

```
src/features/<name>/
  index.js        # public API — the ONLY thing other code may import
  pages/          # routed screens
  components/     # feature-internal components, modals, ui kits
  api/            # this domain's services
  hooks/ constants.js   # only if the feature has them
```

Where the old dirs went:

| Old | Now |
|-----|-----|
| `src/components/` | `src/app/chrome/` (Navbar, Sidebar, AppFooter, ImpersonationBanner, MaintenanceOverlay) + `src/shared/ui/`; single-consumer components live in that feature's `components/` |
| `src/masters/`, `src/masters/cities/City.jsx` | `src/features/masters/pages/` (flat — `City.jsx`, no `cities/`) |
| `src/services/` | each feature's `api/` + `src/shared/api/` |
| `src/admin/` | `src/features/leads/` |
| `src/quotation/` | `src/features/quotation/` + `src/features/bookings/` |

**`src/console/` is not a feature.** It is the SuperAdmin platform console — a separate auth realm with its own token (`sa_token`), login, `ConsoleLayout`/`ConsoleSidebar`, violet/dark theme, and its own `api/`, `lib/`, `theme/`. It follows the same barrel rule (`src/console/index.js`) but is imported as `@/console`, never `@features/...`.

**`src/features/assistant/` is the one feature with no `index.js`** — `DishaWidget` is parked, its mount commented out in `Layout.jsx:9,52-62` because the backend ships `disha.enabled=false` and `/ai/chat` 404s. To re-enable, gate on `GET /api/me/features`; do not just uncomment.

### Import rules / aliases

Aliases are declared **twice and must stay in sync** — `vite.config.js:12-17` (`resolve.alias`, what actually builds) and `jsconfig.json:4-9` (`compilerOptions.paths`, editor only):

| Alias | → |
|-------|---|
| `@/*` | `src/*` |
| `@app/*` | `src/app/*` |
| `@features/*` | `src/features/*` |
| `@shared/*` | `src/shared/*` |

- **Inside a feature: relative** — `../api/xService`, `../components/X`.
- **Feature → shared infra:** `@shared/api/http`, `@shared/lib/access`, `@shared/lib/cn`.
- **Feature → another feature: `@features/<name>` — the index ONLY.** Nothing outside a feature may reach into its `pages/`, `components/` or `api/`.
- `@/...` is the escape hatch (e.g. `@/console`), not the default.
- **Normalize import casing on any line you touch** (`./ui` → `./Ui`) — case-mismatched imports resolve on Windows and break Linux CI.

`index.js` exports routed pages as **named** exports plus anything another feature legitimately consumes — nothing else (e.g. `leads/index.js` also exports `leadService`; `bookings/index.js` exports `bookingService`; `fleet/index.js` is pages-only).

Dev server: `npm run dev` on **5173** — that is Vite's default; `vite.config.js` sets no `server.port`, so a busy port silently shifts to 5174 (both are CORS-whitelisted in `SecurityConfig`). It proxies `/api` → `http://localhost:8080` (`vite.config.js:21-29`), so relative `/api/...` calls work in dev with no CORS.

### Routing & sidebar registration

The whole route tree is `src/app/router.jsx`. `App.jsx` mounts `<ToastHost/>` as a **sibling** of the router — a render crash swaps the tree for `RouteErrorBoundary`'s fallback and the toast host must survive it.

Every route is lazy, via one helper:

```js
const lazyPage = (load, name) => lazy(() => load().then((m) => ({ default: m[name] })));
const fleet = () => import("@features/fleet");        // one thunk per feature = one chunk
const FleetTrips = lazyPage(fleet, "FleetTrips");     // picks the NAMED export off index.js
```

**Never `lazy(() => import("@features/x/pages/Y"))`** — that deep-imports past the index and breaks the boundary rule. Add the named export to the feature's `index.js`, then `lazyPage` it.

Two `<Suspense fallback={<PageLoader/>}>` boundaries: one around the whole tree in `router.jsx`, one inside `Layout.jsx` around `<Outlet/>` so chrome stays visible while a chunk downloads.

Route-level gate — `Guard`, defense-in-depth only:

```js
function Guard({ allow, children }) { return allow ? children : <Navigate to="/" replace />; }
<Route path="fleet" element={<Guard allow={hasPermission(P.FLEET_READ)}><FleetDashboard/></Guard>}/>
```

**`Guard` is applied inconsistently — do not assume it is ambient.** Fleet, accounting, marketing, settings, users, trash, calendar, quotation-templates and subagents are guarded; `allleads`, `Allbookings`, `AllCustomers`, `AllVendors`, `Reminders`, `createquotation` and all seven `Reports*` routes are **not**, and are URL-reachable by any logged-in user. That is tolerable only because the backend `@PreAuthorize` is the real gate.

Separate realms live in the same tree but outside `Layout`, each self-guarding on its own token: `/portal/**` (traveler) and `/console/**` (`sa_token`, login at `/superadmin/login`; `/console/login` is a kept redirect). `/q/:publicId` is public.

`app/router.jsx` registers `masters/destinations` twice (`:243`, `:245`) to the same element; the second is dead.

**Adding a nav item** — `src/app/chrome/Sidebar.jsx` is hand-written JSX, not a data-driven array: wrap an `<li>` in the gate expression.

```jsx
{hasPermission(P.LEAD_READ) && hasModule("LEADS") && (<li>…</li>)}
```

Gating comes from `@shared/lib/access`: `hasPermission(P.X)` / `hasAnyPermission(...)`, `hasModule("KEY")` (tenant plan entitlement), and `isTenantAdmin()` / `isSubAgent()` / `isSuperAdmin()`. Two asymmetries:

- `hasPermission` **short-circuits true for TENANT_ADMIN** and reads cached effective keys from `localStorage.userPermissions`, falling back to role defaults.
- `hasModule` is **fail-open** (`access.js:247-257` — unloaded/malformed `tenantModules` cache ⇒ every module shows) and has **no TENANT_ADMIN bypass**; module access is a tenant/plan property.

Gate the sidebar item and the route with the same key. The sidebar hiding a menu is UX; the backend is the security boundary.

### Auth & token storage

**Three separate auth realms. Never cross them.** A token in the wrong key is a privilege incident, not a bug.

| Realm | Token key | Client | Login route |
|---|---|---|---|
| Staff (tenant app) | `token` | `@shared/api/http` | `/login` |
| Platform console | `sa_token` | `console/api/consoleHttp` | `/superadmin/login` |
| Traveler portal | `travelerToken` | `features/portal/api/portalClient` | `/portal/login` |

Staff token is written by `features/auth/pages/AdminLogin.jsx:871` and read by `http.js:36`. Console session helpers: `console/lib/consoleAuth.js`. Portal keys are exported as `TRAVELER_TOKEN_KEY` / `TRAVELER_NAME_KEY` from `portalClient.js`.

Full key inventory — **clearing a subset is how the permission-leak bugs happened**:

| Key | Written by |
|---|---|
| `token`, `userRole`, `userEmail` | `features/auth/pages/AdminLogin.jsx:871-876` |
| `userPermissions`, `tenantModules`, `isPlatformAdmin` | `shared/lib/access.js:152-153,166-167,192` |
| `userName` | `features/subagents/pages/MyProfile.jsx:65` |
| `impersonation` | `console/pages/Users.jsx:147` (read by `app/chrome/ImpersonationBanner.jsx`) |
| `sa_token`/`sa_name`/`sa_email`/`sa_role` | `console/lib/consoleAuth.js:21-26` |
| `travelerToken`/`travelerName` | `features/portal/api/portalClient.js:15-16` |
| `app:maintenance` (sessionStorage) | `shared/api/http.js:61` |

Dead keys — nothing sets these; do not copy the idiom: `accessToken`, `jwt`, `authToken`.

**`localStorage["authToken"]` is a live bug, not a fixed one.** `features/reports/pages/FollowupReports.jsx:604-606` bare-fetches `/api/reports/followup` with `Bearer ${localStorage.getItem("authToken")}` → literally `Bearer null`. The guard `leadService.getFollowupReport ? … : fetch(…)` never protects it — `leadService` has no such member, so the fetch branch always runs. The real method is `reportsDashboardService.getFollowupReport` (`features/reports/api/reportsDashboardService.js:139`). `FEATURE-STRUCTURE.md` claims consolidation fixed this class of bug; it did not.

**401 logout does not clear the permission caches.** Staff `clearSession()` drops only `token` + `tenantModules` (`http.js:54-57`); `userPermissions` and `isPlatformAdmin` survive an expired session. The complete clear is `clearMyPermissions()` (`shared/lib/access.js:175-179`) — the interceptor never calls it. `primeSessionCaches()` (`access.js:214`) covers the impersonation hand-off only.

### HTTP client & service pattern

`axiosInstance.js` **no longer exists** — it is `src/shared/api/http.js`, default export `API`. Some comments still say "axiosInstance"; ignore them.

- **Exactly three `axios.create` calls in the tree**, one per realm (`shared/api/http.js:19`, `console/api/consoleHttp.js:18`, `portal/api/portalClient.js:18`). **A feature service must never create its own** — import `@shared/api/http` (41 files do).
- baseURL for all three: `import.meta.env.VITE_API_URL || "http://localhost:8080/api"`.
- Timeout 30s, deliberate: CSV exports and quotation PDFs exceed 10s, and an axios timeout yields no `error.response` at all (`http.js:21-24`).
- `consoleHttp` also exports `unwrap(res)` = `res?.data?.data ?? res?.data` for the `ApiResponse` envelope.

**Gotcha — `Navbar.jsx` uses a different baseURL convention.** `API_BASE = VITE_API_URL || ""` then appends `/api/...` (`app/chrome/Navbar.jsx:1113-1117`). Setting `VITE_API_URL` to the documented value produces `…/api/api/reminders/overdue`. It works today only because the var is unset, making the bell's URLs relative and proxied. The bell also bypasses the interceptor, so its 401s never log out. `notificationService` is a third convention: relative `BASE = '/api/notifications'`, ignoring `VITE_API_URL` entirely.

#### Error policy — `shared/api/authRealm.js`

All three clients share `createAuthRealm({loginPath, isAuthUrl, clearSession, onMaintenance})`. Each realm gets **its own logout latch**, so parallel 401s produce one redirect — and a portal 401 can never bounce a staff tab to `/login`.

Ownership is split, and the split is load-bearing:
- **Interceptor toasts/redirects** what the user can't act on: 401 → logout; 403 / `MODULE_NOT_ENABLED` / 429 / network / timeout → toast; 500 → toast + `traceId`; `MAINTENANCE` → full-screen overlay via `sessionStorage` + `app:maintenance` event (staff only — the console must never be locked out of turning maintenance off).
- **Call site renders** what's about the data just typed: 400 / 404 / 409 / validation / optimistic-lock are **silent by design** (`authRealm.js:93-97`). Don't add a duplicate toast; don't assume a 400 was surfaced.
- Errors are normalized by `normalizeError()` (`shared/api/apiError.js`) and always re-rejected — nothing is swallowed. Call-site idiom: `if (isAlreadyReported(err)) return;` before `showToast(getErrorMessage(err, fallback), 'error')`. Leads is the migrated reference.

**`matchesAuthPath` is a boundary regex `(^|/)auth/`** (`authRealm.js:103-110`); the portal passes segment `portal/auth`. A 401 on an auth URL never redirects — login answers 401 for wrong credentials and `LoginService`'s superadmin→user fallback probe depends on catching it. **Any new route containing an `auth/` segment silently opts out of the 401 redirect.**

### Design system

Tailwind **v4** via `@tailwindcss/vite`. **There is no `tailwind.config.js`** — config is CSS-first: `@import "tailwindcss"` plus `@theme` blocks in `src/index.css`. Don't create one.

`src/index.css` is 29 lines and does three things: loads Plus Jakarta Sans + JetBrains Mono from Google Fonts (`:1`), imports the console token layer (`:5`), and registers the **gold scale** in `@theme` (`:16-29`) — brand champagne `#eeda92` = `gold-300`, plus `--color-gold-ink`. Use `bg-gold-300` / `text-gold-700` / `ring-gold-300`, never `bg-[#eeda92]`.

**The font is loaded but NOT applied globally.** There is no `font-family` rule on `body`/`html`/`@theme` in the tenant app — the only one in `src/` is scoped to `.sa-console` (`console/theme/tokens.css:21`). Every tenant page sets it itself:

```jsx
style={{ fontFamily: "'Plus Jakarta Sans',system-ui,sans-serif" }}
```

Omit it and the page silently renders in the browser default font. A feature kit's `<Page/>` shell handles this for you.

Page shell (51 pages, `via-blue-50/30` most common):
```jsx
<div className="min-h-screen bg-gradient-to-br from-slate-50 via-blue-50/30 to-slate-100">
```

| Element | Class string | Source |
|---|---|---|
| Glass card | `bg-white/80 backdrop-blur-md rounded-2xl border border-slate-200/60 shadow-sm` | `marketingUi.jsx:123` |
| Primary button | `rounded-xl font-bold px-5 py-2.5 text-sm bg-gradient-to-r from-blue-600 to-indigo-500 hover:from-blue-700 hover:to-indigo-600 text-white shadow-md shadow-blue-200` | `marketingUi.jsx:168,179` |
| Input | `w-full px-3.5 py-2.5 rounded-xl border border-slate-200 bg-white text-sm text-slate-700 font-medium placeholder-slate-400 focus:border-blue-400 focus:ring-2 focus:ring-blue-50 outline-none` | `marketingUi.jsx:222` |
| Badge | `inline-flex items-center gap-1.5 text-xs font-bold px-2.5 py-1 rounded-full bg-blue-100 text-blue-700` (tone map: green/emerald/amber/red/blue/indigo/purple/teal/rose/slate/orange/sky) | `marketingUi.jsx:143-152` |

Constants: `rounded-xl` controls / `rounded-2xl` cards, `font-bold`/`font-extrabold` labels, slate text ramp, lucide-react icons, blue-600 primary.

#### `src/shared/ui/` is not a component kit

Only three files — there is **no shared Card/Button/Input**:

| File | Exports | Importers |
|---|---|---|
| `toast.jsx` | `toast`, `useToast`, `ToastHost`, `dismiss`, `dismissAll` | **45 — this is the app-wide convention** |
| `gridTable.jsx` | `GridStyles`, `GridHead`, `GridRow`, `Cell`, `Avatar`, `GridSkeleton`, `GridEmpty` | 1 (`customers/pages/AllCustomers.jsx`) |
| `WhatsAppIcon.jsx` | `WhatsAppIcon` | — |

`<ToastHost/>` is mounted once beside the router in `App.jsx:16`. Never add a toast library or a local toast `useState`.

`cn` (`shared/lib/cn.js` — `twMerge(clsx(...))`) exists but is imported by **exactly one file** (`fleetUi.jsx:18`). Every other kit concatenates classes with template literals. Use `cn` in new kits; don't retrofit it.

#### Per-feature UI kits

**Kits are feature-local — zero cross-feature imports.** None is re-exported from a feature's `index.js`.

| Path | Accent | Shape |
|---|---|---|
| `features/marketing/components/marketingUi.jsx` | blue-600→indigo-500 (+ gold variant) | template-literal, ~30 exports |
| `features/accounting/components/accountingUi.jsx` | emerald-500→green-600 | fork of marketingUi, same export names |
| `features/fleet/components/fleetUi.jsx` | flat `bg-blue-600` | shadcn-*shaped*, no shadcn: forwardRef + `variant`/`size` + `cn`, portal `Dialog`, `GlassCard`, `usePaged` |
| `features/quotation/components/Ui.jsx` | blue-600 | builder-tab form primitives |
| `features/portal/components/portalUi.jsx` | blue tint | `StatusChip`, `PayChip`, `Spinner`, `EmptyState`, `StatTile`, `money`, `fmtDate` |
| `features/calendar/lib/calendarUi.js` | — | **not a UI kit** despite the name — constants + date/money helpers, zero JSX |

`marketingUi` and `accountingUi` share export names (`Page`, `Hero`, `Panel`, `PanelHead`, `Badge`, `Btn`, `IconBtn`, `Pills`, `inputCls`, `Field`, `Select`, `Toggle`, `Modal`, `Th`, `EmptyBlock`, `EmptyRow`, `Loading`, `SkeletonRows`, `Pager`, `KV`). Each injects a `GLOBAL_STYLE` `<style>` via `<Page/>` carrying the font @import, `fadeUp`/`popIn`/`slideIn` keyframes and a scoped class (`.mkt-scope`/`.acc-scope`). **A `Panel` rendered outside `<Page/>` gets no animation and no scrollbar styling.**

Which kit for a new page?
- Feature has a kit → use that kit. Don't import another feature's.
- Feature with no kit (leads, bookings, customers, reports…) → hand-write the idiom above; set the gradient + inline font yourself.
- New kit only when a whole new *section* lands and wants its own accent — the established move is to copy `marketingUi.jsx` and swap the accent (that is what accounting did). Keep the export names identical.
- **Never mix fleetUi with marketing/accounting kits on one screen** — fleet's buttons are flat `bg-blue-600`, the others gradient; they read as two different products.

#### The superadmin console is a different visual language

`src/console/` runs on a **violet→fuchsia** token layer, deliberately separate from the tenant blue-600 and **scoped to `.sa-console` so it can never leak** (`console/theme/tokens.css:5-12`). It has its own `font-family` (`:21`), its own dark mode (`.dark` via `ConsoleThemeProvider.jsx:88`), and `@theme inline` semantic utilities — `bg-page`, `bg-surface`, `text-heading`, `text-body`, `bg-accent`, `border-border`, `ring-focus`.

**Inside `console/`, use the semantic utilities — never raw `slate-*`/`white`/`blue-*`.** Outside `console/`, those utilities resolve to nothing. The two vocabularies are not interchangeable in either direction.

### Navbar

`src/app/chrome/Navbar.jsx` — sticky top bar (`sticky top-0 z-40`), rendered by `src/app/Layout.jsx`.

**The file is 1638 lines and lines 1–1079 are two commented-out older drafts of the same component. The live component starts at line 1081** — grep returns three copies of everything; edit the last one.

Props: `toggleSidebar`, `breadcrumb` (array of `{label, href?}` **or** any ReactNode), `onNewBooking`, `appName` (defaults `"TravelCRM"`).

- **On mount** — `getUnreadCount()` + `fetchReminderAlerts()` + `subscribeToSSE(...)`; the SSE handle is closed on unmount. Both count promises `.catch()` to `0` — a failed badge degrades silently, never toasts. Only user-initiated actions toast.
- **Bell = notifications + reminders merged.** `badgeCount = unreadCount + reminderCount`. Opening the bell runs `getNotifications({ size: 10 })` and `fetchReminderAlerts()` (`GET /api/reminders/overdue` + `/due-today`) in parallel and sorts the union newest-first. Reminder rows are client-synthesized (`id: "rem-{id}"`, `kind: "reminder"`, `type: "REMIND"`, `link: "/Reminders"`) and are never marked read. **The badge is not the server unread-count** — a mismatch with `/unread-count` is expected.
- **Click a notification** — calls the module-local `markNotificationReadById(notif.id)` (numeric **`id`**, `PUT /api/notifications/{id}/read`), **not** `notificationService.markRead(publicId)`. Then routes by `referenceType` via `NOTIF_ROUTE_MAP`: `LEAD→/allleads`, `BOOKING→/Allbookings`, `REMINDER→/Reminders`, `CUSTOMER→/AllCustomers`, `VENDOR→/AllVendors`. **An unmapped `referenceType` does nothing on click** — add new ones to the map.
- **"Mark all read"** — `notificationService.markAllRead()`; local state only updates if the server accepted.
- **User info** — `userEmail` / `userRole` read from localStorage on mount; name = email local-part, initials = first 2 chars uppercased.
- **Tenant branding** — `companyService.get()` (from `@features/settings`) drives the logo mark, the favicon (old `link[rel~=icon]` removed + cache-buster) and `document.title = company.name`. It reloads on the global `window` event **`"company-updated"`**, dispatched by CompanyProfile after a save — that's the only channel; there are no props.

Navbar imports cross features only through barrels (`@features/reminders`, `@features/settings`) — app chrome respects the boundary rule.

#### Notification type → dot colour

`TYPE_DOT` in `Navbar.jsx:1103-1110` maps the notification `type` to the list dot: `BOOKING`→`bg-blue-500`, `PAYMENT`→`bg-emerald-500`, `LEAD`→`bg-violet-500`, `REMIND`→`bg-amber-500`, no match→`bg-slate-400`.

**It's a substring match (`type.includes(k)`), not an enum equality check** — `LEAD_ASSIGNED` correctly lights violet, so free-form `NotifyEvent.type` strings work as long as they contain one of these tokens.

### notificationService

`src/features/reminders/api/notificationService.js`, re-exported from `@features/reminders`. Uses `fetch` directly (not axios) against a **relative** `BASE = '/api/notifications'`. Auth header reads `localStorage.getItem("token")`.

**`fetch` does not reject on 4xx/5xx** — every write checks `res.ok` and throws a user-facing message (the status goes to `console.warn`, not the toast).

- `getNotifications({page = 0, size = 20})` → `{ content: body.data ?? [] }` (normalised from `PagedApiResponse.data`); **throws** on non-2xx.
- `getUnreadCount()` → `body.data?.count ?? 0` (from the `ApiResponse<Map>` envelope); swallows errors → `0`.
- `markRead(publicId)` → `PUT /api/notifications/{publicId}/read`. **Currently unused** — the Navbar marks read by numeric id instead.
- `markAllRead()` → `PUT /api/notifications/mark-all-read`.
- `subscribeToSSE(onNotification, onError)` → **returns a `{ close() }` handle, NOT an `EventSource`.** The token is in the URL, so the browser's built-in reconnect would replay a stale token forever; the service rebuilds the `EventSource` itself with a fresh token on `readyState === CLOSED`, backing off 3s → 30s cap. Caller must `.close()` on unmount. `es.onmessage`/`es.readyState` do not exist on the returned object.

Backend sends **named** events (`SseEmitter.event().name("notification")`) — handled via `es.addEventListener("notification", ...)`; named events never fire `onmessage`.

### Leads feature

`src/features/leads/` — replaces the old `src/admin/`.

| Page | Route | Notes |
|---|---|---|
| `AllLeads.jsx` | `/allleads` | The list. 1762 lines; 7 modals inline (not in `components/`) |
| `CreateLead.jsx` | `/createlead` | react-hook-form; phone lookup via `searchByPhone` |
| `EditLead.jsx` | `/EditLead/:id` | |
| `LeadLogs.jsx` | `/LeadLogs` | Per-lead activity logs |
| `AddLeadLog.jsx` | `/AddLeadLog` | Log + optional follow-up reminder |
| `AllLeadLogs.jsx` | `/AllLeadLogs` | Logs grid — **the only server-filtered list** (`/leads/logs/summary`) |
| `WhatsAppPanel.jsx` | — | Overlay rendered from `AllLeads`, not routed |

`components/`: `LeadInformation`, `LeadSummary`, `TravelDetails`, `ItinerarySection`, `ServicesSection`, `ConvertToBookingModal`, `SearchableSelect`, `AccessDenied`. `lib/whatsapp.js` → `formatToWhatsAppLink`.

**There is no lead Kanban.** The only board in the app is `features/calendar/components/TaskBoard.jsx` (tasks). `leadService.getLeadsByStagePerUser()` (`GET /leads/stats/by-stage`), `getUserWorkload()` and `getLeadCountForUser()` are defined but called from nowhere — the aggregation a board would need already exists, unconsumed.

#### The list (`AllLeads.jsx`)

TanStack Table, headless — it drives sorting/pagination/expansion only; the markup renders `row.original`. Desktop columns come from one shared template `LEAD_GRID_COLS` (`:103`) that the header, every row and the skeleton all read:

`expander · Lead (avatar+name+leadType pill+phone) · Travel Date · Assigned · Stage (inline <select>) · Quote Value · Actions`

- **Desktop and mobile rows are duplicated markup** (`:492` vs `:560`) — a column change must be applied twice.
- `leadSource` is **not** in the row; it appears only in the expand panel as plain text (`:652`), no badge/colour.
- `stageOptions` (`:479`) prepends the lead's real stage if it's outside the selectable `STAGES` — **the one place the FE tolerates an unknown enum value; copy this idiom.**
- Quote Value = `lead.latestQuotation.grandTotal` (backend-supplied), distinct from `lead.budget` (expand panel only).

**Filtering is entirely client-side.** `fetchLeads()` runs once on mount with no args → `GET /leads?page=0&size=100`, and the page never re-fetches when a filter changes (it only resets `pageIndex`). All narrowing is a `useMemo` over the in-memory array (`:1437-1473`): `searchTerm`, `dateFilter` (`all|today|yesterday|last_7_days|custom`), `startDate`/`endDate`, `activeTab`.

**Gotcha: the 100-row default cap is the whole dataset.** Past 100 leads, search/filters/stat cards silently operate on a truncated set. Any new filter is a client-side predicate unless the fetch moves server-side first.

#### Lead enums are hardcoded display strings

The wire format is the enum's `displayName` (`@JsonValue`), **not** the enum name — the API sends `"New Lead"`, never `NEW_LEAD`. `@JsonCreator fromValue()` accepts either form case-insensitively inbound, which hides write-side mistakes but not read-side ones. Every FE check is a raw string compare (`lead.leadStage === 'Converted'`).

Backend `LeadStage` (8): `New Lead, Contacted, Follow Up, Qualified, Proposal Sent, Converted, Reopened, Lost`.
Backend `LeadSource` (9): `Social Media, Website, Google Ads, Facebook, Instagram, WhatsApp, Referral, Direct Call, Other`.

**Stage strings are duplicated across 4 FE files with 3 different memberships, and already drift from the backend enum in both directions:**

| File | Constant | Drift |
|---|---|---|
| `AllLeads.jsx:58` | `STAGES` (6) | Converted excluded by design (goes through Convert flow) |
| `AllLeads.jsx:44-52` | `STAGE_PILL` (7) | no `Reopened` → falls to the orange default |
| `LeadInformation.jsx:12-15` | `LEAD_STAGES` (7) | no `Reopened` |
| `AllLeadLogs.jsx:10-13`, `LeadLogs.jsx:25-33` | `STAGES` / `STAGE_CFG` (8) | contains phantom `"Ready to Book"` — **never existed in the backend enum** |

**`LeadInformation.jsx:6-9` `LEAD_SOURCES` is the app's only leadSource `<select>` and hardcodes 8 of the 9 backend values (omits `Other`) — and this loses data, not just display.** `EditLead.jsx:106` seeds the form from `lead.leadSource` and `leadService.js:9` posts `formData.leadSource` straight back, so a lead whose source is outside those 8 strings has no matching `<option>`, the select falls back, and **opening + saving the lead silently rewrites its source**. Adding a backend `LeadSource` value requires touching this file (or fetching the list). By contrast `AllLeads.jsx:652` prints the raw value with a `|| '—'` fallback (safe), and `Dashboard.jsx:254-258` derives its source pie from the data with a colour fallback (safe, but `.slice(0,7)` will push real sources out as the list grows).

**No reusable source/channel badge exists.** `marketing/components/marketingUi.jsx:156` `ChannelBadge` is a binary WhatsApp-vs-Email toggle and is not exported through marketing's public API. Follow the in-feature pill-map idiom instead: a `SOURCE_*` map + a `|| default` lookup fn (`stagePill` `:44-53`, `typePill` `:69`, `serviceColor` `:82`).

#### Conventions

- `api/leadService.js` imports the shared client (`import API from "@shared/api/http"`). No local axios instance anywhere in this feature.
- `transformFormData()` (`leadService.js:4-36`) is the single form→DTO boundary; it coerces numbers, defaults `departCountry`/`departCity` to `"Not Specified"`, and re-numbers `itinerary[].dayNumber` from array order.
- IDs: every call takes `publicId`; rows fall back `lead.publicId || lead.id`.
- Access: page-level `hasPermission(P.LEAD_READ)` → `<AccessDenied />`; a 403 on load sets `denied` and shows the same page. Row actions gate on `P.LEAD_UPDATE` / `P.LEAD_DELETE` / `P.QUOTATION_*`.
- `AllLeads.jsx` imports `quotationService`, `QuotationWebView`, `WeblinkAnalyticsModal`, `SuggestPackagesModal` from `@features/quotation` — via the index, so the boundary holds.

### Master pages — integration status

All pages live under `src/features/masters/pages/`, all services under `src/features/masters/api/`; both re-exported through `src/features/masters/index.js`.

| Page | File (`features/masters/`) | API wired? |
|------|---------------------------|-----------|
| Country | — (no page exists) | ✅ dropdown only, via `@shared/api/geographyService` → `/masters/dropdown/countries` |
| Destination | `pages/Destinations.jsx` | ✅ `api/DestinationService.js` |
| City | `pages/City.jsx` | ✅ `api/CityService.js` |
| Hotel | `pages/Hotel.jsx` | ✅ `api/HotelService.js` |
| Sightseeing | `pages/Sightseeing.jsx` | ✅ `api/SightseeingService.js` |
| Vehicle | `pages/Vehiclas.jsx` (typo is real) | ✅ `api/VehicleService.js` |
| Airline | `pages/Airline.jsx` | ❌ hardcoded `dummyAirlines` (`:28`) |
| Cruise | `pages/Cruise.jsx` | ❌ hardcoded `SEED` (`:38`) |
| Add-on Services | `pages/AddonService.jsx` | ❌ hardcoded `SEED_DATA` (`:299`) |
| Testimonials | `pages/Testimonials.jsx` | ❌ hardcoded `INITIAL_TESTIMONIALS` (`:99`) |

- **The old City `/api/geography/cities` bug is a myth — do not "fix" it.** `CityService.js:18` calls `/cities` on `@shared/api/http` (baseURL `…:8080/api`) → `/api/cities`, which is exactly what `CityController.java:30` serves. **No `/api/geography/cities` route exists in the backend.**
- `CityController` exposes three route families: flat `/api/cities` (**the one the FE uses**), nested `/api/v1/{countries|destinations}/{id}/cities`, and `/api/v1/cities/{id}`. Don't delete the flat family as "unused".
- **Airline / Cruise / Addon / Testimonials are FE-only gaps** — backends already exist (`/api/airlines`, `/api/cruises`, `/api/addons`, `/api/testimonials`). No BE work needed to wire them; nothing consumes their IDs, so they are free for a `publicId` migration.
- `Vehiclas.jsx` deliberately no longer falls back to seed data on API failure (`:37`) — follow that precedent when wiring the four remaining pages: no silent fake-data fallback.

### HotelService.js — field mapping

`src/features/masters/api/HotelService.js`. Form field → `CreateHotelRequest`/`UpdateHotelRequest`:

| Form field | DTO field | Notes |
|---|---|---|
| `form.contact` | `contactPerson` | |
| `form.destinationId` | `destinationId` | `parseInt`, `null` if empty |
| `form.city` | `city` | String, the city **name** |
| `form.lat` / `form.lng` | `latitude` / `longitude` | `parseFloat` |
| `form.imagePath` | `imagePath` | Cloudinary `secure_url` |

- Room type: `name`, `size`, `occupancy`, `bedType`, `description` — **no `capacity`, no `price`**.
- Meal plan: `name`, `price`, `description` — **no `type`, no `pricePerPerson`**. Empty price coerces to `0`, not `null`.
- `transformHotelResponse` is the reverse transformer: maps `contactPerson` → `contact`, `latitude`/`longitude` → `lat`/`lng`, stringifies `destinationId`/`rating`/`occupancy`/`price` for controlled inputs.

**Gotcha — id fields don't line up.** `HotelDto` returns `hotelId`, `RoomTypeDto` returns `roomTypeId`, `MealPlanDto` returns `mealPlanId`. The FE reads `.id` everywhere (`transformHotelResponse:107` sets `id: backendData.id`; `Hotel.jsx:270` pushes list rows raw with no remap), so `.id` is `undefined` and every update/delete path (`Hotel.jsx:421,492,531`) keys off nothing. All three DTOs also carry `publicId` — the field the API is supposed to be keyed by. **Unresolved.**

### SightseeingService.js — field mapping

`src/features/masters/api/SightseeingService.js`.

- Sends `destination` (String) + `city` (String) — **NOT `cityId`/`destinationId`**. The entity stores a real `@ManyToOne City` FK; `SightseeingServiceImpl` resolves the two names via `findByTenantIdAndDestination_NameIgnoreCaseAndNameIgnoreCase` and 404s if the pair doesn't match.
- The form *does* track `destinationId`, but only as a client-side cascade key (city dropdown + country prefill in edit mode). `transformSightseeingData` deliberately omits it from the payload — **don't "fix" this**, the backend resolves by name.
- Other fields: `title` (required), `sequence` (defaults `1`), `estimatedHours` (float), `suggestedStartTime` (`"HH:MM"`), `description`/`remarks` (RichTextEditor HTML), `imagePath`.
- `transformSightseeingResponse` reverse-maps for the edit modal; sets `image: null` and mirrors `imagePath` into `imagePreview`.

**City dropdown is loaded by ID, not name.** `Sightseeing.jsx:169` calls `geographyService.getCitiesByDestination(destId)` → `GET /masters/dropdown/cities?destinationId=`. `GET /api/destinations/cities?destination={name}` still exists on the backend but nothing calls it: both name-based wrappers — `geographyService.getCitiesByDestinationName` and the `destinationService` exported from `SightseeingService.js` — are dead code.

### Image uploads

**All master images upload browser-direct to Cloudinary** (unsigned preset via `XMLHttpRequest`, so progress is reportable). The backend never sees the file — only the resulting `secure_url`, stored as `imagePath`.

| Entity | Uploader | Called from |
|---|---|---|
| Hotel | `uploadHotelImageToCloudinary` — `api/HotelService.js:15` | `pages/Hotel.jsx:223`, `features/quotation/components/HotelTab.jsx:2126` |
| Sightseeing | `uploadSightseeingImageToCloudinary` — `api/SightseeingService.js:23` (via `sightseeingService.uploadSightseeingImage`) | `pages/Sightseeing.jsx:203` |
| Vehicle | `uploadImageToCloudinary` — `api/VehicleService.js:17` | `pages/Vehiclas.jsx:133` |
| Destination | `uploadImageToCloudinary` — `api/DestinationService.js:33` | `pages/Destinations.jsx:881` |

**Company logo/favicon are the only backend-proxied uploads left** — multipart `POST /company/logo` | `/company/favicon` (`features/settings/api/companyService.js:73,88`) → `CloudinaryService.uploadImage(file, folder)`.

**Gotcha — direct uploads bypass the storage quota.** `CloudinaryService.uploadImage()` hard-enforces the tenant plan cap (`storageQuota.enforceWithinQuota`, `CloudinaryService.java:27`) and records bytes for the SuperAdmin usage dashboard (`meterUpload` → `StorageMeter.recordUpload`, `:37`). Browser-direct uploads hit neither, so master images are **neither quota-checked nor counted**. Route a new upload through the backend if it must be metered.

**Never send PII or financial documents to Cloudinary.** Cloudinary URLs are public and unauthenticated. Traveler documents are Postgres `bytea` served only through the ownership-checked portal endpoint (`TravelerDocument.java:54-56`); booking invoices/vouchers/credit notes are rendered on the fly with no Cloudinary caching (`BookingPdfService.java:33`).

Dead code — defined, zero callers: `hotelService.uploadHotelImage` (`HotelService.js:189`, marked LEGACY) and `hotelService.uploadRoomTypeImages` (`HotelService.js:227`). The backend `/upload-image` endpoints on `hotels`, `sightseeings`, `vehicles` and `testimonials` still exist and still return `{imagePath}` — but nothing in the FE calls them.

### Frontend env vars

Vite only exposes `VITE_*`.

| Var | Read by | Notes |
|---|---|---|
| `VITE_API_URL` | `shared/api/http.js:20`, `console/api/consoleHttp.js:19`, `console/api/consoleNotificationService.js:18`, `features/portal/api/portalClient.js:19`, `features/assistant/api/assistantClient.js:14`, `features/quotation/pages/QuotationWebView.jsx:5`, `app/chrome/ImpersonationBanner.jsx:5`, `app/chrome/Navbar.jsx:1113`, `shared/lib/access.js:157` | **Not set in `.env`** — all 9 sites silently fall back to `http://localhost:8080/api` (Navbar falls back to `""`). **A deploy that forgets it points production at localhost instead of failing loudly** — and see the Navbar double-`/api` gotcha above. |
| `VITE_CLOUDINARY_CLOUD_NAME` | `DestinationService.js:21`, `HotelService.js:8`, `VehicleService.js:6`, `SightseeingService.js:14` | Missing ⇒ uploaders reject with "Cloudinary not configured". |
| `VITE_CLOUDINARY_UPLOAD_PRESET` | same four | Must be an **unsigned** preset — a signed one 401s from the browser. |

`.env` is gitignored (`.gitignore:15-18`) and untracked — no credentials are committed. **There is no `.env.example`** despite the gitignore whitelisting one, so the table above is the only reference a fresh clone has.
---

## Common Patterns & Pitfalls

### Getting current user ID in a service

```java
Authentication auth = SecurityContextHolder.getContext().getAuthentication();
User user = (User) auth.getPrincipal();   // works for tenant users only
Long userId = user.getId();               // internal Long id (not publicId)
```

SuperAdmin (`SuperAdmin` entity, not `User`) cannot be cast to `User`. If a service is tenant-user-only, throw `IllegalStateException` on cast failure.

### Getting current tenant ID in a service

```java
Long tenantId = TenantContext.getTenantId();
if (tenantId == null) throw new IllegalStateException("TenantContext is empty...");
```

### Tenant-scoped lookups — never use bare `findById(Long)`

The Hibernate `@Filter("tenantFilter")` is only enabled on `@Transactional` methods (see
`TenantFilterAspect`) and **never** applies to `EntityManager.find()` / `repository.findById()`
/ `getReferenceById()`. Those bypass tenant isolation and can read across tenants.

For any `BaseTenantEntity` (and `User`, which carries `tenant_id` on `BaseEntity`), always
resolve through a tenant-scoped finder:

```java
repository.findByIdAndTenantId(id, TenantContext.getTenantId());
repository.findByPublicIdAndTenantId(publicId, TenantContext.getTenantId());
```

Cross-aggregate logical FKs (e.g. `Booking.customerId`, `Booking.leadId`, `Reminder.leadRefId`,
`Reminder.assignToUserId`) are validated this way in the service before persisting — a missing or
cross-tenant reference throws `ResourceNotFoundException`. `SuperAdmin`/`Tenant` lookups are
platform-level and intentionally exempt.

### Exception types

- `ResourceNotFoundException(message)` — maps to 404
- `BusinessException(message, HttpStatus)` — maps to the given status
- Both are handled by `GlobalExceptionHandler`

### Resolving City by name (Hotel / Sightseeing)

Hotel sends `destinationId` + `city` (string). Look up:
```java
cityRepository.findByTenantIdAndDestinationIdAndNameIgnoreCase(tenantId, destinationId, cityName)
```

Sightseeing sends `destination` + `city` (both strings) in the DTO, but the entity stores a
real `@ManyToOne City` (FK `city_id` / `fk_sightseeing_city`). The service resolves the names
to the City and sets the association:
```java
cityRepository.findByTenantIdAndDestination_NameIgnoreCaseAndNameIgnoreCase(tenantId, destName, cityName)
```

### SSE endpoints

`EventSource` cannot send `Authorization` headers. Pattern used in this project:
1. Permit the endpoint in `SecurityConfig` (no JWT filter enforcement)
2. Accept `?token=` query param
3. Validate token manually with `JwtUtil.isTokenValid(token)` + load user
4. Populate `SecurityContextHolder` + `TenantContext` manually
5. Register emitter via `SseEmitterRegistry.register(userId)`

Do **not** call `TenantContext.clear()` in an SSE endpoint — the response is async and the thread stays open.

### MapStruct + Lombok processor order

Never change the annotation processor order in `pom.xml`: **MapStruct → Lombok → lombok-mapstruct-binding**.

When a mapper tries to auto-map a `String` to an `@Entity` (FK field), add `@Mapping(target = "entityField", ignore = true)` and resolve the FK manually in the service `@AfterMapping` or in the service method itself.

### No Flyway

Schema is managed by `spring.jpa.hibernate.ddl-auto=update`. Do not add Flyway migrations unless switching to `validate` for production.

### No ModelMapper

MapStruct only. Never add ModelMapper as a dependency.