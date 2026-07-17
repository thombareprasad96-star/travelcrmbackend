# Unified Lead Source Integration Framework — Scan Findings

**Date:** 2026-07-16 (supersedes the earlier draft at this path)
**Status:** Findings only. No design, no code, no recommendations.
**Scope requested:** (1) existing WhatsApp integration incl. the multi-tenant webhook gotcha, (2) Lead module — source field, create flow, duplicate detection, Kanban landing, (3) SSE feasibility for realtime new-lead push, (4) QuotationWebView public page, (5) Settings/Integrations frontend.

**Verification note.** Claims below carry a `file:line` citation and were re-read against the working tree. Claims I could not confirm from source are marked **UNVERIFIED** inline rather than laundered into fact. Two caveats on the ground truth: nothing here is committed (git status shows ~30 modified files including `SecurityConfig.java` and `SaasPaymentServiceImpl.java`), and `CLAUDE.md`'s frontend section describes a directory that no longer exists (see Area 5).

---

## What I found that changes the plan

**1. The Lead create path cannot be called without a logged-in human — and one instance of that coupling is a latent bug.**
`createLead` (`LeadServiceImpl.java:82-111`) depends on an authenticated tenant `User` at three hard points: `currentTenantId()` (`:83`) throws on a null `TenantContext`; `assignForCreate` → `currentUser()` throws `IllegalStateException("No tenant user in security context")` (`LeadAssignmentService.java:327-330`); and `recordAssignmentAudit` calls `currentUser()` at **line 119 — outside the `try` that opens at line 120** (verified). So despite the comment at `:121-123` promising "an audit problem must never break the actual lead creation," a null principal there **does** roll back lead creation. Any ingestion path either synthesizes a principal or gets a new create path. There is no precedent for programmatic lead creation: `DevDataSeeder` bypasses the service entirely (`:526-543`).

**2. There is no inbound webhook precedent that solves the multi-tenant problem — the only one sidesteps it.**
`RazorpayWebhookController` (`:23,29`) is the sole inbound webhook in the codebase. It **never calls `TenantContext.setTenantId`**, because every entity it writes extends `BaseEntity`, not `BaseTenantEntity` (`WebhookEvent.java:23`) — so no tenant stamp is required. It resolves tenant by reading a **local row the authenticated outbound call created earlier** (`findByGatewayOrderId(orderId)` → `txn.getTenantId()`, `SaasPaymentServiceImpl.java:237-241`), never from the payload. **There is zero precedent in this codebase for an unauthenticated request that establishes a tenant and persists tenant-scoped data.** That is the largest greenfield item.

**3. `TenantFilterAspect` fails OPEN, and the filter is latched *before* your method body runs.** Verified verbatim:
```java
Long tenantId = TenantContext.getTenantId();
if (tenantId == null) {
    return;                       // ← tenantFilter never enabled
}
```
(`common/aspect/TenantFilterAspect.java:34-37`)
A webhook has no JWT → no `TenantContext` → **every tenant-scoped query in that thread silently spans all tenants**, with no error and no log. And because the advice is `@Before` on `@Transactional` (`:22`), calling `setTenantId()` as the first line of a `@Transactional` method is **too late** — the filter is already decided and stays off for the whole transaction. `TravelerAuthServiceImpl` is live code with exactly this shape (`:67` `@Transactional`, `:77` `setTenantId`), surviving only via explicit `findByTenantIdAnd…` finders. Note `softDeleteFilter` **is** enabled unconditionally (`:30-32`), so an unscoped session still hides trashed rows and *looks* correctly filtered. This bug class is invisible in single-tenant dev testing.

**4. Duplicate detection exists, is enforced twice, and actively rejects the framework's core use case.**
A repeat enquiry from a known OPEN lead's phone gets a hard **409**, not an appended activity. `validateNoDuplicates` throws `DuplicateLeadException` (`LeadServiceImpl.java:598-607`), and the DB independently enforces the same predicate via partial unique indexes (`uq_leads_email_tenant_open`, `uq_leads_phone_tenant_open`, `db/indexes.sql:82-87`) — so the service check cannot simply be skipped; Postgres throws a raw constraint violation instead. Dedup is a **rejection mechanism, not a merge mechanism**: no `mergeLead`, no link-to-existing, no candidate scoring, and **no log or counter on rejection** — dropped enquiries are currently invisible. For an IVR "second call from the same number," today's behaviour is *drop the call*.

**5. Adding LeadSource constants will break any pre-existing database — invisibly in local dev.**
`db/indexes.sql` carries ~20 enum CHECK-constraint refresh blocks across ~14 tables and **zero for `leads`** (verified by grep: the only `leads` statements are indexes and the two partial unique indexes, `:41-46,80-87`). The project documents the mechanism in its own words at `:143-147`, describing an **observed** bug: *"Hibernate generated users_role_check from the Role enum when the table was first created… ddl-auto=update never alters an existing constraint, so roles added later (STAFF, ACCOUNTANT) get rejected at the DB level."* The trap: a fresh DB generates the constraint from the *current* enum and works perfectly, so this only bites databases whose `leads` table predates the change. **UNVERIFIED:** that `leads_lead_source_check` actually exists on the pilot DB — the constraint is generated silently by Hibernate and is declared nowhere in the repo, so grepping finds nothing, which misleadingly reads as "no constraint." One query resolves it: `SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='leads'::regclass AND contype='c';`

