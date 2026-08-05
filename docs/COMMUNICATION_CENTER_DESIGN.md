# TravelCRM — Communication Center

**Status:** **Phase 1 BUILT** (3 Aug 2026) — foundation + read-only inbox. Phases 2–8 pending.
**Author:** architecture pass, 3 Aug 2026
**Scope:** omnichannel unified inbox (WhatsApp, Email, SMS, Calls, Internal chat, Notes) per the 14-screen UI mockup — **less screen 6 (Live Call)**, which is out of scope per D1.

> **Migration note:** this module's DDL is **V2 PART 17**. A parallel workstream (lead claim/broadcast)
> has taken **PART 18**. V2 is still the single growing file; append the next PART there and re-stamp
> until V2 reaches the deployment database.

### Phase 1 — what shipped

| Layer | Delivered |
|---|---|
| Schema | V2 **PART 17**: 12 tables, enum CHECKs, partial indexes (every unique one carrying `deleted_at IS NULL`), the repo's **first `tsvector` + GIN**, plus the two backfills |
| Domain | 14 enums, 12 entities (`BaseTenantEntity`, `Ownable` where row-scoped), 12 tenant-scoped repositories, 1 blob-free projection |
| Read API | `GET /api/communication/conversations \| /summary \| /{id} \| /{id}/messages \| /search` |
| Security | `COMM_*` × 10 + role defaults + `CommPermissionDefaultsTest` (14 assertions pinning defaults ↔ migration); `CommAccessGuard` composing `SubAgentScope` + `ScopeResolver` |
| Entitlement | `ModuleAccessFilter` rule, `PlanCatalogueInitializer.backfillCommunicationKey()`, `tenant_modules` backfill |
| Boot guards | 8 columns registered in `SchemaEnumConstraintValidator` |
| Frontend | `features/communication/` (barrel, service, hybrid kit, inbox page), route + sidebar + `access.js` keys |
| Tooling | **Vitest + Testing Library** — the repo's first FE test infrastructure |

Not yet wired, by design: sending, the inbound fan-out, linking/conversions, notes, chat, calls, email.
Phase 1 is read-only so the row-scope and entitlement wiring is proven against real data before
anything in this module can write.

---

## 0. Corrections to CLAUDE.md discovered during Phase 0

Two statements in `CLAUDE.md` are stale and would have produced a broken module if followed:

| CLAUDE.md says | Reality | Evidence |
|---|---|---|
| "Schema is managed by `spring.jpa.hibernate.ddl-auto=update`" | `ddl-auto=${JPA_DDL_AUTO:validate}` on **every** profile. Hibernate creates nothing, anywhere. | `application.properties:64`, `application-prod.properties:47` |
| "No Flyway. Do not add Flyway migrations" | Flyway is a dependency, `db/migration/` holds `V1` + `V2` (PARTs 1–16), and **migrations own the schema on every environment**. | `application.properties:93-100`, `pom.xml:509-517` |

**Consequence:** every table and column below must be hand-written into the migration. A new `@Entity` with no DDL fails the boot with `Schema-validation: missing table`. There is no fallback.

---

## 1. What already exists (and is therefore not being rebuilt)

| Capability | Where | State |
|---|---|---|
| Outbound WhatsApp | `settings/` — `WhatsAppSender` SPI → `InteraktWhatsAppSender` (`@Primary`), facade `WhatsAppMessagingService` | **Template-only.** `payload.put("type","Template")` is hardcoded (`InteraktWhatsAppSender.java:77`) and it throws when `templateName` is blank (`:59-61`). No free-text, no per-message media, no provider message id stored. |
| Outbound Email | `TenantMailSenderFactory.resolve(tenantId)`, `EmailAuditService.record(...)` | SMTP send only. **No IMAP, no receive, no threading, no attachment store.** |
| Inbound WhatsApp | `leadsource/` — `POST /api/webhooks/leads/{channel}/{token}`, HMAC verified, raw payload persisted before parsing, idempotent on provider message id | **Terminates in a Lead.** The message text survives only inside a redacted, 64 KB-truncated `raw_payload` blob and a `LeadLog.comment`. |
| Real-time push | `SseEmitterRegistry` — per-user, multi-emitter, named events, 25 s heartbeat | Reusable as-is. |
| Bulk outreach | `marketing/` — campaigns, drips, automations, `MessageDispatcher`, `MergeTagResolver`, `RateLimitService` | 1-to-many. Overlaps this module only on templates. |
| RBAC | `Permission` enum (authority string == enum name), `EffectivePermissionResolver`, `ScopeResolver` (OWN/TEAM/ALL/NONE), `SubAgentScope` | Mature. Two independent row-scope mechanisms. |
| Entitlement | `ModuleAccessFilter` + `ModuleAccessCoverageTest` | Build-enforced: an unclassified `/api` prefix **fails the build**. |
| Byte storage | `TravelerDocument` / `FleetAttachment` — `content bytea` + projections + `StorageQuota.enforceWithinQuota` | The attachment precedent. Cloudinary is forbidden for this data. |

### 1.1 Why the message store must be a new table

All four candidates fail structurally:

- **`LeadLog`** — FK to `lead_id` only, no customer reference, no channel/direction/provider-id columns. Its summary query `findAllForTenantWithLead(tenantId)` loads **every log in the tenant** and paginates in Java (`LeadLogServiceImpl.java:114-141`). An inbox built on it is O(all messages in tenant) per page load.
- **`ActivityLog`** — staff audit trail; no contact reference at all.
- **`WaMessageLog` / `EmailMessageLog`** — append-only send-attempt logs. No body, no contact FK, no direction, no provider id; inbound is impossible.
- **`LeadIngestEvent`** — webhook debug log, redacted and 64 KB-capped.

