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

Frontend lives at `E:\CRM_PROJECT\travelcrmfrontend` (Vite + React + Tailwind).

```bash
# Frontend dev server
cd E:\CRM_PROJECT\travelcrmfrontend
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
otp/              OTP strategy pattern (Email/SMS/WhatsApp) — implementation commented out
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

### OTP module

`OtpServiceImpl` is fully implemented but commented out. It uses a Strategy pattern (`EmailOtpStrategy`, `SmsOtpStrategy`, `WhatsAppOtpStrategy`) routed via `OtpStrategyFactory`. OTP keys are namespaced in Redis via `OtpRedisKeyBuilder`. Twilio integration for SMS/WhatsApp is wired but credentials in `application.properties` are placeholders.

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

City endpoints live at `/api/geography/cities` (not `/api/cities`).

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

Project: `E:\CRM_PROJECT\travelcrmfrontend`
Stack: **React 18 + Vite + Tailwind CSS**. No TypeScript. Routing via `react-router-dom`.

### Key directories

```
src/
  components/     Shared components (Navbar, Sidebar, etc.)
  masters/        Master data pages (Hotel, Sightseeing, Airline, Cruise, etc.)
  masters/cities/ City.jsx
  services/       API client files (one per domain)
  admin/          Lead management pages
  quotation/      Quotation/booking pages
```

### Auth & token storage

Token is stored in `localStorage` under the key **`"token"`** (NOT `"accessToken"`).
User email: `localStorage.getItem("userEmail")`
User role: `localStorage.getItem("userRole")`

The shared `axiosInstance.js` reads `localStorage.getItem("token")` and attaches it as `Authorization: Bearer <token>`. All service files that create their own axios instance must also use `"token"` as the key.

On 401, `axiosInstance.js` clears the token and redirects to `/login`.

### Navbar.jsx

Sticky top bar, lives in `src/components/Navbar.jsx`. Props:
- `toggleSidebar` — opens/closes sidebar
- `breadcrumb` — array of `{label, href?}` or ReactNode
- `onNewBooking` — callback for New Booking button
- `appName` — defaults to `"TravelCRM"`

The Navbar is already fully wired to the notification module:
- On mount: fetches unread count + opens SSE connection
- Bell click: loads 10 most recent notifications
- Click notification: marks it read via `markRead(notif.publicId)` — use `publicId` not `id`
- "Mark all read" button: calls `markAllRead()`
- SSE events: named `"notification"` — handled via `addEventListener("notification", ...)` not `onmessage`
- User info (name, role, initials) read from localStorage on mount

### notificationService.js

`src/services/notificationService.js` — uses `fetch` directly (not axios).

- `getNotifications({page, size})` → returns `{content: [...]}` (normalised from `PagedApiResponse.data`)
- `getUnreadCount()` → reads `body.data?.count` (from `ApiResponse<Map>` envelope)
- `markRead(publicId)` → `PUT /api/notifications/{publicId}/read`
- `markAllRead()` → `PUT /api/notifications/mark-all-read`
- `subscribeToSSE(onNotification, onError)` → returns `EventSource`; caller must `.close()` on unmount

### Service files pattern

Most service files create a local axios instance with a hardcoded `baseURL`. The preferred pattern (used by `DestinationService.js`) is to import the shared `axiosInstance` from `./axiosInstance` instead.

Base URL for all API calls: `http://localhost:8080/api` (or `VITE_API_URL` env var).

### Master pages — integration status

| Page | File | API wired? |
|------|------|-----------|
| Country | (via geography) | ✅ |
| Destination | `Destinations.jsx` | ✅ Uses `DestinationService.js` |
| City | `cities/City.jsx` | ⚠️ `CityService.js` calls `/api/cities` but backend is at `/api/geography/cities` |
| Hotel | `Hotel.jsx` | ✅ Uses `HotelService.js` |
| Sightseeing | `Sightseeing.jsx` | ✅ Uses `SightseeingService.js` |
| Vehicle | `Vehiclas.jsx` | ✅ Uses `VehicleService.js` |
| Airline | `Airline.jsx` | ❌ Fully hardcoded (`dummyAirlines`), no API calls |
| Cruise | `Cruise.jsx` | ❌ Fully hardcoded (`SEED` data), no API calls |
| Add-on Services | `AddonService.jsx` | ❌ Fully hardcoded (`SEED_DATA`), no API calls |
| Testimonials | `Testimonials.jsx` | Unknown |

### HotelService.js — field mapping

Frontend form field → backend DTO field:
- `form.contact` → `contactPerson`
- `form.destinationId` → `destinationId` (Long)
- `form.city` → `city` (String, the city name)
- `form.lat` / `form.lng` → `latitude` / `longitude`
- Room type: `size`, `occupancy`, `bedType`, `description` (no `capacity`, no `price`)
- Meal plan: `name`, `price`, `description` (no `type`, no `pricePerPerson`)

The reverse transformer (`transformHotelResponse`) maps `backendData.contactPerson` → `contact` for the form.

### SightseeingService.js — field mapping

- Sends `destination` (String) + `city` (String) — NOT cityId or destinationId
- City dropdown loaded via `GET /api/destinations/cities?destination={name}`
- `type` field in the enum: `BOOKING`, `PAYMENT`, `LEAD`, `REMIND` (used for SSE dot colour in Navbar)

### Image uploads

- **Hotel / Sightseeing / Vehicle**: multipart `POST` to backend (`/upload-image`) → backend uploads to Cloudinary via `CloudinaryService.uploadImage(file, folder)` → returns `{imagePath: "https://..."}`.
- **Destination**: images uploaded **directly from browser to Cloudinary** (unsigned preset via XHR in `DestinationService.js`) — backend never receives the file, only the resulting URL as `imagePath`.

### Cloudinary env vars (frontend)

```
VITE_CLOUDINARY_CLOUD_NAME=...
VITE_CLOUDINARY_UPLOAD_PRESET=...
```

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