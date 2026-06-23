# Backend Code Review — Findings

Scope: reviewed auth/security, multi-tenancy enforcement, transactions, exception handling,
rate limiting, SSE, and swept all 332 source files for risk patterns. Focused on correctness/
security/perf rather than per-field CRUD. Severity: 🔴 High · 🟠 Medium · 🟢 Low/cleanup.

---

## Resolution status (2026-06-23)

**Fixed (backend-only), compiles clean:** H1, M1, M2, M4, M5, M6, M7, M8, L1, L3, L4, L6, L7.
- H1 inactive/soft-deleted auth · M1 rate-limit eviction · M2 spoofable XFF (trusted-proxy gate)
- M4 `@Version` on Vendor + 409 handler · M5 deleted dead `tenent.listener` · M6 constant-time secret
- M7 `SecurityException`→403 · M8 prePersist cross-tenant guard · L1 vendor delete audit user
- L3 rate-limit cleanup · L4 removed stray `System.out` debug · L6 CORS via `app.cors.allowed-origins`
- L7 `emailById` tenant-scoped (tenant-less fallback preserved)

**Skipped intentionally:** H3 (per user — rotation is yours), L5 (Flyway forbidden by CLAUDE.md),
L2 (vendor `getBookings`/email = feature work).

**Needs frontend (not done):** H2 (tenant discriminator at login), M3 (`Long id`→`publicId` in URLs),
H4 (notification `Long`→`publicId` — verify what the frontend sends first).

New optional config (sensible defaults): `app.cors.allowed-origins`, `app.ratelimit.trusted-proxies`.

---

## 🔴 HIGH

### H1. Inactive / soft-deleted users can still authenticate  ✅ FIXED
`auth/service/AuthServiceImpl.userLogin` checks **only** the password — it never checks
`user.getIsActive()` or `deletedAt`. `User.isEnabled()` (User.java:87) returns `isActive`, but
login bypasses `DaoAuthenticationProvider`, and `JwtAuthFilter` builds the auth token from
`userDetails.getAuthorities()` without checking `isEnabled()`. `UserDetailsServiceImpl` also loads
via `findByEmail`/`findByEmailAndTenantId` with **no `deletedAt IS NULL` filter**.
→ A deactivated or soft-deleted user keeps full access (and can mint fresh tokens).
**Fix:** in `userLogin`, reject when `!isActive` or `deletedAt != null`; in `JwtAuthFilter`, reject
`!userDetails.isEnabled()`; change loaders to `...AndDeletedAtIsNull`.

### H2. Login is email-only → multi-tenant collision + no tenant selection
`AuthServiceImpl.userLogin` uses `userRepository.findByEmail(email)`. Users are unique per
`(email, tenant_id)` (see `db/indexes.sql` note), so the **same email can exist in multiple tenants**.
- Two tenants with the same email → `IncorrectResultSizeDataAccessException` (500), login broken.
- There is no way to pick the tenant at login.
Same ambiguity in `UserDetailsServiceImpl.loadUserByUsername` and the `JwtAuthFilter` fallback
(role = tenant user butuh `tenantId == null` → `loadUserByUsername(email)`).
**Fix:** add a tenant discriminator at login (org code / subdomain) and query by `(email, tenantId)`.

### H3. Secrets committed to `application.properties`
Real credentials are in the repo: `spring.datasource.password=Admin123`, Gmail app password
`spring.mail.password=...`, `cloudinary.api-secret=...`, `jwt.secret=...`, `superadmin.signup-secret=...`.
**Fix:** rotate all of them, move to environment variables / a secrets manager, and keep only
placeholders in the file. (They remain in git history even after removal — rotation is required.)

### H4. Notification endpoints key on `Long id` but docs/frontend use `publicId`
`notification/web/NotificationController`: `PUT /{id}/read` and `DELETE /{id}` declare
`@PathVariable Long id`, but the Javadoc says `{publicId}`, CLAUDE.md documents `publicId`, and the
frontend calls `markRead(notif.publicId)` with a UUID. A UUID sent to a `Long` path var → 400.
**Fix (confirm against frontend first):** switch these endpoints + `NotificationService.markRead/delete`
to `UUID publicId`, scoped by current user + tenant.

---

## 🟠 MEDIUM