`LeadLog` is still written **alongside** the message store, as the human-visible activity trail on the lead.

---

## 2. Six constraints that shape the design

**C1 — One webhook URL.** Interakt allows one webhook URL per account and `leadsource` already owns it. `LeadSourceRegistry` throws at boot on a duplicate slug claim, so a second WhatsApp adapter is impossible. **The only correct answer is a fan-out inside the existing gateway.**

**C2 — WhatsApp's 24-hour session window.** Free-text replies are permitted only within 24 h of the customer's last inbound message; outside it, approved templates only. The mockup's plain "Type a message…" box is not deliverable without modelling this — and the current sender cannot send free text at all.

**C3 — `ddl-auto=validate`.** Every column hand-written into the migration, with exact types and varchar lengths.

**C4 — Enum CHECK constraints are data-level.** Adding an `@Enumerated(STRING)` value without refreshing the constraint fails at the **first INSERT in production**, not at boot (`V2:2444-2446` records this exact miss happening in PART 16).

**C5 — No retention mechanism exists.** `TrashPurgeScheduler` is "the only place in the application where a hard delete happens", and it is opt-in per entity. The `notifications` table — today's highest-write table — has no purge at all. There is zero table partitioning and **no write-side JDBC batching configured** (`hibernate.jdbc.batch_size` absent from every properties file), against a 10-connection pool on a 2-vCPU VPS.

**C6 — No telephony, no SMS.** `MarketingChannel` = {WHATSAPP, EMAIL}. `DeliveryChannel` = {IN_APP, SSE, EMAIL}. The OTP SMS sender is a logging stub. Twilio's SDK is on the classpath and **used by nothing**. `Customer.commPref` has `SMS` and `PHONE_CALL` constants that no sender can honour.

*Resolved:* SMS becomes a real transport in Phase 5 (Twilio adapter). Telephony is **logging only** per D1 — the module never places a call, so no dialling capability is built and `commPref = PHONE_CALL` stays advisory.

---

## 3. Data model — V2 PART 17

12 tables. Every one extends `BaseTenantEntity` (⇒ `id`, `public_id`, `tenant_id`, audit columns, soft delete, `tenantFilter`, `TenantEntityListener` + `OwnershipEntityListener`). Every one that a sub-agent can reach implements `Ownable` (`owner_user_id`).

```
comm_contact_identity        canonical contact key — the join everything hangs off
  identity_type   varchar(10)   PHONE | EMAIL
  identity_value  varchar(190)  E.164, or lower-cased email
  display_name    varchar(150)
  customer_id     bigint        logical FK, nullable
  lead_id         bigint        logical FK, nullable (most recent non-terminal)
  UNIQUE (tenant_id, identity_type, identity_value) WHERE deleted_at IS NULL

comm_conversation            one thread
  kind                varchar(20)   CUSTOMER | INTERNAL
  channel             varchar(20)   WHATSAPP | EMAIL | SMS | CALL | INTERNAL_CHAT
  contact_identity_id bigint        null for INTERNAL
  channel_account_id  bigint
  subject             varchar(255)  email thread subject / chat channel name
  owner_user_id       bigint        Ownable
  assigned_user_id    bigint        who owes the reply  ← ScopeResolver filters on this
  status              varchar(20)   OPEN | PENDING | SNOOZED | CLOSED
  priority            varchar(10)
  last_message_at     timestamp
  last_inbound_at     timestamp     ← drives the WhatsApp 24 h window (C2)
  last_direction      varchar(10)
  unread_count        integer
  pinned              boolean
  lead_id bigint, customer_id bigint, booking_public_id uuid, quotation_public_id uuid
  INDEX (tenant_id, last_message_at DESC)      WHERE deleted_at IS NULL
  INDEX (tenant_id, assigned_user_id, status)  WHERE deleted_at IS NULL
  INDEX (tenant_id, channel, status)           WHERE deleted_at IS NULL

comm_message                 the unified timeline row — ONE table for all channels
  conversation_id     bigint NOT NULL
  channel             varchar(20)
  direction           varchar(10)   INBOUND | OUTBOUND | INTERNAL
  body_text           text
  body_html           text          email only
  status              varchar(15)   QUEUED|SENT|DELIVERED|READ|FAILED|SKIPPED|RECEIVED
  status_at           timestamp
  error_message       text
  provider_message_id varchar(190)
  sender_user_id      bigint
  template_id         bigint
  reply_to_message_id bigint
  call_id             bigint        when channel = CALL
  visibility          varchar(15)   PUBLIC | INTERNAL | PRIVATE   (notes)
  owner_user_id       bigint        Ownable
  occurred_at         timestamp NOT NULL
  search_tsv          tsvector GENERATED ALWAYS AS (to_tsvector('simple', coalesce(body_text,''))) STORED
  UNIQUE (tenant_id, provider_message_id) WHERE provider_message_id IS NOT NULL AND deleted_at IS NULL
  INDEX  (tenant_id, conversation_id, occurred_at DESC) WHERE deleted_at IS NULL
  GIN    (search_tsv)

comm_message_email           side table — email-only headers
  message_id, from_address, to_addresses text, cc_addresses, bcc_addresses,
  message_id_header varchar(255), in_reply_to varchar(255), references_header text,
  folder varchar(20), starred boolean, is_draft boolean

comm_attachment              FleetAttachment shape — bytea, NEVER Cloudinary
  message_id, file_name, content_type, size_bytes, sha256 varchar(64), content bytea, direction

comm_call
  conversation_id, direction (INBOUND|OUTBOUND|MISSED), from_number, to_number, agent_user_id,
  provider_call_id varchar(190), started_at, answered_at, ended_at, duration_sec,
  outcome varchar(30), recording_ref varchar(255), recording_available boolean,
  notes text, follow_up_at timestamp, owner_user_id

comm_template                the tenant's single message-template library
  channel, category varchar(30), name varchar(120), subject varchar(255), body text,
  provider_template_name varchar(120), variables text (json), arity smallint,
  status varchar(15), usage_count integer, owner_user_id

comm_scheduled_message       one-off scheduled sends (mockup #7)
  conversation_id, contact_identity_id, channel, template_id, subject, body,
  send_at timestamp, status varchar(15), attempts smallint, last_error text, created_by_user_id

comm_conversation_member     internal chat membership + per-user read state
  conversation_id, user_id, member_role varchar(10), pinned_at, last_read_at, muted boolean
  UNIQUE (tenant_id, conversation_id, user_id) WHERE deleted_at IS NULL

comm_mention
  message_id, mentioned_user_id, read_at

comm_channel_account         connected-channels registry (mockup #12)
  channel, provider varchar(40), display_label, status varchar(15),
  external_ref varchar(190), config_json text, secret_enc text, last_checked_at, last_error
  -- WHATSAPP + EMAIL_SMTP DELEGATE to tenant_settings (secrets are never duplicated).
  -- IMAP, SMS and VOICE store their own credentials here, AES-encrypted via AesSecretCipher.

comm_notification_pref       per-user toggles (mockup #13)
  user_id, event_type varchar(40), channel varchar(20), enabled boolean
  UNIQUE (tenant_id, user_id, event_type, channel)
```

