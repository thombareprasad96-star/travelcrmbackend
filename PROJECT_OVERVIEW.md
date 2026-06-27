# TravelCRM — Project Overview & Learning Guide

> A walkthrough of the **TravelCRM** backend for someone learning the codebase. Read this top-to-bottom once, then use the "How to navigate" section to dive into code.

---

## 1. What is this project?

**TravelCRM** is the backend for a **multi-tenant SaaS CRM for travel agencies**. Many agencies ("tenants") use one deployment; each only ever sees its own data. An agency's staff manage **leads → quotations → bookings → customers**, plus master data (hotels, destinations, vehicles…), vendors, reminders and notifications. There is also a **customer-facing "Traveler Portal"** where the end traveller logs in to see their trips, pay, and upload documents.

It is a **REST API** (JSON) consumed by a separate React frontend.

---

## 2. Tech stack

| Layer | Technology |
|---|---|
| Language / runtime | **Java 21** |
| Framework | **Spring Boot 3.5.3** (Web, Security, Data JPA, AOP, Validation, Mail) |
| ORM | **Hibernate 6** + **Hibernate Envers** (audit history) |
| Database | **PostgreSQL** |
| Auth | **JWT** (jjwt) — stateless |
| Logging | **Log4j2** (Logback excluded) |
| Mapping | **MapStruct** (older modules) + hand-written `@Component` mappers (newer modules) |
| File storage | **Cloudinary** (images) |
| PDF | **OpenPDF** (quotation PDFs) |
| CSV | Apache Commons CSV (exports) |
| Cache/queue | **Redis** (configured; reserved — the OTP module currently defaults to in-memory) |
| SMS/WhatsApp | **Twilio** (dependency present; delivery is a pluggable stub) |
| Build | **Maven** (`mvnw` wrapper) |

**Scale:** ~457 Java files · ~27,500 LOC · 46 entities · 35 REST controllers · 33 repositories.

---

## 3. The big picture (read this carefully — it explains everything else)

### 3a. Multi-tenancy — the single most important concept
Every tenant-scoped row carries a `tenant_id`. Isolation is enforced at **two layers** so one agency can never see another's data:

1. **`TenantContext`** — a `ThreadLocal<Long>` holding "which tenant is this request for." It is filled by the JWT filter from the token's `tenantId` claim, and **must be cleared after every request** (else a pooled thread leaks one tenant's id into the next request).
2. **`TenantEntityListener`** (`@PrePersist`/`@PreUpdate`) auto-stamps `tenant_id` on save and blocks cross-tenant updates; a **Hibernate `@Filter("tenantFilter")`** auto-adds `WHERE tenant_id = ?` to queries.

> **Mental model:** *"Who am I?"* comes from the JWT → `TenantContext` → every query is silently scoped to that tenant.

### 3b. Entity base classes
| Class | Gives every subclass |
|---|---|
| `BaseEntity` | `id` (internal `long` PK), **`publicId` (UUID)**, audit fields (`createdBy/At`, `updatedBy/At`), **soft-delete** (`deletedAt`, `deletedBy`) |
| `BaseTenantEntity` | everything above **+ `tenantId`** + the tenant `@Filter` |

> **Golden rule:** APIs only ever expose **`publicId` (UUID)**, never the internal `Long id`. Look-ups use `publicId`.

### 3c. Authentication — three principals, two realms
- **`SuperAdmin`** — the platform owner; manages tenants. No `tenantId`.
- **`User`** — agency staff (roles: TENANT_ADMIN, MANAGER, TRAVEL_AGENT, STAFF, ACCOUNTANT). Tenant-scoped.
- **`TravelerAccount`** — the end customer, in a **completely separate auth realm** (the Traveler Portal).

Staff & SuperAdmin share one JWT signing key + one `SecurityFilterChain` (`/api/**`). Travelers use a **different signing key** + a **dedicated chain** (`/api/portal/**`), so the two token types can never be used against each other's endpoints.

### 3d. Response envelope
Every endpoint returns a consistent wrapper:
- `ApiResponse<T>` — single item (`{ success, message, data, statusCode, timestamp }`)
- `PagedApiResponse<T>` — paginated lists

### 3e. Permissions
Fine-grained permission **keys** (e.g. `LEAD_CREATE`, `BOOKING_CANCEL`, `TRASH_DELETE`) live in one `Permission` enum and are checked with `@PreAuthorize("hasAuthority('…')")`. Each user has a per-user permission map; `TENANT_ADMIN` implicitly has all. The same catalog is exposed to the frontend so the two never drift.

