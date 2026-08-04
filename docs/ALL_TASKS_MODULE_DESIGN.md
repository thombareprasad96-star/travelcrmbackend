# All Tasks Module — Investigation & Design Proposal

**Status:** BUILT (4 Aug 2026) — backend + frontend implemented and building green.
**One manual step remains:** the V2 re-stamp (see §D3). Until it runs, the four Spring-context tests
fail on a Flyway checksum mismatch and the app will not boot against an already-stamped database.
**Date:** 4 Aug 2026

## Implementation status

| Phase | Scope | State |
|---|---|---|
| 0 | `TenantTimeZone` in `task/`, `TaskAccessGuard`, strict enum parsing | ✅ built |
| 1 | V2 PART 18 + `indexes.sql` mirror, entity/DTO/mapper fields | ✅ built (needs re-stamp to apply) |
| 2 | `GET /api/tasks/list` + `/tab-counts` | ✅ built |
| 3 | `AllTasks.jsx` + `tasksUi.jsx` + router/sidebar/Navbar wiring | ✅ built, `npm run build` green |
| 4 | `UserDirectory.phoneById`, `DeliveryChannel.WHATSAPP`, `WhatsAppNotificationChannel` | ✅ built |
| 5 | `EmailNotificationChannel` `@Async` fix + `notificationExecutor` bean | ✅ built |
| 6 | `TaskOverdueScanner` + per-tenant rate limiting + bell routing | ✅ built, 14 unit tests green |

Tests: **517 of 521 pass.** The 4 failures are `TravelcrmApplicationTests.contextLoads` and the three
`LeadClaimConcurrencyIT` methods, all failing on `Migration checksum mismatch for migration version 2`
— a direct consequence of appending PART 18, cleared by the re-stamp. No test fails on logic.

Decisions taken (the seven open questions in the original proposal):
1. Task-only; the Reminder overlap is left as-is and remains a known product duplication.
2. "Due At" uses the calendar anchor `COALESCE(startAt, dueDate)`, consistent with `isOverdue()`.
3. Booking link is a manual `bookingPublicId` on create/update, mirroring the lead link.
4. `trip_source` is a **snapshot** string, resolved from the booking's lead (else a direct lead link).
5. Overdue channels: IN_APP always; WhatsApp/email are **per-tenant opt-in**, default off
   (`tenant_settings.task_overdue_alert_whatsapp` / `_email`) — the only off-ramp, since no
   per-user mute exists anywhere in the product.
6. Template name is config-driven on the `settings/` path (`app.whatsapp.templates.task-alert`),
   not `CommTemplate` (which has zero code behind it).
7. `overdue_notified_at` is a timestamp, and is cleared when a task leaves the overdue state, so a
   re-scheduled or re-opened task can alert again.

### Deliberately NOT done

- **By-id access was not tightened.** `findOrThrow` still applies `SubAgentScope` only. Routing it
  through `ScopeResolver` would 404 tasks that a TRAVEL_AGENT can open today, while the board and
  calendar still list tenant-wide — producing rows that are listed but 404 on click. The stricter
  rule is written and unused as `TaskAccessGuard.assertVisibleStrict`; switching to it must be done
  together with narrowing the board/calendar list. **Owner decision.**
- No HTML email template — the overdue email is plaintext. `templates/` is PDF-only.
- No WhatsApp retry/outbox — one attempt, logged to `whatsapp_logs`. A failed alert is lost.
- No delivery confirmation — provider status callbacks are still ignored product-wide.
- The WhatsApp template itself must be created and approved provider-side with arity 2
  (`{{1}}` title, `{{2}}` message) before the WhatsApp leg will send anything.