### 3.1 Design decisions inside the model

**One `comm_message` table, not five.** The product's whole point is a single timeline; a `UNION` across five per-channel tables kills it. Channel-specific fields live in side tables (`comm_message_email`) or a referenced row (`comm_call`), which is the same shape `Booking` uses for its non-audited children.

**Notes are messages** (`channel = INTERNAL_NOTE`, `visibility = PUBLIC|INTERNAL|PRIVATE`). This gives notes full-text search, attachments, mentions and timeline ordering for free.

**Internal chat is a conversation** (`kind = INTERNAL`) with `comm_conversation_member`. Same benefit.

**Quick replies are templates** (`category = QUICK_REPLY`). No extra table.

**Unread is modelled twice, deliberately.** `CUSTOMER` conversations carry a denormalised `unread_count` (assigned-agent semantics — one agent owes the reply). `INTERNAL` conversations use per-member `last_read_at`, because a group chat genuinely has N different unread counts. Uniform per-user read state for customer threads would add a row per user per conversation for no product gain.

**FTS is a generated column**, not a trigger — no maintenance code, and `validate` is happy with it. This is the **first `tsvector` in the repo**; there is no precedent to copy.

**Booking/quotation links are `uuid` on the message/conversation, not columns on `Booking`.** `Booking` is `@Audited`; a new column there needs its twin in `bookings_aud` or **every write to every booking fails** (`V2:2107-2110` — PART 14 restructured the whole marketplace link to avoid exactly this).

### 3.2 Migration mechanics (non-negotiable)

- Append as **PART 17** at `V2__lead_code.sql:2738`, using the PART-16 banner format and the `-- ── Verification ────` closing block.
- Every `@Enumerated(STRING)` column gets an inline `CONSTRAINT <table>_<col>_check CHECK (col IN (…))`, **and** a row in `SchemaEnumConstraintValidator.GUARDED` so a future missed refresh becomes a boot failure instead of a production 500 (C4).
- Every partial unique index carries `AND deleted_at IS NULL` — without it a soft-deleted row holds the key forever while the `…AndDeletedAtIsNull` finder cannot see it.
- Re-stamp after editing V2: `DELETE FROM flyway_schema_history WHERE version='2';` then boot. **Never `flyway repair`** — it rewrites the checksum without running the new SQL, and `validate` then fails on the missing columns.
- **Do not** put this DDL in `db/indexes.sql`. That file is switched off (`SQL_INIT_MODE=never`) whenever Flyway is enabled in prod, and duplicated DDL across both is how `platform_audit_logs_action_check` already drifted.

### 3.3 Volume, retention, throughput

`comm_message` becomes the largest table in the application. Against C5:

- **Retention job** — `CommRetentionScheduler`, per-tenant, `app.communication.retention-days` (default: **never purge message bodies**; purge only `comm_attachment.content` and raw inbound payloads past N days). Messages are commercial records; silent deletion is worse than growth. Ship the job with retention **off** and a documented switch.
- **Not registered in `TrashableType`** — the `FleetAttachment` precedent. Soft-delete hides; nothing hard-deletes a customer conversation on a 30-day timer.
- **Batching** — the inbound path is one INSERT per delivery, which is fine. The IMAP poller and any backfill must chunk (`app.communication.batch-size`, the `app.marketing.batch-size=200` precedent) and configure `hibernate.jdbc.batch_size` for its own unit of work.
- **Partitioning is deliberately deferred.** Declaring `comm_message` partitioned from day one on a single-tenant pilot buys nothing and complicates every query. Revisit at ~10 M rows; the `occurred_at` index makes a later range-partition migration mechanical.

