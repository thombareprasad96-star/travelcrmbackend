# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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

## Architecture

Spring Boot 3.5.3 / Java 21 REST API for a multi-tenant Travel CRM. PostgreSQL is the primary database; Redis is used for OTP storage (currently commented out). Logging is **Log4j2 only** — Logback is globally excluded from all starters.

### Multi-tenancy model

Every tenant-scoped entity extends `BaseTenantEntity` (which extends `BaseEntity`). Tenant isolation is enforced at two layers:

1. **`TenantEntityListener`** (`@PrePersist`/`@PreUpdate`) — reads from `TenantContext` (a `ThreadLocal<Long>`) and auto-stamps `tenantId` on persist, blocks cross-tenant updates.
2. **Hibernate `@Filter("tenantFilter")`** on `BaseTenantEntity` — when enabled on a session, all queries automatically add `WHERE tenant_id = :tenantId`.

`TenantContext` is populated by `JwtAuthFilter` from the JWT claim `tenantId` and **must** be cleared in the `finally` block to prevent thread-pool leaks.

### Authentication & JWT

Two distinct principal types share the same filter chain:
- **`SuperAdmin`** — single instance, no `tenantId` in JWT, loaded by `SuperAdminDetailsService`.
- **`User`** — tenant-scoped, `tenantId` embedded in JWT claims, loaded by `UserDetailsServiceImpl`.

`JwtAuthFilter` routes loading to the correct service based on the `role` claim. `SecurityConfig` registers both as separate `DaoAuthenticationProvider` beans.

CORS is configured to allow `http://localhost:5173` only (dev). Add the production URL in `SecurityConfig.corsConfigurationSource()` before deploying.

### Entity base classes

| Class | Purpose |
|---|---|
| `BaseEntity` | `id` (Long), `publicId` (UUID), audit fields, soft delete via `deletedAt`/`deletedBy` |
| `BaseTenantEntity` | extends `BaseEntity`, adds `tenantId`, carries the Hibernate `@Filter` |

Always use `publicId` (UUID) in API responses — never expose the internal `Long id`.

### Module layout

```
auth/         Login, registration, JWT, SecurityConfig, two UserDetailsService impls
booking/      Booking CRUD, CSV export, voucher send, filtering via JPA Specifications
lead/         Lead pipeline (LeadStage, LeadType, LeadSource), itineraries
master/       City and Destination lookup tables (simple CRUD)
tenent/       Tenant lifecycle management (note: package is spelled "tenent")
otp/          OTP strategy pattern (Email/SMS/WhatsApp) — implementation commented out
common/       BaseEntity, BaseTenantEntity, TenantContext, ApiResponse wrappers, GlobalExceptionHandler
```

### Response envelope

All endpoints return `ApiResponse<T>` (single item) or `PagedApiResponse<T>` (paginated). Do not bypass these wrappers.

### Annotation processor order

The Maven compiler plugin explicitly orders annotation processors: **MapStruct → Lombok → lombok-mapstruct-binding**. This order is required — do not change it. MapStruct mappers must use `@Mapper(componentModel = "spring")` to be Spring beans.

### Hibernate Envers

`Booking` is annotated `@Audited`. Envers creates `bookings_aud` and `revinfo` audit tables automatically. Use `@NotAudited` on fields that should be excluded (e.g., `@ElementCollection` services list).

### OTP module

`OtpServiceImpl` is fully implemented but commented out. It uses a Strategy pattern (`EmailOtpStrategy`, `SmsOtpStrategy`, `WhatsAppOtpStrategy`) routed via `OtpStrategyFactory`. OTP keys are namespaced in Redis via `OtpRedisKeyBuilder`. Twilio integration for SMS/WhatsApp is wired but credentials in `application.properties` are placeholders.

## Key configuration properties

| Property | Notes |
|---|---|
| `jwt.secret` | Base64-encoded HMAC-SHA key |
| `jwt.expiry-ms` | Token TTL in milliseconds (default 24 h) |
| `superadmin.signup-secret` | Required header when registering the SuperAdmin |
| `spring.jpa.hibernate.ddl-auto=update` | Schema is auto-managed; use migrations before switching to `validate` in prod |