**6. The FE has no lead Kanban, and the backend board endpoint is dead code.**
`GET /api/leads/board` exists and emits a column per `LeadStage` (`LeadController.java:93`, `LeadServiceImpl.java:341`) — but a grep for `leads/board` across `travelcrmfrontend/src` returns **zero consumers**. The lead list is `AllLeads.jsx`, a 1761-line table filtering entirely client-side over one `getAllLeads(0, 100)` fetch (`leadService.js:45-46`). "New lead lands on the Kanban" is a frontend build, not a wiring exercise.

**7. Realtime push already exists and already fires — the risk is double-notification, not missing transport.**
`publishLeadCreatedNotification` (`LeadServiceImpl.java:641-680`) already fans `LEAD_CREATED` out to every active TENANT_ADMIN + MANAGER. A framework that publishes its own event will **double-notify** for anything routed through `createLead`.

---

## Area 1 — Existing WhatsApp integration (as an integration template)

The WhatsApp stack is a clean, genuinely reusable 3-layer pattern, and it is **strictly outbound**. A per-tenant config entity (`TenantSettings`) holds the credential AES-encrypted; a facade (`WhatsAppMessagingService`) owns tenant lookup, the "is configured?" guard, phone normalisation and the audit row; a swappable `WhatsAppSender` SPI has a `@Primary` real impl displacing a logging stub. Four callers use the facade (OTP, quotation send, booking reminder, marketing dispatch) and none touch the SPI directly. As a template for a new tenant-connectable channel this is the right thing to copy.

**Credential encryption: EXISTS and is mature.** `AesSecretCipher` is AES-256/GCM, 12-byte random IV, 128-bit tag, output `Base64(iv||ct+tag)` into a plain `TEXT` column, key from `app.encryption.key` validated to 16/24/32 bytes at construction — fail-fast at boot (`settings/crypto/AesSecretCipher.java:25-27,32-42,45-60`). It protects exactly two secrets: `smtp_password_enc` and `whatsapp_api_key_enc` (`TenantSettings.java:45,58`). It is a plain `@Component` with **explicit** `encrypt()`/`decrypt()` calls — there is no `AttributeConverter` and no `@Convert` anywhere, so encryption is opt-in per call site and a new entity with a credential field will silently store **plaintext** unless each site remembers.

**The write-only-secret contract** is worth copying verbatim: the key is never returned (`WhatsAppConfigDTO` exposes only `configured`/`apiKeySet`), and writes carry an explicit `apiKeyChanged` flag so a save that doesn't touch the key preserves the ciphertext (`WhatsAppConfigService.java:55-58`).

**Inbound webhook: DOES NOT EXIST.** No message-received endpoint, no delivery-status callback, no Interakt webhook, no signature verification for any WhatsApp payload, no inbound message entity or table. `WaMessageLog` (`whatsapp_logs`) is outbound-attempt-only — `status` is decided purely by whether the outbound HTTP call threw (`WhatsAppMessagingService.java:123-136`), so the settings page's "Delivery Rate" is an **API-acceptance rate**, not a delivery metric.

**And the mapping needed to build one does not exist either.** Two independent gaps make an inbound WhatsApp webhook currently unable to resolve a tenant *at all*:

- **No provider message id is captured on send.** `InteraktWhatsAppSender` discards the response body, so nothing correlates a callback to a log row. This is exactly what `PaymentTransaction.gatewayOrderId` does for Razorpay — and exactly why the Razorpay webhook *can* resolve a tenant.
- **`TenantSettings.whatsAppPhone` is permanently null.** Verified by grep — the field appears in exactly three places: entity (`:62`), DTO (`:19`), and one read (`WhatsAppConfigService.java:124`). **There is no `setWhatsAppPhone` call anywhere.** `WhatsAppConfigRequest` has no phone field; the FE form has no phone input.

A durable local mapping must be created *first* before inbound is even possible.

**The webhook precedent's mechanics, for reuse:** raw `@RequestBody byte[]` with `consumes = ALL_VALUE` because the HMAC is over the exact received bytes (`RazorpayWebhookController.java:29-33`); verify signature **first**, before touching state (`SaasPaymentServiceImpl.java:176-180`); idempotency via `existsByEventId` against a `UNIQUE` column, with a `"sha256:" + hash(rawBody)` fallback when the header is absent (`:190-196,396-401`); ledger row persisted **last** so a failure rolls back and the provider's retry re-processes (`:201-209`). The `WebhookEvent` javadoc (`:9-13`) says the row is inserted *before* processing — it **contradicts the implementation. The code is the truth; the javadoc is stale.** Also note the body-hash fallback means a provider redelivering a byte-identical payload for a genuinely *new* event would be wrongly deduped.

**Caveat on the precedent:** Razorpay is disabled by default (`app.razorpay.enabled=false`, `application.properties:271`) and the fallback `UnavailablePaymentGatewayClient.verifyWebhookSignature` returns `false` unconditionally (`:43-45`). Anyone testing "the webhook precedent" locally will see nothing but 401s.

### Filter-chain behaviour for any new webhook