**Trigger:** Sembark-style "All Tasks" screen — columns Due At (+Overdue badge), Description,
Trip Source, Guest, For (Trip#), Created By, Assigned To; left-rail tabs Today / Yesterday /
Overdue / Upcoming / All with count badges; overdue tasks firing WhatsApp / email / in-app alerts
to the assigned agent.

> **Headline:** A complete `task/` module already exists and ships in the V1 baseline schema. This
> is **not** a greenfield build. It is (a) a new paginated read surface + tab counts, (b) three new
> denormalised columns for the booking/guest columns, (c) one new scheduler, and (d) one new
> notification channel. The notification spine needs **no changes at all**.

---

## PART A — Existing Task / Reminder concepts

### A1. Do we already have a Task or Reminder entity? — YES, both. Independently.

| | `Task` (`task/`) | `Reminder` (`reminder/`) |
|---|---|---|
| Table | `tasks` + `task_logs` (V1 baseline, **zero drift** vs entity) | `reminders` + `reminder_logs` |
| Base | `BaseTenantEntity` + `Ownable` | `BaseTenantEntity` + `Ownable` |
| API key | `publicId` (UUID) ✅ | internal `Long id` ❌ |
| Scheduling | `startAt` / `endAt` / `allDay` / `dueDate` (nullable) | `dueDate` **NOT NULL** only |
| Taxonomy | `TaskCategory` (9), `TaskPriority` (4), `TaskStatus` (4) | `ReminderType` (8), `ReminderPriority` (3), `ReminderStatus` (5) |
| Overdue | **derived** `@Transient isOverdue()` | **mutated persisted status** `OVERDUE` + live query |
| Due notification | ❌ none | ✅ `ReminderScheduler` → `REMINDER_DUE` NotifyEvent |
| Assignment notification | ✅ `TASK_ASSIGNED` (IN_APP) | ❌ none |
| Snooze | ❌ | ✅ `snoozedUntil` |
| Lead link | `leadRefId` / `leadPublicId` / `leadName` | same + `phone` |
| Booking / customer link | ❌ **none** | ❌ **none** |
| Frontend use | only inside `/calendar` (TaskBoard Kanban) | whole nav section + 2 bells + login popup |

Enum values, verbatim:

```
TaskStatus     TODO, IN_PROGRESS, DONE, CANCELLED
TaskPriority   LOW, MEDIUM, HIGH, URGENT
TaskCategory   GENERAL, FOLLOW_UP, CALL, MEETING, PAYMENT, DOCUMENT, VISA, TRAVEL, OTHER

ReminderStatus    Active, Snoozed, Completed, Dismissed, OVERDUE   ← note the casing break
ReminderType      First_contact, Follow_up, Quotation, Payment, Document, Birthday, Confirmation, Custom
ReminderPriority  High, Medium, Low
```

`Task*` enums are `UPPER_CASE` with no `@JsonValue`. `Reminder*` enums are `Mixed_Case` with no
`@JsonValue` — **the opposite convention from `LeadStage`/`LeadSource`**, which do use `@JsonValue`
displayName strings. Do not assume the lead idiom here.

**Critical overlap finding:** nothing converts a Task into a Reminder or vice versa. **Both** appear
on the calendar feed as separate `CalendarSource` values, so one real-world follow-up can legitimately
appear **twice** — a lead-log follow-up creates a `Reminder` (`LeadLogServiceImpl.java:222-237`)
while a manually created `Task` for the same lead is an independent row. This duplication is a
pre-existing product problem that an All-Tasks screen will make visible. See **Open Decision 1**.

### A2. Existing "follow-up" / "call back" concepts

The follow-up concept is real but **spread across four unlinked places**:

1. `Lead.followUpDate` — `LocalDate`, a mirror field (`Lead.java:204-210`)
2. `LeadLog.followUpDate` — `LocalDate`, the history (`LeadLog.java:45-47`)
3. `Reminder` — the only aggregate with a due **timestamp** + assignee + status lifecycle
4. `report/followup` — read-only, renders Reminders as "tasks"

Trace of what actually creates a Reminder (`LeadLogServiceImpl.addLog`):

- log **with** `createReminder=true` + `followUpDate` → **creates a Reminder**, due at a **hardcoded
  09:00 server-local** (`LeadLogServiceImpl.java:222-239`)
- log with `followUpDate` but `createReminder=false` → only mirrors the date onto the Lead. No
  Reminder, nothing in the bell, nothing in the popup, nothing in the follow-up report.
- **creating a Lead with `followUpDate` creates NOTHING** — `LeadServiceImpl.java:520` sets the field
  and the file never imports the reminder package.
- deleting a log repairs `Lead.followUpDate` but **leaves the Reminder it created alive and due**.

There is no follow-up concept on `Booking` or `Quotation`. `activity/` is a separate audit-log
subsystem, not a task timeline.

### A3. Multi-tenant isolation fit — already solved, nothing new needed

`Task` already extends `BaseTenantEntity` and implements `Ownable`; `TaskServiceImpl.create` stamps
`ownerUserId = creator` explicitly. Rules a new read path must follow:

- `TenantFilterAspect`'s pointcut is `@Before("@annotation(...Transactional)")` — **method-level
  `@Transactional` only**, and it **fails open** when `TenantContext` is null. Put `@Transactional`
  on every new service method *and* keep the explicit `tenantId` predicate in the Specification, as
  `TaskSpecification.build` already does.
- `findById` / `getReferenceById` bypass the filter. Guarded by a real ArchUnit test
  (`TenantIsolationArchTest`) that fails the build.

**Row scope gap:** `Task` uses only `SubAgentScope` (confines `SUB_AGENT` to `owner_user_id = self`).
The richer `ScopeResolver` (OWN / TEAM / ALL / NONE, with a `TENANT_ADMIN → ALL` bypass) is **never
consulted** by `TaskServiceImpl`. So today every non-sub-agent role sees every task in the tenant.
Also note the asymmetry: row scope filters on **owner**, the grid column shows **assignee** — a task
assigned to a sub-agent but owned by a manager is invisible to that sub-agent under `/tasks/my`.

### A4. Reusing the lead-assignment Strategy Pattern for "Assigned To"

A genuine Strategy Pattern exists: `LeadAssignmentStrategy` with 4 implementations, selected by enum
through `LeadAssignmentStrategyResolver`. Its workload metric has **already been extracted** into a
module-neutral `workload/WorkloadService` that counts **tasks + leads + reminders** — and
`TaskServiceImpl.getWorkload()` already consumes it.

**But** the strategy entry points are hard-typed to lead creation — `assignForCreate(UUID)`,
`assignForInbound(Long)`. There is no generic "assign this thing" method. So: the *scoring* is
reusable as-is; *auto-assigning a Task* needs a new generic entry point. Given the Sembark screen
only **displays** the assignee, I propose **deferring auto-assignment entirely** — out of scope.

### A5. Missed-reminder popup on login — reusable, but it is 100% frontend

There is **no backend login-time endpoint**. The popup is `ReminderPopupCenter.jsx`, mounted in
`Layout.jsx:138`, which polls `GET /api/reminders` (all statuses) + `GET /api/booking-reminders?status=Pending`
every 60s and on window focus, filters client-side, and suppresses with a `localStorage` hide-map.

The pattern **does** transfer to task due-reminders unchanged, and is the cheapest way to get a
"you have overdue tasks" popup. It is polling, not push — which is fine, since the same component
already runs. I propose extending that component rather than writing a second one.

---

## PART B — Communication / notification backend audit

### B1. NotifyEvent architecture

`NotifyEvent` is a Lombok `@Builder` value object whose `type` is a **free-form `String`** — so a new
`TASK_OVERDUE` type costs zero schema and zero module changes. Dispatch is `NotifyEventListener`,
a **synchronous** `@EventListener` on the publisher's own thread; it save/restores `TenantContext`
(it does **not** clear — several files' comments still claim it does and are stale), and wraps each
channel in try/catch so one failure cannot abort the others.

Three channels are registered: `InAppNotificationChannel` (IN_APP — DB row + SSE push),
`SseNotificationChannel` (SSE — ephemeral), `EmailNotificationChannel` (EMAIL).

**20 publish sites exist across the backend, and every single one passes
`.channels(Set.of(DeliveryChannel.IN_APP))`.** Nothing has ever requested EMAIL or SSE. So the email
leg is architecturally complete but **never exercised in production** — turning it on for overdue
tasks is a *first use*, not a reuse.

`TaskServiceImpl` **already** fires `TASK_ASSIGNED` (IN_APP) on create and on assignee change, with
a correct self-assign guard (`TaskServiceImpl.java:408-422`). Nothing fires on completion or due.

**`NotificationReferenceType` has no `TASK` value** — only `LEAD, BOOKING, REMINDER, CUSTOMER, VENDOR`,
backed by a DB CHECK constraint. So every existing `TASK_ASSIGNED` row already persists
`reference_type = NULL` and cannot be deep-linked from the bell.

### B2. SSE layer

`SseEmitterRegistry` keeps **two** indexes — `Map<Long, Set<SseEmitter>> byUser` and `byTenant`, both
`ConcurrentHashMap` of `CopyOnWriteArraySet` — so multiple browser tabs are supported with no
overwrite. `new SseEmitter(0L)` (no server timeout), `@Scheduled(fixedRate = 25_000)` comment
heartbeat (nginx `proxy_read_timeout` is 60s). `GET /api/notifications/stream?token=` is `permitAll`
and validates the token manually; `TenantContext` is deliberately not cleared in the controller —
`ContextCleanupFilter` handles it.

Per-user isolation is **structural** (`byUser.getOrDefault(userId, …)`); a cross-user leak is not
expressible. A separate `PlatformSseEmitterRegistry` exists for the SuperAdmin realm because
`super_admins.id` and `users.id` come from independent sequences.

**Verdict: reuse as-is, zero changes.**

### B3. WhatsApp — outbound is REAL (and it is Interakt, not Meta Cloud API)

CLAUDE.md is stale here. The wired sender is `InteraktWhatsAppSender`, annotated
`@Primary @ConditionalOnProperty(name="app.whatsapp.provider", havingValue="interakt", matchIfMissing=true)`,
POSTing to `https://api.interakt.ai/v1/public/message/` with `Authorization: Basic <tenant API key>`,
where the key is `cipher.decrypt(ts.getWhatsAppApiKeyEnc())` from `tenant_settings.whatsapp_api_key_enc`
(per-tenant, AES-encrypted — **not** an env var). `LoggingWhatsAppSender` is the fallback stub.

Capability: **template messages only** — ordered `bodyValues` for `{{1}}, {{2}}…` plus an optional
`mediaUrl` header. There is no free-text path anywhere. Template names resolve through a 3-layer
fallback: `app.whatsapp.templates.{otp,quotation,reminder}` property → `tenant_settings.wa_template_name`
→ explicit per-call name via `sendTemplate(tenantId, phone, templateName, bodyValues)`.

An **inbound** webhook also exists — `POST /api/webhooks/leads/whatsapp/{token}` with real HMAC-SHA256
verification (`Interakt-Signature`, `sha256=` prefix, constant-time, fails closed) — but it feeds the
**lead ingest** pipeline, not a message inbox.

**Three real gaps that matter for this feature:**

1. **Rate limiting is NOT applied on the WhatsApp send path.** `RateLimitService` exists (in-memory
   fixed window, per-JVM) but is only wired into the auth/webhook servlet filter and
   `CampaignDispatchService.sendBatch`. A per-tenant overdue sweep that WhatsApps every assignee would
   hit Interakt unthrottled. Any new scheduled WhatsApp job **must** copy the
   `rateLimitService.isAllowed("key:" + tenantId, n, Duration.ofMinutes(1))` idiom explicitly — it is
   not inherited.
2. **No retry, no outbox, no dead-letter.** One attempt in a try/catch, then an append-only
   `whatsapp_logs` row with a free-String status of `SENT` or `FAILED`. Nothing re-reads a FAILED row.
   By contrast the email channel retries 3× — so email is *more* reliable than WhatsApp today.
3. **Delivery callbacks are ignored.** `message_status` webhooks are explicitly dropped as
   `Ignored("not_an_inbound_message")`. `whatsapp_logs.status` never advances past `SENT`. Any UI
   promising "delivered / read" would be lying.

### B4. Email — REAL, per-tenant SMTP (CLAUDE.md is wrong)

`spring-boot-starter-mail` is a declared dependency. `spring.mail.*` is configured but reserved for
platform/SuperAdmin security mail; **every tenant email goes through `TenantMailSenderFactory`**,
which builds a fresh `JavaMailSenderImpl` per call from per-tenant AES-encrypted SMTP credentials on
`TenantSettings` (not cached), and hard-fails rather than leaking onto the platform mailbox.

`EmailNotificationChannel` is a **full `MimeMessage` send with 3-attempt retry and linear backoff** —
CLAUDE.md's "implementation stub" is flatly wrong. Six real send sites exist (quotation PDF, marketing
campaign/drip, SMTP test, SuperAdmin login alert, notification channel).

**Two defects to fix before routing overdue mail through it:**

- `EmailNotificationChannel.send()` calls `sendAsync(event)` on **itself** — a self-invocation Spring's
  `@Async` proxy cannot intercept.
- The named executor bean `notificationExecutor` **does not exist** anywhere in the source tree.

Net effect: email sends run **synchronously on the caller's thread**, with `Thread.sleep(1000ms × attempt)`
backoff. An N-agent overdue scan would block the scheduler thread for ~3s per failing recipient.

There is also **no HTML email template infrastructure** — `templates/` is PDF-only and every email body
is string-concatenated.

### B5. Is there a unified multi-channel dispatcher? — YES, and it is the right one

`NotifyEvent.channels` + `NotifyEventListener` **is** the unified fan-out. It auto-discovers every
`NotificationChannel` bean and dispatches per-channel with per-channel try/catch. Adding a channel is,
per the enum's own javadoc, *"a new constant here + one class implementing NotificationChannel. The
dispatcher never changes."*

So **WhatsApp is a missing channel implementation, not an architectural absence.**

Two systems deliberately bypass it and should stay bypassed:
- `marketing/MessageDispatcher` — customer-facing, `Customer`-typed, merge-tag resolved, campaign-scoped.
- `otp/OtpSenderResolver` — routes to exactly **one** sender; cannot fan out; its email leg is still a stub.

### B6. The `communication/` module (requested separately) — schema-only shell, DO NOT USE

12 entities, 14 enums, 12 repositories, and exactly **one** controller (`CommInboxController`) exposing
**five read-only GETs** under a single class-level `@PreAuthorize("hasAuthority('COMM_READ')")`.

The decisive fact: **`grep` for `.save(` / `.saveAll(` / `@Modifying` / `@Scheduled` across all 51 files
in the module returns ZERO hits.** Nothing in the entire backend ever writes a `CommMessage` row. The
Communication Center is an inbox view over a table that can only ever be empty.

- **Six repositories have zero consumers:** `CommScheduledMessageRepository`, `CommNotificationPrefRepository`,
  `CommTemplateRepository`, `CommMentionRepository`, `CommChannelAccountRepository`, `CommMessageEmailRepository`.
- `CommScheduledMessage` is a textbook outbox (`sendAt`, `attempts`, `lastError`, `isDue(now)`) with
  **no worker** — doubly inert: unwritten *and* unpolled.
- `CommNotificationPref` is the per-user × eventType × channel opt-out. Its repository already declares
  the exact query needed. **It is injected by nobody.** Its own javadoc says the quiet part out loud:
  *"a settings screen that writes toggles nothing enforces is worse than no settings screen."*
- Zero integration with `notification/` in either direction — no `NotifyEvent`, no `SseEmitterRegistry`,
  no `ApplicationEventPublisher`.
- Nine `COMM_*` write permissions are defined and granted to role defaults but **gate zero endpoints**.

`CommTemplate.providerTemplateName` and `TenantSettings.waTemplateName` are two unconnected
representations of the same Meta-approved template name. Since `CommTemplate` has zero code behind it,
a task-overdue template name should live on the **settings/** path.

**Verdict: do not route All-Tasks alerts through `communication/`.** Doing so would mean building its
Phase-2/3 writer (conversation creation, contact-identity resolution, unread-count denormalisation) as
a side effect of a task screen — and it still would not deliver anything, because the module has no
transport. Revisit only after its writer exists, at which point the WhatsApp channel bean can *also*
record a `comm_messages` row for a unified audit trail — **additive, never load-bearing**.

---

## PROPOSAL

### D1. Extend the existing `Task` entity. Do not build new. Do not build on `Reminder`.

`Task` already backs 4 of the 7 Sembark columns cleanly, is `publicId`-keyed, has the right filter
axes in `TaskSpecification`, has indexes on `due_date` / `start_at` / `assign_to_user_id`, already
implements `Ownable`, and its table ships in V1 with zero drift.

`Reminder` is the wrong base despite being the more-used frontend surface: it is addressed by internal
`Long id`, has no `startAt`/`category`/`location`/`completedAt`, and its overdue is a **mutated
persisted status** rather than a derived flag.

### D2. Sembark column feasibility

| Column | Backing | Work |
|---|---|---|
| Due At | `Task.dueDate` (nullable) + derived `TaskResponse.overdue` | ✅ none |
| Description | `Task.description` | ✅ none |
| Assigned To | `assign_to_user_id` / `_public_id` / `_name` triplet | ✅ none |
| Created By | `Task.ownerUserId` (a real joinable user id) | ⚠️ expose + denormalise `owner_name` |
| **For (Trip#)** | **NO FIELD** — Task has no booking link at all | ❌ new columns |
| **Guest** | **NO FIELD** — Task has no customer link | ❌ new columns |
| **Trip Source** | **NO FIELD** — `Lead.leadSource` only, via the lead link | ❌ new column |

For "Created By", note `BaseEntity.createdBy` is a **login-username String**, not an id — resolvable
only via a per-row `findByUsernameAndTenantId` lookup (N+1) and blank for scheduler-written rows.
`ownerUserId` is the correct source.

`Booking`'s human-facing identifier is `booking_code` (`varchar(20)`, NOT NULL). The guest display name
is already denormalised as `Booking.customerNameSnapshot` (NOT NULL) — the calendar already renders it.

### D3. DDL — append **PART 18** to `V2__lead_code.sql`

> Verified directly: the last PART header in V2 is **17** (Communication Center) at line 2742.
> Flyway **is** on the classpath, `ddl-auto=validate` on every profile, `application-local.properties`
> turns Flyway on. CLAUDE.md's "No Flyway / ddl-auto=update" is **flatly wrong**.

```sql
-- ╔══════════════════════════════════════════════════════════════════════════╗
-- ║  PART 18 — All Tasks: booking/guest link, creator snapshot, alert marks  ║
-- ╚══════════════════════════════════════════════════════════════════════════╝
ALTER TABLE tasks
    ADD COLUMN IF NOT EXISTS booking_id_ref          bigint,
    ADD COLUMN IF NOT EXISTS booking_public_id       uuid,
    ADD COLUMN IF NOT EXISTS booking_code            varchar(20),
    ADD COLUMN IF NOT EXISTS customer_name_snapshot  varchar(255),
    ADD COLUMN IF NOT EXISTS trip_source             varchar(50),
    ADD COLUMN IF NOT EXISTS owner_name              varchar(150),
    ADD COLUMN IF NOT EXISTS overdue_notified_at     timestamp(6) with time zone;

CREATE INDEX IF NOT EXISTS idx_task_booking
    ON tasks (booking_id_ref) WHERE deleted_at IS NULL;

-- The All-Tasks list is always (tenant, assignee, due window) — the existing
-- idx_task_assignee is not selective enough for the Today/Overdue tabs.
CREATE INDEX IF NOT EXISTS idx_task_tenant_assignee_due
    ON tasks (tenant_id, assign_to_user_id, due_date) WHERE deleted_at IS NULL;

-- The overdue sweeper's poll: open tasks past due that have not been alerted.
CREATE INDEX IF NOT EXISTS idx_task_overdue_sweep
    ON tasks (due_date) WHERE deleted_at IS NULL AND overdue_notified_at IS NULL;
```

All cross-aggregate refs are **logical FKs with no DB constraint**, validated app-side — the same
pattern `Task.leadRefId` and `Booking.customerId` already use.

Mirror the same DDL into `db/indexes.sql` for pilot/dev boxes still on `SQL_INIT_MODE=always`, then
re-stamp: run the file with `psql -v ON_ERROR_STOP=1`, `DELETE FROM flyway_schema_history WHERE version='2';`,
boot. **Do not use `flyway repair`** — it rewrites the checksum without running the SQL, and `validate`
then fails on the missing columns.

Also add `tasks.status` / `priority` / `category` to `SchemaEnumConstraintValidator.GUARDED` — they are
currently unguarded, so a forgotten CHECK-constraint refresh would fail at INSERT time with no boot warning.

### D4. API — new endpoints, do not break the existing ones

`GET /api/tasks` returns a **bare unpaginated `List<TaskResponse>`** and its controller javadoc says
this is deliberate — the calendar/board frontend reads `res.data` directly. Changing it is a breaking
change. Add alongside:

| Method | Path | Auth | Returns |
|---|---|---|---|
| `GET` | `/api/tasks/list` | `TASK_READ` | `PagedApiResponse<TaskResponse>` |
| `GET` | `/api/tasks/tab-counts` | `TASK_READ` | `ApiResponse<TaskTabCountsDto>` |

`GET /api/tasks/list` params: `tab` (`today\|yesterday\|overdue\|upcoming\|all`), `q`, `assignee`
(publicId), `status`, `priority`, `category`, `page`, `size`, `sortBy`, `sortDir`.

`TaskTabCountsDto`: `{ today, yesterday, overdue, upcoming, all }`.

**Copy `CommInboxServiceImpl`, not `LeadServiceImpl`.** It is the only module in the codebase that gets
`clampSize`, `Math.max(page,0)`, an empty page for scope `NONE`, a **throwing** `parseEnum`, and —
critically — a single `scopedSpec()` that **both** the list and the counts consume, so a tab badge can
never disagree with the list it opens. `LeadController`/`BookingController` pass raw `sortBy` into
`Sort.by()` (→ 500 on a typo) and clamp nothing.

Note the existing `/api/tasks/stats` **cannot** back the sidebar: it is `@PreAuthorize("hasAuthority('CRM_FULL')")`,
computes tenant-wide counts with no row scope, and has no `yesterday`/`upcoming` buckets.

Also introduce a **`TaskAccessGuard`** modelled on `CommAccessGuard` before writing any new read path.
The scope rule is currently inlined in three places in `TaskServiceImpl`; new read paths would make
copies four and five.

### D5. Overdue calculation — and the timezone trap

Overdue itself is zone-neutral and already correct: `status NOT IN (DONE, CANCELLED) AND anchor < now()`,
compared as `Instant`. `TaskRepository.countOverdue(...)` and `countDueBetween(...)` **already exist**.

**Today / Yesterday / Upcoming are not.** Every day boundary in `task/`, `reminder/` and `calendar/`
hardcodes UTC:

- `TaskServiceImpl.java:221` — `LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC)`
- `ReminderServiceImpl.java:199` — `Instant.now().truncatedTo(ChronoUnit.DAYS)`
- `CalendarServiceImpl.java:79-81, 108-111, 371` — all UTC

For an IST tenant "today" begins at **05:30 local**. A `TenantTimeZone` resolver already exists at
`common/context/TenantTimeZone.java` (`DEFAULT = Asia/Kolkata`, 30s TTL cache, `forTenant(Long)`,
`today()`, `todayFor(Long)`) and is proven in `fleet/` and `LeadAlertService` — but is injected in
**zero** of the three modules. Wiring it into `task/` is **adoption, not invention**, and must happen
before the Today/Yesterday tabs ship.

Second subtlety: `Task.isOverdue()` and `TaskSpecification` both anchor on `COALESCE(startAt, dueDate)`,
not `dueDate`. So a scheduled task is judged by `startAt`. The grid must pick one — see **Open Decision 2**.

### D6. Notification wiring

**Reuse as-is (zero changes):** the whole `NotifyEvent → NotifyEventListener → NotificationChannel`
spine, `SseEmitterRegistry` (multi-tab, per-user, heartbeat), `InAppNotificationChannel`,
`EmailNotificationChannel`'s send logic, `TenantMailSenderFactory`, `WhatsAppMessagingService` +
`InteraktWhatsAppSender`, and the existing `TASK_ASSIGNED` publish.

**Extend (small, well-bounded):**

1. `DeliveryChannel` — add `WHATSAPP` (the enum literally reserves the name in a comment).
2. New `WhatsAppNotificationChannel implements NotificationChannel`, delegating to
   `WhatsAppMessagingService.sendTemplate(...)`. Must apply `RateLimitService` itself — it is not inherited.
3. **`UserDirectory.phoneById(Long)`** — this is the real blocker. The interface exposes only
   `emailById(Long)` and `activeAdminIds(Long)`. `User.phoneNumber` exists (`phone_number varchar(20)`)
   but the notification module has no route to it. Add to `UserDirectory` + `AuthApiService`;
   `emailById` is the 6-line template.
4. `NotificationReferenceType` — add `TASK` **and** refresh the `reference_type` CHECK constraint in the
   same PART 18. (Cheaper dodge: reuse `"REMINDER"`, as the marketplace module reused `"BOOKING"`.)
5. Fix `EmailNotificationChannel`'s `@Async` self-invocation **and** define the missing
   `notificationExecutor` bean — before enabling EMAIL on a scheduler.
6. Publish `TASK_COMPLETED` from `changeStatus`/`markComplete` (currently silent).

**Build new:**

7. **`TaskOverdueScanner`.** There is no `@Scheduled` anywhere in `task/`. Two templates exist:
   - `ReminderScheduler` — `@Scheduled(fixedRate = 60_000)`, runs with **no** TenantContext, queries
     across all tenants, re-applies isolation **per row** via `TenantContext.setTenantId(row.getTenantId())`
     inside try/`finally { clear(); }`.
   - `CampaignDispatchScheduler` — `tenantRepository.findAll()`, set context per tenant, clear in
     `finally`, per-tenant try/catch, work delegated **cross-bean** so each unit gets its own transaction.

   I recommend the **per-tenant** shape (marketing's), because rate limiting and tenant timezone are
   both per-tenant concerns.

   Idempotency: the new `overdue_notified_at` column, flushed after publish — mirroring `Reminder.notified`.
   **The notification module has zero dedup**, so a naive `fixedRate` job would write a duplicate bell
   row every tick.

   **Notify `assignToUserId`, not `ownerUserId`.** `ReminderScheduler` notifies the *creator*, which is
   arguably a bug there; the Sembark requirement is explicitly "the assigned agent."

   Set `recipientUserIds` **explicitly** — the IN_APP channel silently falls back to *tenant admins* when
   it is empty, and the email/SSE channels would NPE (swallowed by the per-channel catch).

---

## Frontend plan

Live repo path is **`D:\CRM PROJECT\travelcrmfe\travelcrmfrontend`** (the path in CLAUDE.md does not exist).

No All-Tasks page exists. The only task UI is the Kanban `TaskBoard.jsx` inside `features/calendar/`,
which calls `taskService.list()` with **no arguments** — the whole task set client-side.
`features/calendar/index.js` exports only `Calendar`, so `taskService` must be added to the barrel
(deep-importing violates the boundary rule).

| Need | Reuse |
|---|---|
| Paginated table | `usePagedList` (`shared/api/usePagedList.js`) — debounce, page reset, out-of-order guard |
| Column template | `leadAlertUi.jsx` `COLUMNS` + `TABLE_MIN_W` + `<colgroup>` idiom |
| Grid primitives | `marketingUi.jsx` — `Page`, `GridHead`, `GridRow`, `Cell`, `Avatar`, `Pager`, `Badge` |
| Tabs + count badges | `LeadAlerts.jsx:62-66,125-134,595-627` — pill + `tabular-nums` badge |
| Page shell | `<Page icon title crumb actions>` from `marketingUi.jsx` |

**The left rail is genuinely new markup** — every tab construct in the app is horizontal. `LeadAlerts.jsx`
supplies the count logic and badge styling; it must be re-laid-out vertically.

Router (`app/router.jsx`) and Sidebar (`app/chrome/Sidebar.jsx`) both clone the Calendar entry verbatim:
`<Guard allow={hasPermission(P.TASK_READ)}>` and `hasPermission(P.TASK_READ) && hasModule("TASKS")`.
`ClipboardList` is already imported in Sidebar's lucide bundle.

`Navbar.jsx` needs a `NOTIF_ROUTE_MAP` entry for `referenceType: "TASK"` — **without it, clicking the
overdue notification does nothing.** `TYPE_DOT` is a substring match, so `TASK_OVERDUE` needs a `TASK` key.

**No frontend or backend permission work is needed** — `TASK_READ/CREATE/UPDATE/DELETE` already exist
and are already in the role defaults, and `/api/tasks` is already mapped to the `TASKS` module in
`ModuleAccessFilter`.

---

## Open decisions — I need your call before building

1. **Task vs Reminder duplication.** Two competing follow-up inboxes exist, and one real follow-up can
   show twice on the calendar. Options: (a) ship All-Tasks on `Task` only and accept the overlap,
   (b) also surface Reminders in the same grid as a read-only union, (c) plan a migration of Reminder
   into Task. I recommend **(a)** now, **(c)** as a separate project.
2. **"Due At" semantics** — `dueDate` alone, or the calendar anchor `COALESCE(startAt, dueDate)`?
   The existing overdue flag uses the anchor. I recommend the **anchor**, for consistency with the board.
3. **How does a Task get linked to a Booking?** Manual picker on the task form, or auto-created tasks from
   booking events? The columns are useless until something populates them.
4. **`trip_source` — snapshot or join?** A `varchar(50)` snapshot is fast and survives lead deletion but
   goes stale. A join through `leadRefId → Lead.leadSource` is always fresh but only works for
   lead-linked tasks. I recommend the **snapshot**, matching the `assign_to_name` / `lead_name` idiom.
5. **Per-user mute.** There is **no opt-out anywhere in the system**. Agents will not be able to turn
   WhatsApp/email alerts off. Ship without it, or wire `CommNotificationPref` (its repository already
   has the exact query) into the channel beans first?
6. **WhatsApp template approval.** Interakt/Meta must approve a task-overdue template before a single
   message sends, and the positional arity must match or Interakt silently returns FAILED. Who creates it,
   and what is the body copy?
7. **Alert cadence.** One alert when a task first goes overdue, or escalating re-alerts (e.g. +24h)?
   The `overdue_notified_at` timestamp supports both; a boolean would not.

## Suggested build order

| Phase | Scope | Risk |
|---|---|---|
| 0 | `TenantTimeZone` into `task/`; `TaskAccessGuard`; enum guard entries | Low, no new surface |
| 1 | PART 18 DDL + entity/DTO fields + mapper | Low |
| 2 | `GET /api/tasks/list` + `/tab-counts` (CommInbox shape) | Low |
| 3 | Frontend All Tasks page + left rail + router/sidebar | Medium — new markup |
| 4 | `UserDirectory.phoneById`, `DeliveryChannel.WHATSAPP`, `WhatsAppNotificationChannel` | Medium |
| 5 | Fix `EmailNotificationChannel` `@Async` + `notificationExecutor` bean | Medium — pre-req for 6 |
| 6 | `TaskOverdueScanner` + rate limiting + `NOTIF_ROUTE_MAP`/`TYPE_DOT` | Highest |

Phases 1–3 deliver the Sembark screen with in-app alerting only and are independently shippable.
Phases 4–6 add WhatsApp/email and are where the real risk lives.

## Corrections to CLAUDE.md found during this investigation

- **"No Flyway. Schema is managed by `ddl-auto=update`"** — false. Flyway is on the classpath,
  `ddl-auto=validate` on every profile, `application-local.properties` enables Flyway.
- **`EmailNotificationChannel` "(implementation stub)"** — false. It is a real tenant-SMTP `MimeMessage`
  send with 3-attempt retry.
- **OTP senders "logging stubs"** — half true. `WhatsAppOtpSender` is a real send; `EmailOtpSender` and
  `SmsOtpSender` remain stubs.
- **Frontend path `D:\CRM PROJECT\travelcrmfrontend`** — does not exist; the live repo is
  `D:\CRM PROJECT\travelcrmfe\travelcrmfrontend`.
- The notification endpoint table omits `DELETE /api/notifications/{publicId}` and the `/read-all` alias.
- Four source files (`BookingProfitService:43-45`, `BookingAssigneeViewFactory:28-30`,
  `LeadServiceImpl:220-222`, `LeadIngestOutcome:19-21`) still claim `NotifyEventListener` *clears*
  `TenantContext`. It save/restores instead (`NotifyEventListener.java:53-59`).