---

## 4. Module-by-module tour

| Module (`com.crm.travelcrm.*`) | What it does | Main API base |
|---|---|---|
| **auth** | Login/registration, JWT, `SecurityConfig`, staff + super-admin user-details services, user management | `/api/auth`, `/api/users` |
| **permission** | Permission catalog, per-user permission maps, templates, scope resolver | `/api/permissions`, `/api/permission-templates` |
| **tenent** | Tenant (organization) lifecycle — SuperAdmin creates/manages agencies *(note the spelling "tenent")* | `/api/super-admin/tenants` |
| **company** | Per-tenant company settings & tax rates | `/api/company`, `/api/tax-rates` |
| **lead** | Lead pipeline (stages/types/sources), itineraries, Kanban board, user workload | `/api/leads` |
| **customer** | Customer master, booking history, stats, CSV export | `/api/customers` |
| **booking** | Booking CRUD, lifecycle (convert-from-lead, **cancel**), financials (GST/TCS/profit), CSV, Envers audit | `/api/bookings` |
| **quotation** | Multi-section quotations (flights/hotels/sightseeing/cruise/vehicle/add-ons), **versioning**, **PDF**, public share link, analytics | `/api/quotations`, `/api/public/quotations` |
| **vendor** | Vendor master — one entity mapped across **3 tables** (`@SecondaryTable`); financials, ratings | `/api/vendors` |
| **reminder / bookingreminder** | Follow-up reminders + a scheduler that fires due ones | `/api/reminders`, `/api/booking-reminders` |
| **master** | Reference data: **geography** (Country→Destination→City), hotel (+room types/meal plans), vehicle, airline, cruise, add-on, sightseeing, and unified **dropdown** endpoints | `/api/hotels`, `/api/airlines`, `/api/v1/countries`, `/api/masters/dropdown`, … |
| **notification** | Plug-and-play notifications — other modules fire an event; this handles in-app + **SSE** push + email | `/api/notifications` |
| **notificationsetting** | Per-user notification preferences | `/api/notification-settings` |
| **otp** | **Shared, plug-and-play OTP** — generate / store / verify / deliver (used by the portal login) | *(library, no controller)* |
| **portal** | **Traveler Portal** — customer self-service (separate auth realm): my trips, payments, documents | `/api/portal/**` |
| **trash** | **Universal soft-delete → Trash → 30-day auto-purge** across all modules | `/api/trash` |
| **common** | `BaseEntity`/`BaseTenantEntity`, `TenantContext`, `ApiResponse`, global exception handler, Cloudinary, rate-limiting, config | *(shared)* |

---

## 5. Cross-cutting conventions (the "house style")

- **`publicId` everywhere** in the API; internal `Long id` never leaves the server.
- **Tenant-safe look-ups** — never bare `findById(Long)`; always `findByPublicIdAndTenantId(...)` so isolation holds.
- **Soft-delete by default** — user "deletes" set `deletedAt`/`deletedBy`; the row stays. Only a scheduled purge (or an admin "delete-now") ever hard-deletes. (See the Trash feature below.)
- **Thin controllers, fat services** — controllers just map HTTP ↔ DTO; business logic lives in `@Transactional` services.
- **Constructor injection** (Lombok `@RequiredArgsConstructor`).
- **Events for decoupling** — e.g. a lead soft-delete publishes an event; the quotation module listens and cascades, without the two modules importing each other.
- **Exceptions** → a `GlobalExceptionHandler` turns `ResourceNotFoundException` (404), `BusinessException` (custom status), validation errors, etc. into the standard envelope.
- **No N+1** — list endpoints use batch queries / projections.

---

## 6. Five features worth studying in depth

### 6a. Multi-tenancy enforcement
Start at `JwtAuthFilter` (sets `TenantContext`) → `TenantEntityListener` (stamps `tenant_id`) → `TenantFilterAspect` (enables the Hibernate filter on `@Transactional` methods). This trio is the backbone.

### 6b. Quotation → Booking → Customer lifecycle
A **lead** is nurtured, a **quotation** (versioned, PDF-able, shareable via a public capability-URL) is sent, then converted into a **booking**, which creates/links a **customer**. Cancelling a booking can revert or trash the lead. This is the core business flow.