| Mechanism | Behaviour on `/api/webhooks/**` | Evidence |
|---|---|---|
| `POST /api/webhooks/**` | Already `permitAll`, **prefix-wide** — a new webhook is publicly reachable with zero config change | `SecurityConfig.java:93` (verified) |
| `RateLimitFilter` | **Skipped** — only `/api/auth/` and `/api/portal/auth/` covered. Webhooks are unthrottled | `RateLimitFilter.java:52-56` |
| `ModuleAccessFilter` | **No-ops** — gates on `TenantContext != null`, so plan entitlement is bypassed | `ModuleAccessFilter.java:80-81` |
| `MaintenanceModeFilter` | **No-ops** for the same reason (desirable — a filter-level tenant would 503 the provider) | `MaintenanceModeFilter.java:37` |
| `JwtAuthFilter` | **Runs** (permitAll only affects authorization); early-returns at `:45-48` on a missing Bearer header, so its own `finally` never clears | `JwtAuthFilter.java:43-48,107-113` |
| `ContextCleanupFilter` | **Covers it** — auto-registered at `/*`, `@Order(HIGHEST_PRECEDENCE+1)`, unconditional `clear()` in `finally` | `ContextCleanupFilter.java:52,59-64` |

Two further landmines. `TenantEntityListener` throws when both `TenantContext` and `entity.tenantId` are null (`:15-21`) — but the fourth combination (**explicit tenantId + null context**) matches no branch and **persists silently with zero validation** (`:24-30`); and `preUpdate`'s cross-tenant guard is short-circuited by a null context (`:36`). On a webhook thread both isolation layers are off simultaneously. Separately, if a caller ever sends `Authorization: Bearer <valid JWT>` to a permitAll webhook, `JwtAuthFilter` **will** authenticate it and set `TenantContext` from the token (`:89-90`) — your handler would inherit a caller-chosen tenant it never asked for.

---

## Area 2 — Lead module

### The source field

`Lead.leadSource` is `@Enumerated(EnumType.STRING) @Column(nullable=false, length=50)` (`Lead.java:53-55`), typed to a closed 9-constant enum — verified in full: `SOCIAL_MEDIA, WEBSITE, GOOGLE_ADS, FACEBOOK, INSTAGRAM, WHATSAPP, REFERRAL, DIRECT_CALL, OTHER` (`LeadSource.java:6-15`).

**There are two vocabularies.** `@JsonValue` on `getDisplayName()` means the API emits and accepts the *display* name ("Google Ads"); `@Enumerated(STRING)` persists the *enum* name (`GOOGLE_ADS`); and the DB CHECK constraint would list enum names. `@JsonCreator fromValue` accepts either, case-insensitively, and **throws `IllegalArgumentException`** otherwise (`:28-37`), which `GlobalExceptionHandler` maps to a clean 400 "Please select Lead Source." (`:486-511`). Good news: an unknown source fails loud, never silently defaults. Note two constants sharing a displayName would not fail at compile time — `fromValue` silently returns the first declaration-order match.

**Attribution is essentially absent.** No campaign, ad-id, adset, utm_*, gclid, fbclid, referrer, landing-page, externalId, rawPayload or ingestedAt field exists on `Lead` — and no JSON/JSONB column anywhere in the codebase (`jsonb`, `@JdbcTypeCode`, `SqlTypes` all return zero hits). The only free-text field is `notes` TEXT (`Lead.java:115`). The 9-value enum is the entire provenance model. Nothing anywhere stores a call-recording URL, duration, direction, or external call id.

**Namespace collision — flagging early.** `marketing.entity.Campaign` exists but means an **outbound broadcast** to a Segment (table `marketing_campaigns`, fields `body`/`templateName`/`sentCount`/`totalRecipients`; javadoc at `Campaign.java:14-17`). It has no relationship to an inbound Meta/Google ad campaign, and attributing an inbound lead to it would corrupt its counters and feed junk rows to `CampaignDispatchScheduler`. Inbound ad attribution needs a distinct name, table and package.

**No source reporting exists.** The report module contains no lead-source query; `leadSource` is referenced outside `/lead/` only by `DevDataSeeder` and a `GlobalExceptionHandler` label formatter. The only source breakdown in the product is `Dashboard.jsx:254-258`, computed **client-side** from the fetched lead page — and it is `.slice(0,7)` with only 6 colors, so going to ~24 sources silently shows the top 7 with the 7th always fallback grey and ~17 sources vanishing.

### The create flow

`createLead` (`:82-111`): `currentTenantId()` → `enforceLeadQuota` → `validateNoDuplicates` → `checkTrashedForRestore` → map → `setTenantId` → `assignForCreate` → `save` → `publishLeadCreatedNotification` → `recordAssignmentAudit`.

Minimum valid lead: `customerName`, `phone`, `email`, `leadSource`, `leadType`, `leadStage`, `assignedUserId` — all `@NotNull`/`@NotBlank` and all `nullable=false` at the DB. There is **no server-side default stage** — the client dictates it. Creation is **quota-capped** (`enforceLeadQuota:505-515`, 403 against `Tenant.maxLeads`), so an inbound firehose starts hard-failing at the plan cap — and duplicate-blocked enquiries never reach the counter, so quota metrics under-report real inbound volume.

`assigned_user_id` is a NOT NULL FK with `optional=false` (`Lead.java:65-72`) — **there is no unassigned inbox**. Round-robin/load-based strategies exist and work (`LeadAssignmentService:137-201`) but are reachable only through the privileged (TENANT_ADMIN/MANAGER) branch, which itself requires `currentUser()`. `resolveRecommended` throws when the pool is empty (`:285-292`) — an out-of-hours enquiry into a tenant with no eligible user hard-fails.

The phone `@Pattern` is `^\+?[1-9]\d{7,14}$` (`CreateLeadRequestDto.java:24-27`) — it rejects spaces, dashes, parens and any leading zero. Telephony payloads routinely arrive in exactly those rejected formats. Note `DevDataSeeder` writes phones **with spaces** via the repository, so existing DB rows do not necessarily match the DTO pattern.