### M1. In-memory rate limiter — memory leak + not distributed
`common/ratelimit/RateLimitService` uses a `ConcurrentHashMap` that is **never evicted** (every IP/key
stays forever → unbounded growth) and is **per-instance** (won't rate-limit across replicas). The Redis
implementation is commented out.
**Fix:** evict expired entries (or use the Redis Lua script already drafted in the file).

### M2. Rate-limit IP is spoofable
`RateLimitFilter.resolveClientIp` trusts the first `X-Forwarded-For` token unconditionally. A client can
send a random `X-Forwarded-For` per request to get a fresh bucket and defeat the login brute-force limit.
**Fix:** only trust `X-Forwarded-For` from known proxy IPs; otherwise use `getRemoteAddr()`.

### M3. Pervasive `Long id` exposure in REST APIs
`@PathVariable Long` appears 79× across 13 controllers (hotel, sightseeing, vendor, geography
country/city/destination, reminder, cruise, tenant, booking, notification, airline, addon). This
violates the "expose only `publicId`" rule and exposes sequential, enumerable ids. (Vendor & Reminder
lookups are tenant-scoped, so not a cross-tenant breach — but inconsistent and information-leaking.)
**Fix:** standardize on `publicId` (UUID) path vars + `findByPublicIdAndTenantId...` lookups.

### M4. No optimistic locking → lost updates on counters
No `@Version` anywhere. Read-modify-write counters race under concurrency, e.g.
`VendorServiceImpl.updatePayment` (`totalPaid = totalPaid + amount`) and `rateVendor` (running average).
Two concurrent requests can lose one update.
**Fix:** add `@Version` to mutated aggregates (at least `Vendor`), or do atomic UPDATEs.

### M5. Duplicate `TenantEntityListener` (dead code + System.out)
`tenent/listener/TenantEntityListener.java` duplicates `common/listener/TenantEntityListener` and prints
`System.out.println(">>> TenantEntityListener.prePersist CALLED ...")` on every persist. Only the
`common` one is wired via `BaseTenantEntity`. The `tenent` copy is dead (or, if ever wired, spams stdout).
**Fix:** delete the `tenent/listener` copy.

### M6. SuperAdmin signup secret uses non-constant-time compare
`AuthController` does `!secret.equals(signupSecret)` — vulnerable to timing analysis. (The endpoint is
otherwise correctly gated and `count() > 0` blocks a second SuperAdmin.)
**Fix:** `MessageDigest.isEqual(...)` / `java.security.MessageDigest`-based constant-time compare.

### M7. `SecurityException` (cross-tenant update) not mapped → 500
`TenantEntityListener.preUpdate` throws `SecurityException` on a cross-tenant write, but
`GlobalExceptionHandler` has no handler → falls through to generic 500 instead of 403.
**Fix:** add a handler mapping `SecurityException` → 403.

### M8. `prePersist` trusts a pre-set `tenantId`
`TenantEntityListener.prePersist` only stamps `tenantId` when null; it does **not** verify a
service-supplied `tenantId` matches `TenantContext`. A service bug could persist a row under the wrong
tenant undetected.
**Fix:** if `entity.tenantId != null && != context`, throw (defense-in-depth, mirroring `preUpdate`).

---

## 🟢 LOW / CLEANUP

- **L1.** `VendorServiceImpl.delete` sets `softDelete("system")` instead of the current user → audit gap.
- **L2.** Vendor stubs: `getBookings` returns `new ArrayList<>()`, `sendEmail`/`rateVendor` partly stubbed.
- **L3.** Stale comments / dead code in rate limiting: `RateLimitFilter` says "5 login attempts" but
  `LOGIN_MAX = 3`; `RateLimitService` keeps a large commented Redis block + now-unused imports
  (`List`, `Map`, `RedisScript`, `StringRedisTemplate`).
- **L4.** `printStackTrace()` used in several places (reminder scheduler, cloudinary, etc.) — route through
  the logger instead.
- **L5.** `ddl-auto=update` (documented) — move to migrations + `validate` before prod.
- **L6.** CORS still localhost-only — add the prod origin before deploy (`SecurityConfig`).
- **L7.** `AuthApiService.findById(userId)` (notification actor lookup) is by internal id, not tenant-
  scoped — low risk (internal id, read-only email) but inconsistent with the tenant-scoping rule.

---

## ✅ Verified GOOD (no action)
- Tenant filter wiring is correct: TX advisor `@Order(0)` (outer) opens the session before
  `TenantFilterAspect @Order(1)` enables the Hibernate `tenantFilter`; **all `@Transactional` are
  method-level**, so the `@annotation` pointcut matches (no class-level bypass).
- Tenant-scoped lookups confirmed in Vendor (`findByIdAndTenantIdAndDeletedAtIsNull`) and Reminder.
- `GlobalExceptionHandler` does not leak stack traces (generic handler returns a safe message).
- `JwtAuthFilter` clears `TenantContext` in `finally` (no thread-pool leak).
- SuperAdmin signup is gated (secret header + single-instance `count()` check).
- `JwtUtil` pins the key via `verifyWith` (no alg-confusion).
- Lead list N+1 already mitigated (assignedUser `@EntityGraph`, collections `@BatchSize`); the real
  list slowness was SQL/bind logging — addressed separately (SQL→WARN, bind off, batch_fetch_size,
  `idx_leads_tenant_created`).