---

## 4. Integration layer — provider-agnostic

Follows the two patterns the repo already proves: **SPI + `@ConditionalOnMissingBean` logging stub + `@Primary` real adapter** (the `WhatsAppSender` shape), and **a resolver factory keyed by channel** (the `OtpSenderResolver` shape).

```java
// ── Outbound ──────────────────────────────────────────────────────────────
public interface CommTransport {
    CommChannel channel();
    boolean     isConfigured(Long tenantId);
    SendResult  send(Long tenantId, OutboundMessage message);   // never throws for a delivery failure
}
public interface CommTransportResolver { CommTransport forChannel(CommChannel c); }

// impls: WhatsAppCommTransport  → delegates to WhatsAppMessagingService (keeps the whatsapp_logs audit)
//        EmailCommTransport     → TenantMailSenderFactory + EmailAuditService
//        SmsCommTransport       → SmsProvider SPI (Twilio adapter; SDK already on the classpath)
//        LoggingCommTransport   → @ConditionalOnMissingBean fallback per channel

// ── Inbound: the fan-out seam ─────────────────────────────────────────────
public interface InboundDeliveryConsumer {
    void onDelivery(InboundDelivery d);   // tenantId, channel, integrationId, eventId, RawInbound, parse result
}

// ── Voice: LOGGING ONLY (D1) — no dial(), no softphone ────────────────────
public interface CallEventSource {                 // parses one PBX/provider webhook delivery
    String            provider();
    ParsedCallEvent   parse(RawInbound in);        // direction, numbers, timestamps, duration, outcome, recording ref
}
public interface CallRecordingFetcher {            // optional; absent ⇒ recordings simply unavailable
    Optional<byte[]> fetch(Long tenantId, String providerCallId);
}

// ── Email receive ─────────────────────────────────────────────────────────
public interface MailboxReader {
    List<FetchedMail> fetchSince(Long tenantId, MailboxCursor cursor, int max);
}
```

### 4.1 The inbound fan-out (resolves C1)

`LeadIngestGateway.process()` is the **only** point where the resolved tenant, the committed `eventId`, the `RawInbound` and the parse result all exist together — and the only point that sees non-lead traffic. Inject `List<InboundDeliveryConsumer>` and call them from the `InboundParseResult` switch (`LeadIngestGateway.java:214-236`), **not** from `ingestAll()`.

Why the switch and not the `Complete` branch:

> Interakt status callbacks and the agency's **own outbound echoes** take the `Ignored` branch (`:219-225`) and never reach `ingestAll()`. Attaching at `Complete` silently loses most of a conversation thread.

Four rules for the seam, each from a specific failure mode found in the code:

1. **Own try/catch.** The gateway's outer `catch` (`:169-176`) flips the delivery to `FAILED` and loses the lead while still ACKing the provider — a message-store failure must never reach it.
2. **Own `TenantScope.call(...)` → cross-bean into a method-level `@Transactional(REQUIRES_NEW)` writer.** `TenantScope.call()` **throws** if entered while a transaction is active (`TenantScope.java:50-58`), so this cannot be done from inside `LeadIngestService.ingest`.
3. **One INSERT, no I/O.** Interakt's response SLA is ~3 s and the whole pipeline runs on the request thread before the ACK.
4. **The consumer lives in `communication/`, never `leadsource/adapter/`.** `LeadSourceAdapterPurityArchTest` requires every class in that package to implement `LeadSourceAdapter`, and forbids it from touching repositories, JPA, Spring Data, transactions or `TenantContext`.

**Direction and provider metadata.** The lead pipeline discards `data.message.chat_message_type` (which distinguishes a customer message from our own echo) and everything except the dedup id. Rather than widen the sealed `InboundParseResult` SPI and touch all four adapters, the communication module carries its own `InboundMessageExtractor` per channel, reading the `RawInbound` JSON for direction, provider message id, media type and sender. Two parsers over one payload — accepted deliberately, to keep `leadsource`'s SPI and its ArchUnit purity intact.

### 4.2 WhatsApp free-text (resolves C2)

Add to the existing SPI, as a `default` method that throws, so no existing implementation breaks:

```java
default void sendText(TenantSettings ts, String toPhoneE164, String text) {
    throw new UnsupportedOperationException("This provider supports template messages only.");
}
```

`InteraktWhatsAppSender` implements it with `type: "Text"`. `WhatsAppMessagingService` gains `sendSessionText(tenantId, toPhone, text)` — same tenant lookup, same E.164 normalisation, same `whatsapp_logs` audit.

**The window is enforced on the backend, not just reflected in the UI.** `POST /conversations/{id}/messages` rejects a free-text WhatsApp body with `422` when `now - last_inbound_at > 24h`, and the composer reads the same state to switch itself to the template picker. A UI-only gate means a stale tab sends into a closed window and the failure surfaces as a provider rejection three seconds later.

### 4.3 Channel connection registry

`comm_channel_account` is the registry backing mockup screen 12. For WhatsApp and SMTP it **delegates** to `tenant_settings` (reads status, never duplicates the secret). IMAP, SMS and Voice store their own credentials there, AES-encrypted through the existing `AesSecretCipher`.

---

## 5. API surface