`LeadMapper` is a **hand-written `@Component`, not MapStruct** (`:16-17`), contradicting the project-wide rule — adding any attribution field means editing both `toEntity` and `toResponse` by hand, with no compile error if you forget.

### Duplicate detection — EXISTS, in three layers

1. **Service:** `validateNoDuplicates` (`:580-608`) — `existsBy{Email,Phone}AndTenantIdAndDeletedAtIsNullAndLeadStageNotIn(TERMINAL_STAGES)` → `DuplicateLeadException`. Email is checked **first** and lowercased (`:590`); **phone is passed raw** (`:601`) — no normalisation, no trim.
2. **Database:** partial unique indexes `ON leads(col, tenant_id) WHERE deleted_at IS NULL AND lead_stage NOT IN ('CONVERTED','LOST')` (`db/indexes.sql:82-87`). The absolute constraints were deliberately dropped because they permanently blocked repeat business (`:72-79`).
3. **Trash:** `checkTrashedForRestore` (`:616-639`) → `RestoreAvailableException` (409) carrying the trashed publicId. An automated caller has no way to "click Restore."

Consequences worth stating plainly. Terminal stages **release** the natural key, so a returning customer's enquiry legitimately creates a **new** Lead row — any "is this a repeat?" logic must reason about a *chain* of leads per phone and use `findFirstByPhone…OrderByCreatedAtDesc` (`LeadRepository.java:62`); a plain `Optional` finder throws `NonUniqueResultException`. The `existsBy*` checks are **not race-safe** — two concurrent calls from the same number can both pass, and the index then throws a raw `DataIntegrityViolationException`, not `DuplicateLeadException`. The service rule and the index must change **together**, and since there is no Flyway, applying `indexes.sql` is an **operational** step, not a code deploy.

**Three phone treatments coexist and disagree.** `PhoneNormalizer.normalize()` is `trim()` only, deliberately (javadoc: stronger canonicalisation "belongs with a deliberate one-time data migration, not a passive read-path change"); `WhatsAppMessagingService.normalize()` (`:146-156`) does real E.164 canonicalisation; and lead dedup calls **neither**. `Lead` has **no** `customerId` — the only link is `Customer.createdFromLeadId` (`Customer.java:81`), one-directional and only for conversion-created rows. The repeat-customer-by-phone algorithm already exists but lives in the booking module (`BookingServiceImpl.resolveOrCreateCustomer:321-363`) and *does* use `PhoneNormalizer`.

### Kanban

Backend-only, and unconsumed — see finding #6. Note the "seven lanes" comments (`LeadServiceImpl:340`, `LeadController:92`) are **stale**: `LeadStage` has eight constants since `REOPENED` was added (which itself was added with no `lead_stage` check refresh — the same latent bug as #5). `CONVERTED` is a locked stage owned by the booking lifecycle: any transition into or out of it throws 409 (`:425-437`). On the FE, `leadSource` renders **only** in the expanded detail panel (`AllLeads.jsx:652`), never as a row badge, and there is **no source filter** on either the FE or the backend list endpoint (page/size/sortBy/sortDir only).

---

## Area 3 — SSE feasibility for realtime new-lead push

**Feasible, and a working precedent exists on a webhook thread.** `NotifyEventListener` sets `TenantContext` from `event.getTenantId()` **before** invoking any channel (`:24`), which is exactly what `TenantEntityListener.prePersist` reads — so a `NotifyEvent` fired from a context-less thread persists correctly **provided `.tenantId(...)` is set**. `SaasPaymentServiceImpl.notifyTenantAdmins` (`:371-388`) does this live from the Razorpay webhook and is the template. If `tenantId` were omitted, `setTenantId(null)` makes `prePersist` throw and the listener's per-channel catch **swallows it into an ERROR log** — the notification vanishes silently.

Mechanics: `NotifyEvent` is an immutable `@Builder` whose only default is `channels = Set.of(IN_APP)`. `InAppNotificationChannel` persists one row per recipient and SSE-pushes inline. The registry is `ConcurrentHashMap<Long userId, Set<SseEmitter>>` — multi-tab safe, infinite timeout, 25s heartbeat; when no emitter is live `push()` **silently no-ops** and the user sees it only on their next feed/badge poll (there is no offline buffer, no replay, no Last-Event-ID). The event name is the hardcoded string `"notification"`, so the browser must use `addEventListener("notification", …)`, not `onmessage`. `/api/notifications/stream` is `permitAll` (`SecurityConfig.java:100`) and authenticates via `?token=`.

**The main risk is duplicate notifications, not transport** — `LEAD_CREATED` already fires (`LeadServiceImpl.java:641-680`).

Sharp edges that constrain the design:

- **Delivery is synchronous, inside the publisher's transaction.** There is not a single `@TransactionalEventListener` in the codebase. `InAppNotificationChannel` is `@Transactional(REQUIRED)` so it **joins** the caller's tx — its "the DB row is already committed" comment is **false**, and the SSE push fires **before commit**. If the publisher rolls back, the browser has already been told about a lead that does not exist.
- **Publishing nulls the caller's `TenantContext`.** `NotifyEventListener` is a plain synchronous `@EventListener` calling `TenantContext.clear()` in `finally` (`:37-39`) with no save/restore (`TenantContext` is a bare ThreadLocal with no stack). `LeadServiceImpl:108` publishes, then `:109` runs with a null context. **Publish last.**
- **Use `IN_APP` alone.** `SseNotificationChannel`'s duplicate guard (`if (notification != null) return;`) is dead code — the dispatcher hardcodes `null` (`NotifyEventListener.java:31`) — so `IN_APP + SSE` double-pushes. SSE-only pushes leave `publicId` null and can never be marked read.
- **Never include `EMAIL`.** `EmailNotificationChannel.send()` calls `sendAsync(event)` on `this` — a self-invocation, so `@Async` never applies; its retry loop sleeps up to ~6s **synchronously** on the publisher's thread. Its named executor `notificationExecutor` does not exist as a bean anywhere.
- **Always set `recipientUserIds` explicitly.** The implicit admin fallback exists *only* in `InAppNotificationChannel` (`:49-51`) and resolves `TENANT_ADMIN` **only** — silently dropping every MANAGER, unlike every explicit publisher. The SSE and EMAIL channels dereference the list with no null check and NPE.
- **`referenceType` is a String parsed leniently into a closed enum** (`LEAD, BOOKING, REMINDER, CUSTOMER, VENDOR`); unknown values return null with no log and no throw (`NotificationReferenceType.java:19-26`). Already bitten live: `SaasPaymentServiceImpl:384` passes `"BILLING"` → persists as NULL. `LEAD` is available.
- **`payload` is silently dropped for IN_APP/SSE** — only the email channel reads it. `LEAD_CREATED` already attaches a 4-key payload that goes nowhere (`:669-674`). Title/message must be fully pre-rendered.
- **Cosmetic but visible:** `LEAD_CREATED`'s message string-concatenates the enum (→ `toString()` → `DIRECT_CALL`, not the display name) and `departCity`, which is nullable — producing `"DIRECT_CALL lead from null assigned to X"` (`:664`).

**No permission-based recipient resolution exists.** There is no way to ask "which users hold `LEAD_READ`?" — `UserPermissionRepository` offers only `findByTenantIdAndUserId` and `findByTenantIdAndUserIdIn`, both requiring the candidate ids up front. Every existing resolution is role-**string** based via a copy-pasted `findByTenantIdAndRoleInAndIsActiveTrue(tenantId, List.of("TENANT_ADMIN","MANAGER"))` idiom repeated at ~9 sites.

---

## Area 4 — QuotationWebView public page

A pure **capability-URL** model: access *is* knowledge of the unguessable `publicId` UUID. No token, no signature, no expiry, no revocation, no view cap; soft-delete is the only kill switch (`PublicQuotationController.java:23-30,41-49`). Tenant is not resolved at all — a global `findByPublicIdAndDeletedAtIsNull` (`QuotationServiceImpl.java:342-349`) — which works *only* because the tenant filter fails open and `ContextCleanupFilter` guarantees the thread arrives clean. This is a **thread-hygiene dependency, not just a permission one**: `ContextCleanupFilter`'s javadoc (`:20-38`) names this share link as a real observed victim of a stale-tenant leak (links intermittently 404'd depending on which thread served the request).

**The single most important constraint for a public enquiry form:**
```java
.requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()
```
(`SecurityConfig.java:95` — verified)

**It is GET-only.** A `POST /api/public/…` compiles, deploys, and **401s at runtime**, falling through to `.anyRequest().authenticated()` (`:108`). The prefix merely *looks* already-permitted. Meanwhile `POST /api/webhooks/**` is already open and prefix-wide (`:93`) — so the prefix choice alone silently decides the auth posture.

**There is no POST-back channel** from the page today: every CTA is a `tel:`/`wa.me`/`mailto:`/PDF deep link (`QuotationWebView.jsx:646-662`), and the page makes exactly one network call — the GET at `:94`. A weblink enquiry has no existing transport.

Also relevant:
- The backend **never generates** the `/q/{publicId}` web-view URL. `getShareLink()` (`:494-502`) emits the *PDF API* URL, and `app.public-base-url` is **doubly-bound** — it is also the Razorpay webhook base (`application.properties:206-209,268`), so it cannot be repointed at the frontend origin without breaking the registered webhook.
- **CORS is not an access control here.** One global config on `/**` with `allowCredentials=true` (`SecurityConfig.java:128-139`) makes `*` categorically illegal in browsers. But CORS is a browser-JS control only — a plain HTML `<form>` POST and any server-to-server POST are **not** subject to it. "CORS blocks it" is true for a JS widget, **false** for a form-post or backend relay.
- The public read uses `ClientIp.resolve`, which trusts `X-Forwarded-For` **unconditionally** and whose own javadoc says "(not a security control)" (`ClientIp.java:5-8,17-21`) — weblink analytics are trivially spoofable. `RateLimitFilter.resolveClientIp` (`:84-96`) is the correct one for anything security-bearing.
- No captcha/honeypot/bot protection exists anywhere (grep across both repos: zero). No JSON body-size limit is configured — only multipart (`:319-320`) — and a webhook binds the body as `byte[]` fully into memory.
- The public DTO uses **whitelist projection**, never blacklist-stripping, with `markup` deliberately omitted (`:352-371`). Copy this discipline for any public response.
- `WeblinkAnalyticsService` (`:30-43,64`) is the directly reusable shape for *writing* tenant data from a public thread: `@Async` (so the ThreadLocal definitively does not propagate), re-resolve `tenantId` from the entity, pass it **explicitly** to both finder and builder, swallow all failures.
- The public PDF endpoint 302-redirects to a cached Cloudinary URL when set (`:332-335`) — that URL is permanently public and **survives any revocation the app later implements**.

---

## Area 5 — Settings / Integrations frontend