### 6c. Universal Trash (soft-delete lifecycle)
Every delete is recoverable. A registry (`TrashableType`) drives generic list/restore/delete-now plus a **per-tenant 30-day auto-purge** job. Master data adds a referential guard ("can't delete a city still used by active hotels"). Two read-exclusion mechanisms coexist: explicit `…DeletedAtIsNull` finders (core modules) and a toggleable `softDeleteFilter` (master data).

### 6d. Traveler Portal (separate auth realm)
A customer logs in with an **OTP to their registered phone/email** (no password), gets a **traveler-scoped JWT** (distinct key + filter chain), and can only ever touch **their own** bookings/documents (object-level ownership; a foreign id returns 404). Passport/visa scans are stored **in the DB**, not on a public URL, and streamed through an authenticated endpoint.

### 6e. Plug-and-play modules (Notification & OTP)
Both are designed for reuse and easy extension:
- **Notification:** any module publishes a `NotifyEvent`; delivery channels (in-app, SSE, email) are pluggable.
- **OTP:** one `OtpService` facade over swappable `OtpGenerator` / `OtpStore` (in-memory default, Redis-ready) / `OtpDeliverySender` (SMS/email/WhatsApp strategy). Codes are hashed, attempt-capped, one-time-use.

---

## 7. Data-model highlights

- **Geography hierarchy:** `Country → Destination → City`, with hotels/sightseeing/etc. hanging off a city.
- **Vendor:** a single Java entity spread across `vendors` + `vendor_bank_details` + `vendor_financials` via `@SecondaryTable`.
- **Booking financials:** customer amount → GST + TCS → total payable; paid vs pending; net profit (internal). The portal hides internal fields (vendor cost, profit) from the customer.
- **Snapshots vs FKs:** bookings/quotations often store **name snapshots** (e.g. destination name) so a generated document stays stable even if the master record later changes.
- **Audit:** `Booking` is `@Audited` (Envers) — full change history in `bookings_aud`.

A detailed ER diagram lives in `docs/ERD.md` / `docs/ER-DIAGRAM.md`.

---

## 8. How to build & run

```bash
# Build (requires JDK 21 — the project will NOT build on JDK 17)
./mvnw clean package -DskipTests          # mvnw.cmd on Windows

# Run
./mvnw spring-boot:run

# Tests (need a PostgreSQL database available)
./mvnw test
```

Needs a running **PostgreSQL** (connection in `application.properties`). Schema is auto-created by Hibernate (`ddl-auto=update`). A dev seeder inserts demo data on first run (`app.seed.enabled`).

---

## 9. How to navigate the codebase (suggested reading order)

1. `common/entity/BaseEntity` + `BaseTenantEntity` — the foundation every entity builds on.
2. `common/context/TenantContext`, `common/listener/TenantEntityListener`, `auth/security/JwtAuthFilter`, `auth/security/SecurityConfig` — how a request becomes "authenticated + tenant-scoped."
3. `lead/` then `booking/` then `quotation/` — follow one entity end-to-end: Controller → Service → Repository → Entity → DTO + Mapper.
4. `common/dto/ApiResponse` + `common/exception/GlobalExceptionHandler` — the response/error contract.
5. `notification/` and `otp/` — clean examples of plug-and-play module design.
6. `trash/` and `portal/` — the newest, well-documented features.

> Tip: every module follows the same shape — `controller/`, `service/`, `repository/`, `entity/`, `dto/`, `mapper/`. Learn one module and you can read them all.

---

## 10. Glossary

| Term | Meaning |
|---|---|
| **Tenant** | One travel agency using the SaaS; the isolation boundary. |
| **publicId** | The external UUID of any record (the internal `Long id` is never exposed). |
| **TenantContext** | Thread-local holding the current request's tenant id. |
| **Soft delete** | Marking a row deleted (`deletedAt`) instead of removing it; recoverable. |
| **Lead → Booking** | The sales funnel: enquiry → quote → confirmed trip. |
| **Master data** | Reusable reference data (hotels, destinations, vehicles…). |
| **SSE** | Server-Sent Events — the live push channel for in-app notifications. |
| **Traveler** | The end customer (portal user), distinct from agency **staff**. |

---

*Generated as a learning aid. For deeper API/field specifics, see `CLAUDE.md` (the in-repo developer guide) and `docs/ERD.md`.*