Base `/api/communication`. `ApiResponse<T>` / `PagedApiResponse<T>` throughout; `publicId` only, never the internal `Long`.

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/conversations` | `COMM_READ` | paged; filters `channel,status,assigned,unread,q,from,to`; row-scoped |
| GET | `/conversations/summary` | `COMM_READ` | hub counters: unread, pending replies, missed calls, due follow-ups |
| GET | `/conversations/{publicId}` | `COMM_READ` | header + linked lead/customer/booking/quotation panel |
| GET | `/conversations/{publicId}/messages` | `COMM_READ` | cursor-paged, newest-first |
| POST | `/conversations/{publicId}/messages` | `COMM_SEND` | text \| template \| attachment; enforces the 24 h window |
| PUT | `/conversations/{publicId}/read` \| `/assign` \| `/status` \| `/pin` \| `/link` | `COMM_READ` / `COMM_ASSIGN` | |
| POST | `/conversations/{publicId}/convert/{target}` | target's own key | `lead\|quotation\|booking\|reminder\|task` — delegates (§6) |
| GET | `/search` | `COMM_READ` | FTS over `search_tsv` + contact/booking-code/lead-id |
| GET/POST | `/email/**` | `COMM_READ` / `COMM_SEND` | folders, drafts, star, reply / reply-all / forward |
| GET | `/calls` | `COMM_READ` | all / incoming / outgoing / missed, paged, row-scoped |
| POST | `/calls` | `COMM_CALL_LOG` | **manual** call entry (D1 — there is no dialling) |
| PUT | `/calls/{publicId}/outcome` | `COMM_CALL_LOG` | outcome, notes, follow-up date/time |
| GET | `/calls/{publicId}/recording` | `COMM_RECORDING_READ` | only when a provider supplied one; separate, sensitive |
| POST | `/sms`, `/sms/schedule` | `COMM_SEND` | |
| GET/POST | `/chat/channels`, `/chat/channels/{id}/messages`, `/members` | `COMM_CHAT` | |
| GET/POST | `/notes`, GET `/mentions` | `COMM_READ` / `COMM_SEND` | |
| CRUD | `/templates` | `COMM_READ` / `COMM_TEMPLATE_MANAGE` | |
| GET/PUT | `/workflows` | `COMM_READ` / `COMM_WORKFLOW_MANAGE` | surfaces existing automations (§7) |
| GET/POST | `/channels`, `/channels/{key}/connect\|test\|disconnect` | `SETTINGS_MANAGE` | |
| GET/PUT | `/settings/notifications` | `COMM_READ` | per-user prefs |
| GET | `/reports/overview`, `/reports/channels`, `/reports/agents` | `COMM_REPORT_VIEW` + `CRM_FULL` | tenant-wide roll-ups — `CRM_FULL` excludes sub-agents |

Webhooks (no auth, no tenant context): `POST /api/webhooks/comm/{channel}/{token}` for SMS and Voice. **WhatsApp keeps the existing `/api/webhooks/leads/whatsapp/{token}` URL** — that is the whole point of the fan-out. `/api/webhooks` is already permitted (`SecurityConfig:94`) and already in `ALWAYS_ALLOWED`; only a GET verification handshake would need a new matcher.

---

## 6. Quick actions — exact delegation targets

Nothing is reimplemented. Verified signatures:

| Action | Bean | Method | Notes |
|---|---|---|---|
| Create lead from message | `LeadService` | `createLead(dto, LeadActor, IngestPolicy)` | 3-arg form; `LeadActor.integration(...)` + `IngestPolicy.MACHINE`. Leave `assignedUserId` null — MACHINE ignores it and runs `assignForInbound`. |
| Resolve contact | `CustomerService` | `lookup(phone, email)` → `resolveOrCreate(req, leadId)` | `lookup` never throws. **Never re-implement phone matching** — `CustomerMatcher.canonicalPhone()` is the single authority. |
| Create quotation | `QuotationService` | `create(QuotationRequestDto)` with only `leadId` set | The service snapshots customer/pax/travelDate/services and auto-versions. |
| Convert to booking | `BookingService` | `convertLeadToBooking(leadPublicId, LeadConversionRequestDTO)` | Never `BookingService.create()` — that skips the lead→CONVERTED flip and the duplicate-booking guard. |
| Add reminder | `ReminderService` | `create(CreateReminderRequestDto)` | Copy `LeadLogServiceImpl.createFollowUpReminder` (`:191-208`) verbatim. |
| Schedule meeting | `TaskService` | `create(CreateTaskRequest)` with `category = MEETING` | `MEETING` is a real `TaskCategory`; inventing a value violates `tasks_category_check` at runtime. |
| Any lead touch | `LeadAccessGuard` | `requireVisible(publicId, permissionKey)` | The chokepoint every other module goes through. Bypassing it drops sub-agent row scope. |
| Log on the lead | write `LeadLog` directly | — | `addLog()` **cannot** stamp `activityKind`; the ingest path builds the entity and calls `setTenantId(tenantId)` explicitly. |

---

## 7. Recommendation: do not build a third automation engine

Mockup screen 11 ("Notifications / Workflow") overlaps two existing systems. The repo already has:

- `marketing/AutomationTrigger` — but only `{BIRTHDAY, ANNIVERSARY}`, one config row per (tenant, type), fired hourly, day-idempotent.
- `marketing/Campaign` + `DripSequence` — the genuine scheduled-send engine, rate-limited and resumable.

**Recommendation:** the Communication Center's Workflow tab **surfaces and toggles these**, and the module adds only `comm_scheduled_message` for one-off scheduled sends (screen 7's "Schedule SMS"). New trigger types are added to `AutomationType` where they are genuinely missing. This removes an entire subsystem from the build and prevents two engines disagreeing about what was sent to whom.

**Note:** `bookingreminder` has **no scheduler at all** — `reminderDate` is stored and nothing polls it; delivery only happens via manual `sendNow(id)`. Do not assume creating a BookingReminder schedules anything.

## 7.1 Recommendation: one template library

There is **no reusable message-template entity anywhere in the codebase**. Marketing stores `subject`/`body` inline on the campaign, drip step and trigger rows; `templateName` is a bare string reference to a provider-approved WhatsApp template with no arity recorded. `comm_template` becomes the tenant's single library, and marketing adopts it in a later, separate pass. Reversible: keeping them separate costs only duplicated merge-tag vocabulary.

**Trap to encode:** WhatsApp `bodyValues` are **positional** `{{1}},{{2}}` substitutions. `MessageDispatcher` smuggles a whole composed message in as one value; `QuotationServiceImpl` passes three ordered params. Nothing records a template's arity, so a mismatch is a silent provider rejection logged as `FAILED`. Hence `comm_template.arity`.

## 7.2 Correction: notification preferences do not exist

`notificationsetting/NotificationSetting` is **not** a preference model despite the package name. It is one JSON blob per tenant of lead-stage auto-reminder rules, keyed `UNIQUE(tenant_id)` alone, and **nothing anywhere reads it**. There is no opt-out model at all — channel selection is decided by the publisher at `NotifyEvent` build time and no consumer consults a preference.

Mockup screen 13 therefore needs the genuinely new `comm_notification_pref` (per user, per event type, per channel), **enforced inside a channel bean at send time**. Assuming the existing table already does this produces a Communication Center that sends on channels the user believes they disabled.

---

## 8. RBAC, entitlement and the two silent-failure backfills

### 8.1 Permissions

New keys in `Permission` (module label `"Communication"`):

| Key | Meaning |
|---|---|
| `COMM_READ` | View conversations. **Scope OWN/TEAM/ALL is what implements "assigned vs all".** |
| `COMM_SEND` | Send on a customer channel |
| `COMM_ASSIGN` | Reassign / change status of a conversation |
| `COMM_CALL_LOG` | Log a call and set its outcome / follow-up (D1 — no dialling exists) |
| `COMM_RECORDING_READ` | Listen to call recordings — deliberately separate |
| `COMM_NOTE_PRIVATE_READ` | See other users' private notes (managers) |
| `COMM_TEMPLATE_MANAGE` | Manage the template library |
| `COMM_WORKFLOW_MANAGE` | Toggle/edit workflows |
| `COMM_CHAT` | Participate in internal chat |
| `COMM_REPORT_VIEW` | Communication analytics |

Role defaults: `TENANT_ADMIN` picks them up automatically (`EnumSet.allOf`). `MANAGER` gets every key; `TRAVEL_AGENT` gets `READ / SEND / CALL_LOG / CHAT`; `ACCOUNTANT` `READ / CHAT`; `SUB_AGENT` `READ / SEND / CHAT` (row-scoped OWN); `STAFF` none (deny-by-default).

`COMM_RECORDING_READ`, `COMM_NOTE_PRIVATE_READ`, `COMM_TEMPLATE_MANAGE` and `COMM_WORKFLOW_MANAGE` are in **no** role default — TENANT_ADMIN holds them via the resolver bypass until explicitly granted, following the `BOOKING_REFUND` / `LEAD_PERMANENT_DELETE` precedent for privacy- and config-sensitive keys.

### 8.2 Two backfills that are easy to miss and fail silently

**(a) Permissions.** `EffectivePermissionResolver` treats a **non-null saved map as authoritative**. Any user whose permission screen has ever been saved will **not** receive a new key from `defaultsFor` — they get a blank Communication screen, with no error and no log line. The migration must carry the `jsonb` UPDATE, copying the `FLEET_MONEY_READ` backfill at `V2:989-1006` (including the existing `fleet_safe_jsonb` wrapper, which makes an unparseable row return NULL instead of aborting the migration). Plus a `CommPermissionDefaultsTest` mirroring `FleetMoneyPermissionDefaultsTest`, which exists precisely to stop the two grant paths drifting.

**(b) Entitlement.** Granting `COMMUNICATION` to **plans** does **not** reach existing **tenants**. `computeEffectiveModules` returns the tenant's own `tenant_modules` snapshot whenever it is non-empty and never consults the plan — and `TenantServiceImpl:133` snapshots modules at tenant creation. **No `tenant_modules` backfill has ever been written in this repo.** Without one, every existing tenant is `403 MODULE_NOT_ENABLED` the moment the filter rule lands.

So PART 17 carries three things: DDL, the permission `jsonb` backfill, and an `INSERT INTO tenant_modules … WHERE NOT EXISTS` backfill.

### 8.3 Build gates and conventions

- `RULES.put("/api/communication", "COMMUNICATION");` — one line. Matching is **exact-or-followed-by-slash**, so `/api/communications` (plural) or `/api/comm` would not be covered. Keep every controller strictly under `/api/communication/`.
- Do **not** add `/api/webhooks/comm` to `RULES` — `/api/webhooks` is already allow-listed, and `RULES` is consulted first, which would silently gate it.
- `PlanCatalogueInitializer.backfillUngatedModuleKeys()` must register `COMMUNICATION`, or the key is invisible in the SuperAdmin console and stripped by `updateModules`' whitelist.
- **`@Transactional` on methods only** — class-level is banned tree-wide by ArchUnit *and* silently disables the tenant filter for every query in the class.
- **Never** `findById`/`findAllById`/`getById`/`getOne`/`getReferenceById` on these repositories, and never inject `EntityManager` to load. Declare `findByPublicIdAndTenantIdAndDeletedAtIsNull`.
- **`TenantIsolationArchTest` does not inspect Specifications or `@Query`.** Pass `tenantId` into every Specification explicitly (the `TaskSpecification.build(tenantId, …)` shape).
- **Rows written off-request have `owner_user_id = NULL`** and become invisible to sub-agents — `OwnershipEntityListener` only stamps when a tenant `User` principal is present. Inbound webhook messages and scheduled sends must set the owner explicitly.
- Publish `NotifyEvent` **last** in a method. Publishing mutates `TenantContext` on the caller's own thread.

---

## 9. Frontend

New feature `src/features/communication/` with a barrel `index.js` (named exports only; nothing outside may reach into `pages/`, `components/` or `api/`).

**Pages:** `CommunicationInbox` (hub), `ConversationView`, `WhatsAppInbox`, `EmailInbox`, `CallCenter`, `SmsCenter`, `InternalChat`, `NotesCenter`, `CommTemplates`, `CommWorkflows`, `CommChannels`, `CommSettings`, `CommReports`. **No `LiveCallPanel`** — mockup screen 6 is out of scope per D1; `CallCenter` carries a "Log call" modal instead.

**Wiring:** one thunk + N `lazyPage` bindings in `router.jsx` (the marketing block at `:162-167` / `:380-386` is the exact template); a gated `<li>` in `Sidebar.jsx` (`hasPermission(P.COMM_READ) && hasModule("COMMUNICATION")`); the new `P.*` constants **and** the `ROLE_PERMISSIONS` arrays in `shared/lib/access.js`, matching the backend enum strings exactly.

**Lists:** `usePagedList` + `shared/ui/Pager` (the `AllCustomers` pattern), **not** the `AllLeads` client-side pattern — an inbox exceeds 100 rows on day one. The fetcher must be `useCallback`-memoised or the list refetches forever.

**Errors:** `if (isAlreadyReported(err)) return; showToast(getErrorMessage(err, '…'), 'error');`. 400/404/409/validation are **silent by design** in the interceptor — the call site must render them. Never `err.response.data.message`.

**Real-time:** **do not open a second `EventSource`.** `SseEmitterRegistry` appends emitters per user and `push()` loops all of them, so a second subscription delivers every notification twice into the app. Extend `notificationService.subscribeToSSE` to accept a map of `eventName → handler` (keeping the current signature working), add `comm.message` / `comm.conversation` named events on the existing `/api/notifications/stream`, and fan out in-app through a module-level store — the `toast.jsx` `useSyncExternalStore` pattern is the repo's only precedent for one source / many subscribers.

**Layout:** no persistent master/detail pane exists anywhere. The three closest precedents are a fixed overlay drawer (`console/pages/MarketplaceBookings.jsx` — but its `bg-surface`/`text-heading` utilities resolve to **nothing** outside `src/console/`), a 12-col rail (`Calendar.jsx:919-977`), and `leads/WhatsAppPanel.jsx`. **`WhatsAppPanel` is a mock** — it pushes to local state and calls `window.open('https://wa.me/…')`; there is no API call in the file. Reuse its visual primitives (`MsgBubble`, auto-scroll, Enter-to-send, auto-grow textarea), treat its transport as nonexistent.

**Kit — hybrid per D3.** Fork `marketingUi.jsx` into `communicationUi.jsx` for the **outer** layer: `Page`, `Hero`, `Modal`, `Badge`, `Btn`, `IconBtn`, `Field`, `Select` — swap the accent, rename `.mkt-scope`/`.mkt-fade`, change the hard-coded breadcrumb at `:46`, keep export names identical (what `accountingUi` did). This is what makes the module sit naturally beside the other 21 features.

Add a **second, denser layer** used only inside the inbox and conversation panes: `ThreadList`, `ThreadRow`, `MessageBubble`, `Composer`, `DetailRail`, `CommandBar`. Flat surfaces, 1 px `border-slate-200` separators, no `backdrop-blur`, no `shadow-2xl`, ~34 px rows. Keyboard: `j`/`k` move, `Enter` open, `r` reply, `e` archive, `⌘K`/`Ctrl+K` command bar, `/` focus search, `Esc` close. Shortcuts live in one `useCommKeys` hook so they are registered and torn down in one place — never per-component `keydown` listeners.

There is no global `font-family` rule outside `.sa-console`, so both layers must set `style={{ fontFamily: "'Plus Jakarta Sans',system-ui,sans-serif" }}` (the kit's `<Page/>` does it once).

**State:** no Redux/Zustand/React Query, and the only `createContext` in the tenant app is never mounted. Component-local `useState` + props, plus the module-level store for the SSE fan-out.

**Tests — new infrastructure per D4.** Add `vitest`, `@testing-library/react`, `@testing-library/user-event`, `jsdom` to `devDependencies`; a `vitest.config.js` reusing the `vite.config.js` aliases (they must not drift a third time — import the alias object); and `"test": "vitest run"` / `"test:watch": "vitest"` scripts. Wired in Phase 1 with one smoke test; real component tests land in Phase 3 covering conversation-list filtering, composer window-gating (free-text disabled outside 24 h), and quick-action conversion.

---

## 10. Phases

Each phase ends with: `mvnw compile`, `mvnw test`, a boot under `validate`, `npm run build`, `npm run lint` and `npm run test`.

| # | Phase | Delivers | Checkpoint |
|---|---|---|---|
| **1** | Foundation & read-only inbox | PART 17 DDL + **both** backfills (§8.2); 12 entities + tenant-scoped repos + MapStruct mappers + DTOs; `COMM_*` keys, role defaults, `CommPermissionDefaultsTest`; `ModuleAccessFilter` rule + `PlanCatalogueInitializer` + `SchemaEnumConstraintValidator` rows; `GET /conversations`, `/summary`, `/{id}`, `/{id}/messages`, `/search` (FTS); FE feature skeleton + barrel + route + sidebar + `access.js` keys; **Vitest wired with one smoke test** | Five arch tests green; boots under `validate`; existing tenant not 403'd; hub renders |
| **2** | WhatsApp two-way | `InboundDeliveryConsumer` seam in `LeadIngestGateway.process()`; `InboundMessageExtractor` (direction from `chat_message_type`, provider id, media type); `WhatsAppSender.sendText` + Interakt `type:"Text"`; `sendSessionText` facade; **24 h window enforced server-side**; inbound/outbound media → `comm_attachment` + quota | Test: consumer throw ≠ lost lead and ≠ FAILED delivery. Echoes and status callbacks land as the right direction. Live Interakt smoke (needs key + approved template) |
| **3** | Conversation view, linking, conversions, notes | Contact-identity resolution via `CustomerMatcher`/`PhoneCanonicalizer`; lead/customer/booking/quotation links; convert endpoints delegating per §6; notes with `visibility` + `comm_mention` + notification; FE two-pane inbox + conversation view + quick actions; **component tests** (list filtering, composer gating, conversion) | Round trip: inbound WhatsApp → lead → quotation → booking, every link intact and visible in one timeline |
| **4** | Email (IMAP — D2) | `MailboxReader` SPI + IMAP impl; per-tenant opt-in config in `comm_channel_account`; cursor-based poller (chunked, own batch size); threading via `Message-ID`/`In-Reply-To`/`References`; folders, drafts, star; reply / reply-all / forward; attachments | Threading correctness against a real mailbox; poller does not starve the 10-connection pool |
| **5** | SMS, scheduling, templates | `SmsProvider` SPI + Twilio adapter (SDK already on the classpath); inbound SMS webhook at `/api/webhooks/comm/sms/{token}`; `comm_template` library + quick replies + `arity`; `comm_scheduled_message` + scheduler on the `CampaignDispatchScheduler` shape | Scheduled send fires exactly once, per tenant, idempotent across a restart |
| **6** | Calls — **logging only (D1)** | `CallEventSource` SPI + webhook ingest for tenants with a PBX; **manual call entry**; all/incoming/outgoing/missed views; duration, outcome, notes, follow-up; optional `CallRecordingFetcher` behind `COMM_RECORDING_READ`; quick actions from a call row | A provider webhook and a manual entry both produce the same `comm_call` + timeline row. **No dialling, no Live Call screen.** |
| **7** | Internal chat & presence | `kind=INTERNAL` conversations, `comm_conversation_member`, pinned, per-member `last_read_at`, group + 1:1, file sharing; presence derived from the SSE registry | Two browsers, live, both unread counts correct |
| **8** | Reports, settings, channels, retention | `/reports/overview|channels|agents`; `comm_notification_pref` **enforced inside a channel bean at send time**; channels page; workflow surfacing (§7); retention job (ships off) | Report numbers reconcile against raw row counts; a disabled preference actually suppresses delivery |

---

## 11. Decisions

Resolved by the product owner, 3 Aug 2026.

| # | Decision | Consequence |
|---|---|---|
| **D1** | **Calls: logging only.** No dialling, no click-to-call, no softphone. Calls are recorded from provider webhooks (where a PBX exists) and by manual entry, with outcome, notes and follow-up. | **Mockup screen 6 (Live Call) is out of scope** and is not built. `VoiceProvider` narrows to inbound event ingest + optional recording fetch — no `dial()`. `COMM_CALL` becomes `COMM_CALL_LOG`. Phase 6 shrinks accordingly. |
| **D2** | **Email receive: IMAP polling.** Per-tenant, opt-in, cursor-based, credentials AES-encrypted through the existing `AesSecretCipher`. | Gmail tenants use an app password. A Gmail-API `MailboxReader` can be added later behind the same SPI with no other change. |
| **D3** | **UI: hybrid.** House-style page shell and chrome so the module sits naturally beside the other 21 features; Linear-style density and keyboard behaviour **inside** the inbox and conversation panes. | `communicationUi.jsx` forks `marketingUi.jsx` for `Page`/`Hero`/`Modal`/`Badge`/`Btn`, and adds dense list/thread primitives with `j`/`k`, `⌘K`, `r`-to-reply, `e`-to-archive. |
| **D4** | **Add Vitest + Testing Library.** First FE test infrastructure in the repo, usable by every feature after. | New devDependencies + `vitest.config.js` + a `test` script, wired in Phase 1; real component tests land in Phase 3. |

Recorded as architect's calls, reversible, not blocking: §7 (surface existing automations rather than build a third engine), §7.1 (one template library), §3.1 (one message table; notes and internal chat as conversations), §4.1 (own extractor rather than widening the `leadsource` SPI), §3.3 (partitioning deferred; retention ships off by default).