**First, a correction to `CLAUDE.md`.** `D:/CRM PROJECT/frontend` **does not exist**. The real frontend is `D:/CRM PROJECT/travelcrmfrontend`. There is also no `src/services/axiosInstance.js` — the shared client is `src/shared/api/http.js`, imported as `import API from '@shared/api/http'`. The `localStorage` key is still `"token"`, so that part holds. Stack is Vite + React 18 + **Tailwind v4** — no `tailwind.config.js`; tokens live in an `@theme` block in `src/index.css:17-30`.

**There is no Integrations page, route, tab, or nav item** — and no tab component in `settings/` at all. But the structure is already the right shape: `CompanySettings.jsx` is **already a channel-card grid** — a static `CARDS` array of 3 descriptors (`:19-113`) → `SettingsCard` (`:129-227`) → `navigate(card.route)`, in a responsive grid (`:356-360`), with live status merged over the static template via `Promise.allSettled` + a `cardOverride` map (`:236-283`). An Integrations grid is this pattern with N channels.

The per-channel backend contract is uniformly 4 endpoints — `GET /config`, `POST /config`, `POST /test`, `GET /stats` under `/api/settings/{channel}` — identical for both existing channels (`WhatsAppConfigController.java:30,36,43,52,61`; `EmailConfigController.java:29,35,42,51,60`). That uniformity is the strongest signal for how an N-channel backend should be shaped.

**Does the FE hardcode source lists? YES — and it has already drifted.** Verified side-by-side:
```js
const LEAD_SOURCES = [
  "Social Media", "Website", "Google Ads", "Facebook",
  "Instagram", "WhatsApp", "Referral", "Direct Call",
];
```
(`features/leads/components/LeadInformation.jsx:6-9` — **8 entries**, hardcoded in a *form component*, not fetched, not exported from the feature barrel)

The backend enum has **9** (`LeadSource.java:6-15`). **"Other" is missing.** A lead whose source is `OTHER` — the natural fallback — cannot be selected or re-selected in the UI today. This is live proof that hardcoding has already failed once.

It gets worse on the edit round-trip: `EditLead.jsx:106` resets `leadSource` from the API into a `<select>` whose options come from that array. An unknown value matches no option and falls back to the empty placeholder; if the user saves without touching it, `leadService.js:9` sends `leadSource: ""` → 400. So **every pre-existing lead carrying a new-vintage source becomes un-editable until the FE ships.** Backend and FE must ship together, or the FE must become data-driven first.

**A reusable auto-sync pattern already exists** and should be adopted rather than reinvented: `MarketingFieldCatalog.fields()` derives options straight from `.values()` — `Arrays.stream(X.values()).map(t -> new OptionDTO(t.name(), t.getDisplayName()))` (`:30-54`) — served as `ApiResponse<List<SegmentFieldDef>>` (`SegmentController:37-40`). `OptionDTO(value=name, label=displayName)` maps 1:1 onto the two-vocabulary problem. (`VendorController:45-52` does something similar but returns a **bare Map**, bypassing the mandatory `ApiResponse` envelope — do not copy that shape.) The lead module already has the pattern in-house for `LeadStage`; it was simply never applied to source.

Other constraints: there is **no shared UI kit** with cards/buttons/inputs — `shared/ui/` has only `gridTable`, `toast` and an icon. Six per-feature kits exist; the newest and most complete is `marketing/components/marketingUi.jsx` (already ships a `ChannelBadge`, though it is a binary WHATSAPP/EMAIL ternary at `:156-164` that a third channel forces into a lookup map). It is **not exported** from `marketing/index.js`, so importing it from `settings/` would breach the documented boundary rule (`FEATURE-STRUCTURE.md:81`) — copy or promote to `shared/`. The design-system claim is a literal quote — "Design system is untouchable: gradient bg, glass cards, blue-600, Plus Jakarta Sans" (`FEATURE-STRUCTURE.md:73`) — but it is a rule, not an enforced kit, and channel pages already override the accent (the WhatsApp page is green/emerald).

Two live gotchas not to copy: `isConfigured` derives from **form** state, not server state (`WhatsAppConfiguration.jsx:213`), so the Test button flips true before saving and can fire against unsaved config; and the test endpoint returns **HTTP 200 with `{success:false}`** on failure (`WhatsAppConfigService.java:86-88`) — treating 2xx as success reports broken credentials as working. The three settings pages are also **not** on the shared toast or the `apiError` contract — they hand-roll a local Toast and use the deprecated `err?.response?.data?.message` idiom (`:50-71,177,200`), the exact anti-pattern `apiError.js` was written to kill.

---

## Reuse inventory

| Need | Status | Evidence / note |
|---|---|---|
| **Credential encryption at rest** | **EXISTS** | `AesSecretCipher` AES-256/GCM (`settings/crypto/AesSecretCipher.java:25-27,45-60`). Reuse verbatim. Explicit calls only — no `AttributeConverter`/`@Convert` exists, so a new field silently stores plaintext if you forget. |
| **Per-tenant config storage** | **EXISTS, but strained** | `TenantSettings` (`:24,30`) — one **flat** row per tenant, hardcoded columns for exactly 2 integrations. No type discriminator, no many-per-tenant connections, no connection status. Cannot host a multi-connection framework without column sprawl. Also **no unique constraint** on `tenant_id` — one-per-tenant is convention only (`findByTenantId` + `orElseGet`), so a race can create duplicates and then `findByTenantId` throws. |
| **Write-only-secret contract** | **EXISTS** | `apiKeyChanged` + `apiKeySet` (`WhatsAppConfigService.java:55-58`; `WhatsAppConfigDTO.java:14-15`). |
| **Adapter/strategy registry** | **EXISTS** — copy exactly | `OtpSenderResolver:18-39` and `LeadAssignmentStrategyResolver:17-32` — two independent instances of: interface self-declares its key, `@Component` resolver takes `List<Strategy>` in the ctor and folds into an `EnumMap`, `resolve()` throws on miss. Adding an adapter never edits the resolver. This *is* a `LeadSourceAdapter` registry. |
| **Pluggable-provider default/stub** | **EXISTS** — two idioms | `@Bean` + `@ConditionalOnMissingBean` inside a `@Configuration` (`PortalPaymentConfig.java:10-23`; also EInvoice, LedgerExport) — javadoc warns it is "only reliable in the configuration phase," so never on a `@Component`. Or `@Primary` + `@ConditionalOnProperty` when both are `@Component`s (`InteraktWhatsAppSender.java:34-36`). |
| **Per-tenant credentialed client factory** | **PARTIAL** | `TenantMailSenderFactory:40-57` exists but has **no cache** — rebuilds the client and re-decrypts on *every* call. Not a caching precedent. `InteraktWhatsAppSender` (shared `RestClient` in the ctor + per-call credential decrypt, `:44-51,55`) is the better model for a hot path. |
| **Per-tenant scheduler loop** | **EXISTS** — copy exactly | `DocumentExpiryReminderScheduler:44-59`, `CampaignDispatchScheduler:30-42`. Externalized cron; `setTenantId` **outside** the transaction; delegate **cross-bean** (self-invocation defeats both the tx proxy *and* the tenant filter); `clear()` in `finally` every iteration; one tenant's failure never blocks others. |
| **Notification recipient resolution** | **PARTIAL** | Role-**string** resolution exists (`findByTenantIdAndRoleInAndIsActiveTrue`), copy-pasted at ~9 sites. The module's own facade (`TenantAdminResolver` → `activeAdminIds`) resolves `TENANT_ADMIN` **only** — narrower than every explicit caller. **Permission-based must be BUILT** — no reverse lookup exists (`UserPermissionRepository:15,22`). |
| **Realtime push to browser** | **EXISTS** | `NotifyEvent` + `SseEmitterRegistry`; webhook-thread precedent at `SaasPaymentServiceImpl:371-388`. Use `IN_APP` alone; set `recipientUserIds`; publish last. `LEAD_CREATED` already fires — beware double-notify. |
| **Rate limiting** | **PARTIAL** | `RateLimitService.isAllowed(key, max, window)` is generic and key-agnostic — `CampaignDispatchService:140` proves it keys on tenant+channel, not just IP. But `RateLimitFilter` covers **only** auth paths (`:52-56`), so wiring for public/webhook paths must be **BUILT**. In-memory per-JVM — not correct behind >1 node. |
| **HMAC signature verification** | **PARTIAL** | Working code exists but is payment-specific, behind `PaymentGatewayClient` (`RazorpayGatewayClient:116-123,143-166` — fails closed, `MessageDigest.isEqual` constant-time). A **general** inbound-payload verifier must be **BUILT**. |
| **Webhook idempotency** | **PARTIAL** | `WebhookEvent` + `existsByEventId` + `UNIQUE` (`:15-17,23`) proves the pattern, but it lives in `platform/payment`, hardcodes provider `RAZORPAY`, and has **no tenant_id**. A generic dedup table must be **BUILT**. |
| **Raw payload / inbound audit log** | **BUILT** | `WebhookEvent` stores **no** raw body and no headers — payload is parsed, routed, discarded. Nearest structural model: `AiAuditLog.tool_params` (TEXT holding a JSON snapshot, `:46-48`). Note `error_message`/`processed=false` exist but are **never written** — a failing event rolls back its own ledger row and leaves no trace. |
| **Tenant resolution without JWT** | **BUILT** (the big one) | Four variants exist, **no shared helper**: (a) don't resolve at all (public quotation); (b) resolve from the entity + stamp explicitly (`WeblinkAnalyticsService:41-43,64`); (c) resolve from a **local row keyed by the provider's id** — the only variant an attacker cannot steer (`SaasPaymentServiceImpl:237-241`); (d) global identity lookup then `setTenantId` (`TravelerAuthServiceImpl:139-143,77`) — first-match-wins, ambiguous when two tenants share a phone, and done *inside* `@Transactional`. `TenantContext` is a bare `ThreadLocal<Long>` with **no stack and no save/restore** — nesting destroys the outer value. |
| **Opaque tokens / per-tenant ingest key** | **BUILT** | No token/slug/site_key/form_key/webhook_secret column exists on any entity. `SecureRandom` appears in exactly 2 files (OTP codes, GCM IVs) — neither a URL identifier. Every public identifier is a Hibernate `publicId` UUID. The Razorpay webhook secret is **one global platform value**, not per-tenant. **This is why a first-contact inbound POST currently has no way to declare which tenant it belongs to.** |
| **Retry / backoff / DLQ** | **BUILT** | Nothing anywhere has `attempt_count`, `next_retry_at`, or `max_attempts`. `CampaignRecipient` has status + error but never retries. The webhook path relies on the **provider** re-delivering. |
| **OAuth / token refresh** | **BUILT** | Every integration uses a static long-lived credential. No refresh_token storage, no `expires_at`, no refresh job, no 401-triggered re-auth. A Meta Lead Ads adapter needs all of it. |
| **Enum → FE dropdown auto-sync** | **EXISTS** — adopt it | `MarketingFieldCatalog:30-54` + `SegmentController:37-40` + `OptionDTO`. No lead equivalent exists. |
| **`ContextCleanupFilter` safety net** | **EXISTS, already covers you** | `@Order(HIGHEST_PRECEDENCE+1)`, auto-registered at `/*`, unconditional clear (`:52,59-64`). Any new non-JWT endpoint that sets `TenantContext` is already protected from leak — but must not *assume* the context is empty on entry. |
| **Userless-write listeners** | **EXISTS, safe** | `OwnershipEntityListener:30-40` leaves `ownerUserId` null with no principal; `AuditorAware` returns `"system"` (`AuditingConfig:17-27`). Neither throws. A webhook write needs only the **tenant**, not a principal. |
| **ArchUnit tenant-isolation gate** | **EXISTS — will fail your build** | `TenantIsolationArchTest:60-61,119-149` bans `findById`/`getReferenceById`/`EntityManager.find` on any `BaseTenantEntity` repo. Razorpay's `findById` is legal only because its entities are `BaseEntity`. A webhook touching real tenant data **cannot** copy that idiom, and the test's javadoc (`:52-55`) forbids adding to `EXEMPT_CLASSES`. |
| **Enum CHECK-constraint refresh** | **EXISTS as a pattern, ABSENT for leads** | ~20 blocks in `db/indexes.sql`; none for `leads`. Nullable enums need the `IS NULL OR` disjunct (`:203-205,288-290`). `WaMessageLog.status` is a free String, not an enum — a deliberate sidestep worth noting. |

---

## Open questions for the owner

Genuine product/architecture decisions the code cannot answer. Each changes the design materially.

1. **Repeat enquiry: append or block?** Today a second enquiry from an open lead's phone is a hard 409 and the enquiry is *lost*, with no trace. Should inbound append a `LeadLog` (find-or-create), bump a counter, re-open a LOST lead, or keep rejecting? This is the single biggest behavioural decision and it determines whether the framework can reuse `createLead` at all.

2. **Channel-grain or campaign-grain attribution?** Is this "~15 more `LeadSource` constants" (cheap, but a closed enum needing a DDL block per addition, and one that still cannot carry a campaign name, ad id, utm set, or call id), or a proper attribution model — new columns/entity on Lead? If the constants are a *proxy* for campaign tracking, the enum is the wrong container. Note the collision: `marketing.Campaign` already means outbound broadcast and must not be overloaded.

3. **How does an inbound request declare its tenant?** No opaque-token infrastructure exists. Options with real trade-offs: a per-tenant ingest token in the URL path; a per-tenant HMAC secret (chicken-and-egg — the secret lookup must be by a *platform-level* index, since you don't know the tenant yet); or provider-account-id → local mapping row (the Razorpay variant — the only one an attacker cannot steer, but it requires creating the mapping during an authenticated setup step first). **Never** derive tenant from an unvalidated body field: `TenantEntityListener` will not catch it on a context-less thread (`:24-30`).

4. **Do inbound leads consume the plan lead quota?** `enforceLeadQuota` throws 403 (`:505-515`); a webhook caller cannot act on a 403. Bypass, queue, or hard-fail?

5. **Who owns an inbound lead?** `assigned_user_id` is NOT NULL — there is no unassigned inbox, and the auto-assign strategies require a `User` principal. Options: a system principal, an "inbound" pseudo-user, a nullable-owner migration, or a per-tenant default assignee. Also: assignment hard-fails when the eligible pool is empty (`:285-292`) — what should an out-of-hours enquiry do?

6. **Phone-only leads?** Making `email` optional needs a DTO change **and** an `ALTER` that `ddl-auto=update` will not perform, **and** a decision on the email dedup branch. A synthesized placeholder must be unique per lead (e.g. phone-derived) or it collides on the second inbound lead via `uq_leads_email_tenant_open`. Should phone become the sole identity key for inbound?

7. **Phone canonicalisation — now or never?** Three treatments coexist (DTO regex rejects separators; lead dedup compares raw; booking uses `trim()`-only `PhoneNormalizer`, whose javadoc says E.164 "belongs with a deliberate one-time data migration"). Inbound delivers E.164; the FE may not. Without a decision, `+919876543210` / `919876543210` / `+91 98765 43210` are three different leads. A migration touches existing rows — that is your call, not the code's.

8. **FE data-driven before or with the backend change?** Given the live 8-vs-9 drift and the edit-round-trip 400, shipping enum constants without a meta endpoint makes existing leads un-editable.

9. **Does inbound need a plan entitlement gate?** `ModuleAccessFilter` no-ops without a `TenantContext` (`:80-81`), so `/api/webhooks/**` bypasses the module check entirely. A tenant whose plan excludes `LEADS` (or a future `INTEGRATIONS` module) would still have inbound leads processed unless the handler re-checks `entitlementService` after resolving the tenant.

10. **Delivery/read receipts and inbound WhatsApp messages — in scope?** Each requires an Interakt inbound webhook that does not exist, a stored provider message id that is currently discarded, and an inbound message table. Note `features/leads/pages/WhatsAppPanel.jsx` **looks** like a working inbound chat inbox but is 100% mock (hardcoded `useState`, `wa.me` deep link, zero API calls) — do not mistake it for existing capability.

11. **Single-node or multi-node?** `RateLimitService` and the OTP store are in-memory per-JVM by documented design. If inbound throttling matters and we run >1 node, a Redis-backed limiter is in scope.

12. **Should a rejected/duplicate enquiry be recorded at all?** Today they vanish. If you want to measure inbound volume or debug a provider, that needs a raw-payload store that does not exist.