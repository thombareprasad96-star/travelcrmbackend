# Unified Lead Source Integration Framework — Design

**Status: APPROVED. Phase 1 BUILT and verified end-to-end against the dev database (2026-07-17). Phases 2 and 3 remain BLOCKED on R1–R4 — see "Phase 0 survey — RESULTS" below.**
**Date:** 2026-07-17
**Ground truth:** `docs/LEAD_SOURCE_INTEGRATION_SCAN.md` (2026-07-16). Every claim below that constrains the design carries a `file:line`.

> **Reading this document after the fact.** It was written *before* implementation and is preserved as
> the reasoning record. Where the build learned something the design did not know, the correction is
> inline and marked **[BUILT]**. Two things changed materially: five more binding decisions were taken
> after this was written (they are folded into the list below, numbered 5–9), and the Phase 0 survey
> has now actually been run — its results are recorded below rather than left as an open question.

Tenants connect their own lead-source accounts (JustDial, IndiaMART, Meta Ads, Google Ads, IVR, their own website form, …) from a Settings → Integrations grid; every inbound lead lands in the existing Lead pipeline with campaign-grain attribution intact, and **adding a channel is one adapter class and nothing else**.

---

## The ten binding decisions

Settled by the owner. Not open. Do not re-litigate.

**1–4 and 10 were settled before this document was written. 5–9 were settled after it, during Phase 1,
and are folded in here so this list is the single record.** (10 is the original decision 5 — META_ADS —
kept last because it is the only one about a *later* phase.)

1. **Repeat inbound contact from a known OPEN lead's phone → APPEND AN ACTIVITY to that lead.** Not a new lead, not a 409. Today it is a hard 409 and the enquiry is *lost with no trace* (`LeadServiceImpl.java:598-607`). Appending deliberately never writes a second open row for that phone, so **the partial unique indexes at `db/indexes.sql:82-87` are never touched and never need to change**.
2. **Tenant identity → a per-tenant OPAQUE INGEST TOKEN in the URL** — `/api/webhooks/leads/{channel}/{token}`. `publicId` **never** appears in an ingest URL. `tenantId` is **NEVER** derived from the request body: on a context-less thread `TenantEntityListener` accepts an explicit tenantId with zero validation (`:24-30`), so a body-supplied tenant is a silent cross-tenant write.
3. **Attribution → CAMPAIGN-GRAIN, schema ready NOW** — campaign name, ad id, adset id, form id, gclid/fbclid, keyword, call recording URL, caller DID. Not channel-grain-only. Today the 9-constant enum is the entire provenance model; no campaign/utm/gclid/rawPayload field exists on `Lead` (scan Area 2, "Attribution is essentially absent").
4. **`GOOGLE_ADS` and `WHATSAPP` stay MANUALLY selectable** *and* machine-stamped. Two constants therefore cannot say who created them — **which is the entire reason `Lead.origin` (MANUAL|INTEGRATION|SYSTEM) exists**, alongside a nullable `source_integration_id`. SYSTEM covers Sub-Agent / Repeat Customer: machine-made, no integration row.
5. **Inbound (machine) lead assignment = LOWEST WORKLOAD FIRST, round-robin among ties.** Not pure round-robin: a round-robin cursor distributes *count*, and an agent already buried keeps receiving. The persisted cursor is retained but demoted to the tie-break — which is also what makes the outcome deterministic and testable. **[BUILT]** `LeadAssignmentService.assignForInbound(Long tenantId)`, returning `Optional` — a tenant with nobody eligible is a real reachable state (deactivate every agent) and must quarantine, not throw, or the webhook 5xxs and the provider disables the integration. It cannot reuse `assignForCreate`, which opens with `currentUser()`: on the ingest path the caller is a token, not a person.
6. **Workload metric = `todo + inProgress + activeLeads + openReminders`.** Open reminders are `Active + OVERDUE`. This is the *same* number the Calendar's workload tab shows — one definition, one place (`WorkloadService`), or the dropdown's "recommended" and the calendar's bar chart drift apart and neither is trustworthy. **[BUILT]** `workload/UserWorkload.score()`; `AssignmentContext.activeLeadCounts` was renamed `workloadScores` because it no longer counts only leads.
7. **Open reminders COUNT toward workload; quotations, customers and campaigns do NOT.** The rule the owner gave is "it depends on the organisation" — so the metric counts only what is unambiguously *assigned work with a due state*. A quotation is an artefact, not an obligation; a customer is a relationship. Adding them would inflate the score for people who merely *have* a big book of business and would silently starve them of new leads.
8. **`Booking.assignedUserId` — a booking's assignee defaults to the LEAD's assignee, editable.** Explicitly *not* whoever clicks Convert: a manager converting on an agent's behalf must not silently take the account. Relationship-based, deliberately NOT load-based — unlike an inbound lead, a booking already has a human attached to it. **[BUILT]** `BookingAssigneeResolver`; direct (non-conversion) creates default to the current user instead, there being no lead to inherit from.
9. **Inbound leads DO consume the tenant's lead quota; over-cap → quarantine, HTTP 200, notify. Never 403.** The quota is a billing boundary, not an input-validation boundary. A 403 to JustDial means retries, then a disabled integration, then leads silently lost — protecting a cap by destroying the thing the cap meters.
10. **`META_ADS` stays in scope and ships Phase 3 — but the Phase-1 SPI must already accommodate it.** Meta needs no-token resolution by page id, a `hub.challenge` GET echo, batched `entry[].changes[]`, and a deferred two-step fetch by `leadgen_id`. Retrofitting those into a token-only SPI later is a rewrite, so `resolution()`, `Echo`, `Complete(List<…>)` and `Deferred` are in the Phase-1 interface even though the Meta adapter is not.

---

## Phase 0 survey — RESULTS (run 2026-07-17)

This document repeatedly flags two things as UNVERIFIED and gates the estimate on them. Both have now
been run against the dev database. Recording the answers here so the open questions do not outlive
their answers.

| Question | Answer | How it is now kept answered |
|---|---|---|
| Does `leads_lead_source_check` exist, and does it accept all 25 constants? | **Resolved.** `db/indexes.sql` refreshes it, and the refresh demonstrably applies. | `SchemaEnumConstraintValidator` asserts **4** guarded columns at every boot (`lead_source`, `lead_stage`, `origin`, `lead_ingest_events.status`). It passing proves `indexes.sql` genuinely ran — `lead_ingest_events_status_check` is new and nothing else creates it. This closes the "`continue-on-error=true` swallows every failure" risk at line ~737: the swallow is still real, but it is no longer *silent*. |
| Phone-collision report — would canonicalising phones merge two distinct existing leads? | **CLEAN on dev.** Zero collisions; 7 legacy rows backfilled, idempotent on re-run. | `LeadPhoneNormalizationBackfill` logs the collision report **at every boot**, so the pilot answers this question about its own data automatically instead of depending on someone remembering to run a query. |
| **R1–R4 (provider payload shapes)** | **STILL UNANSWERED. Unanswerable from this repo.** | Unchanged — see §8. This is why Phase 1 shipped `WEBSITE_FORM` first: its payload is *ours*. **Phases 2 and 3 are blocked on these, not on engineering time.** |

---

## What the scan changed about the obvious design

The naive design is "add ~15 enum constants, add a `@Transactional` webhook controller, call `createLead`." Every clause of that is wrong.

- **No machine can create a lead today.** `createLead` hard-requires an authenticated tenant `User` at three points (`LeadServiceImpl.java:568-574` `currentUser()`; `LeadAssignmentService.java:327-330`; `enforceLeadQuota` 403s at `:505-515`). Worse, `recordAssignmentAudit` calls `currentUser()` at **`:119` — outside the `try` that opens at `:120`**, so a null principal rolls back the lead the comment at `:121-123` promises it can never break. There is no precedent for programmatic lead creation — `DevDataSeeder` bypasses the service entirely (`:526-543`). **Ingestion therefore routes through ONE shared `createLeadInternal` behind an explicit `LeadActor` + `IngestPolicy` parameter — NOT a second create path.** The fork was proposed and is **rejected**: it rested on "createLead is used by the entire app," which has exactly **one** caller (`LeadController.java:49`), and a fork is what gives birth to the double-notify it then hand-rolls a fix for.
- **`TenantFilterAspect` fails OPEN *and* its advice fires before the method body.** Verified: `if (tenantId == null) return;` (`common/aspect/TenantFilterAspect.java:34-37`) — no filter, no error, no log, and `softDeleteFilter` still enables unconditionally (`:30-32`) so the session *looks* filtered. The advice is `@Before` on `@Transactional` (`:22`), so **`setTenantId()` as the first line of a transactional method is TOO LATE** — the filter is already decided and stays off for the whole transaction. `TravelerAuthServiceImpl` is live code with exactly this shape (`:67` / `:77`). **TenantContext MUST be established BEFORE crossing any transactional boundary.** This is invisible in single-tenant dev testing and is a cross-tenant leak.
- **Dedup rejects rather than merges, and is enforced twice.** `validateNoDuplicates` throws (`LeadServiceImpl.java:598-607`) *and* Postgres independently enforces the same predicate (`db/indexes.sql:82-87`) — so skipping the service check just converts a clean 409 into a raw `DataIntegrityViolationException`. Decision 1 sidesteps both by never inserting a competing open row.
- **Append-on-repeat is dead code without canonicalisation.** Three phone treatments coexist and disagree: `PhoneNormalizer.normalize` is `trim()`-only by deliberate javadoc (`:25`), `WhatsAppMessagingService.normalize` does real E.164 (`:146-156`), and **lead dedup calls neither — phone is compared RAW** (`LeadServiceImpl.java:601`). Inbound delivers E.164; the DTO regex `^\+?[1-9]\d{7,14}$` rejects the spaces/dashes/leading zeros telephony actually sends (`CreateLeadRequestDto.java:24-27`). Hence the new `leads.phone_normalized` column — matching on raw phone would match nothing.
- **No opaque-token infrastructure exists at all.** No token/slug/site_key/webhook_secret column on any entity; `SecureRandom` appears in exactly 2 files (OTP codes, GCM IVs), neither a URL identifier; every public identifier is a `publicId` UUID; Razorpay's webhook secret is **one global platform value**, not per-tenant (scan reuse table, "Opaque tokens / per-tenant ingest key: **BUILD — nothing exists**"). Decision 2 is greenfield.
- **The prefix silently decides the auth posture.** `POST /api/webhooks/**` is already `permitAll` prefix-wide (`SecurityConfig.java:93` — verified above), but the permit is **POST-only**, so a Meta/JustDial verification **GET falls through to `.anyRequest().authenticated()` at `:108` and 401s**; and `POST /api/public/**` is *not* permitted (`:95` is `HttpMethod.GET`) — it compiles, deploys, and 401s. Webhooks are also entirely unthrottled (`RateLimitFilter.java:52-56`) and skip `ModuleAccessFilter` (`:80-81`).
- **Publishing a notification wipes your own TenantContext.** `NotifyEventListener` is a plain synchronous `@EventListener` calling `TenantContext.clear()` in `finally` (`:38`) on the *publisher's* thread, and `TenantContext` is a bare `ThreadLocal<Long>` with no stack. **Publish last.** `LEAD_CREATED` already fans out (`LeadServiceImpl.java:641-680`) — a framework that publishes its own event double-notifies.

---

## Architecture

**The pieces.**

| Piece | Role |
|---|---|
| `LeadIngestController` | Thin. 3 URL shapes, binds `@RequestBody byte[]` (`consumes=ALL_VALUE`) because HMAC is over the exact received bytes (`RazorpayWebhookController.java:29-33`). **Not `@Transactional`.** |
| `LeadIngestGateway` | **NON-transactional** orchestrator. Owns resolve → log → verify → parse → `TenantScope` → delegate. The only place TenantContext is set. |
| `IngestTokenResolver` | **NON-transactional** so `TenantFilterAspect` never fires and the probe is legitimately global. Two derived finders on the tenant-scoped repo — no `findById`, so `TenantIsolationArchTest:60-61` stays green. |
| `LeadSourceAdapterRegistry` | `Map<String, LeadSourceAdapter>` keyed by `channel.slug()`, folded from `List<LeadSourceAdapter>` in the ctor (`OtpSenderResolver:20-24` idiom) but **throwing on a duplicate slug** — last-one-wins here is wrong-tenant attribution on a public endpoint. |
| `LeadSourceAdapter` | **PURE** — no repository, no `EntityManager`, no `TenantContext`. ArchUnit-enforced, because adapter purity *is* the tenant-isolation argument. |
| `LeadIngestionService` | The transactional service. Entered **with TenantContext already live**. Match-or-create, activity append, attribution write. Calls the **shared** `createLead(r, LeadActor.integration(…), MACHINE)` — one create path, not a fork. |
| `LeadSourceFetcher` | Sibling SPI for two-step channels (Meta). Background drain, own transaction, re-resolves credentials per attempt. Zero implementations until Phase 3. |

**End-to-end trace — one real JustDial lead.**

`POST /api/webhooks/leads/justdial/lsk_7Qk…` with a JSON body.

1. **Chain.** `SecurityConfig.java:93` permits the POST prefix-wide — no config change. `JwtAuthFilter` early-returns on the missing Bearer header (`:45-48`). `ContextCleanupFilter` (`@Order(HIGHEST_PRECEDENCE+1)`, `/*`, unconditional `clear()` in `finally`, `:52,59-64`) guarantees the thread arrives clean and leaves clean — **the gateway must not *assume* it arrives clean, it must rely on the filter to clean up after.**
2. **Token → integration row.** Gateway computes `sha256(presented)` and calls `findByIngestTokenHashAndDeletedAtIsNull(hash)`; on a miss, `findByIngestTokenHashPreviousAndDeletedAtIsNull(hash)` and checks `token_previous_revoke_at > now`. **O(1) unique btree probe** — SHA-256 is deterministic. Both finders run on a **non-transactional** resolver, so no tenant filter is enabled and the global probe is intentional, not accidental. Miss → **404**. Path segment `justdial` ≠ `row.getChannel()` → **404, never 400** (a 400 confirms the token exists for another channel).
3. **Row → tenantId.** `integration.getTenantId()`. This is the Razorpay-shaped variant — resolve from a local row an authenticated tenant created earlier (`SaasPaymentServiceImpl.java:237-241`) — the only variant an attacker cannot steer. **Nothing was read from the body.**
4. **Set context BEFORE the transactional boundary.** `TenantScope.run(tenantId, …)` sets `TenantContext` on the *non-transactional* gateway thread, then makes a **cross-bean** call into the transactional writer, and `clear()`s in `finally`. Cross-bean is mandatory: self-invocation defeats both the tx proxy *and* the aspect (`UsageAlertScheduler:36-46`, `DocumentExpiryReminderScheduler:44-59`). Setting the context inside the writer would be **too late** (`TenantFilterAspect.java:22,34-37`).
5. **Raw payload logged FIRST.** `LeadIngestEvent{status=RECEIVED}` in its **own committed transaction, before verification**, with `raw_payload` capped at 64KB (`payload_truncated`) and the adapter's `secretFieldPaths()` redacted. Deliberately inverts Razorpay, which persists its ledger row **last** (`SaasPaymentServiceImpl.java:201-209`) and therefore leaves **no trace** of a failure — the exact reason dropped enquiries are invisible today. A rejected delivery must be debuggable; that is why the row lands before the verdict.
6. **Verify.** `adapter.verification()` is **mandatory — no default**, because a defaulted `verify()` fails open by inheritance, invisibly. JustDial declares `SharedSecretInBody`/`HmacHeader` per its console; the mode is stored on the row, so a payload arriving **without** a signature is **REJECTED, not downgraded** — otherwise the strongest channel degrades to the weakest at the attacker's option. Secret unset → **fail CLOSED** (`RazorpayGatewayClient:117-120` precedent). Fail → event `FAILED`, 401.
7. **Parse.** `adapter.parse(RawInbound)` → `Complete(List<NormalizedLead>)`. **PURE** — no IO, no DB, replayable from the stored raw payload. `NormalizedLead` is deliberately honest: nullable email, **raw** `phoneRaw` + nullable `phoneCountryHint`, **no** leadStage, assignedUserId, tenantId or LeadSource — every one of those is a default, and a default baked into the SPI is a default re-invented 16 times.
8. **Canonicalise.** Framework (not adapter) derives `phone_normalized` from `phoneRaw` + hint. **Without this step, step 9 matches nothing** — dedup compares raw today (`:601`).
9. **Match?** `findFirstByPhoneNormalized…OrderByCreatedAtDesc` scoped to the tenant and to non-terminal stages. A plain `Optional` finder throws `NonUniqueResultException` — terminal stages release the natural key, so there is a **chain** of leads per phone (`LeadRepository.java:62`).
10. **Append OR create.**
    - **Hit (open lead exists) → APPEND** (decision 1): one `LeadLog` with the new `activity_kind`, `ingest_event_id` and `source_integration_id`; the existing lead's stage, owner and `lead_attributions` row are **untouched** (attribution is first-touch, 1:1). Event → `APPENDED`. **No new row, so no unique-index contact.**
    - **Miss → CREATE** via `LeadIngestionService` calling the shared `createLead(r, actor, MACHINE)`: `origin=INTEGRATION`, `source_integration_id`, `leadSource` from `channel.leadSource()`, `phone_normalized`, nullable `email` (the NOT NULL drop is a hand-written `db/indexes.sql` block — `ddl-auto=update` will not perform it). Then one `lead_attributions` row (`campaign_name`, `ad_id`, `gclid`, …) with `lead_id` as a **logical FK, no DB constraint** — that is the fix for the trash-purge rollback hole. Event → `PROCESSED` + `lead_id`.
11. **Notify LAST.** One `NotifyEvent` with **`.tenantId(…)` explicitly set** — on a webhook thread `TenantEntityListener` reads it and the notification vanishes into a swallowed ERROR log without it (`SaasPaymentServiceImpl:371-388` is the live template). `IN_APP` alone; `recipientUserIds` always explicit; `referenceType="LEAD"`. **Publish last** — `NotifyEventListener:38` clears the publisher's own `TenantContext`. `LEAD_CREATED` (`:641-680`) is **reused** for machine leads and fires exactly **once**, precisely because the machine arm shares `createLeadInternal` and publishes nothing inline: under `MACHINE` the events ride out on `LeadIngestOutcome.pendingEvents()` and this gateway publishes them after commit. No double-notify.
12. **Respond 200** with a bare ack. `TenantScope`'s `finally` clears the context; `ContextCleanupFilter` clears it again regardless.

---

## Data model + every `db/indexes.sql` delta

**Five entities.** Three new (`LeadSourceIntegration`, `LeadAttribution`, `LeadIngestEvent`), two extended (`Lead` +3 columns, `LeadLog` +3 columns). Three new enums (`LeadOrigin`, `SourceSelectability`, `LeadIngestStatus`) plus 16 constants on `LeadSource` and the new `LeadSourceChannel`. Everything below is forced by a citation or is marked an open risk.

### 0. Facts re-verified against the working tree (several overturn the draft)

| Claim | Result |
|---|---|
| `db/indexes.sql` is hand-APPLIED | **FALSE — and this is the headline.** `application.properties:84-87`: `spring.sql.init.mode=always`, `schema-locations=classpath:db/indexes.sql`, `continue-on-error=true`, `defer-datasource-initialization=true`. `application-prod.properties` has no override. The file ships inside the jar and Spring Boot runs it after Hibernate schema-gen **on every boot**. CLAUDE.md's "by hand" means hand-*authored*, not hand-*applied*. Every consequence below flows from this. |
| `TenantFilterAspect` fires on class-level `@Transactional` | **NO** — `@Before("@annotation(org.springframework.transaction.annotation.Transactional)")` (`TenantFilterAspect.java:22`), method-level only, and it `return`s on a null tenant (`:34-37`) leaving `tenantFilter` off. "Non-transactional ⇒ the aspect never fires" is a load-bearing tool, not a coincidence. |
| ArchUnit blocks a token-hash derived finder | **NO.** It bans exactly five names — `findById`, `findAllById`, `getById`, `getOne`, `getReferenceById` (`TenantIsolationArchTest.java:60-61`). A derived finder is legal. Its javadoc forbids the `EXEMPT_CLASSES` escape (`:52-55`). |
| `Lead` is `@Audited` (Envers) | **FALSE.** Only `Booking`, `TaxInvoice`, `BookingCancellation`. The Envers argument for a separate attribution table is unavailable; the read-path argument (§5) is the real one. |
| Any JSON/JSONB mapping exists | **FALSE** (scan `:87`). Zero hits for `@JdbcTypeCode`, `SqlTypes.`, `jsonb`. |
| `AesSecretCipher` can back an indexed lookup | **FALSE.** Random 12-byte IV per `encrypt()` (`AesSecretCipher.java:50-51`) ⇒ non-deterministic ciphertext. Kills AES for the token column on technical grounds, not preference. |
| `LeadType.REPEAT_CUSTOMER("Repeat Customer")` exists | **YES** (`LeadType.java:9`) — a collision the scan did not surface. See §7. |
| `LeadMapper` dereferences email unguarded | **YES, verified verbatim:** `.email(request.getEmail().toLowerCase())` at `LeadMapper.java:23`, inside `toEntity(CreateLeadRequestDto)` which `createLead` calls at `LeadServiceImpl.java:88`. Every phone-only inbound lead NPEs before the entity is built. **`LeadMapper` is a touched file.** |
| `lead_logs` survives the trash purge | **NO — pre-existing hole, fixed in this pass.** `LeadLog.lead` is a real FK, `optional=false` (`LeadLog.java:32-35`); `Lead` cascades to `itinerary` only (`Lead.java:128-131`) and has no `LeadLog` collection at all; `TrashableType` has `LEAD` but no `LEAD_LOG` (`TrashableType.java:48-81`). `TrashServiceImpl.purgeForCurrentTenant:135-157` `em.remove()`s expired leads inside ONE `@Transactional` loop over `TrashableType.values()`. A 31-day-old trashed lead with any log row → FK violation → the whole tenant's purge rolls back, for every type, forever. See §9. |

**The inverted risk that replaces the deploy-runbook.** `continue-on-error=true` means `DROP CONSTRAINT IF EXISTS` succeeds, the following `ADD CONSTRAINT` fails on one violating row, and **the app boots green with no constraint at all — weaker than before, logging nothing.** The file already documents this exact swallow at `:112-117`. Two controls, both owned elsewhere but stated here because this section creates the exposure:
- Phase 0's query is a **data survey** (`SELECT DISTINCT lead_source FROM leads;`, same for `lead_stage`, plus the phone-collision report), not a constraint survey — the block is idempotent and ships regardless of what the DB looks like.
- A boot-time assertion in `ProductionConfigValidator` that `leads_lead_source_check` **exists and contains all 25 constants**, failing loudly. Without it the acceptance test passes vacuously: with no constraint, every INSERT succeeds.

---

### 1. `LeadSourceIntegration` — the connection row

`com.crm.travelcrm.leadsource.entity.LeadSourceIntegration extends BaseTenantEntity`, table **`lead_source_integrations`**.

```java
private String        channel;                    // "justdial" — LeadSourceChannel.slug(). Plain VARCHAR. §2
private String        label;                      // tenant's own name: "Mumbai DID", "Goa Packages page"
private boolean       enabled;
private String        resolutionMode;             // TOKEN | PROVIDER_ACCOUNT
private String        ingestTokenHash;            // VARCHAR(64) NULLABLE — SHA-256 hex. THE tenant resolver.
private String        ingestTokenHashPrevious;    // VARCHAR(64) NULLABLE — overlapping rotation. §3
private String        ingestTokenPrefix;          // VARCHAR(24) plaintext. Masked display + log correlation ONLY.
private LocalDateTime tokenRotatedAt;
private LocalDateTime tokenPreviousRevokeAt;
private LocalDateTime tokenLastUsedAt;
private String        externalAccountId;          // FB page id / IVR DID / site key. NULLABLE.
private String        credentialsEnc;             // TEXT: Base64(AES-GCM(json)). Adapter-only. NEVER returned.
private String        configJson;                 // TEXT: plaintext json. Non-secret. FE-returnable wholesale.
private LocalDateTime credentialsExpireAt;        // typed on purpose — §1c
private short         keyVersion;
private String        status;                     // CONNECTED | DEGRADED | DISABLED
private long          leadCount;
private LocalDateTime lastLeadReceivedAt;
```

**(a) MANY rows per (tenant, channel). The row is a CONNECTION, not a config.** `TenantSettings` is one flat row per tenant with 13 columns for 2 integrations, "no type discriminator, no many-per-tenant connections, no connection status" (scan reuse inventory; `TenantSettings.java:45,58`) — and no unique constraint on `tenant_id`, so one-per-tenant is convention only. Forcing one-per-channel here pushes connections into a child table later: the same mistake at a different grain. Uniqueness is on the **token**, never on `(tenant, channel)`.

> The many-connections *rationale* is proven (TenantSettings' shape). The *enumeration* — "Meta means several FB pages, IVR several DIDs, JustDial possibly several city accounts" — is **assumption, not fact**, and is a **research task** per provider. The decision does not depend on it.

**(b) Credentials: hybrid, split by one mechanical rule.**

> **Typed column ⟺ the FRAMEWORK queries it. Encrypted blob ⟺ only the OWNING ADAPTER reads it, after the tenant is resolved.**

1. Typed per-channel columns cannot meet the goal. "One adapter class and nothing else" and "a column per credential" are directly contradictory — `TenantSettings` is the proof.
2. Shapes genuinely differ: Meta needs page token + expiry + refresh + page id; JustDial one key; `WEBSITE_FORM` no credential but an allowed-origins list. A bag absorbs all three.
3. **`credentials_expire_at` stays typed anyway** — a refresh sweep must ask "which connections expire in <7 days" **across tenants without decrypting every row**. That single query is what makes this a hybrid rather than a pure blob.
4. **No JSON/JSONB pattern is introduced, and that is forced, not conceded.** The bag is encrypted ⇒ the column holds opaque Base64 ⇒ Postgres cannot index inside it regardless. Encryption forces `TEXT`. Jackson ⇄ `Map<String,String>` Java-side. In-house precedent: `AiAuditLog.tool_params` (TEXT holding a JSON snapshot, `:46-48`).
5. **Two blobs, not one — this is what makes the write-only-secret contract STRUCTURAL.** `config_json` is FE-returnable wholesale; `credentials_enc` never is. One combined blob would force `GET` to decrypt and hand-filter — a **blacklist**, and the scan is explicit that the public-DTO discipline is *whitelist, never blacklist-strip* (`:163`). Two blobs makes the whitelist a property of the schema, not of someone remembering.

**Price paid, knowingly:** no DB validation of the bag; a typo'd key surfaces at ingest, not at save. Mitigation is `requiredCredentialKeys` — folded into `ChannelCatalogEntry` per canon (one declaration serves both the FE form and the save-time fail-fast). It is a convention, not a constraint. **ACCEPTED RISK.**

**`AesSecretCipher` reuse hazard.** It is a plain `@Component` with **explicit** `encrypt()`/`decrypt()`; there is no `AttributeConverter` and no `@Convert` anywhere (scan `:47`, `:202`). `credentials_enc` will silently store **plaintext** if any write path forgets the call. Encrypt at exactly **one chokepoint** (the config service), never per call site.

**(c) Counters.** Atomic `@Modifying` JPQL, never read-modify-write, never `@Version` — *an optimistic-lock conflict on a stats counter must never be able to reject a real lead.*

```java
@Modifying @Query("UPDATE LeadSourceIntegration i SET i.leadCount = i.leadCount + 1, "
                + "i.lastLeadReceivedAt = :now WHERE i.id = :id AND i.tenantId = :tenantId")
```
`@Modifying` JPQL **bypasses `@PreUpdate`**, so `TenantEntityListener`'s cross-tenant guard (`:33-41`) does not run. `AND i.tenantId = :tenantId` is load-bearing, not decoration.

---

### 2. `channel` — a String slug, no `@Enumerated`, no check constraint

In the JVM: a new `LeadSourceChannel` enum with `String slug()` (lowercase, url-safe, **permanent**) and `LeadSource leadSource()`. It is the registry key and the URL segment source. It is **not** `LeadSource` — `LeadSource` carries `@JsonValue getDisplayName()` (`LeadSource.java:23-26`), so its wire vocabulary is a **renameable display string**, and a webhook URL pasted into a third party's console is effectively permanent. Renaming a dropdown label must never break live ingestion. It also makes `adapterFor(WALK_IN)` a compile-time impossibility rather than a runtime throw.

In the DB: `lead_source_integrations.channel` is a plain `VARCHAR` storing `channel.slug()` — **no `@Enumerated`, no CHECK, no `indexes.sql` block**. `row.getChannel()` returns `String`; the path segment is compared to it as a `String` (mismatch → **404, never 400** — a 400 confirms the token exists for a different channel); the registry is looked up via `LeadSourceChannel.fromSlug(pathSegment)`.

**The registry is the constraint, and a strictly stricter one than a CHECK.** A CHECK validates *spelling*, so it would happily accept a `'meta_ads'` row on a node with no Meta adapter deployed. The registry rejects an unknown slug **at save time and again at ingest**. Precedent for the deliberate free-String sidestep: `WaMessageLog.status` (scan `:223`). Adding SULEKHA touches the DB **not at all**.

Rule, stated once: **closed vocabularies the framework owns → enum + CHECK. The open vocabulary the adapters own → slug + registry.**

---

### 3. Token columns — whole-token SHA-256, two nullable hashes on one row

`ingest_token_hash` **VARCHAR(64) NULLABLE**, `ingest_token_hash_previous` **VARCHAR(64) NULLABLE**, `ingest_token_prefix` VARCHAR(24) plaintext.

- **Whole-token SHA-256, not selector+verifier.** The premise "a fully-hashed token forces a full table scan" is false: SHA-256 is deterministic, so `WHERE ingest_token_hash = sha256(presented)` is an O(1) unique btree probe. The split buys nothing and costs a permanent plaintext partial-credential at rest.
- **Format** `lsk_` + Base64URL-unpadded(32 bytes `SecureRandom`). The `lsk_` prefix exists solely for secret scanning.
- **Unsalted is safe here and only here** — the input is 256 bits of `SecureRandom`; there is nothing to brute-force. **BCrypt is rejected**: per-row salt = unindexable, and ~100ms on an unauthenticated endpoint is a CPU-exhaustion amplifier. (The OTP module needs BCrypt because a 6-digit code is ~20 bits. This is not that.) **AES is rejected as a technical impossibility** — random IV per encrypt (`AesSecretCipher.java:50-51`) ⇒ non-deterministic ciphertext ⇒ unlookupable.
- **Reveal-once.** The raw token exists exactly once, in the mint/rotate response body; it is never re-derivable. Matches the existing `apiKeyChanged`/`apiKeySet` write-only contract (`WhatsAppConfigService.java:55-58`).
- **Overlapping rotation.** Regenerate moves current → previous and sets `token_previous_revoke_at = now + 72h`. Instant cutover drops every lead in the human gap while the tenant goes and pastes the new URL into JustDial's console. Two columns on one row, each with its own partial unique index `WHERE ... IS NOT NULL`.
- **A child `lead_source_ingest_tokens` table is REJECTED.** It has no `tenantId`, so resolving integration → credentials before the tenant is known forces `leadSourceIntegrationRepository.findById(...)` — which `TenantIsolationArchTest.java:60-61` **fails the build on**, and whose javadoc at `:52-55` forbids the `EXEMPT_CLASSES` escape. Two columns on one row cannot produce a `findById`.
- **Full-token logging is BANNED everywhere.** Log `ingest_token_prefix` only.

#### The resolver contract — the whole isolation argument

```java
// LeadSourceIntegrationRepository — two DELIBERATELY tenant-less finders. These ARE the tenant resolver.
Optional<LeadSourceIntegration> findByIngestTokenHashAndDeletedAtIsNull(String hash);
Optional<LeadSourceIntegration> findByIngestTokenHashPreviousAndDeletedAtIsNull(String hash);
```

**Both are called from a NON-`@Transactional` resolver, and that is a contract, not a style.** `TenantFilterAspect` is `@Before` on `@annotation(Transactional)` (`:22`) — method-level only. Non-transactional ⇒ the aspect never fires ⇒ the probe is legitimately global, which is what a tenant resolver must be. If it *were* transactional and the caller sent `Authorization: Bearer <valid JWT>` to the permitAll webhook, `JwtAuthFilter` **will** authenticate it and set `TenantContext` from the token (`:89-90`; scan `:75`) — `tenantFilter` would then scope the lookup to the **caller's** tenant and a valid token for tenant B resolves **empty**. Symptom: intermittent, unreproducible 404s.

> **CONTRACT — everyone downstream.** Resolve the token → `TenantContext.clear()` (the thread may arrive dirty; `ContextCleanupFilter` guarantees cleanup on *exit*, not cleanliness on *entry*, scan `:220`) → `setTenantId(row.getTenantId())` → *only then* call a transactional service. **Never inside** — the `@Before` has already latched (or skipped) the filter for the whole transaction, and it fails **open** (`:34-37`).

Neither finder filters `enabled` — **resolve first, authorize second**, so a delivery to a disabled connection is logged against the **right tenant** instead of vanishing into a 404. Scan finding #4 is precisely that dropped enquiries are invisible today; do not add a second invisible drop.

The token indexes are **NOT partial on `deleted_at`**, deliberately breaking this file's dominant idiom (vendors/customers/leads blocks, `:53-87`, where the point of a partial unique is that a value can be REUSED after soft-delete). Reuse is exactly what must not happen: reissuing a retired token silently redirects a provider that still has the old URL pasted in its console. **A retired token stays reserved forever.**

---

### 4. `resolution_mode` — how Meta gets a real row

`resolution_mode` VARCHAR: `TOKEN` | `PROVIDER_ACCOUNT`, with a CHECK asserting `TOKEN ⟺ ingest_token_hash IS NOT NULL` and `PROVIDER_ACCOUNT ⟺ external_account_id IS NOT NULL`.

Meta posts to one app-level callback for all pages, so it cannot carry a per-tenant token. Without this column, `leads_origin_link_check` cannot represent the flagship paid channel: a Meta lead would need `origin='SYSTEM'` (a lie — it came from a paid ad connection a tenant configured, and it breaks every "show me my integration leads" query), or a fabricated row with a dead token, or a CHECK violation. One column and one CHECK dissolves it. **There is NO `MetaPageBinding` entity and NO `IngestToken` entity** — Meta is a row with `resolution_mode=PROVIDER_ACCOUNT` and `external_account_id=<page_id>`.

`uq_ls_integration_account` is **platform-wide, not per-tenant**, and is therefore **load-bearing, not hygiene** — it IS the resolver for that mode. A provider account belongs to exactly ONE tenant; a per-tenant unique would let tenant B register tenant A's page id and harvest A's leads.

Owner decision 2 is honoured: for `PROVIDER_ACCOUNT` the **body supplies an account key, never a tenantId**, and that key is only a lookup into a row an authenticated tenant created — and the platform HMAC proves the body's authenticity **before** the lookup runs.

---

### 5. `LeadAttribution` — campaign grain, LOGICAL FK

`com.crm.travelcrm.lead.attribution.entity.LeadAttribution extends BaseTenantEntity`, table **`lead_attributions`**. First-touch, 1:1.

```java
private Long   leadId;                 // LOGICAL FK. NO DB FOREIGN KEY CONSTRAINT. UNIQUE on (lead_id).
private String campaignName;           // String SNAPSHOT — never campaignId, never an FK. §5b
private String adId; private String adsetId; private String formId;
private String gclid; private String fbclid; private String keyword;
private String recordingUrl; private LocalDateTime recordingUrlExpiresAt;
private String callerDid;
```
All nullable — provider-dependent by nature.

**(a) `lead_id` is a LOGICAL Long with NO DB constraint. This is the fix for the trash-purge rollback hole, and it is not optional.** A real FK + no inverse mapping + absence from `TrashableType` means `TrashServiceImpl.purgeForCurrentTenant:135-157` — a single `@Transactional` loop over `TrashableType.values()` calling `em.remove(entity)` — hits a live `lead_attributions` row on a 31-day-old trashed inbound lead, Postgres throws, **the transaction rolls back, and EVERY `TrashableType` purge for that tenant (leads, customers, bookings, master data, fleet) silently fails forever** because the same row is retried next night. The unique index on `lead_id` still enforces 1:1, so the logical FK costs nothing. Consistent with CLAUDE.md's cross-aggregate rule (`Booking.customerId`) and with `LeadIngestEvent.leadId`. Do **not** add it to `TrashableType` (it is not user-trashable) and do **not** rely on `ON DELETE CASCADE`.

**(b) Separate table, not columns on `Lead`.** The Envers argument is **unavailable** (§0). The real argument is the read path: `findAllByTenantIdAndDeletedAtIsNull` carries `@EntityGraph("assignedUser")` (`LeadRepository.java:24-26`) and `AllLeads.jsx` fetches **100 leads in one call**, filtering client-side (`leadService.js:45-46`). ~10 extra columns × 100 rows on every list render, for fields the list never shows — `leadSource` itself renders only in the expanded detail panel (`AllLeads.jsx:652`). Sparsity: only INTEGRATION-origin leads have attribution — today 0% of rows. `Lead` is already ~25 columns + 2 collections.

**(c) `Lead` must NOT map the inverse side.** A nullable inverse `@OneToOne` **cannot be proxied** without bytecode enhancement, so Hibernate fires an extra SELECT **per lead** in the 100-row list query — re-creating the exact N+1 this table exists to avoid. The detail path fetches explicitly via `findByLeadIdAndTenantId`.

**(d) Naming — the collision the scan flags.** `marketing.entity.Campaign` is an **outbound broadcast** to a Segment (`marketing_campaigns`; `sentCount`/`totalRecipients`; javadoc `Campaign.java:14-17`). Attributing an inbound lead to it corrupts its counters and feeds junk to `CampaignDispatchScheduler`. The field is **`campaignName` — a String snapshot**, never `campaignId`. That is independently correct: the ad campaign lives in Meta/Google, not our DB. **We record what the provider told us, at the time it told us.**

Package is `lead.attribution`, not `leadsource.*`: attribution is a property of the **Lead**, and SYSTEM-origin leads carry it with no integration row at all.

**(e) First-touch, 1:1. ACCEPTED RISK, flagged to the owner.** Decision 1 says a repeat contact is a follow-up on an existing acquisition, not a new attributable one. Nothing is lost — the second delivery's full payload lives in `lead_ingest_events`, keyed to the same lead. Forward path if multi-touch is ever wanted: add `touch_seq`, unique becomes `(lead_id, touch_seq)` — purely additive.

**Campaign/recording data lives ONLY here.** `LeadLog.ingest_event_id` points at the raw log, which purges at 30 days — it must never be the sole path to attribution.

---

### 6. `LeadIngestEvent` — raw log. **Yes, it can be `BaseTenantEntity`.**

`com.crm.travelcrm.leadsource.entity.LeadIngestEvent extends BaseTenantEntity`, table **`lead_ingest_events`**.

**Decision 2 resolves the sharpest question completely: tenant resolution does not depend on parsing.** The tenant is in the URL token (or the HMAC-proven account key), not the body. By the time the body is parsed *or fails to parse*, the tenant is already known.

| Failure | Tenant known? | Action |
|---|---|---|
| Token valid, body garbage | **YES** — from the token | Persist `FAILED` with the raw body. Tenant-scoped log works perfectly. |
| Token unknown | **NO** | An unauthenticated stranger. **No row, anywhere.** WARN + **401**. |

**Unknown-token deliveries are DROPPED with a WARN and a 401.** (The draft said 404; **overruled — the provider should stop.**) Persisting a stranger's body is an **unauthenticated write primitive and a disk-fill DoS** on a prefix that is completely unthrottled today (`RateLimitFilter.java:52-56`). `WebhookEvent`'s own precedent agrees — it stores no raw body at all (scan `:214`). The WARN carries the **token prefix only** plus the client IP resolved via `RateLimitFilter.resolveClientIp` (trusted-proxy gated) — **never `ClientIp.resolve`**, whose own javadoc says "(not a security control)" (`ClientIp.java:5-8`) and which lets an attacker rotate `X-Forwarded-For` for an unlimited fresh bucket. Note the asymmetry that makes this safe: a delivery to a **known** token on a **disabled** connection IS logged, against the right tenant, so the tenant sees "you turned this off and are dropping leads" rather than nothing.

This is the **opposite** of `WaMessageLog`, which relies on ambient `TenantContext` and throws on a webhook thread. We never rely on ambient context: the ingest path stamps `.tenantId(...)` **explicitly** from the resolved row. Scan `:75` notes that explicit tenantId + null context matches no `TenantEntityListener` branch and persists silently — that is precisely the combination used here, deliberately.

```java
private Long             integrationId;     // logical, nullable
private String           channel;
private String           externalEventId;   // nullable — content idempotency key
private String           dedupKey;          // nullable — transport-window key. §6b
private LeadIngestStatus status;            // @Enumerated(STRING). Proper enum, NOT WaMessageLog's free String.
private String           rawPayload;        // TEXT, UTF-8, capped 64KB; adapter secretFieldPaths REDACTED first
private boolean          payloadTruncated;
private Long             leadId;            // logical, nullable, NO FK — must survive the lead's purge
private int              attemptCount;
private LocalDateTime    nextRetryAt;
private String           errorMessage;
```

**NO `received_at`** — use `BaseEntity.createdAt`. Two columns meaning "when did this arrive" drift the first time a retry path or a `REQUIRES_NEW` boundary sets one and not the other, and support will not know which to trust. The delivery-history index already sorts on `created_at`. If provider-asserted event time is ever wanted it is a different column with a different name (`provider_event_at`), documented as untrusted, clock-skewed input **never used as a sort key**.

`LeadIngestStatus { RECEIVED, PROCESSED, APPENDED, DUPLICATE, IGNORED, DEFERRED, QUARANTINED_QUOTA, FAILED }` — a proper enum with a check refresh, rejecting `WaMessageLog`'s free-String sidestep: this status is a **query predicate and a partial-index predicate**, not a label. `APPENDED` must be distinct from `PROCESSED` or **decision 1's append rate is unmeasurable**. `IGNORED` = valid-but-not-a-lead (Meta's subscription echo, status callbacks). `DEFERRED` = a `FetchHandle` is queued. `QUARANTINED_QUOTA` = the plan cap rejected it; it exists so a quota-blocked enquiry is visible rather than lost (scan `:97` — duplicate-blocked enquiries never reach the counter today, so quota metrics under-report real inbound volume).

`leadId` is **logical with no FK** (unlike `LeadLog`'s real FK): `LeadLog` is a child that *should* die with its lead; an ingest event is an **audit record that must survive it**.

**(b) Idempotency — two keys, two grains, and the real rationale.**

- `external_event_id`: partial unique on **`(tenant_id, channel, external_event_id) WHERE external_event_id IS NOT NULL`**. Deliberately **not** `WebhookEvent`'s platform-wide `UNIQUE` (scan `:213`). **The rationale is first-principles, not folklore:** an external event id is **provider-controlled and namespaced by an account we do not administer**, so a platform-wide unique lets any one connection permanently poison a key for every other tenant — silent cross-tenant lead **loss**. *(The draft's "IVR vendors commonly use per-account sequence numbers" is invented, uncited, and unknowable from this repo. It is **deleted**. Per-vendor payload/id semantics for Exotel/MyOperator/Knowlarity are **research tasks**.)*
- `dedup_key`: the **transport-window** key that `IdempotencyKey.none()` produces — `(integration_id, sha256(body), floor(createdAt/60s))`. `none()` means "no CONTENT dedup", **not** "no dedup". It absorbs a retry storm (seconds apart, same window) while letting two genuinely distinct identical calls minutes apart both land. Partial unique on `(tenant_id, channel, dedup_key) WHERE dedup_key IS NOT NULL`.

**No unbounded body-hash fallback.** The scan flags that Razorpay's `"sha256:"+hash(rawBody)` **wrongly dedupes a byte-identical redelivery of a genuinely new event** (`:60`). That flaw is worse here: under decision 1 a redelivery costs a duplicate **activity**, never a duplicate lead — but a false dedup costs a **lost enquiry**. **Choose noise over loss.**

**Transaction boundaries are opposite and must stay opposite.** The dedup row lives in the **same transaction as the lead create** (so a rollback frees the key); the raw log commits in **its own** (so a failure still leaves a trace — `WebhookEvent.error_message`/`processed=false` exist but are never written, and a failing event rolls back its own ledger row and leaves nothing, scan `:214`).

**Retry is greenfield** — nothing anywhere has `attempt_count`/`next_retry_at`/`max_attempts` (scan `:217`). The schema carries the columns; the sweep must copy `DocumentExpiryReminderScheduler:44-59` exactly (`setTenantId` **outside** the transaction, delegate **cross-bean**, `clear()` in `finally`).

---

### 7. `LeadSource` — 25 constants + `SourceSelectability`

Wire format is `displayName` via `@JsonValue` (`LeadSource.java:23-26`). **Two constants sharing a displayName do not fail at compile time** — `fromValue` silently returns the first declaration-order match (`:29-37`). All 25 displayNames below are distinct, *and* no constant's displayName matches a **different** constant's `name()` under `equalsIgnoreCase` (all such matches are self-matches; `"Instagram"` → `INSTAGRAM` resolves before `INSTAGRAM_DM`, correctly). **A startup assertion that all 25 are distinct is required** — a collision compiles fine and silently mis-resolves.

| Constant | displayName | Selectability |
|---|---|---|
| `SOCIAL_MEDIA` | Social Media | MANUAL_SELECTABLE |
| `WEBSITE` | Website | MANUAL_SELECTABLE |
| `GOOGLE_ADS` | Google Ads | MANUAL_SELECTABLE *(decision 4 — ambiguous)* |
| `WHATSAPP` | WhatsApp | MANUAL_SELECTABLE *(decision 4 — ambiguous)* |
| `REFERRAL` | Referral | MANUAL_SELECTABLE |
| `OTHER` | Other | MANUAL_SELECTABLE |
| `FACEBOOK` | Facebook | **LEGACY_READ_ONLY** |
| `INSTAGRAM` | Instagram | **LEGACY_READ_ONLY** |
| `DIRECT_CALL` | Direct Call | **LEGACY_READ_ONLY** |
| `MANUAL` | Manual Entry | MANUAL_SELECTABLE |
| `WALK_IN` | Walk-in | MANUAL_SELECTABLE |
| `PHONE_MANUAL` | Phone (Manual) | MANUAL_SELECTABLE |
| `TRAVEL_MARKETPLACE` | Travel Marketplace | MANUAL_SELECTABLE *(a **third** ambiguous one)* |
| `JUSTDIAL` | JustDial | MACHINE_ONLY |
| `INDIAMART` | IndiaMART | MACHINE_ONLY |
| `TRADEINDIA` | TradeIndia | MACHINE_ONLY |
| `SULEKHA` | Sulekha | MACHINE_ONLY |
| `META_ADS` | Meta Ads | MACHINE_ONLY |
| `INSTAGRAM_DM` | Instagram DM | MACHINE_ONLY |
| `FB_MESSENGER` | Facebook Messenger | MACHINE_ONLY |
| `IVR_CALL` | IVR Call | MACHINE_ONLY |
| `WEBSITE_FORM` | Website Form | MACHINE_ONLY |
| `WEBLINK_ENQUIRY` | Weblink Enquiry | MACHINE_ONLY |
| `SUB_AGENT` | Sub-Agent | MACHINE_ONLY (SYSTEM origin) |
| `REPEAT_CUSTOMER` | **Repeat Enquiry** | MACHINE_ONLY (SYSTEM origin) |

**10 MANUAL_SELECTABLE / 12 MACHINE_ONLY / 3 LEGACY_READ_ONLY = 25.**

**`SourceSelectability` is ONE three-state field on the enum, not two booleans.** A boolean cannot express "readable but deprecated for new tagging", and `selectable && !deprecated` is **arithmetically incapable** of yielding the owner's 10. The FE dropdown is `catalog.filter(o => o.selectability === 'MANUAL_SELECTABLE')` — exactly 10, guaranteed. `withCurrent` prepends the row's own value for the other 15 regardless of bucket. **`origin` is NOT on the catalog DTO** — it is per-LEAD-ROW (a column on `Lead`) and a per-constant origin is meaningless for exactly the two constants decision 4 makes ambiguous. It belongs on `LeadResponseDto`.

That third state is what prevents re-running the live bug the scan proved: `LeadInformation.jsx:6-9` hardcodes 8 sources (the backend has 9 — `"Other"` is missing) → a lead carrying the 9th hits `EditLead.jsx:106`, matches no option, saves as `""` → **400**. A separate `Set` is the FE's hardcoding failure moved one layer down.

**`REPEAT_CUSTOMER` → "Repeat Enquiry" — a finding not in the scan.** `LeadType.REPEAT_CUSTOMER("Repeat Customer")` already exists (`LeadType.java:9`). Different enums, so `fromValue` does not break — but the create form would show **two dropdowns offering the identical string "Repeat Customer" meaning two different things** (*who the customer is* vs *how they arrived*). The enum name stays as specified; the displayName was mine to choose.

**`TRAVEL_MARKETPLACE` is a third ambiguous constant** beyond decision 4's two — a human must be able to tag a MakeMyTrip lead we have no adapter for (the same reasoning that keeps `OTHER` selectable), *and* a future marketplace adapter can stamp it. This does not contradict decision 4; it **confirms `origin` must be a general mechanism** rather than a two-constant patch.

**Never remove a constant.** `@Enumerated(STRING)` maps the stored string back and `fromValue` **throws** on unknown (`:36`) — deleting one breaks *reading* every historical lead that carries it.

#### The `@JsonValue` trap — binding note for the metadata endpoint

> **`value` MUST be `getDisplayName()`, NOT `name()`.**

`MarketingFieldCatalog.java:31` is `new OptionDTO(t.name(), t.getDisplayName())` — **correct for its enums** (they carry no `@JsonValue`) and **wrong here**. `GET /leads` serializes `leadSource` as `"Google Ads"`; a `value="GOOGLE_ADS"` option set matches nothing, `EditLead` saves `""` → 400. The POST direction *would* work because `fromValue` accepts **both** vocabularies case-insensitively (`:32`) — **which is exactly why this bug survives review.** So `value == label`. That looks redundant and **is the truth**: the wire vocabulary *is* the display name. The DTO carries a comment saying so, or someone will "fix" it.

---

### 8. `Lead` gains exactly THREE columns; `LeadLog` gains THREE

```java
// Lead.java
@Enumerated(EnumType.STRING)
@Column(name = "origin", length = 20)   // NULLABLE in the entity — see below. DO NOT "correct" this.
@Builder.Default
private LeadOrigin origin = LeadOrigin.MANUAL;

@Column(name = "source_integration_id")     private Long   sourceIntegrationId;  // logical, nullable
@Column(name = "phone_normalized", length = 20) private String phoneNormalized;  // nullable. §8c
```
**Nothing else. No inverse `@OneToOne` to `LeadAttribution`** (§5c). `leads.email` **drops NOT NULL** (§8d).

`LeadLog` gains three nullable logical columns: `ingest_event_id` (Long), `source_integration_id` (Long), `activity_kind` (a new enum on a **new** column — Hibernate generates its check fresh, so **no `indexes.sql` block is needed**).

**(a) `@Builder.Default = MANUAL` is what keeps `SET NOT NULL` safe.** `DevDataSeeder.java:526-533` builds `Lead` entities directly and calls `leadRepository.save(lead)`, bypassing both `createLead` and `LeadMapper` — the only two places that would otherwise stamp `origin`. With the Java-side default, **every** construction path (seeder, mapper, future direct writers) is correct by default and only the ingest path overrides. **DevDataSeeder needs no edit**, and the hand-written-mapper silently-null-field risk (`LeadMapper` is a `@Component`, not MapStruct — scan `:103`) is removed for this field.

**(b) The `nullable=true` divergence is deliberate and must carry a comment.** With `nullable=false`, `ddl-auto=update` emits `ADD COLUMN origin varchar(20) NOT NULL` **with no default**; Postgres **rejects** that on a populated table; Hibernate's `SchemaUpdate` **swallows** the DDL error (`halt_on_error=false` by default — the codebase documents this exact swallow class at `UserDetailsServiceImpl.java:28-30`). Result: **the column does not exist and every subsequent lead insert fails.** Sequence is therefore **declare nullable → backfill in `indexes.sql` → `SET NOT NULL`**, in that order. Someone will "correct" the entity to `nullable=false` and break every populated database.

`'MANUAL'` for pre-existing rows is **correct, not a guess**: scan finding #1 establishes there was **no programmatic lead-create path at all** (`createLead` requires an authenticated tenant `User` at three hard points, `LeadServiceImpl.java:82-111`; `DevDataSeeder` bypasses the service, `:526-543`). Every existing lead was hand-entered by a logged-in human.

**(c) `phone_normalized` — this section OWNS decision 1, and the sequencing is the sharpest point in it.** Verified: `validateNoDuplicates` passes the phone **RAW** — no normalisation, no trim (`LeadServiceImpl.java:598-607`) — and `uq_leads_phone_tenant_open` indexes the raw `phone` column (`indexes.sql:85-87`). `DevDataSeeder.java:527` writes `"+91 91234 5000" + i` **with spaces**, proving real rows carry separators the DTO regex `^\+?[1-9]\d{7,14}$` (`CreateLeadRequestDto.java:24-27`) would reject. An inbound E.164 payload therefore **never string-matches a human-typed lead**, the append never fires, and the framework silently creates a duplicate — no error, no log. Without this column decision 1 is **dead code**.

> **CRITICAL SEQUENCING.** Ship `phoneNormalized` **WRITE-ONLY first**: populate on create/update, backfill, publish the collision report, and **leave `LeadServiceImpl:601` on the raw phone**. Only after the report is clean and `uq_leads_phone_norm_tenant_open` exists does `:601` move to the canonical column. Otherwise, on day one, two pre-existing open leads that canonicalise identically become **permanently un-editable for a human** — a bigger blast radius than the un-creatable case, and of unknown volume at the moment it is introduced. `checkTrashedForRestore`'s phone finder moves in the **SAME change** (`LeadServiceImpl.java:616-639`) — leaving it on raw silently kills the restore affordance for exactly the format-mismatch cases it exists for. The machine path reads the canonical column from day one; it has no legacy rows to collide with.

The backfill **reuses the same `canonical()` Java code** — a SQL reimplementation that disagrees by one edge case *is* the migration risk. **Rewrite-in-place of the raw `phone` column is REJECTED**: `PhoneNormalizer`'s javadoc states stronger canonicalisation "belongs with a deliberate one-time data migration, not a passive read-path change" and that rewriting the stored format "would silently break matches against existing rows"; `Customer.phone` / `uq_customers_phone_tenant` key off the same strings (`indexes.sql:69-70`), and a non-atomic rewrite spawns duplicate customers.

**(d) `leads.email` drops NOT NULL. Nullable wins; the synthetic placeholder is rejected.** `@NotBlank` **stays** on `CreateLeadRequestDto` — bean validation fires at the controller boundary via `@Valid`, not on the service call, so the human/machine asymmetry is **free**. The service is already null-tolerant (`validateNoDuplicates:587`, `checkTrashedForRestore:617` both guard). Postgres partial unique indexes treat NULLs as distinct, so **N anonymous IVR leads coexist under `uq_leads_email_tenant_open` with no index change**.

A synthetic `<e164>@no-email.invalid` is rejected on three counts: it is a lie in a column humans read and mail-merge to; the marketing broadcast module would dispatch to it; and its claimed emergent property is **false** — dedup is a **rejection** mechanism (scan finding #4), so a collision produces a 409 (or a raw 500 if the service check is skipped), **never an append**, and a trashed match produces a `RestoreAvailableException` a machine cannot act on.

**Code deltas this forces — `LeadMapper` is a TOUCHED FILE:**
- `LeadMapper.java:23` — `.email(request.getEmail().toLowerCase())` is **unguarded** and NPEs on every phone-only inbound lead, before the entity is built. The 5xx makes the provider retry forever. Null-guard it.
- `LeadServiceImpl.updateLead:235` — same unguarded `getEmail().toLowerCase()`.
- **Grep every unguarded `getEmail()`/`getPhone()` dereference on the create path before Phase 1 closes.**
- `LeadMapper` is the **single construction point** for `origin`, `sourceIntegrationId` and `phoneNormalized`. It maps `actor.origin() → lead.origin` and `actor.integrationId() → lead.sourceIntegrationId`. **`LeadActor` carries the Long integration id, not the publicId** — the ingest path already HOLDS the row (resolved by token ⇒ tenant-correct by construction) and a publicId would force a redundant lookup. **`toEntity` must NEVER accept `origin` from the client** — it is server-decided, or a caller self-declares `INTEGRATION` origin.
- FE note: `LeadResponseDto.email` becomes nullable. `AllLeads` is already null-safe (`CopyableEmail:410`, `:920`, `lead.email?.toLowerCase()`:1442) — verified during the red-team, not assumed.

**`LeadOrigin { MANUAL, INTEGRATION, SYSTEM }` is ONE enum** in `com.crm.travelcrm.lead.enums`. The parallel `ActorOrigin` is **deleted**.

---

### 9. `lead_logs` — the identical pre-existing hole, fixed in this pass

**State it in writing so it is not discovered when the first inbound lead ages out.** `LeadLog.lead` is a real FK, `optional=false` (`LeadLog.java:32-35`); `Lead` cascades to `itinerary` only and declares no `LeadLog` collection (`Lead.java:128-131`); `TrashableType` has no `LEAD_LOG` (`TrashableType.java:48-81`). So `TrashServiceImpl:135-157` already cannot purge any trashed lead that has a log row — and one FK violation rolls back **every** `TrashableType` purge for that tenant, permanently.

It survives today only because `lead_logs` rows exist **when a human wrote one**. Under decision 1, **every repeat inbound contact writes a `LeadLog`** — the blast radius goes from incidental to universal. `lead_attributions` avoids this by construction (logical FK, §5a); `lead_logs` has a real FK that predates us, so the cheap correct fix is `ON DELETE CASCADE` — a log genuinely *should* die with its lead. It is an `indexes.sql` delta because `ddl-auto=update` never alters an existing constraint. Ordering is safe: Hibernate runs first and leaves a same-named constraint alone; `indexes.sql` then drops and re-adds it with the cascade, idempotently, every boot.

---

### 10. `db/indexes.sql` — every delta, verbatim

Append to the end of the file, matching its existing comment idiom.

```sql
-- ============================================================================
-- UNIFIED LEAD SOURCE INTEGRATION FRAMEWORK (com.crm.travelcrm.leadsource)
-- ============================================================================

-- ── LeadSource enum CHECK constraint refresh (leads.lead_source) ─────────────
-- The FIRST leads_* check block in this file. Hibernate generates leads_lead_source_check from
-- LeadSource when the table is first created; ddl-auto=update never alters it, so the 16 values
-- added by this framework are rejected at the DB level on any database whose `leads` table
-- predates the change — the same bug as users_role_check above, and invisible on a fresh dev DB
-- (which generates the constraint from the current enum and works perfectly).
--
-- Whether leads_lead_source_check exists on a given database is NOT known: Hibernate creates it
-- silently and it is declared nowhere in the repo, so grepping finds nothing — which misleadingly
-- reads as "no constraint". DROP ... IF EXISTS + ADD is correct EITHER WAY: a no-op drop followed
-- by a fresh add on databases that never had it.
--
-- All 9 original values are retained. NEVER remove one: @Enumerated(STRING) maps the stored string
-- back and LeadSource.fromValue throws on unknown, so deleting a constant breaks READING every
-- historical lead that carries it.
--
-- ⚠ spring.sql.init.continue-on-error=true: if any row holds a value outside this list the ADD
-- FAILS and startup CONTINUES, leaving NO constraint at all — weaker than before, logging nothing.
-- A clean boot proves nothing. ProductionConfigValidator asserts at startup that this constraint
-- EXISTS and contains all 25 values; without that assertion the acceptance test passes vacuously
-- (with no constraint, every INSERT succeeds). Survey the data BEFORE deploying:
--   SELECT DISTINCT lead_source FROM leads;
-- and verify after:
--   SELECT conname FROM pg_constraint WHERE conrelid='leads'::regclass AND contype='c';
ALTER TABLE leads DROP CONSTRAINT IF EXISTS leads_lead_source_check;
ALTER TABLE leads ADD CONSTRAINT leads_lead_source_check
        CHECK (lead_source IN (
                -- original 9 — never remove
                'SOCIAL_MEDIA','WEBSITE','GOOGLE_ADS','FACEBOOK','INSTAGRAM','WHATSAPP',
                'REFERRAL','DIRECT_CALL','OTHER',
                -- directory / marketplace
                'JUSTDIAL','INDIAMART','TRADEINDIA','SULEKHA','TRAVEL_MARKETPLACE',
                -- paid social / messaging
                'META_ADS','INSTAGRAM_DM','FB_MESSENGER',
                -- telephony / owned web
                'IVR_CALL','WEBSITE_FORM','WEBLINK_ENQUIRY',
                -- human-entered
                'MANUAL','WALK_IN','PHONE_MANUAL',
                -- system-generated
                'SUB_AGENT','REPEAT_CUSTOMER'));

-- ── LeadStage enum CHECK constraint refresh (leads.lead_stage) ───────────────
-- Pre-existing latent bug, fixed here because this file is now touching leads' checks anyway.
-- REOPENED was added to LeadStage (the booking-cancellation "move back to lead" path,
-- LeadStage.java:14-15) with no check refresh, so on any database whose `leads` table predates it,
-- reopening a cancelled booking's lead is rejected at the DB level. Identical mechanism to
-- lead_source above. Survey first: SELECT DISTINCT lead_stage FROM leads;
ALTER TABLE leads DROP CONSTRAINT IF EXISTS leads_lead_stage_check;
ALTER TABLE leads ADD CONSTRAINT leads_lead_stage_check
        CHECK (lead_stage IN ('NEW_LEAD','CONTACTED','FOLLOW_UP','QUALIFIED',
                'PROPOSAL_SENT','CONVERTED','REOPENED','LOST'));

-- ── leads.email: drop NOT NULL (phone-only inbound leads) ────────────────────
-- An IVR call and most Meta lead-ad forms deliver a phone and no email. ddl-auto=update will NOT
-- relax an existing NOT NULL, so this is by definition an indexes.sql delta. @NotBlank STAYS on
-- CreateLeadRequestDto — bean validation fires at the controller boundary via @Valid, not on the
-- service call, so the human path keeps its requirement for free while the machine path does not.
-- No index change is needed: Postgres partial unique indexes treat NULLs as DISTINCT, so N
-- anonymous inbound leads coexist happily under uq_leads_email_tenant_open above.
-- Idempotent (DROP NOT NULL on an already-nullable column is a no-op).
ALTER TABLE leads ALTER COLUMN email DROP NOT NULL;

-- ── leads.origin backfill + NOT NULL ────────────────────────────────────────
-- origin (MANUAL | INTEGRATION | SYSTEM) is added to an EXISTING table, so ddl-auto=update adds it
-- NULLable and every pre-existing row reads NULL. The entity deliberately declares it nullable
-- (with @Builder.Default = MANUAL on the Java side): with nullable=false Hibernate emits
-- ADD COLUMN ... NOT NULL with no default, Postgres rejects that on a populated table, and the DDL
-- error is SWALLOWED — leaving the column ABSENT and every subsequent lead insert failing.
-- So: add nullable, backfill here, then tighten. DO NOT "correct" the entity to nullable=false.
--
-- 'MANUAL' is CORRECT for every pre-existing row, not a guess: before this framework there was no
-- programmatic lead-create path at all (LeadServiceImpl.createLead requires an authenticated
-- tenant User at three hard points, and DevDataSeeder bypasses the service entirely), so every
-- existing lead was hand-entered by a logged-in human. Both statements are idempotent.
--
-- ⚠ continue-on-error=true again: if the UPDATE misses any row the SET NOT NULL fails SILENTLY and
-- origin stays nullable. Verify with:
--   SELECT count(*) FROM leads WHERE origin IS NULL;   -- must be 0
UPDATE leads SET origin = 'MANUAL' WHERE origin IS NULL;
ALTER TABLE leads ALTER COLUMN origin SET NOT NULL;

ALTER TABLE leads DROP CONSTRAINT IF EXISTS leads_origin_check;
ALTER TABLE leads ADD CONSTRAINT leads_origin_check
        CHECK (origin IN ('MANUAL','INTEGRATION','SYSTEM'));

-- The origin <-> connection-link invariant: only an INTEGRATION-origin lead may carry a connection
-- id, and it must carry one. MANUAL (human) and SYSTEM (sub-agent / repeat enquiry) leads have no
-- connection row to point at. Meta satisfies this because a PROVIDER_ACCOUNT connection is a real
-- lead_source_integrations row (resolution_mode='PROVIDER_ACCOUNT', no token) — see the
-- ls_integration_resolution_check below. Existing rows satisfy this trivially: source_integration_id
-- is a new column and is NULL everywhere. Safe AFTER the backfill above, and only after.
ALTER TABLE leads DROP CONSTRAINT IF EXISTS leads_origin_link_check;
ALTER TABLE leads ADD CONSTRAINT leads_origin_link_check
        CHECK ((origin = 'INTEGRATION' AND source_integration_id IS NOT NULL)
            OR (origin IN ('MANUAL','SYSTEM')  AND source_integration_id IS NULL));

-- ── leads.phone_normalized: WRITE-ONLY first. The index below stays COMMENTED OUT. ───────────
-- The append-on-repeat rule needs to match an inbound E.164 payload against a human-typed lead.
-- Today validateNoDuplicates passes the phone RAW (LeadServiceImpl:598-607) and
-- uq_leads_phone_tenant_open indexes the raw column, while DevDataSeeder writes phones WITH SPACES
-- ("+91 91234 5000"+i) — so "+919876543210" never string-matches an existing row and the append
-- silently becomes a duplicate lead. phone_normalized is the canonical key.
--
-- The BACKFILL IS JAVA-SIDE, deliberately not SQL: it must reuse the exact same canonical() code the
-- write path uses. A SQL reimplementation that disagrees by one edge case IS the migration risk.
-- The raw `phone` column is NEVER rewritten in place — PhoneNormalizer's javadoc says stronger
-- canonicalisation "belongs with a deliberate one-time data migration", Customer.phone /
-- uq_customers_phone_tenant key off the same strings, and a non-atomic rewrite spawns duplicate
-- customers.
--
-- ⚠ THE INDEX BELOW IS COMMENTED OUT ON PURPOSE and is uncommented only in the phase that moves
-- LeadServiceImpl:601 (and checkTrashedForRestore:626-638, in the SAME change) onto the canonical
-- column. Because this file auto-applies on every boot (spring.sql.init.mode=always), shipping it
-- early against colliding rows would FAIL SILENTLY under continue-on-error and leave the append
-- path with no backstop. Run the collision report first and get a clean result:
--   SELECT phone_normalized, tenant_id, count(*) FROM leads
--    WHERE deleted_at IS NULL AND lead_stage NOT IN ('CONVERTED','LOST')
--      AND phone_normalized IS NOT NULL
--    GROUP BY phone_normalized, tenant_id HAVING count(*) > 1;
-- Two pre-existing OPEN leads that canonicalise identically would otherwise become permanently
-- UN-EDITABLE for a human — a bigger blast radius than the un-creatable case.
--
-- CREATE UNIQUE INDEX IF NOT EXISTS uq_leads_phone_norm_tenant_open
--         ON leads (phone_normalized, tenant_id)
--         WHERE deleted_at IS NULL AND phone_normalized IS NOT NULL
--           AND lead_stage NOT IN ('CONVERTED', 'LOST');

-- Non-unique lookup index, safe to ship immediately: the append path probes by canonical phone
-- (findFirstBy...OrderByCreatedAtDesc — a plain Optional finder throws NonUniqueResultException,
-- because terminal stages RELEASE the natural key and a returning customer legitimately produces a
-- CHAIN of leads per phone).
CREATE INDEX IF NOT EXISTS idx_leads_phone_norm
        ON leads (tenant_id, phone_normalized) WHERE phone_normalized IS NOT NULL;

-- Source reporting ("leads per source this month") has no index today — lead_source is not
-- filterable on any endpoint and the only breakdown in the product is computed client-side.
-- Partial over live rows, mirroring idx_leads_tenant_created above.
CREATE INDEX IF NOT EXISTS idx_leads_tenant_source
        ON leads (tenant_id, lead_source) WHERE deleted_at IS NULL;

-- "Which leads came from connection X" — the audit trail behind lead_source_integrations.lead_count.
CREATE INDEX IF NOT EXISTS idx_leads_source_integration
        ON leads (source_integration_id) WHERE source_integration_id IS NOT NULL;

-- ── lead_logs: FK must cascade, or the trash purge is already broken ─────────
-- PRE-EXISTING BUG, fixed here because this framework makes it universal. LeadLog.lead is a REAL FK
-- (fk_lead_log_lead, optional=false, LeadLog.java:32-35); Lead cascades to `itinerary` ONLY
-- (Lead.java:128-131) and declares no LeadLog collection; and LEAD_LOG is absent from TrashableType
-- (TrashableType.java:48-81). TrashServiceImpl.purgeForCurrentTenant:135-157 em.remove()s expired
-- leads inside ONE @Transactional loop over TrashableType.values() — so a 31-day-old trashed lead
-- with any log row throws an FK violation and rolls back EVERY TrashableType purge for that tenant
-- (leads, customers, bookings, master data, fleet), forever, because the same row is retried nightly.
-- It survives today only because lead_logs exist when a HUMAN wrote one. Under the append-on-repeat
-- rule, every repeat inbound contact writes one.
--
-- ON DELETE CASCADE is correct here (unlike lead_attributions, which is a LOGICAL FK): a log is a
-- child that SHOULD die with its lead. ddl-auto=update never alters an existing constraint, so this
-- drop+re-add is the only way to attach the cascade. Ordering is safe and idempotent: Hibernate runs
-- first (defer-datasource-initialization=true) and leaves a same-named constraint alone; this block
-- then re-adds it with the cascade on every boot.
ALTER TABLE lead_logs DROP CONSTRAINT IF EXISTS fk_lead_log_lead;
ALTER TABLE lead_logs ADD CONSTRAINT fk_lead_log_lead
        FOREIGN KEY (lead_id) REFERENCES leads (id) ON DELETE CASCADE;

-- Ingest traceability on the activity log. NOTE: lead_logs.ingest_event_id points at a row that is
-- PURGED at 30 days, so it must NEVER be the sole path to attribution — campaign / ad / recording
-- data lives ONLY on lead_attributions, which is permanent.
CREATE INDEX IF NOT EXISTS idx_lead_log_ingest_event
        ON lead_logs (ingest_event_id) WHERE ingest_event_id IS NOT NULL;

-- ── lead_source_integrations: the token lookup ──────────────────────────────
-- THE hot path and the tenant resolver: an inbound webhook presents an opaque token in the URL and
-- these indexes are the ONLY thing that turns it into a tenant. UNIQUE because a collision would
-- route one tenant's leads into another's pipeline. Whole-token SHA-256 hex is DETERMINISTIC, so
-- this is an O(1) unique btree probe — no scan, and no need for a selector/verifier split. (AES is
-- impossible here: AesSecretCipher uses a random IV per encrypt, so ciphertext is non-deterministic
-- and unlookupable. BCrypt is impossible: per-row salt is unindexable, and ~100ms on an
-- unauthenticated endpoint is a CPU-exhaustion amplifier.)
--
-- PARTIAL on IS NOT NULL, because a PROVIDER_ACCOUNT connection (Meta — one app-level callback for
-- all pages, so it cannot carry a per-tenant token) has NO token at all. Postgres already treats
-- multiple NULLs as distinct, so the predicate is not what makes those rows legal; it states the
-- intent and keeps the index to only the rows that can ever be probed.
--
-- Deliberately NOT partial on deleted_at, breaking this file's dominant idiom (see the vendors /
-- customers / leads blocks above, where the whole point of a partial unique is that a value can be
-- REUSED after soft-delete). Reuse is exactly what must not happen here: re-issuing a retired token
-- would silently redirect a provider that still has the old URL pasted into its console — leads
-- landing in the wrong connection, or after a hard delete + re-add, the wrong TENANT. A retired
-- token stays reserved forever.
CREATE UNIQUE INDEX IF NOT EXISTS uq_ls_integration_token
        ON lead_source_integrations (ingest_token_hash)
        WHERE ingest_token_hash IS NOT NULL;

-- Overlapping rotation: Regenerate moves current -> previous and sets token_previous_revoke_at =
-- now + 72h, so the tenant has a window to go paste the new URL into the provider's console without
-- dropping every lead in that human gap. The previous hash needs its own unique index for the same
-- reason as the current one — it is probed by the same resolver (a second derived finder).
CREATE UNIQUE INDEX IF NOT EXISTS uq_ls_integration_token_prev
        ON lead_source_integrations (ingest_token_hash_previous)
        WHERE ingest_token_hash_previous IS NOT NULL;

-- A provider account (an FB page id, an IVR DID, a website site key) belongs to exactly ONE tenant,
-- PLATFORM-WIDE — not one per tenant. This is the RESOLVER for resolution_mode='PROVIDER_ACCOUNT',
-- not a hygiene index: without it, tenant B could register tenant A's page id and have A's leads
-- attributed to B when the shared-app webhook routes by account id. Partial: TOKEN-mode rows have no
-- account id. NOT partial on deleted_at, for the same "must not be reusable" reason as the token.
CREATE UNIQUE INDEX IF NOT EXISTS uq_ls_integration_account
        ON lead_source_integrations (channel, external_account_id)
        WHERE external_account_id IS NOT NULL;

-- The tenant's Integrations page: list this tenant's live connections, grouped by channel. There is
-- deliberately NO unique constraint on (tenant_id, channel) — the row is a CONNECTION and there are
-- MANY per (tenant, channel). Uniqueness is on the TOKEN.
CREATE INDEX IF NOT EXISTS idx_ls_integration_tenant
        ON lead_source_integrations (tenant_id, channel) WHERE deleted_at IS NULL;

-- resolution_mode is a closed vocabulary the FRAMEWORK owns (unlike `channel`), so it gets a check.
ALTER TABLE lead_source_integrations DROP CONSTRAINT IF EXISTS ls_integration_resolution_check;
ALTER TABLE lead_source_integrations ADD CONSTRAINT ls_integration_resolution_check
        CHECK ((resolution_mode = 'TOKEN'            AND ingest_token_hash  IS NOT NULL)
            OR (resolution_mode = 'PROVIDER_ACCOUNT' AND external_account_id IS NOT NULL));

ALTER TABLE lead_source_integrations DROP CONSTRAINT IF EXISTS ls_integration_status_check;
ALTER TABLE lead_source_integrations ADD CONSTRAINT ls_integration_status_check
        CHECK (status IN ('CONNECTED','DEGRADED','DISABLED'));

-- NOTE: there is deliberately NO lead_source_integrations_channel_check, and this is the one place
-- in this framework that departs from the ~20 enum-check blocks above. `channel` is a plain VARCHAR
-- holding LeadSourceChannel.slug(), NOT an @Enumerated column: the framework's goal is that a new
-- channel is ONE adapter class, and a check block here would make it one class + one edit to this
-- file. The adapter REGISTRY is the constraint, and a STRICTER one than a CHECK could be — a CHECK
-- validates SPELLING, so it would happily accept a 'meta_ads' row on a node with no Meta adapter
-- deployed, whereas the registry rejects an unknown slug at save time AND again at ingest.
-- (Compare WaMessageLog.status, the same free-String sidestep, also made deliberately.)

-- ── lead_attributions ───────────────────────────────────────────────────────
-- One attribution row per lead (FIRST-TOUCH: under the append-on-repeat rule a second enquiry is a
-- follow-up on an existing acquisition, not a new attributable one — its payload is retained in
-- lead_ingest_events).
--
-- lead_id is a LOGICAL FK with NO DB CONSTRAINT, and that is load-bearing, not laziness. A real FK
-- here would make TrashServiceImpl.purgeForCurrentTenant:135-157 — one @Transactional loop over
-- TrashableType.values() calling em.remove() — throw on the first 31-day-old trashed inbound lead,
-- rolling back EVERY TrashableType purge for that tenant forever. lead_attributions is not
-- user-trashable, so it does NOT belong in TrashableType; and ON DELETE CASCADE is not relied on.
-- Consistent with the cross-aggregate rule (Booking.customerId) and with LeadIngestEvent.leadId.
-- The unique index below still enforces 1:1, so the logical FK costs nothing.
--
-- If multi-touch is ever wanted, add touch_seq and make this (lead_id, touch_seq) — purely additive.
CREATE UNIQUE INDEX IF NOT EXISTS uq_lead_attribution_lead
        ON lead_attributions (lead_id);

-- Campaign-grain reporting ("leads per ad campaign") — the reason this table exists at campaign
-- grain rather than channel grain. campaign_name is a String SNAPSHOT of what the provider sent and
-- is deliberately NOT a reference to marketing_campaigns, which is an unrelated OUTBOUND-broadcast
-- concept (Campaign.java:14-17) whose sentCount/totalRecipients junk rows would corrupt and whose
-- CampaignDispatchScheduler they would feed.
CREATE INDEX IF NOT EXISTS idx_lead_attribution_campaign
        ON lead_attributions (tenant_id, campaign_name) WHERE campaign_name IS NOT NULL;

-- ── lead_ingest_events ──────────────────────────────────────────────────────
-- lead_ingest_events is a NEW table, so Hibernate creates its *_check constraint with the current
-- enum values at first create and inserts work immediately. This drop+recreate is
-- belt-and-suspenders for any FUTURE LeadIngestStatus value (ddl-auto=update never alters an
-- existing constraint — the recurring gotcha across this file). Update the list here whenever a
-- constant is added, or inserts with it fail at the DB.
-- APPENDED is distinct from PROCESSED on purpose: collapsing them makes the append rate — the
-- headline metric for the append-on-repeat rule — unmeasurable. QUARANTINED_QUOTA exists so a
-- plan-cap rejection is VISIBLE rather than lost (duplicate-blocked enquiries vanish today).
ALTER TABLE lead_ingest_events DROP CONSTRAINT IF EXISTS lead_ingest_events_status_check;
ALTER TABLE lead_ingest_events ADD CONSTRAINT lead_ingest_events_status_check
        CHECK (status IN ('RECEIVED','PROCESSED','APPENDED','DUPLICATE','IGNORED',
                'DEFERRED','QUARANTINED_QUOTA','FAILED'));

-- Content idempotency. Deliberately per (tenant, channel) and NOT the platform-wide UNIQUE that
-- payment_webhook_events.event_id uses. The reason is first-principles, not folklore about any
-- specific vendor: an external event id is PROVIDER-CONTROLLED and namespaced by an account we do
-- not administer, so a platform-wide unique lets any one connection permanently poison a key for
-- every other tenant — silent cross-tenant lead LOSS. Partial because not every provider sends an
-- event id; when absent the column stays NULL and content dedup does not apply.
CREATE UNIQUE INDEX IF NOT EXISTS uq_lead_ingest_event_external
        ON lead_ingest_events (tenant_id, channel, external_event_id)
        WHERE external_event_id IS NOT NULL;

-- Transport-window dedup — what IdempotencyKey.none() produces: sha256(body) bucketed into a 60s
-- window, i.e. (integration_id, body hash, floor(createdAt/60s)) folded into dedup_key. none() means
-- "no CONTENT dedup", NOT "no dedup": this absorbs a provider retry storm (seconds apart, same
-- window) while letting two genuinely distinct identical calls minutes apart BOTH land.
-- There is deliberately NO unbounded "sha256:"+hash(body) fallback of the kind SaasPaymentServiceImpl
-- uses: it wrongly collapses a byte-identical redelivery of a genuinely NEW event. Under the
-- append-on-repeat rule an over-delivery costs a duplicate ACTIVITY; a false dedup costs a LOST
-- ENQUIRY. Choose noise over loss.
CREATE UNIQUE INDEX IF NOT EXISTS uq_lead_ingest_dedup
        ON lead_ingest_events (tenant_id, channel, dedup_key)
        WHERE dedup_key IS NOT NULL;

-- Retry / deferred-fetch sweep. The scheduler scans for due work ACROSS tenants first and then loops
-- per tenant (the DocumentExpiryReminderScheduler:44-59 shape — setTenantId OUTSIDE the transaction,
-- cross-bean delegate, clear() in finally), so this index leads with next_retry_at rather than
-- tenant_id. Tiny: only rows actually awaiting work are in it. FAILED = retry the parse/create;
-- DEFERRED = a FetchHandle is queued for the two-step fetch. Values are enum NAMEs
-- (@Enumerated(STRING)), same convention as the uq_leads_*_open predicates above.
CREATE INDEX IF NOT EXISTS idx_lead_ingest_retry
        ON lead_ingest_events (next_retry_at)
        WHERE next_retry_at IS NOT NULL AND status IN ('FAILED','DEFERRED');

-- Per-connection delivery history (the Integrations page's "recent deliveries" panel). Sorts on
-- created_at from BaseEntity — there is deliberately no received_at column: two columns meaning
-- "when did this arrive" drift the first time a retry path sets one and not the other.
CREATE INDEX IF NOT EXISTS idx_lead_ingest_integration
        ON lead_ingest_events (tenant_id, integration_id, created_at DESC);
```

---

### 11. Contract summary — what other subsystems bind to

| Thing | Value |
|---|---|
| Connection entity | `com.crm.travelcrm.leadsource.entity.LeadSourceIntegration` → `lead_source_integrations`. **MANY rows per (tenant, channel).** |
| **Token columns** | `ingest_token_hash` / `ingest_token_hash_previous`, `VARCHAR(64) NULLABLE` — SHA-256 **hex** of the full raw token. Unique via `uq_ls_integration_token` / `uq_ls_integration_token_prev`. |
| Token format | `lsk_` + Base64URL-unpadded(32 bytes `SecureRandom`). Reveal-once. |
| Token display column | `ingest_token_prefix` `VARCHAR(24)` — plaintext, masking/log correlation only. **Never** a lookup key. |
| **Lookup** | `findByIngestTokenHashAndDeletedAtIsNull` + `findByIngestTokenHashPreviousAndDeletedAtIsNull` — **tenant-less by design**, derived finders (legal under `TenantIsolationArchTest:60-61`). Neither filters `enabled`. |
| **Resolver** | **NOT `@Transactional`; MUST be called outside any transaction.** |
| Channel vocabulary | `LeadSourceChannel.slug()` in the JVM; plain `VARCHAR` in the DB. **No `@Enumerated`, no CHECK, no `indexes.sql` block.** |
| Raw log | `LeadIngestEvent` → `lead_ingest_events`. **`BaseTenantEntity`** — tenant comes from the token, never the body. No `received_at`. |
| Attribution | `LeadAttribution` → `lead_attributions`. First-touch, 1:1, **LOGICAL** `lead_id`, no DB FK. `Lead` has **no** inverse mapping. |
| Lead columns | `origin` (NOT NULL after backfill, `@Builder.Default = MANUAL`), `source_integration_id` (Long, logical), `phone_normalized`. `email` NOT NULL **dropped**. |
| LeadLog columns | `ingest_event_id`, `source_integration_id`, `activity_kind` — all nullable logical. |
| Source enum | `LeadSource` — 25 constants, `SourceSelectability` 10/12/3. **Wire vocabulary = `displayName`, both directions.** |
| Origin enum | `LeadOrigin { MANUAL, INTEGRATION, SYSTEM }` in `lead.enums`. `ActorOrigin` does not exist. |

**Four hard rules for everyone downstream:**
1. **Resolve the token → clear → set `TenantContext` → THEN enter a transaction.** Never inside — `TenantFilterAspect`'s `@Before` (`:22`) has already latched or skipped the filter by then, and it fails **OPEN** (`:34-37`).
2. **Never derive `tenantId` from the body**, and never trust `{channel}` in the path — validate it against the resolved row (mismatch → 404).
3. **Never log the full token.** `ingest_token_prefix` only.
4. **Never remove a `LeadSource` constant.** It breaks *reading* every historical lead that carries it.

### 12. Accepted risks and open items

- **ACCEPTED RISK — the credential bag is not DB-validated.** A typo'd key surfaces at ingest, not at save. Mitigated only by `ChannelCatalogEntry.requiredCredentialKeys` checked at save time — a convention, not a constraint.
- **ACCEPTED RISK — attribution is first-touch only.** A second enquiry's campaign data lives in `lead_ingest_events` (30-day retention) and is never promoted. Forward path is additive (`touch_seq`).
- **ACCEPTED RISK — `continue-on-error=true` swallows every failure in `indexes.sql`.** The `ProductionConfigValidator` boot assertion on `leads_lead_source_check` is the ONLY signal; without it, Phase 0's acceptance test passes vacuously.
- **ACCEPTED RISK — `uq_leads_phone_norm_tenant_open` ships commented out.** Until the phase that uncomments it, there is no DB backstop on the canonical phone; the append path relies on the service check alone, and the `existsBy*` checks are **not race-safe** (scan `:111`).
- **OPEN RISK — `spring.jpa.open-in-view` was not re-verified by me.** The canon states it is `false`, which is what makes the `IntegrationCredentials` view on `RawInbound` necessarily a detached copy rather than a managed entity. Confirm before Phase 1.
- **RESEARCH TASK, not fact** — per-provider mechanics: whether JustDial/IndiaMART/Sulekha/TradeIndia send a stable event id at all; whether IVR vendors (Exotel / MyOperator / Knowlarity) namespace ids per account; whether Meta's `entry[].changes[]` batching bounds are what we assume; recording-URL TTLs (which is why `recording_url_expires_at` exists). None of this is knowable from this codebase, and none of the schema decisions above depend on it.

---

## Ingestion Pipeline + The Tenant Recipe

The security-critical core. One fact governs everything below: **on a webhook thread both tenant-isolation layers are off simultaneously.** `TenantFilterAspect` fails open — verified verbatim at `TenantFilterAspect.java:34-37`, `if (tenantId == null) return;` — and `TenantEntityListener`'s cross-tenant guard is short-circuited by a null context (`TenantEntityListener.java:24,36`). **Nothing in this design may rely on either as a backstop.**

---

### 1. The URLs — three, verbatim

| Mode | Method + path | Channels |
|---|---|---|
| **TOKEN** (default) | `POST /api/webhooks/leads/{channel}/{token}`<br>`GET /api/webhooks/leads/{channel}/{token}` | JUSTDIAL, INDIAMART, TRADEINDIA, SULEKHA, IVR_CALL, GOOGLE_ADS, TRAVEL_MARKETPLACE |
| **PROVIDER_ACCOUNT** | `POST /api/webhooks/leads/{channel}`<br>`GET /api/webhooks/leads/{channel}` | META_ADS (`/api/webhooks/leads/meta_ads`); later INSTAGRAM_DM, FB_MESSENGER |
| **Browser form** (Phase 3) | `POST /api/ingest/forms/{siteKey}`<br>`GET /api/ingest/v1/lead-form.js` | WEBSITE_FORM only |

Same controller, same gateway, second mapping for PROVIDER_ACCOUNT. `publicId` appears in none of these.

**Both methods ship in Phase 1.** `SecurityConfig.java:93` is verified `.requestMatchers(HttpMethod.POST, "/api/webhooks/**")` — POST-qualified. A GET therefore falls to `.anyRequest().authenticated()` at `:108` and 401s, which would break Meta's `hub.challenge` handshake. The fix is a **narrow addition**:

```java
.requestMatchers(HttpMethod.GET, "/api/webhooks/leads/**").permitAll()
```

**Do not drop the POST qualifier at `:93`** — that would open Razorpay's webhook to GET. Add the line; leave `:93` alone.

The Phase-3 embed asset **must** live under `/api/ingest/**` or `IngestSecurityConfig`'s `securityMatcher("/api/ingest/**")` does not cover it and it 401s.

`{siteKey}` is `external_account_id`, is **public by construction**, and is **never** the ingest token.

**`{channel}` is a validated cross-check, not decoration.** The token already identifies the row, which knows its channel. Keep the segment anyway: it rejects an unknown channel with zero DB work, it stops a token leaked from a JustDial connection being replayed against another adapter's parser, and it gives access logs a channel dimension without a lookup. `row.getChannel()` is a **String** (canon: plain VARCHAR, no `@Enumerated`, no check constraint); compare the path segment to it as a String. **Mismatch → 401, never 400** — a 400 confirms the token exists for a different channel. Registry lookup is `LeadSourceChannel.fromSlug(pathSegment)`.

**Path-token leak surface** (accepted, mitigated): access logs, `Referer`, proxy logs, support screenshots. Mitigations: the `lsk_` prefix is secret-scanner-greppable, rotation is first-class, `token_last_used_at` supports leak detection. **Ops task the code cannot enforce:** redact the final path segment of `/api/webhooks/leads/` in access logs.

**Research task — not fact.** Whether JustDial / IndiaMART / TradeIndia / Sulekha / IVR vendors permit pasting a URL with a path segment, whether any support a custom header instead, and what each retries on non-2xx. The scan does not settle it and I will not invent it. Design for the worst case (path); the resolver also accepts `X-Ingest-Token` from day one so a header-capable provider is a config flip, not a redesign. **Provider retry behaviour is UNVERIFIED and the §5 and §6 rationales depend on it** — confirm before Phase 2.

---

### 2. Token model — whole-token SHA-256

**The split selector+verifier token is dead. It was argued from a false premise.** The claim "a hashed token cannot be looked up by index" is true of BCrypt (per-row salt) and of AES-GCM (random 12-byte IV, `AesSecretCipher`) — **not** of SHA-256, which is deterministic. `WHERE ingest_token_hash = sha256(presented)` is an O(1) unique btree probe. The original design contradicted itself: it correctly named determinism as the indexability criterion when rejecting AES, then asserted the opposite about a deterministic hash. The split bought nothing and cost a permanent plaintext partial-credential at rest.

```
FORMAT   lsk_ + Base64URL-unpadded(32 bytes SecureRandom)
STORAGE  SHA-256 hex, 64 chars → ingest_token_hash
```

The `lsk_` prefix is kept **solely** for secret scanning, and lets a malformed token be rejected before any DB access.

**Unsalted is safe here and only here** — the input is 256 bits of `SecureRandom`. There is nothing to brute-force.

**BCrypt is rejected**, deliberately diverging from the OTP module: a per-row salt is unindexable, and ~100ms on an endpoint any unauthenticated caller can flood with garbage is a CPU-exhaustion amplifier masquerading as rigour. The OTP module needs BCrypt because a 6-digit code is ~20 bits. This is not that.

**Overlapping rotation — two nullable columns on one row, not a child table.** The tenant must go paste the new URL into JustDial's console; instant cutover drops every lead in that human gap.

| Column | Role |
|---|---|
| `ingest_token_hash` | current, nullable, partial unique `WHERE ... IS NOT NULL` |
| `ingest_token_hash_previous` | prior, nullable, own partial unique |
| `token_previous_revoke_at` | Regenerate sets `now + 72h` |
| `ingest_token_prefix` VARCHAR(24) | masked display only — **never a lookup key** |
| `token_rotated_at`, `token_last_used_at` | leak detection + FE display |

Regenerate moves current → previous and stamps `token_previous_revoke_at`.

**A child `lead_source_ingest_tokens` table is rejected on a build-breaking ground, not taste:** it has no `tenantId`, so resolving integration → credentials before the tenant is known forces `leadSourceIntegrationRepository.findById(...)`, which the **existing** `TenantIsolationArchTest` fails the build on (`:60-61` bans `findById`/`findAllById`/`getById`/`getOne`/`getReferenceById`), and whose javadoc at `:52-55` explicitly forbids the `EXEMPT_CLASSES` escape. Two columns on one row cannot produce a `findById`.

**Lookup — two derived finders, called from a NON-`@Transactional` resolver:**

```java
Optional<LeadSourceIntegration> findByIngestTokenHashAndDeletedAtIsNull(String hash);
Optional<LeadSourceIntegration> findByIngestTokenHashPreviousAndDeletedAtIsNull(String hash);
```

Derived finders are permitted by the ArchUnit rule; only key-loaders are banned (`:60-61`).

**Non-transactional is a contract, not a style.** The pointcut at `TenantFilterAspect.java:22` is `@Before` on `@annotation(Transactional)`, so a non-transactional resolver never fires the aspect and the probe is legitimately global. If the resolver *were* transactional and a caller sent `Authorization: Bearer <their own JWT>` to this permitAll endpoint, `JwtAuthFilter.java:89-90` would authenticate it and set `TenantContext`, the tenantFilter would scope the lookup to the **caller's** tenant, and a valid token for tenant B would resolve to **empty** — intermittent, unreproducible 404s.

**Neither finder filters `enabled`. Resolve first, authorize second** — a delivery to a disabled connection is then logged against the *right* tenant instead of vanishing into a 401.

**Uniqueness is on the token, never on (tenant, channel)** — many connections per (tenant, channel) is the point.

**Indexes are NOT partial on `deleted_at`.** A retired token stays reserved forever: reissuing it would silently redirect a provider that still has the old URL pasted in its console.

**Reveal-once.** The raw token exists exactly once, in the mint/rotate response body — never re-derivable. This copies the write-only-secret contract at `WhatsAppConfigService.java:55-58`. Every subsequent page load shows `ingest_token_prefix` + `token_last_used_at` + Regenerate.

**Full-token logging is BANNED everywhere. Log `ingest_token_prefix` only.**

---

### 3. `TenantScope` — the helper that does not exist yet

```java
public final class TenantScope {
    public static <T> T call(Long tenantId, Supplier<T> work);
    public static void   run (Long tenantId, Runnable work);
}
```

Three invariants, each closing a named landmine:

1. **Null-rejecting.** `tenantId == null` → `IllegalArgumentException`. Closes the fail-open at source (`TenantFilterAspect.java:34-37`).
2. **Save/restore, not set/clear.** Capture `previous = TenantContext.getTenantId()`, restore in `finally` (or `clear()` if it was null). `TenantContext` is a bare `ThreadLocal<Long>` with no stack (`TenantContext.java:5`) — nesting destroys the outer value. No change to `TenantContext` itself.
3. **Transaction guard — the point of the whole helper.**
   ```java
   if (TransactionSynchronizationManager.isActualTransactionActive())
       throw new IllegalStateException("TenantScope must be entered BEFORE a transaction begins");
   ```
   The aspect is `@Before` on `@Transactional` (`:22`), so the filter is latched **before the method body runs**. `setTenantId()` inside a transactional method is **too late** and the filter stays off for the whole transaction. Invisible in single-tenant dev; a cross-tenant leak in production. The guard converts it into a loud exception at the offending call site.

**Scope call:** the ~9 existing hand-rolled sites are **NOT migrated** — out of scope, nonzero regression risk, zero user-visible benefit.

---

### 4. THE RECIPE

```
 0. CLEAR      TenantContext.clear(); PlatformContext.clear(); SecurityContextHolder.clearContext();
               ── gateway entry, non-transactional, before anything else
 1. SIZE       IngestBodySizeGuard rejected oversized bodies already (global filter, §7)
 2. FORMAT     TOKEN mode: reject a malformed lsk_ token          ── zero DB
 3. THROTTLE   filter-level, keyed on IP (never the path)         ── zero DB
 4. RESOLVE    TOKEN:            findByIngestTokenHash…(sha256(presented))
                                 → miss? findByIngestTokenHashPrevious…  (revoke_at guard)
               PROVIDER_ACCOUNT: verify platform HMAC over RAW BYTES first,
                                 then adapter.accountKey(in) [PURE],
                                 then lookup channel + external_account_id
               ── NON-transactional. tenantId is now known.
 5. VERIFY     path {channel} == row.getChannel()      → mismatch 401
               row-declared verification()             → fail 401
               row.enabled / status                    → disabled: log, then 401
 6. THROTTLE-2 per-integration limit, in the SERVICE (post-resolution)
 7. LOG RAW    TenantScope.run(row.getTenantId(), () -> deliveryLogger.logRaw(...))
               ── REQUIRES_NEW, cross-bean, COMMITTED before step 8 opens
 8. SCOPE      outcome = TenantScope.call(row.getTenantId(), () -> ingestService.ingest(...))
               ── cross-bean; ingestService is METHOD-level @Transactional
               8a. plan entitlement re-check            → 202 quarantine
               8b. adapter.parse() → Complete|Deferred|Ignored|Echo
               8c. delivery dedup  (SAME tx as the create)
               8d. create / append ── every builder sets .tenantId(...) EXPLICITLY
 9. RACE       DataIntegrityViolationException surfaces HERE, at the gateway.
               Re-enter ingest() ONCE (fresh tx) → finds the winner → appends.
               Second failure → quarantine (REQUIRES_NEW from the gateway).
10. PUBLISH    gateway publishes outcome.notifyEvent() AFTER commit. Nothing inside the tx.
11. FINALLY    TenantScope restores; ContextCleanupFilter is the backstop
```

#### Why step 0 clears three contexts

An attacker sends `Authorization: Bearer <their own valid JWT>` to this permitAll endpoint. `JwtAuthFilter.java:89-90` authenticates it and sets `TenantContext` **from their token**; `:92` may set `PlatformContext`. Our handler would inherit a caller-chosen tenant it never asked for.

`SecurityContextHolder.clearContext()` stays, but **on honest grounds**:

- `AuditorAware` reads `SecurityContextHolder` statically and returns `auth.getName()` (`AuditingConfig.java:19-25`) — `@CreatedBy` would attribute an attacker's email to another tenant's row.
- The path may touch `Customer` during FK resolution, and **`Customer` IS `Ownable`**.

**Deleted as false:** the claim that `OwnershipEntityListener` would make the attacker the row-level **owner of another tenant's inbound lead**. `Ownable`'s own javadoc says so verbatim at `Ownable.java:14-15`: *"`Lead` is NOT `Ownable`: its owner dimension is the existing `assignedUser` FK."* The listener no-ops for any non-`Ownable` entity. The decision survives; that justification does not.

A stale inherited context is the third reason: it would enable the tenant filter and silently 401 a valid token intermittently — the exact symptom `ContextCleanupFilter`'s javadoc records for share links (`ContextCleanupFilter.java:20-38`).

#### Why step 4 may run unscoped

Chicken-and-egg: the tenant is what we are resolving. Safe because it runs outside a transaction, on a context we explicitly cleared at step 0, and the token is 256 bits of `SecureRandom` returning exactly one row. **`LeadIngestTokenResolver` is the only class in the module permitted to run without a `TenantContext`, and its javadoc must say so.**

#### Why step 7 is wrapped in its own `TenantScope`

The original recipe placed LOG RAW outside the scope while the contract text said "always written inside `TenantScope`" — the flagship subsystem containing, in its own recipe, the exact fail-open pattern `TenantScope` was invented to prevent. **Wrapped.** The transaction guard does not fire (the gateway holds no transaction), `REQUIRES_NEW` still commits **sequentially** before step 8 opens, and the logger's transaction gets the tenant filter armed. The INSERT is safe either way thanks to the explicit stamp, but any future read added to the logger (an integration lookup, a per-tenant cap check) would otherwise silently span all tenants.

**Sequential, never nested.** Two pooled connections held at once against a HikariCP default of 10 is its own hazard. Inlining `LeadIngestDeliveryLogger` into `LeadIngestService` reintroduces the pressure *and*, via self-invocation, **defeats `REQUIRES_NEW` entirely** — a gotcha this codebase has already been bitten by once (`PlatformAuditRecorder`).

#### Why step 8d stamps `tenantId` explicitly

`TenantEntityListener` has an asymmetry (`:15-30`): it throws when *both* context and entity tenantId are null (`:16-21`), but **silently accepts an explicit tenantId with a null context** — the fourth combination matches no branch and persists with zero validation. Explicit stamping degrades a mid-method context loss to *"row saved correctly"* rather than *"IllegalStateException swallowed into an ERROR log."* This is why the Razorpay notify path works (`SaasPaymentServiceImpl:371-388`).

**Downgraded (accepted risk).** Earlier drafts claimed the ordering "converts `TenantEntityListener` into a live cross-tenant assertion." On this path that write-guard is **decorative**: both operands derive from the same resolved integration row, so the `SecurityException` at `:27-29` can only fire if a third source of tenantId exists, and none does. The **read filter re-arming** is the actual protection; the explicit stamp is belt-and-braces against the silently-accepting fourth branch. What actually catches a wrong-tenant write is the two-tenant integration test (§9) — which makes that test **load-bearing, not confirmatory**.

#### Why publishing happens at step 10, outside the transaction

**Publish nothing from inside the transaction.** `ingest()` **returns** a `LeadIngestOutcome` carrying recipients and the pre-rendered title/message; the non-transactional gateway publishes after commit. This also fixes for free the "SSE push fires before commit" problem — `InAppNotificationChannel` is `@Transactional(REQUIRED)` and joins the caller's tx, so today the browser is told about a lead before it exists (scan:131).

The reason for the rule, **stated correctly** — the folklore version is wrong and being wrong here is not harmless. Clearing `TenantContext` does **not** disable an already-enabled Hibernate filter: `TenantFilterAspect.java:38-40` binds the value at `enableFilter(...).setParameter("tenantId", tenantId)` and never re-reads the ThreadLocal. A contributor who tests the folklore version (publish mid-method, query after, observe correct scoping) concludes the rule is superstition and drops it. The **true** mechanism, after `NotifyEventListener` calls `TenantContext.clear()` in `finally` on the publisher's own thread (`NotifyEventListener.java:38`, no save/restore):

- any `BaseTenantEntity` persisted afterwards without an explicit `.tenantId()` throws in `TenantEntityListener` and the listener **swallows it into an ERROR log** (`:32-35`) — the notification or the LeadLog vanishes silently;
- any subsequent cross-bean `@Transactional(REQUIRES_NEW)` call re-enters the aspect with a null context and **genuinely does span all tenants** — exactly what `recordAssignmentAudit` does today at `LeadServiceImpl.java:109`.

**Event types.** Reuse `LEAD_CREATED` for machine-created leads — a lead created by JustDial is a lead created, same triage need, and forking the type forces the FE to learn two types for one concept. Add `LEAD_ACTIVITY_APPENDED` for the append path, addressed to the lead's **OWNER ONLY**, not admins+managers: an append already has an owner, and fanning every repeat call to every admin is what gets the bell muted.

**`IN_APP` only.** `IN_APP + SSE` double-pushes (scan:133); `EMAIL` sleeps ~6s synchronously on the publisher's thread (scan:134). **Set `recipientUserIds` explicitly** — the implicit fallback resolves `TENANT_ADMIN` only and silently drops every MANAGER (scan:135).

**Fix the rendering while here.** `LeadServiceImpl.java:664` string-concatenates the enum → `DIRECT_CALL`, not `Direct Call` (`@JsonValue` does not affect concatenation), and `departCity` is nullable → `"DIRECT_CALL lead from null"`. **Webhook leads never have a departCity**, so this hits 100% of inbound and goes from cosmetic to default.

#### Method-level `@Transactional` only

The pointcut at `:22` is `@annotation` — it matches **method-level annotations only**. A class-level `@Transactional` service would silently never enable the tenant filter: the fail-open landmine through a second door. Verified zero class-level `@Transactional` today, so this codifies an existing convention — but here it is load-bearing, not stylistic. ArchUnit-enforced (§9).

---

### 5. Idempotency

**Delivery dedup ≠ lead dedup.** This layer answers *"have I seen this HTTP delivery?"*. Whether the result is a new lead or an appended activity (owner decision 1) is the lead layer's problem.

**Key grain:**
- partial unique on `(tenant_id, channel, external_event_id) WHERE external_event_id IS NOT NULL`
- plus the transport-window key in `dedup_key`

**Not the platform-wide `uq_webhook_event_id` shape.** An external event id is provider-controlled and namespaced by an account we do not administer, so a global unique lets one connection permanently poison a key for every other tenant — **silent cross-tenant lead loss**.

**`IdempotencyKey.none()` means "no CONTENT dedup", never "no dedup".** It gets a short-window **transport** dedup: `(integration_id, sha256(body), floor(createdAt / 60s))`. This absorbs a retry storm (seconds apart, same window) while letting two genuinely distinct identical calls minutes apart both land. Without it, the sync rationale collapses for exactly the channel `none()` is prescribed for: an IVR provider posts a missed call, our fan-out exceeds its timeout, it retries 3× — one call becomes 4 activities plus 4 raw rows.

**The interface default is `none()` (transport-window), NOT `bodyHash`.** An unbounded body hash wrongly dedupes a byte-identical redelivery of a genuinely **new** event (scan:60) — which here costs a lost enquiry, while over-delivery costs only a duplicate activity under owner decision 1. **Choose noise over loss.**

| Preference | When |
|---|---|
| `of(providerEventId)` | provider supplies one — always correct |
| `of(providerTimestamp + hash)` | no event id, but a timestamp exists |
| `none()` | identical bodies are legitimately distinct events (IVR) — transport window applies |

**Transaction boundaries are deliberately opposite:**

| Row | Transaction | Why |
|---|---|---|
| Raw payload (`lead_ingest_events`) | `REQUIRES_NEW`, committed **before** processing opens | must **survive** a rollback — a lost lead is unrecoverable |
| Delivery dedup | **same** tx as the lead create | must **be released by** a rollback, or a transient failure permanently poisons the key and the provider's retry is swallowed as a duplicate |

**This inverts the Razorpay precedent on purpose.** Razorpay persists its ledger row **last** so a failure rolls back and the provider re-delivers (scan:60 — its own javadoc claims the opposite and is **stale**; the code is the truth). That is right for reliable providers and idempotent money. Here a lost lead is unrecoverable revenue and provider retry behaviour is UNVERIFIED. Different requirement → different ordering.

**Not reusing `WebhookEvent`:** it extends `BaseEntity` (no tenant), hardcodes `provider = "RAZORPAY"`, stores no raw body, lives in `platform/payment`, and its `error_message`/`processed=false` columns are **never written** (scan:214).

**Retry of our own failures: no scheduler in Phase 1.** Transient (DB down) → 5xx → the provider retries, free. Permanent → recorded `FAILED` → manual replay via an authenticated endpoint. Retry infrastructure is greenfield (scan:217). `attempt_count` / `next_retry_at` exist on `lead_ingest_events` because **Meta's `Deferred` fetch forces a drain scheduler into existence in Phase 3** — not because Phase 1 uses them. When that scheduler is built it must copy `UsageAlertScheduler.java:36-46` verbatim: externalized cron, `setTenantId` **outside** the transaction, **cross-bean** delegate, `clear()` in `finally` every iteration, one tenant's failure never blocking others. `@EnableScheduling` lives in `reminder/config/ReminderSchedulingConfig` — do not add a second.

---

### 6. Response contract — canon law, published before any adapter ships

**Synchronous. Decided.** Async means permanent lead loss: `TenantContext` does not propagate across threads (bare ThreadLocal, no `TaskDecorator` — precisely why `WeblinkAnalyticsService` re-resolves and passes `tenantId` explicitly, scan:164), and with no durable queue an in-memory executor drops leads on JVM restart with zero trace. We 200'd, so the provider never retries. Sync's worst case is a provider timeout → provider retry → our dedup absorbs it.

| Status | When | Why |
|---|---|---|
| **200** | processed, appended, duplicate, ignored, unparseable-but-recorded | a 200 on unparseable stops a provider retrying forever a payload we can never parse; we recorded it, replay is manual |
| **202** + outcome in body | quarantined or deferred (quota, plan entitlement, Meta `Deferred` fetch) | without it an over-quota tenant falls through to 200 (lead silently dropped, nobody notified) or 5xx (provider retries forever against a cap that will not move) |
| **401** | bad / unknown / revoked token, unknown account key, channel mismatch, verification failure | **the provider SHOULD stop.** The token is 256 bits — enumeration is not the threat, and a provider retrying a dead URL forever is a real operational cost. This overrules the earlier 404 "never confirm the token exists" position. |
| **413** | body over cap | written by the filter itself (§7) |
| **429** + `Retry-After` | the single hard rate ceiling | provider retries — correct |
| **5xx** | genuine transient fault only | provider retries — correct |

**Tier 1 of the two-tier throttle is DELETED for Phase 1.** "Accept 200, store raw, defer creation to a drain scheduler" has no drain scheduler anywhere, so deferred payloads become permanent silent lead loss. **A 200-ACK into a table nobody drains is strictly worse than a 429, because the provider never retries.** One hard limit, real 429, provider retry is the recovery mechanism — zero new infrastructure. Tier 1 becomes buildable in Phase 3 only because Meta's `Deferred` fetch forces the scheduler into existence anyway. **Not before.**

**The entitlement re-check at 8a is load-bearing.** `ModuleAccessFilter` gates on `TenantContext != null` (`:80-81`) and therefore **no-ops on every webhook thread** — without the re-check, a tenant whose plan excludes `LEADS` still gets inbound leads processed.

Body: `ApiResponse<Void>` per convention, exactly as `RazorpayWebhookController:35`. Providers ignore it; the envelope is for us. **The one documented exception is `Echo`** — Meta's `hub.challenge` must be returned as bare `text/plain`.

**Never echo request content; never return the created lead's `publicId`.** The caller is unauthenticated. Whitelist projection, never blacklist-stripping (scan:163).

**Unknown token → DROP. No row persisted, in any table.** WARN + 401. An unknown token is an unauthenticated stranger; persisting their bodies is an unauthenticated write primitive and a disk-fill DoS on a prefix that is unthrottled today. `WebhookEvent`'s own precedent supports this — it stores no raw body at all. The WARN carries **the token prefix only** plus the client IP.

**Note the asymmetry that makes this safe:** a delivery to a **known** token on a **disabled** connection **is** logged, against the right tenant — so the tenant sees *"you turned this off and are dropping leads"* rather than nothing. That is the whole reason step 4's finders do not filter `enabled`.

**Accepted cost:** a tenant who pastes a typo'd URL sees silence on our side; support diagnoses it from the metric, not a row.

**Latency budget depends on discipline nothing enforces.** `IN_APP`-only and publish-after-commit are mandated; a future contributor adding `EMAIL` pushes us past every provider's timeout and converts every successful ingest into a provider retry.

---

### 7. Filter chain

| Mechanism | Behaviour on our path | Change |
|---|---|---|
| `POST /api/webhooks/**` permitAll | reachable, prefix-wide (`SecurityConfig.java:93`, verified) | **comment only** |
| `GET /api/webhooks/leads/**` | 401s today via `:108` | **narrow permit added** (§1) |
| `JwtAuthFilter` | runs; early-returns `:45-48` with no Bearer, so its `finally` at `:107-113` never fires | none — step 0 neutralises the Bearer case |
| `ContextCleanupFilter` | **covers it** — `@Order(HIGHEST_PRECEDENCE+1)`, `/*`, unconditional clear (`:52,59-64`) | none |
| `ModuleAccessFilter` | **no-ops** — gates on `TenantContext != null` (`:80-81`) | **handler re-checks entitlement** (8a) |
| `MaintenanceModeFilter` | no-ops (desirable — a 503 would cause a retry storm) | none |
| `RateLimitFilter` | **skipped** (`:52-56` — only `/api/auth/`, `/api/portal/auth/`) | **`RateLimitPolicy` extraction** |
| `IngestBodySizeGuard` | does not exist | **new global filter** |

**No `SecurityConfig` change is required for reachability, and that is itself the risk.** The comment at `:91-92` says *"Payment-gateway webhooks … authenticity is the HMAC-SHA256 signature verified in the service"* — stale the moment lead ingest lands, and it invites someone to later narrow the matcher to `/api/webhooks/razorpay` and silently 401 every lead provider. **Update it** to record that lead ingest authenticates by opaque token in the path.

#### Rate limiting — do NOT extend the existing ternary

Both failures below are **created by the shipped change**, not pre-existing warts:

- `RateLimitFilter.java:73` builds the bucket key as `"rate_limit:" + path.replace('/',':') + ":" + ip` — **path-derived**. Our URL ends in the token, so every distinct token string is its own bucket: an attacker POSTing random tokens is **never limited** and grows `RateLimitService`'s `ConcurrentHashMap` (`:21`) unboundedly between the 60s `evictExpired` sweeps (`:36-39`) — unauthenticated memory pressure on the exact endpoint the body guard exists to protect.
- `RateLimitFilter.java:76` is `log.warn("Rate limit exceeded — ip={} path={}", ip, path)` — **that writes a live tenant credential into the Log4j2 output** the first time their provider trips the limit.

**Fix:** extract a `RateLimitPolicy` resolver (path prefix → `{keyFn, limit, window, logFn}`); `shouldNotFilter` becomes `policy.forPath(uri) == null`.

- Filter branch keys on `"rate_limit:ingest:" + ip` — **never the path**. The filter cannot see the token→integration mapping without a DB hit, so it cannot key per-connection.
- The **per-integration** limit runs in the **service**, post-resolution (step 6). `RateLimitService.isAllowed(key, max, window)` is key-agnostic (`:23`) and `CampaignDispatchService:140` already proves it is callable from a service on a tenant+channel key.
- The ingest log line emits **integration publicId + token prefix, never `getRequestURI()`**.
- **Add a regression test asserting no log statement on this path receives `getRequestURI()`.**
- **Widen coverage to `/api/webhooks/leads/**` including the tokenless PROVIDER_ACCOUNT route** — an unthrottled Meta endpoint burns CPU on HMAC per request at zero attacker cost.

Any IP here comes from `RateLimitFilter.resolveClientIp` (`:84-96`, trusted-proxy gated) — **never** `ClientIp.resolve`, which trusts `X-Forwarded-For` unconditionally and whose own javadoc says *"(not a security control)"*.

**Known risk, restated:** `shouldNotFilter` is currently one negated boolean guarding `/api/auth/` (`:52-56`), and `SIGNUP_MAX` dispatch keys off `path.endsWith("/signup")` (`:68`). A malformed edit **silently disables login rate limiting** with no test catching it. The `RateLimitPolicy` extraction is what makes that edit reviewable rather than a one-character hazard.

`RateLimitService` is in-memory per-JVM by documented design (`:13-16`) — **not correct behind more than one node.** Open risk if we ever scale out.

#### Body-size guard — on BOTH prefixes, globally

Verified: `application.properties:319-320` configures **only** multipart (`max-file-size`, `max-request-size`). There is **no** `max-http-request-size`. Tomcat's `max-http-post-size` governs form-encoded parsing only and **does not cap a raw JSON body**, so there is no container knob, and the controller is too late — `@RequestBody byte[]` is already in heap.

An anonymous caller POSTs a huge chunked body to `/api/webhooks/leads/justdial/anything` and OOMs the JVM **for every tenant**, before token resolution runs. Meta is worse: largest body, raw bytes bound for HMAC, signature checked **after** the bytes are in heap.

`IngestBodySizeGuard` is a **global servlet filter at `HIGHEST_PRECEDENCE`** covering **both** `/api/webhooks/leads/**` and `/api/ingest/**` (skipping everything else), 256KB default, per-channel override on the adapter catalog. It **must** cap the actual read — `Content-Length` pre-check **plus** a counting wrapped `InputStream`, because CL is absent under chunked transfer-encoding and header-only checking is a false sense of security.

It writes **413 via `ApiErrorWriter`** (the `RateLimitFilter.rejectRequest:102-107` idiom; `ErrorCode.PAYLOAD_TOO_LARGE` already exists at `ErrorCode.java:45`). **Never abort a wrapped `InputStream` mid-read** — that surfaces as `HttpMessageNotReadableException` → 400 and tells the tenant nothing.

---

### 8. Raw bytes, and why the SPI cannot take a parsed object

`RawInbound` carries body **bytes** + lazy typed accessors (`json()`, `form()`, `header()`, `query()`) + an **immutable `IntegrationCredentials` view** — never the managed entity, which is **detached** (verified `spring.jpa.open-in-view=false` at `application.properties:59`, and the resolver is non-transactional).

Raw bytes are mandatory and cannot be replaced by a parsed object: **Meta's HMAC is over the exact received bytes**, which is why `RazorpayWebhookController:29-33` binds `@RequestBody byte[]` with `consumes = ALL_VALUE`. Re-serializing a parsed object changes them.

**Adapters are PURE** — no repository, no `EntityManager`, no `TenantContext`, no Spring data access — and this is ArchUnit-enforced, **because adapter purity IS the tenant-isolation argument.** Resolution is a **framework** step in the non-transactional gateway; Meta is accommodated by the pure `accountKey(RawInbound)` extractor the gateway calls, **not** by an adapter that resolves.

**`RazorpayWebhookController` is not the template.** It never touches `TenantContext` because every entity it writes is `BaseEntity` (`WebhookEvent.java:23`, scan:17) — it **avoids** the tenant machinery rather than participating in it, and per scan:222 that idiom would fail the ArchUnit gate the moment it touched real tenant data. **There is zero precedent in this codebase for an unauthenticated request that establishes a tenant and persists tenant-scoped data.** That is what this subsystem is.

---

### 9. Enforcement — three ArchUnit rules + one test that cannot be skipped

The transaction guard in `TenantScope` is opt-in and trivially bypassed by calling `TenantContext.setTenantId` directly. Nothing catches that today. Three rules ship with Phase 1, next to `TenantIsolationArchTest`, whose javadoc already states our exact rationale (`:41-45`): *"it compiles, passes every unit test, and returns the right answer on a single-tenant dev database. It misbehaves only in production, with real tenants, as a data leak."*

1. **`TenantContext.setTenantId` is callable only from an allowlist** — `LeadIngestGateway` + existing schedulers + `JwtAuthFilter` + `TravelerAuthServiceImpl`.
2. **`leadsource.adapter..` may not depend on `..repository..`, `TenantContext`, or `EntityManager`** — this is what mechanically enforces the purity the whole isolation argument rests on.
3. **No class-level `@Transactional`** — zero violations today, so it codifies an existing convention at zero cost and permanently retires the question from every future design doc.

**Plus a mandatory two-tenant integration test.** A single-tenant test **cannot observe this bug class by construction**. Per §4 this test — not the entity listener's write-guard — is what actually catches a wrong-tenant write, which makes it load-bearing rather than confirmatory.

**Also fix `NotifyEventListener` regardless** (three lines): give it the same save/restore `TenantScope` has — capture `previous`, restore in `finally` instead of the bare `clear()` at `:38`. Four of the six designs independently trip over this landmine.

---

### 10. Cross-section requirements this section imposes

- **On machine-create:** the new `appendSystemLog(Lead, SystemLogCommand)` **must stamp `.tenantId(lead.getTenantId())` explicitly.** `LeadLogServiceImpl.addLog` documents the **opposite** convention — *"tenantId is auto-stamped by TenantEntityListener on @PrePersist"* (`:82`), builder sets none (`:74-83`) — and is unusable from a webhook thread anyway: `:65` calls `leadAccessGuard.requireVisible` and `:72` calls `currentUser()`, which throws on a null principal. `appendSystemLog` takes the **resolved managed `Lead`**, never a `publicId` — a machine has no row-level scope to check, and passing the entity makes it structurally impossible to call without having resolved it tenant-scoped first. **It must never be widened to accept a UUID** or it becomes a scope-bypass primitive.
- **On data-model:** `lead_ingest_events` is a `BaseTenantEntity` written only inside `TenantScope` with an explicit stamp. No un-tenanted log table is needed — **token resolution is a read that requires no tenant, so the tenant is known before the log write.** "Log first" means log **before parse**, not before resolution.
- **On phasing:** `List<LeadSourceAdapter>` and `List<LeadSourceFetcher>` inject via `ObjectProvider` defaulting to `List.of()`, or Phase 1 cannot boot — **zero fetchers exist until Phase 3.**

---

### 11. New files

| Path | Purpose |
|---|---|
| `leadsource/ingest/web/LeadIngestController.java` | the three mappings; raw `byte[]`, `consumes=ALL_VALUE`. Thin — zero logic |
| `leadsource/ingest/LeadIngestGateway.java` | **non-transactional.** Owns steps 0–11: clear, resolve, scope, sequential REQUIRES_NEW raw log, race-retry, post-commit publish |
| `common/context/TenantScope.java` | null-rejecting, save/restore, transaction guard |
| `leadsource/ingest/token/IngestTokenService.java` | mint / rotate / revoke. `SecureRandom`, Base64URL-unpadded, SHA-256 hex, reveal-once |
| `leadsource/ingest/resolve/LeadIngestTokenResolver.java` | the two derived finders + `accountKey` lookup. **The only class permitted to run without a `TenantContext`** — javadoc'd as intentional |
| `leadsource/ingest/service/LeadIngestService.java` | **method-level** `@Transactional`, called cross-bean from inside `TenantScope`. Returns `LeadIngestOutcome` — never publishes |
| `leadsource/ingest/service/LeadIngestDeliveryLogger.java` | owns the `REQUIRES_NEW` raw write. **Separate bean** — self-invocation defeats `REQUIRES_NEW` |
| `leadsource/ingest/dto/RawInbound.java` | bytes + lazy accessors + immutable credentials view |
| `leadsource/ingest/dto/IdempotencyKey.java` | `of(eventId)` / `of(ts+hash)` / `none()` (transport-window) |
| `leadsource/ingest/dto/LeadIngestOutcome.java` | status + recipients + pre-rendered title/message for the post-commit publish |
| `common/web/IngestBodySizeGuard.java` | global `HIGHEST_PRECEDENCE` filter, both prefixes, counting stream, 413 via `ApiErrorWriter` |
| `common/ratelimit/RateLimitPolicy.java` | path prefix → `{keyFn, limit, window, logFn}` |

### 12. Touched files

| Path | Change | Risk |
|---|---|---|
| `common/ratelimit/RateLimitFilter.java` | replace the `shouldNotFilter` boolean and the `:73` key / `:76` log with `RateLimitPolicy` dispatch | **the token-in-log and per-token-bucket bugs are created by this change if done naively.** A malformed edit silently disables login rate limiting — no test catches it. `SIGNUP_MAX` dispatch at `:68` must not be perturbed |
| `auth/security/SecurityConfig.java` | add `GET /api/webhooks/leads/**` permit; **do not touch the POST qualifier at `:93`**; refresh the stale `:91-92` comment | file is uncommitted-modified. The zero-change-for-reachability property **is** the risk: no review checkpoint |
| `notification/infrastructure/NotifyEventListener.java` | save/restore instead of bare `clear()` at `:38` | 3 lines; fixes a landmine four designs trip over |
| `lead/service/LeadServiceImpl.java` | fix the `:664` message rendering (enum `toString()`, null `departCity`) | hits 100% of inbound leads |
| `db/indexes.sql` | two partial unique indexes on the token hash columns, `WHERE ... IS NOT NULL`; **not** partial on `deleted_at`; `uq_ls_integration_account` | **HIGH, operational.** No Flyway — a manual deploy step that can be forgotten, and **invisible in dev**: a fresh DB works perfectly, only the pilot DB breaks |
| `platform/payment/webhook/RazorpayWebhookController.java` | **NONE** — sibling under the same prefix, not an extension | the risk is **copying** it (§8) |

---

### 13. Open risks

1. **Provider mechanics are UNVERIFIED** (research task, §1): URL-with-path-token support, header support, and **retry-on-non-2xx behaviour per provider**. The §5 dedup rationale and the §6 sync decision both assume providers retry. If a provider does not, a 5xx is silent loss for that channel.
2. **`RateLimitService` is per-JVM** (`:13-16`). Behind more than one node every limit multiplies by node count. Redis-backed is out of scope for Phase 1 — flag before scale-out.
3. **`leads_lead_source_check` on the pilot DB is UNVERIFIED** (scan:33). Not this section's column, but the same `indexes.sql` deploy step. One query settles it: `SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='leads'::regclass AND contype='c';`
4. **Access-log token redaction is an ops task the code cannot enforce** (§1).
5. **The `IN_APP`-only / publish-after-commit latency discipline is convention.** Rule 3 of §9 does not cover it; only code review does.

---

## Adapter SPI + Registry

The unit of extension. Everything here exists to make **"a new channel = one new class"** as close to true as a closed-enum, no-Flyway codebase permits — and to state plainly where it is not. The interface below is the **frozen contract**: it is shaped in Phase 1 for every constraint META_ADS imposes in Phase 3, because retrofitting costs the interface plus every shipped adapter.

### 1. Package placement — ArchUnit-enforceable, not aspirational

```
com.crm.travelcrm.leadsource.spi/        the SPI types
com.crm.travelcrm.leadsource.registry/   LeadSourceRegistry
com.crm.travelcrm.leadsource.adapter/    adapters and NOTHING else
com.crm.travelcrm.leadsource.gateway/    the non-transactional resolve/verify/dispatch step
com.crm.travelcrm.leadsource.web/        controllers
com.crm.travelcrm.leadsource.domain/     entities (LeadSourceIntegration, LeadAttribution, LeadIngestEvent)
com.crm.travelcrm.leadsource.client/     outbound provider clients (Graph API, …)
```

`leadsource.adapter..` containing adapters and nothing else is **what makes the purity rule below enforceable**. A controller or an entity parked there turns rule 2 into a comment. This supersedes the draft's `lead/ingest/spi/**`.

### 2. Which idiom this copies, refuses, and deviates from

**Copy — the self-declaring key + `List<T>` fold.** `OtpSenderResolver` folds `List<OtpDeliverySender>` into an `EnumMap` keyed by `sender.channel()` (`OtpSenderResolver.java:20-24`); `LeadAssignmentStrategyResolver:20-24` is the same shape. The load-bearing property: **the resolver is never edited when a bean is added.** That property *is* the one-class promise.

**Refuse — the `@Primary` swap.** `InteraktWhatsAppSender` is `@Component @Primary @ConditionalOnProperty` (`:34-36`) — exactly one bean wins *globally*. Correct for "one provider, one job"; categorically wrong for "16 channels live at once" (two `@Primary` = boot failure, zero = arbitrary winner). It is not a registry.

**Deviate 1 — throw on duplicates.** Both copied resolvers do a bare `map.put(key, bean)` (`OtpSenderResolver.java:21-23`). Two beans claiming one key = last-one-wins by Spring bean ordering, **silently**. For OTP that is a wrong-provider bug; here it is **wrong-tenant attribution on a public endpoint**. `List<T>` holds both cheerfully; Spring will not catch it. The registry throws in its constructor.

**Deviate 2 — `ObjectProvider`, not a bare `List<T>`.** A required `List<T>` constructor parameter with zero candidate beans fails with `NoSuchBeanDefinitionException` — it does not inject an empty list. Phase 1 ships one adapter and **zero fetchers** (no fetcher exists until META_ADS in Phase 3), so a bare `List<LeadSourceFetcher>` **cannot boot the framework in its own Phase 1**. Both lists inject via `ObjectProvider<List<T>>` defaulting to `List.of()`. `OtpSenderResolver` gets away with a bare `List` only because a stub bean is always present (`LoggingWhatsAppSender`); we have no such stub. **This must land before Phase 1.**

### 3. The frozen SPI

```java
public interface LeadSourceAdapter {
    LeadSourceChannel channel();
    default TenantResolution resolution() { return TenantResolution.TOKEN; }   // TOKEN | PROVIDER_ACCOUNT
    default String accountKey(RawInbound in) { throw new UnsupportedOperationException(); } // PURE. PROVIDER_ACCOUNT only. Meta: entry[0].id
    InboundVerification verification();          // MANDATORY. No default.
    InboundParseResult parse(RawInbound in);     // PURE. No IO, no DB. Replayable from the stored raw payload.
    ChannelCatalogEntry catalog();               // FE grid + credential field descriptors + requiredCredentialKeys
    default Set<String> secretFieldPaths() { return Set.of(); }   // redacted before the raw payload is persisted
    default IdempotencyKey dedupKey(RawInbound in) { return IdempotencyKey.none(); }
}
```

Three genuinely different failure domains, which is why fetch is a **separate SPI** and not a second method:

| Concern | When | Thread / tx | Fails how |
|---|---|---|---|
| **verify** | before any state is touched | webhook request, no tx | reject, never retry |
| **parse** | before the provider ACK | webhook request, no tx | bad payload — don't retry |
| **fetch** | *after* the ACK | background, own tx | transport / 401 — **do** retry |

An interface whose two methods share none of thread, transaction, credential need or retry semantics is lying about its own contract, and ~14 adapters would carry a no-op `hydrate()`.

#### `secretFieldPaths()` and `requiredCredentialKeys` — closed SPI omissions

Three siblings each independently planned to add a method here. All three are folded in now:

- **`secretFieldPaths()`** — the security sibling's only mechanism for redacting an in-body shared secret before `lead_ingest_events.raw_payload` is persisted. Without it the raw store writes a live credential in plaintext next to PII for 30 days: a **real defect created purely by an SPI omission**, now closed. A `default Set.of()` is acceptable **here and not on `verification()`** because the fail-open direction is "redact nothing" — visible in the stored payload, not silent.
- **`requiredCredentialKeys`** folds into `ChannelCatalogEntry`, which already carries credential field descriptors. One declaration serves both the FE form and the save-time fail-fast — the data model's only stated mitigation for the un-validated credential blob.
- **Adapter-owned tenant resolution is REJECTED** — see §4.

#### Verification: declarative, mandatory, sealed

```java
public sealed interface InboundVerification permits HmacHeader, SharedSecretInBody, TokenOnly {
    record HmacHeader(String header, String algo, Enc enc, String prefix) implements InboundVerification {}
    record SharedSecretInBody(String jsonPath) implements InboundVerification {}
    record TokenOnly() implements InboundVerification {}
}
```

Three shapes were on the table and two fail open:

- **`boolean verify()` on every adapter** → ~12 of 16 write `return true`, and a copy-pasted `return true` is **indistinguishable on review from a deliberate no-signature decision**. Fail-open by boilerplate.
- **A defaulted `verify()`** → fail-open by *inheritance*, invisible in the file.
- **A separate verifier-bean registry** → a second lookup that can **miss**, and **a miss in a verifier registry fails open** — the exact mode being designed against. **DELETED**, along with `InboundVerification.Custom` and the `InboundVerifier` escape hatch the draft proposed. The sealed set is closed at three; every missing field is a `Custom` we want to keep at zero, which is why R1–R4 (§8) gate the freeze.

`TokenOnly()` is **a statement written in the file**, never an inherited silence and never a runtime fallback when a secret happens to be null.

**`prefix` is not cosmetic.** Meta sends `X-Hub-Signature-256: sha256=<hex>`; Razorpay — the implementation we are told to copy — computes hex and compares `signatureHeader.trim()` verbatim with no prefix handling (`RazorpayGatewayClient.java:116-123`, verified: `constantTimeEquals(expected, signatureHeader.trim())`). Copied as-is against Meta it fails **100% of the time**. `""` for Razorpay-shaped bare hex, `"sha256="` for Meta.

Keeping the posture **data** means the framework runs the comparison **once, correctly** — reusing `MessageDigest.isEqual` (`RazorpayGatewayClient.java:160-166`) — instead of 16 hand-rolled comparisons, some with `.equals()`.

**Every verifier fails CLOSED when its secret is unset** — the `verifyWebhookSignature` precedent returns `false` on a null/blank secret (`RazorpayGatewayClient.java:117-120`). `verificationMode` is stored on the integration row, so a payload arriving **without** a signature header is **REJECTED, not downgraded** — otherwise the strongest channel degrades to the weakest at the attacker's option.

Verification runs **first, before any state is touched** (`SaasPaymentServiceImpl.java:176-180`).

#### `parse` returns a sealed result

```java
public sealed interface InboundParseResult permits Complete, Deferred, Ignored, Echo {
    record Complete(List<NormalizedLead> leads) implements InboundParseResult {}
    record Deferred(List<FetchHandle> handles) implements InboundParseResult {}
    record Ignored(String reason)               implements InboundParseResult {}
    record Echo(byte[] body, String contentType) implements InboundParseResult {}
}
```

This signature **wins outright** over the two competing sibling shapes (`List<NormalizedLead> parse(…)` and a bare `NormalizedLead parse(…)`); it is a strict superset of both and it is law.

- **`Complete` is a LIST.** Meta batches multiple `entry[].changes[]` per POST — one delivery yields N leads. Singular was unfixable-in-place.
- **`Deferred` is a LIST**, same reason, and is **durable — persisted before the ACK**. A crash between ACK and fetch is recoverable. That is the entire reason the ACK comes first, and it works only because parse and fetch are separate objects.
- **`Ignored` is not politeness.** **Most real traffic on these endpoints is not a lead** — subscription echoes, test pings, delivery-status callbacks. A bare return has no way to signal "valid, not a lead": `null` is indistinguishable from a bug, and throwing turns a routine ping into a **retry storm**. `Ignored` carries a counted reason, which is also the parse-stage answer to scan open-question 12 (*"today they vanish"*) and finding #4 (*"no log or counter on rejection"*).
- **`Echo` carries a response body** — a bare `text/plain` reply for a provider handshake (Meta's GET `hub.challenge`). It is a **documented, deliberate exception** to the `ApiResponse` envelope rule, and the only one in this framework. It is paired with the narrow GET permit: `SecurityConfig.java:93` is verified `.requestMatchers(HttpMethod.POST, "/api/webhooks/**").permitAll()` — POST-only — so a GET falls to `.anyRequest().authenticated()` at `:108` and 401s.

`Echo`, `Complete(List)`, `Deferred(List)` and `PROVIDER_ACCOUNT` **cost one enum, two signatures and one sealed case in Phase 1 whether or not R1 confirms Meta needs them** — and GOOGLE_ADS or a later channel may need them anyway. That asymmetry is the whole argument for shaping now.

### 4. Tenant resolution is a FRAMEWORK step, not an adapter method

The proposal that *"tenant resolution must be a method ON the adapter"* is **REJECTED**: it requires a DB lookup by definition, which breaks adapter purity, and **adapter purity IS the tenant-isolation argument**.

Adapters are **PURE** — no repository, no `EntityManager`, no `TenantContext`, no Spring data access. Resolution is performed by the **non-transactional gateway**. The adapter contributes at most `accountKey(RawInbound)` — a pure field extraction over bytes.

The apparent circularity for PROVIDER_ACCOUNT (verify needs the row → the row needs the tenant → the tenant needs the parse → the parse comes after verify) is **broken by ordering, not by a new abstraction**:

1. **Platform-level HMAC over the raw bytes** — the app secret is platform config, not per-tenant, so **no row is needed to verify**.
2. Gateway calls the **pure** `adapter.accountKey(in)` — one field extraction (Meta: `entry[0].id`).
3. Gateway looks that up against `external_account_id` where `resolution_mode=PROVIDER_ACCOUNT`.

Owner decision 2 holds: the body supplies an **account key, never a tenantId**, and that key is only a lookup into a row an authenticated tenant created — with the platform HMAC proving the body's authenticity **before** the lookup runs. For TOKEN mode the tenant is known before any parsing at all; for PROVIDER_ACCOUNT it is known after HMAC + one field extraction and **still before the full parse**, so `lead_ingest_events` remains a `BaseTenantEntity` with an explicit `.tenantId(...)` stamp **in every case**.

**Context discipline — the mechanic, verified, not inherited from the brief.** `TenantFilterAspect:34-37` returns without enabling `tenantFilter` when the context is null (fails **OPEN** — every query silently spans all tenants, no error, no log), and `:22` is `@Before` on `@Transactional`, so the filter is latched **before the method body**. **Setting `TenantContext` inside a `@Transactional` method is too late for that entire transaction.** The gateway is **non-transactional**, sets `TenantContext` **before** delegating, and delegates **cross-bean** into the method-level-`@Transactional` creation service.

> The `@annotation(…@Transactional)` pointcut (`TenantFilterAspect.java:22`) matches **method level only** — a class-level-annotated service would get no tenant filter at all. Downgraded from "possible live bug" to **codified convention with zero current violations**: I ran the grep, and there are **zero line-initial `@Transactional` annotations across `src/main/java`**. Convert it into an ArchUnit rule (§7) so it stops costing every reviewer the same grep.

### 5. `RawInbound`, `IntegrationCredentials` — never the entity

```java
public interface RawInbound {
    byte[] body();                        // exact bytes — HMAC input
    JsonNode json();                      // lazy, cached
    Map<String,String> form();            // lazy, cached
    String header(String name);
    String query(String name);
    IntegrationCredentials credentials(); // immutable view — NEVER the entity
}
```

**Raw bytes are mandatory and irreplaceable.** Meta's HMAC is over the exact received bytes; re-serializing a parsed body changes them. This is precisely why the sole webhook precedent binds `@RequestBody byte[]` with `consumes = ALL_VALUE` (`RazorpayWebhookController.java:29-33`). Bytes-only would make every form-POST adapter re-implement form decoding, hence the lazy typed accessors **over the same immutable bytes**.

**The entity must never cross the resolver.** `spring.jpa.open-in-view=false` is verified (`application.properties:59`) and the resolver is non-transactional — **the row is DETACHED**. A lazy field touched during verify or parse throws `LazyInitializationException` on a webhook thread, a failure that **never appears in a `byte[]`-fixture unit test**. The gateway maps the row into an immutable `ResolvedIntegration` / `IntegrationCredentials` record **at resolution time**. That satisfies "immutable credentials view, not the entity" and makes the detachment question moot. No DB session, no repository, no `EntityManager` — purity is not aesthetics, it is what makes replay possible.

### 6. `NormalizedLead` and `LeadSourceFetcher`

**The rule: no field on `NormalizedLead` is a default, because a default baked into the SPI is a default re-invented 16 times.**

Deliberately absent, each for a reason the scan forces:

| Absent | Why the adapter must not set it |
|---|---|
| `leadStage` | `@NotNull` with **no server default** — the client dictates it (`CreateLeadRequestDto:42-43`). 16 authors each pick `NEW_LEAD` until one picks `CONTACTED`. |
| `assignedUserId` | `@NotNull`, and a **UUID publicId** (`:47-48`), resolvable only against tenant users. An adapter parsing bytes cannot know them. |
| `tenantId` | Resolved by the framework before the adapter runs (owner decision 2). Putting it here re-opens the body-derived-tenant hole. |
| `LeadSource` | Derivable from `channel.leadSource()`. Otherwise a JUSTDIAL adapter stamps `WEBSITE`. |
| `origin` | Stamped by the pipeline (`INTEGRATION`, always). This is what makes owner decision 4 safe — `GOOGLE_ADS` from an adapter and `GOOGLE_ADS` typed by a human differ by `origin`, not by enum constant. |

**`email` is nullable** even though `Lead.email` is `@NotBlank`/`nullable=false`. That is the creation layer's problem. An IVR call **has no email**; an adapter synthesizing `noreply@ivr.local` has silently made a *uniqueness* decision that collides on the second call via `uq_leads_email_tenant_open` (`db/indexes.sql:82-87`).

**`phoneRaw` passes through untouched, with a nullable `phoneCountryHint`.** Three phone treatments already coexist and disagree — `PhoneNormalizer.normalize()` is `trim()`-only *by deliberate design* (javadoc: stronger canonicalisation *"belongs with a deliberate one-time data migration, not a passive read-path change"*), `WhatsAppMessagingService:146-156` does real E.164, lead dedup calls **neither** (`LeadServiceImpl:601` passes phone raw). If each adapter canonicalises, the framework ships a **fourth** treatment, sixteen times, differently. Scan open-question 7 is the owner's and is **open**; the SPI must not pre-empt it. Note the DTO regex `^\+?[1-9]\d{7,14}$` (`CreateLeadRequestDto.java:24-27`) rejects leading zeros, spaces and dashes — telephony payloads arrive in exactly those forms, so the creation layer **will** need a normalisation step.

`extras` is a bounded diagnostic map, **documented as never read by business logic**, so a provider quirk never forces a schema change. Known dumping-ground risk; mitigation is the doc contract plus review.

```java
public interface LeadSourceFetcher {
    LeadSourceChannel channel();
    List<NormalizedLead> fetch(FetchHandle handle, IntegrationCredentials creds);
}
```

**Credential freshness is an SPI javadoc contract, not an implementation detail** — this is the one channel where the gap is guaranteed to bite:

- **`FetchHandle` persists ONLY the provider pointer** (`leadgen_id`, `page_id`, `form_id`), **NEVER credentials**.
- **The drain scheduler re-resolves the integration row per attempt** — inside `TenantScope`, **outside** the transaction, **cross-bean**, copying `UsageAlertScheduler:36-46` (`setTenantId` outside the tx, delegate cross-bean, `clear()` in `finally` per iteration, one tenant's failure never blocks others). A tenant who reconnects after a token expiry has their **queued handles succeed**; a rotated token is picked up automatically.
- **A soft-deleted or disabled integration abandons its pending handles with a terminal status — NOT `FAILED`.** It must not retry.
- **We do NOT auto-refresh Meta page tokens.** It needs a fresh user authorization and cannot be done unattended, so an auto-refresh job would be a lie that fails exactly when it matters. Detect 401/expiry → `status=DEGRADED` + `NotifyEvent` to tenant admins. (The scan confirms no OAuth/refresh infrastructure exists anywhere.)

### 7. The registry

```java
@Component
public class LeadSourceRegistry {
    private final Map<String, LeadSourceAdapter> adapters = new HashMap<>();   // keyed by channel.slug()
    private final Map<String, LeadSourceFetcher> fetchers = new HashMap<>();

    public LeadSourceRegistry(ObjectProvider<List<LeadSourceAdapter>> as,
                              ObjectProvider<List<LeadSourceFetcher>> fs) {
        // fold; THROW on a duplicate slug claim, naming both classes
        // boot-fail if any adapter can return Deferred and no fetcher is registered for its channel
    }
    public LeadSourceAdapter adapterFor(LeadSourceChannel c);   // -> adapters.get(c.slug()), throw on miss
    public LeadSourceFetcher fetcherFor(LeadSourceChannel c);
    public List<ChannelCatalogEntry> catalog();
}
```

**Eager fold at construction, not a stream filter per request.** `NotifyEventListener` streams per event (`:27-28`) — fine for ≤3 channels, an O(n) scan on a public hot path here, and a stream filter **cannot detect a duplicate claim at all**.

**Keyed on `channel.slug()`, resolved via `LeadSourceChannel.fromSlug(pathSegment)`.** Two reasons; the second is decisive:

1. `LeadSource` is the **attribution** vocabulary — 25 constants including `MANUAL`, `WALK_IN`, `REPEAT_CUSTOMER`, which can never have an adapter. Keying on it makes `adapterFor(WALK_IN)` *expressible*, converting a compile-time impossibility into a runtime throw.
2. **The URL slug must not come from `LeadSource`.** `LeadSource` carries `@JsonValue getDisplayName()` (`LeadSource.java:23-26`, verified) — its wire vocabulary is a **renameable display string**. A webhook URL registered in a third party's console is effectively **permanent**. Coupling them means renaming a dropdown label silently breaks live ingestion for every tenant on that channel.

**In the DB, `lead_source_integrations.channel` is a plain VARCHAR storing `channel.slug()` — NO `@Enumerated`, NO check constraint, NO `db/indexes.sql` block.** The draft's proposed channel-CHECK block is **deleted**. The registry is the constraint and a **strictly stricter one**: it rejects an unknown slug at save time *and* again at ingest, whereas a CHECK only validates spelling and would happily accept a `meta_ads` row on a node with no Meta adapter deployed. **`row.getChannel()` returns String**; the path segment is compared to it as a String, and a **mismatch is 404, never 400** — a 400 confirms the token exists for a different channel. Unknown slug / missing adapter → a generic 404 resolved **before** the token lookup, body identical to the invalid-token 404, so the endpoint never reveals whether a token is valid.

**Thread-safety.** Adapters are Spring **singletons shared across every tenant's traffic**. Any non-final instance field is a cross-tenant leak that no existing test would catch.

**ArchUnit rules to add**, next to the existing `TenantIsolationArchTest` (`src/test/java/com/crm/travelcrm/arch/TenantIsolationArchTest.java`, which already bans `findById`/`getReferenceById` on `BaseTenantEntity` repos at `:59-61` and whose javadoc at `:52-55` forbids the `EXEMPT_CLASSES` escape):

1. Nothing in `leadsource.adapter..` may depend on `..repository..`, `jakarta.persistence..`, `TenantContext`, or `..gateway..`.
2. Every class in `leadsource.adapter..` implements `LeadSourceAdapter` or `LeadSourceFetcher`.
3. No class in `leadsource.adapter..` declares a non-final instance field.
4. No class-level `@Transactional` anywhere (zero current violations — grep verified; this codifies, it does not fix).

### 8. The honest cost of a new channel

The draft's headline claim — *"zero FE change"* — is **false and structurally so**, not conditionally. Tailwind v4 purges any class string it does not see literally in source, so a server-sent `"bg-orange-100"` is never seen by the scanner and renders unstyled. The FE holds a `code → {icon, gradient}` presentation map and catalog DTOs carry **no styling fields**. Reaching zero is impossible.

| Group | Channels | Cost |
|---|---|---|
| **A** | JUSTDIAL, INDIAMART, SULEKHA, TRADEINDIA | 1 adapter class + 1 `LeadSource` constant + 1 `LeadSourceChannel` constant + **1 optional** FE presentation entry. **DB untouched.** |
| **B** | GOOGLE_ADS, IVR_CALL, WEBSITE_FORM | adapter + config |
| **C** | META_ADS | ~7 classes + DDL + config |

**The FE entry is POLISH, not wiring.** The grid renders a new channel correctly from `catalog()` with a neutral default icon/gradient. The presentation map **MUST** carry a `|| default` fallback (the existing `SOURCE_PILL`/`STAGE_PILL:44-53` idiom) so a channel shipping without an FE edit **degrades visually rather than failing**. The promise is worth keeping as an aspiration **for group A specifically**; it is falsified, not demonstrated, by claiming it for Meta. **Claim the true number everywhere.**

**FE auto-sync is what buys even that**, via `catalog()` on the adapter served from `registry.catalog()`, applying the proven pattern (`MarketingFieldCatalog:30-54` + `SegmentController:37-40` + `OptionDTO`). That exact hardcoding **has already failed once here**: `LeadInformation.jsx:6-9` hardcodes **8** sources against a **9**-constant enum, `OTHER` is unselectable, and any lead carrying an unknown source becomes **un-editable** (the select falls back to the placeholder, posts `leadSource: ""` → 400).

**`leads_lead_source_check` is a ONE-TIME Phase 0 cost for all 25 constants — not per channel.** `ddl-auto=update` **never alters an existing constraint**, and `db/indexes.sql` has ~20 refresh blocks across ~14 tables and **zero for `leads`** (scan finding #5). A fresh DB generates it from the current enum and works perfectly, so this **only bites databases whose `leads` table predates the change — invisible in local dev, breaks the pilot.** **UNVERIFIED that the constraint exists on the pilot DB**; Hibernate generates it silently and it is declared nowhere, so grep finds nothing, which misleadingly reads as "no constraint". One query settles it:

```sql
SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
WHERE conrelid='leads'::regclass AND contype='c';
```

### 9. Research tasks — provider mechanics are NOT facts

**No provider payload shape below is established by this codebase.** These are **named research tasks with owners, scheduled in Phase 0** where they are free and parallel and where they gate Phase 2/3's *shape* — not discovered mid-build. **The near-term cost ranking in §8 is PROVISIONAL until R1–R4 land.**

| # | Task | What it gates |
|---|---|---|
| **R1** | META: is the callback app-level only; failure-count/disable semantics; page-token expiry semantics; batching envelope | Phase 3's size. The SPI is deliberately shaped to survive **any** answer R1 returns. |
| **R2** | GOOGLE Lead Form Extension: is the shared secret in the body? | This is the **entire** forcing argument for `secretFieldPaths()` + `SharedSecretInBody`. The mechanism stays because it is cheap and conditional; **the certainty goes.** |
| **R3** | INDIAMART: push or pull? | If pull, it is a scheduler + per-integration cursor, **NOT group A**, and Phase 2's size is wrong. |
| **R4** | JUSTDIAL / IVR: payload shape, HTTP method, whether a per-account custom webhook URL is permitted | Whether TOKEN mode is even available on those channels. |

The same discipline applies to the many-connections enumeration ("Meta = several FB pages, IVR = several DIDs, JustDial = possibly several city accounts"): the decision is right on first principles — **the token, not the (tenant, channel) pair, is the key** — but the enumeration is assumption.

**Phase 1 is `WEBSITE_FORM` because it is the only channel whose payload we define ourselves** — the framework gets built, tested and proven end-to-end with **zero research dependency**. `WebsiteFormAdapter` is the reference adapter.

### 10. Files

**New** (all under `com.crm.travelcrm.leadsource`): `spi/LeadSourceChannel`, `spi/LeadSourceAdapter`, `spi/LeadSourceFetcher`, `spi/RawInbound`, `spi/InboundParseResult`, `spi/InboundVerification`, `spi/NormalizedLead`, `spi/FetchHandle`, `spi/IntegrationCredentials`, `spi/ChannelCatalogEntry`, `spi/TenantResolution`, `spi/IdempotencyKey`, `registry/LeadSourceRegistry`, `adapter/WebsiteFormAdapter`.

**Deleted from the draft:** `InboundVerifier` and `InboundVerification.Custom` (the verifier registry fails open on a miss); the `db/indexes.sql` channel-CHECK block (String slug, registry is the stricter constraint).

**Touched, not owned:** `lead/enums/LeadSource.java` (+16 constants; consumed via `LeadSourceChannel.leadSource()`). **Risk:** two constants sharing a `displayName` would **not** fail at compile time — `fromValue` (`LeadSource.java:31-35`, verified) returns the **first declaration-order match**, so an accidental duplicate display string makes one constant permanently unreachable over the wire. The 16 new names must be display-unique; **nothing enforces that invariant, so it needs a unit test.**

### 11. Open risks

- **R1–R4 are unanswered.** The SPI is shaped to survive any answer; the **phase sizing is not**.
- **`leads_lead_source_check` on the pilot DB is UNVERIFIED** (§8). One query resolves it, and it is a Phase 0 blocker.
- **`extras` is a documented-contract-only guard rail** against becoming a dumping ground. No mechanical enforcement exists.
- **Assumed, not settled by the scan:** that a `Deferred` drain scheduler may re-resolve a tenant row on a background thread under `TenantScope` without violating the ArchUnit finder ban. It relies on a derived tenant-scoped finder, which is legal — but no existing scheduler resolves an integration row this way, so the pattern is new here even though its two halves (`UsageAlertScheduler:36-46` + derived finders) are both precedented.

---

## Machine lead-creation path

Today no machine can create a lead. This section removes that blocker without forking the create logic, and implements owner decision 1 (repeat inbound → append an activity).

`createLead` has exactly **one** call site: `LeadController.java:49`. The AI tool layer does not call it; `DevDataSeeder` bypasses the service entirely (`DevDataSeeder.java:526-543`, scan finding #1). So the refactor is small — but it is **three** policy branches, not one, and it does widen the `LeadService` interface. Both corrections are load-bearing and are stated as such below.

### 1. Entry contract — TenantContext before the transaction, publish after commit

**`TenantFilterAspect` fails OPEN and latches BEFORE the body.** Verified: `common/aspect/TenantFilterAspect.java:34-37` returns without enabling `tenantFilter` when `TenantContext` is null, and the advice is `@Before` on `@Transactional` (`:22`). Setting the tenant as the first line of `LeadIngestionService.ingest` is **too late** — every query in that transaction silently spans all tenants (scan finding #3). The contract copies `DocumentExpiryReminderScheduler:44-59` exactly:

```java
// non-transactional gateway thread (webhook / ingest controller)
SecurityContextHolder.clearContext();                 // see below — a Bearer token WOULD authenticate
TenantContext.setTenantId(tenantIdFromResolvedIntegration);   // unconditional overwrite
try {
    outcome = ingestionService.ingest(command);       // cross-bean → tx proxy + aspect apply
} finally {
    TenantContext.clear();                            // ContextCleanupFilter is a net, not an excuse
}
outcome.pendingEvents().forEach(eventPublisher::publishEvent);   // AFTER commit. §8.
```

- **`SecurityContextHolder.clearContext()` is mandatory.** `/api/webhooks/**` is `permitAll` but `JwtAuthFilter` still runs and will authenticate a caller-supplied Bearer token, setting `TenantContext` from *their* token (`JwtAuthFilter.java:89-90`; scan :75). The gateway must overwrite the tenant from the resolved `LeadSourceIntegration` and drop the inherited principal so nothing downstream can be steered.
- **`ingest` is invoked cross-bean.** Self-invocation defeats both the tx proxy and the aspect.
- The tenant is resolved by the framework gateway (whole-token SHA-256 probe / `external_account_id` lookup) — **never** from the body. Owned by the ingestion section; this section starts once `TenantContext` holds a tenant.

### 2. The principal problem — explicit actor parameter, and the interface DOES widen

All existing create logic moves into one method; the public entry point becomes a delegate.

```java
// LeadService (interface) — WIDENS. See below.
LeadResponseDto createLead(CreateLeadRequestDto r);                       // unchanged, HUMAN
LeadResponseDto createLead(CreateLeadRequestDto r, LeadActor actor, IngestPolicy policy);
```

```java
public LeadResponseDto createLead(CreateLeadRequestDto r) {
    return createLead(r, LeadActor.human(currentUser()), IngestPolicy.INTERACTIVE);
}
```

**Correction (red-team, hole 9): the method cannot be private.** `LeadIngestionService` lives in `lead/ingest/` and is a different bean from `LeadServiceImpl` in `lead/service/` — a private method is uncallable across both. The earlier claim "`LeadController` needs ZERO edits *because* it stays private" does not compile. The corrected position:

- The interface widens to the 3-arg overload. `LeadController.java:49` still needs **zero** edits and `@PreAuthorize("LEAD_CREATE")` at `:44` is untouched — that part survives.
- A public method taking a caller-supplied `LeadActor` is a privilege primitive, so **`LeadActor` must be un-forgeable from outside the lead module**: its constructor is package-private/sealed and the only factories are `LeadActor.human(User)`, `LeadActor.integration(Long integrationId, String label)`, `LeadActor.system(String reason)`. Same discipline as `appendSystemLog` (§6). ArchUnit-guard it if the ingest module ends up outside `lead.*`.

**Why not a synthesized system principal.** It must be a real `users` row (`recordAssignmentAudit` reads `creator.getId()` at `LeadServiceImpl.java:128`), and a real row is picked up indiscriminately by `AssignableUserResolver.resolve` (`:42-46` filters only on active / unlocked / not SUPERADMIN / not SUB_AGENT / has `LEAD_READ`) — so the bot enters the round-robin pool, appears in `findUserWorkload` (`LeadRepository.java:162-175` LEFT JOINs every active user) and the assignee dropdown, and consumes a plan user seat. Putting it in `SecurityContextHolder` additionally makes the webhook thread authenticated for every `ScopeResolver` decision — a privilege-escalation primitive.

**Why not a separate principal-less create path.** Quota, dedup, trash, assignment and notification would exist twice and drift — exactly where scan finding #7's double-notify is born.

**Nearly free:** `LeadAssignmentAudit.createdByUserId` (`:45-46`) and `createdByName` (`:48-49`) are **already nullable**. The audit trail natively describes a non-human creator with zero schema change.

`LeadActor.origin` is the canon **`LeadOrigin {MANUAL, INTEGRATION, SYSTEM}`** in `com.crm.travelcrm.lead.enums`. The draft's parallel `ActorOrigin` is **deleted** — two identical three-value enums in two packages drift.

#### The three policy branches (red-team, hole 2)

`IngestPolicy {INTERACTIVE, MACHINE}` gates **three** things, not two. The draft's cost claim ("1 record, 1 signature, 1 caller") was arithmetically wrong: it counted `recordAssignmentAudit` and ignored `assignForCreate`, which is scan finding #1's hard point #2.

| Step | INTERACTIVE | MACHINE |
|---|---|---|
| `enforceLeadQuota` (`:84`) | first, throws 403 | after the match, quarantines (§7) |
| `checkTrashedForRestore` (`:91`) | runs | **skipped** (§6) |
| assignment (`:102`) | `assignForCreate(request.getAssignedUserId())` | `assignForInbound(tenantId, defaultOwnerUserId)` — `request.getAssignedUserId()` is **never read** (§5) |
| `recordAssignmentAudit` (`:109`) | `actor` = human | `actor` = integration |
| notify | publish inside, last | returned on the outcome, published after commit (§8) |

`assignForCreate` calls `currentUser()` at `LeadAssignmentService.java:139-140` → throws `IllegalStateException("No tenant user in security context")` at `:327-333`. Without the assignment branch the first JustDial webhook rolls back at `:102` and 500s forever. The actor parameter alone fixes exactly one of the three hard points.

**The DTO is honest about this.** `CreateLeadRequestDto.assignedUserId` is `@NotNull UUID` (`:46-48`) while `assignForInbound` works in internal `Long`s. The machine arm builds a `CreateLeadRequestDto` in `LeadIngestionService` with `assignedUserId = null` and **never** has it read — bean validation only fires from `@Valid` at `LeadController.java:46`, not on a service call, so no `@NotNull` is evaluated on the machine path. That asymmetry is deliberate and free; it is also the reason `@NotBlank` stays on `email` (§9).

### 3. `recordAssignmentAudit` — the latent bug, and the ordering bug next to it

`currentUser()` sits at `LeadServiceImpl.java:119`, **outside** the try opening at `:120`, so a null principal throws and rolls back lead creation — defeating the promise at `:121-123`. With the actor parameter the call disappears:

```java
private void recordAssignmentAudit(Lead lead, AssignmentOutcome o, Long tenantId, LeadActor actor) {
    try {
        leadAssignmentAuditRecorder.record(LeadAssignmentAudit.builder()
                .createdByUserId(actor.userId())      // null for INTEGRATION / SYSTEM
                .createdByName(actor.displayName())   // "JustDial — Mumbai Ads" / "system"
                ...);
    } catch (Exception ex) { log.warn(...); }
}
```

**Second bug, same two lines.** `:108` publishes, `:109` records. `NotifyEventListener` calls `TenantContext.clear()` in its `finally` (`:37-39`) on the **publisher's own thread** (scan :132), so `recordAssignmentAudit` already runs today with a null context; `record()` is `REQUIRES_NEW`, so `TenantFilterAspect` re-fires, hits the null and enables no filter. It survives only because the builder carries an explicit `.tenantId(tenantId)` at `:125` — the "explicit tenantId + null context persists silently with zero validation" branch of `TenantEntityListener` (`:24-30`, scan :75). One refactor from a cross-tenant read.

**Corrected order in `createLeadInternal`:** `… → save → recordAssignmentAudit → (INTERACTIVE only) publishLeadCreatedNotification` — publish **last**.

### 4. Origin + integration stamping (red-team, hole 8)

`createLead(r, actor, policy)` maps `actor.origin()` → `lead.origin` and `actor.integrationId()` → `lead.sourceIntegrationId`. Both are written **by the mapper**, not by a post-build setter, so no path can produce an unstamped Lead:

```java
Lead toEntity(CreateLeadRequestDto r, LeadActor actor, String phoneNormalized)
```

- The data-model section adds `leads.origin VARCHAR(20)` (`@Builder.Default = MANUAL`), backfills it to `MANUAL` in `db/indexes.sql`, then tightens with `SET NOT NULL` plus a CHECK on the origin↔link invariant. **This section owns the only code that writes it.** If the mapper does not stamp it, the first insert after that `SET NOT NULL` lands `NULL` and 500s — on the *human* path too.
- `LeadActor` carries the **internal `Long` integration id**, not a `publicId`. The ingestion gateway resolved the `LeadSourceIntegration` row by token before the tenant was even known; the id is tenant-correct by construction and a `publicId` round-trip would buy a redundant lookup and nothing else. `publicId` still governs every **API** surface — `LeadActor` never crosses one.
- `LeadMapper` is a hand-written `@Component` (`LeadMapper.java:16-17`) with no compile-time safety net (scan :103). Every field added here is a manual edit to `toEntity` **and** `toResponse`.

### 5. Ownership of an inbound lead

`assignForCreate` cannot be reused (`currentUser()` at `:139-140`) and must not be duplicated. A sibling on the *same* service reuses everything that matters:

```java
@Transactional
AssignmentOutcome assignForInbound(Long tenantId, Long configuredOwnerUserId);
```

Reuses `assignableUserResolver.resolve`, `recommendationCandidates` (`:231-237`), `pointerProvisioner.ensureExists`, the pessimistic pointer lock (`:163-169`) and the `LOAD_BASED` strategy. It drops only the principal-derived role split (`:144-151`) and the `scopeResolver` narrowing (`:300-307`) — a machine has no row scope.

**Precedence:** integration's `defaultOwnerUserId` (if active + in pool) → `LOAD_BASED` → integration's `fallbackOwnerUserId` → lowest-id active `TENANT_ADMIN`. **Never throws.** `resolveRecommended` throws at `:285-292`; a webhook cannot act on a throw, and an out-of-hours enquiry into a tenant with no eligible user would be lost.

**No unassigned inbox.** `assigned_user_id` is NOT NULL with `optional=false` (`Lead.java:65-72`); a nullable-owner migration needs a hand `ALTER` plus changes to `ownerId()` (`:563-565`), `LeadAccessGuard.assertVisible:57`, every `ScopeResolver` path and the workload `GROUP BY`s.

**No new `AssignmentStrategyType` constant.** `strategy_used` is `@Enumerated(STRING)` on an **existing** column (`LeadAssignmentAudit:65-67`), so a new constant walks into the check-constraint trap (scan :33; `db/indexes.sql:143-147`) — invisible on a fresh DB, fatal on the pilot. Configured owner → `MANUAL`; algorithmic pick → `LOAD_BASED`; `createdByName` carries the nuance. Zero DDL.

> **ACCEPTED RISK — shared round-robin cursor.** `assignForInbound` advances the same per-tenant `LeadAssignmentPointer` the human create form reads, so inbound volume visibly changes what the form recommends to an admin. That is arguably correct (it *is* load), but nobody asked for it. A separate inbound cursor row is a one-column change if the owner wants it later.

### 6. The append-activity flow (owner decision 1)

**The match.**

```java
@EntityGraph(attributePaths = "assignedUser")
Optional<Lead> findFirstByPhoneNormalizedAndTenantIdAndDeletedAtIsNullAndLeadStageNotInOrderByCreatedAtDesc(
        String phoneNormalized, Long tenantId, Collection<LeadStage> excludedStages);
```

Called with `LeadStageGroups.TERMINAL_STAGES` — **never a hardcoded set**. That constant is already documented as the shared definition behind dedup, assignment and `uq_leads_*_open` (`LeadStageGroups.java:26-32`; `db/indexes.sql:80-87`); reusing it is the only way the service predicate and the index predicate cannot drift. Broader → we append to a CONVERTED lead; narrower → we fall through to create and Postgres throws a raw `DataIntegrityViolationException` (a 500 → the provider retries forever).

`findFirst` is mandatory even though the partial unique index guarantees at most one match: `db/indexes.sql` is an *operational* artifact (no Flyway, scan :111), so on a DB where it was never applied a plain `Optional` finder throws `NonUniqueResultException` — the exact trap `LeadRepository.java:53-63` documents.

| | Service | DB index (`db/indexes.sql:85-87`) |
|---|---|---|
| key | `phone_normalized` | `phone_normalized` (post-migration, §10) |
| tenant | `tenant_id = :tenantId` | `tenant_id` (index column) |
| trash | `deleted_at IS NULL` | `WHERE deleted_at IS NULL` |
| stage | `NOT IN LeadStageGroups.TERMINAL_STAGES` | `WHERE lead_stage NOT IN ('CONVERTED','LOST')` |

**The entity is already machine-ready — the service method is not.** `LeadLogServiceImpl.currentUser()` returns null instead of throwing (`:257-261`) and `:79-80` falls back to `addedByName = "system"`. But `addLog:65` calls `leadAccessGuard.requireVisible`, whose own `currentUser()` **throws** — unusable from a webhook thread. New method taking the **resolved entity**, so it is structurally impossible to call without having resolved under tenant scope:

```java
LeadLogResponseDto appendSystemLog(Lead lead, SystemLogCommand cmd);   // no guard, no reminder branch
```

**It must never be widened to accept a `UUID`** — that turns it into a scope-bypass primitive. It bypasses `LeadAccessGuard`, whose javadoc (`:16-31`) says every module resolves leads through it; the bypass is safe *only* because the argument is an already-resolved managed entity.

**`LeadLog` cannot carry campaign/recording data.** It has only `comment`/`stageSnapshot`/`followUpDate`/`addedByUserId`/`addedByName` (`LeadLog.java:37-55`), and rendering attribution into `comment` repeats the antipattern the scan names ("the 9-value enum is the entire provenance model", :87) — unqueryable, unreportable. Per canon, `LeadLog` gains exactly **three nullable columns**:

- `ingest_event_id BIGINT` — logical id of the `LeadIngestEvent` row (canon name; the draft's `inbound_event_id` is deleted)
- `source_integration_id BIGINT` — "which connection produced this", without a join
- `activity_kind` → new `LeadActivityKind {NOTE, INBOUND_ENQUIRY, INBOUND_CALL}`

**Campaign name / ad id / gclid / fbclid / recording URL live ONLY on `lead_attributions`.** `lead_ingest_events` purges at 30 days, so `LeadLog.ingest_event_id` points at the raw log and **must never be the sole path to attribution**. Duplicating attribution onto `lead_logs` would fork the campaign-grain model and create a fourth home for phone/campaign data.

`activity_kind` is a **new column**, so Hibernate generates its check constraint fresh — the `leads.lead_source` trap (scan :33) only bites *pre-existing* columns. **No `db/indexes.sql` block needed.** Existing rows read `NULL` → the mapper renders `NOTE`.

**Free win:** `addedByName` = the integration label ("JustDial", not "system") makes `LeadLogCardDto.latestLog.addedBy` (`LeadLogServiceImpl.java:211`) render correctly with **zero FE change**.

**Stage / `lastContactedAt`: neither.** `assertConversionStageTransitionAllowed` (`:425-437`) proves stage is a governed lifecycle owned by humans and the booking flow; a machine flipping `NEW_LEAD → CONTACTED` corrupts the metric (CONTACTED means *a human made contact*) and fires a spurious `LEAD_STAGE_CHANGED`. The LOST-reopen case never reaches append — terminal stages are excluded by the predicate, so a call from a LOST lead's phone **creates a new lead**, which is the repeat-business design (`db/indexes.sql:72-79`). `lastContactedAt` does not exist on `Lead` (verified across `Lead.java:44-131`) and must not be added: `LeadLog.createdAt` answers it and `findByLead_IdAndDeletedAtIsNullOrderByCreatedAtDesc` already serves it.

**Soft-deleted match → ignore and create.** `checkTrashedForRestore` (`:616-639`) throws a 409 carrying a publicId for a human to click — meaningless to a machine. Auto-restore reverses a deliberate human act (possibly a PII erasure) with no audit and can race the 30-day purge. Log-and-drop loses a real enquiry (scan :30). Ignore-and-create is **provably safe at the DB level**: both partial unique indexes are `WHERE deleted_at IS NULL` (`db/indexes.sql:82-87`), so a trashed row does not occupy the key and the insert cannot conflict. `checkTrashedForRestore` is an interactive-UX affordance, not an invariant — `IngestPolicy.INTERACTIVE` only.

**Append is not idempotent by nature** — it happily writes a second `LeadLog`. It depends **absolutely** on the ingestion section's `UNIQUE (integration_id, external_event_id)` dedup on `lead_ingest_events` (mirroring `existsByEventId`, scan :190-196). Without it, every provider retry produces a duplicate activity.

### 7. The email collision — OWNER CONFIRMATION NEEDED (red-team, hole 4)

Owner decision 1 settles the **phone** key and is silent on email. Matching phone-only while creation still enforces email uniqueness loses enquiries: Rahul has an open lead (`rahul@gmail.com`, `+919888888888`) from a web form, then enquires via JustDial from his office line `+912212345678`, same email. Phone match → none → create → the email branch at `LeadServiceImpl.java:586-596` throws `DuplicateLeadException` → 409 to JustDial → the enquiry vanishes, the exact failure scan :30 names. Skipping the service check does not help: `uq_leads_email_tenant_open` (`db/indexes.sql:82-84`) enforces it independently and converts the 409 into a raw 500.

**Design ruling, pending the owner's yes:** the machine match is **`phone_normalized`, then `email` (lowercased) as a fallback key**, both under the identical OPEN predicate of §6, and an email match **appends** exactly like a phone match. Rationale: it is the only rule the DB indexes agree with, it needs no new `LeadIngestOutcome` case (both arms are `APPENDED` / `LeadIngestStatus.APPENDED`), and the alternative — dropping the inbound email and creating phone-only — knowingly writes a lead with no email for a customer who supplied one and lets the two dedup branches disagree about one human. Phone is checked first (it is the owner's key); email is checked only when phone misses.

**Open risk:** this extends decision 1's *spirit* to a key the owner did not name. If the owner says no, the only DB-consistent alternative is (b) drop-the-colliding-email, and `LeadIngestOutcome` still needs no new case.

### 8. Notification

**Reuse `LEAD_CREATED` for machine-created leads.** A lead created by JustDial *is* a lead created — same triage need — and `publishLeadCreatedNotification` already resolves recipients correctly (`:641-649`). The double-notify risk (scan finding #7) is avoided **because** the machine path calls the same `createLeadInternal`, which publishes exactly once.

**Add a distinct `LEAD_ACTIVITY_APPENDED`.** Append is genuinely different (no new lead; an existing one got hotter) and must not masquerade as `LEAD_CREATED` — an admin clicking a "New Lead" toast to land on a 3-week-old lead is a bug. Recipients: **the lead's assigned owner only.** `LEAD_CREATED` fans to admins+managers because nobody owns it yet; an append already has an owner and that owner is who must call back. Fanning every repeat call to every admin is the spam that gets the bell muted.

Rules, all from the scan: `recipientUserIds` **always set explicitly** (the implicit fallback in `InAppNotificationChannel:49-51` resolves `TENANT_ADMIN` only and silently drops MANAGERs, scan :135); `IN_APP` alone (`IN_APP + SSE` double-pushes, :133; `EMAIL` sleeps ~6s synchronously on the publisher thread, :134); `referenceType = "LEAD"` is a valid constant (:136). The 4-key `payload` at `:669-674` is dead for `IN_APP` (:137) — leave it, do not rely on it.

**Publishing must not happen inside the ingest transaction (red-team, hole 6).** `NotifyEventListener` is a synchronous `@EventListener` that clears `TenantContext` on the publisher's own thread (`:37-39`) — so a publish from inside `createLeadInternal` nulls the context for the **rest of `ingest`**: the `LeadIngestEvent` status update is a `REQUIRES_NEW` write, and it would re-enter `TenantFilterAspect` with a null context and get **no tenant filter**. That is the identical mechanism this section diagnoses at `:109`, reintroduced one frame up. The draft's own rule ("publish last") is stated for `createLead` and violated by `ingest`, because `createLead` is no longer last.

**Rule:** under `MACHINE`, `createLeadInternal` and the append arm **publish nothing**. They attach fully-rendered `NotifyEvent`s to `LeadIngestOutcome.pendingEvents()`; the non-transactional gateway of §1 publishes them **after commit**. Free bonus: this also fixes "the SSE push fires before commit" (scan :131) for the inbound path. Under `INTERACTIVE`, publishing stays inside `createLead` as today (unchanged behaviour) but strictly last, after `recordAssignmentAudit`.

**Two rendering fixes at `:664`** — currently `lead.getLeadSource() + " lead from " + lead.getDepartCity() + …`:

1. String concatenation invokes `toString()` → `name()` → `"DIRECT_CALL"`. `@JsonValue` on `getDisplayName()` affects **Jackson only** — the notification has always shown the wrong vocabulary.
2. `departCity` is nullable (`Lead.java:88-89`) → `"DIRECT_CALL lead from null assigned to X"`. Webhook leads have **no `departCity` ever**, so this hits **100% of inbound** — cosmetic wart → default rendering.

```java
String from = lead.getDepartCity() != null ? " from " + lead.getDepartCity() : "";
.message(lead.getLeadSource().getDisplayName() + " lead" + from + " assigned to " + assignedTo)
```

### 9. Quota, the race, and the status contract

`enforceLeadQuota` runs first at `:84` and throws 403 (`:505-515`). A webhook caller cannot act on a 403, and **any non-2xx makes the provider retry forever**.

**Order inverts for MACHINE: match → quota → create.** An append creates no `Lead` row, consumes no quota, and must not be gated by it — blocking a repeat caller's activity log would lose the enquiry for no reason.

**Over-cap create → quarantine. Never bypass, never drop.** Bypassing makes `maxLeads` meaningless the moment a tenant connects JustDial, and inbound is exactly the volume the plan is priced on. Instead: the raw payload is already persisted as a `LeadIngestEvent`; set `status = QUARANTINED_QUOTA` (canon `LeadIngestStatus`), return **202** with `{"status":"QUARANTINED"}`, fire a distinct `LEAD_INGEST_QUOTA_BLOCKED` to TENANT_ADMIN (a revenue-loss event and the subscription module's ideal upgrade prompt), and expose an authenticated replay. This fixes "dropped enquiries are invisible" (scan :30) *and* keeps quota honest.

**The race, corrected (red-team, hole 3).** Two events from one number land 40ms apart, both miss the match, both create; the loser hits `uq_leads_phone_norm_tenant_open` and gets a raw `DataIntegrityViolationException` (scan :111). **The retry cannot live inside the transaction.** `BaseEntity:26-28` is `GenerationType.IDENTITY`, so `save()` flushes immediately and the violation *does* surface inside the try — but Hibernate's `ExceptionConverter` marks the session rollback-only, so the retry, the append, the quarantine write and the commit all die with `UnexpectedRollbackException` → 500 → infinite retries. The very outcome the retry exists to prevent, caused by the retry.

**Rule:** the retry loop lives in the **non-transactional gateway of §1**, which already owns `TenantContext`. Each attempt is its own transaction: attempt 1 → gateway catches `DataIntegrityViolationException` → re-enter `ingest` → the match now finds the winner → `APPENDED`. A second failure quarantines, and that quarantine write is a `REQUIRES_NEW` write **from the gateway**, never from inside a doomed transaction.

**Status-code contract.** 202 for anything authentic and parseable — the outcome lives in the body (`LeadIngestOutcome`: `CREATED | APPENDED | QUARANTINED_QUOTA | REJECTED_INVALID` + `leadPublicId`, mapped 1:1 onto `LeadIngestStatus`). Non-2xx means what it should: **401** on a bad/unknown ingest token (the provider *should* stop), **5xx** on a genuine fault (retry is correct).

### 10. Phone canonicalisation — shadow column, Java backfill, sequenced code change

Dedup compares `request.getPhone()` **raw** at `:601`; email is lowercased at `:590`. Without a fix, `+91 98765 43210` and `+919876543210` never match and **decision 1 silently never fires**.

**Asymmetric (ingestion-only) is not half a fix — it is harmful.** The strings stay different, so not only does append never trigger, `uq_leads_phone_tenant_open` **also** does not fire — two open leads for one human, the precise invariant the index exists to prevent. Rejected.

**Rewrite-in-place** is correct but too big and can abort halfway: `PhoneNormalizer`'s javadoc (`:13-17`) explains rewriting the stored format silently breaks matches against existing rows, and `Customer.phone` / `uq_customers_phone_tenant` / `Reminder.phone` key off the same strings (`BookingServiceImpl.resolveOrCreateCustomer:321-363`) — a non-atomic rewrite starts spawning duplicate customers, and can collide mid-migration against the live unique index. Rejected for this slice.

**Chosen: `leads.phone_normalized` shadow column** (canon: `VARCHAR(20)` nullable). `phone` keeps its exact bytes; `PhoneNormalizer.normalize()` (trim-only) keeps its documented contract untouched.

#### `canonical()` must be WRITTEN, not lifted (red-team, hole 5)

The draft asserted `WhatsAppMessagingService.normalize()` (`:145-156`) is "already a correct E.164 canonicalizer". **It is not**, and the scan named the exact input class (open question 7). Verified verbatim: strip `[^0-9+]`; `if (cleaned.startsWith("+")) return cleaned;` else strip leading zeros and return `props.getDefaultCountryCode() + cleaned`. There is **no branch detecting a country code present without a `+`**:

| input | `normalize()` output |
|---|---|
| `+91 98765 43210` | `+919876543210` |
| `9876543210` | `+919876543210` |
| `919876543210` | **`+91919876543210`** ← a fourth distinct value |

Indian providers routinely deliver the country code without a `+`. So a JustDial payload of `919876543210` never matches the human-typed open lead — append silently dead for that provider — and `uq_leads_phone_norm_tenant_open` does not fire either. It even passes the garbage check below: 14 digits after `+` satisfies `^\+?[1-9]\d{7,14}$`.

`PhoneNormalizer.canonical(String raw, String defaultCountryCode)` is a **pure static** in `common/util` that adds the missing branch: after stripping separators, if the value already starts with the default country code's digits **and** the remainder is a plausible national length, do not prepend. It ships with a table test over exactly the four forms above **before** anything delegates to it.

**Config: ONE key.** `app.phone.default-country-code` (default `+91`, fail-fast at boot if unset) is the single binding, owned by a thin `@Component PhoneCanonicalizer` so the util stays config-free. `app.whatsapp.default-country-code` (`application.properties:184`) is **retired** and `WhatsAppProperties.defaultCountryCode` deleted — `InteraktWhatsAppSender.splitCountryCode` (`settings/provider/InteraktWhatsAppSender.java:98-109`) **strips the configured code back off**, so two keys governing one algorithm silently misroute OTP delivery the day they diverge. The property rename is an operational step: miss it and the strip step sees a null.

**`WhatsAppMessagingService.normalize()` delegates** to `canonical()` — the scan's complaint is that three treatments coexist and disagree (:113); adding a fourth makes it worse, consolidating makes it better. **This is a LIVE outbound path** (OTP, quotation send, booking reminder, marketing dispatch — 4 callers, scan :45): the delegation is gated on the characterization test above, and the behaviour for `919876543210` **deliberately changes** (that was a bug in outbound too — it produced an unroutable number).

#### Sequencing — the code change is gated on the data (red-team, hole 7)

Repointing `:601` at `phone_normalized` on day one is a **live regression for human edits**, not a future risk. `updateLead:229` calls `validateNoDuplicates` before every mutation. A tenant with two live open leads for one person — `9876543210` and `+91 98765 43210`, both canonicalising to `+919876543210` — has both leads editable today; the day the switch ships, an agent pressing Save on either gets `DuplicateLeadException("An open lead already exists with phone: …")` and **both leads are un-editable** until a human closes one. Collision volume is unknown until the report runs, so the blast radius is unknown at the moment of introduction.

**Ship in two steps:**

**Step A (code deploy).** `Lead.phoneNormalized` (`ddl-auto` creates it). `LeadMapper.toEntity` and `updateLead:236` populate it via `PhoneCanonicalizer` — **write-only**. `:601` and `checkTrashedForRestore` stay on **raw** `phone`. The `PhoneNormalizedBackfillRunner` (one-shot `@ConditionalOnProperty`) backfills existing rows **in Java reusing `canonical()`** — a hand-written SQL expression that disagrees with the Java by one edge case *is* the migration risk; one implementation removes it. It logs a per-tenant collision report and exits. The **machine path reads the canonical column from day one** — it has no legacy rows to collide with, so decision 1 works immediately.

> This deviates from "schema deltas go in `db/indexes.sql`" for the **data** step only. The **index** is still hand-written SQL.

**Step B (after the report is clean, per DB).**

```sql
-- run the Java backfill FIRST, then eyeball the collision report:
--   SELECT tenant_id, phone_normalized, count(*) FROM leads
--    WHERE deleted_at IS NULL AND lead_stage NOT IN ('CONVERTED','LOST')
--    GROUP BY 1,2 HAVING count(*) > 1;
CREATE UNIQUE INDEX IF NOT EXISTS uq_leads_phone_norm_tenant_open
    ON leads (phone_normalized, tenant_id)
    WHERE deleted_at IS NULL AND lead_stage NOT IN ('CONVERTED', 'LOST');
-- DROP INDEX IF EXISTS uq_leads_phone_tenant_open;   -- only after the above is verified clean
```

Only then do `:601` **and** `checkTrashedForRestore`'s phone finder (`:628-638`) move to `phone_normalized` **together** (red-team, hole 10). Moving one and not the other splits the three coordinated dedup layers the scan documents (:107-109): trash a lead as `+91 98765 43210`, create one as `9876543210` — `validateNoDuplicates` (canonical) finds no open match and `checkTrashedForRestore` (raw) finds no trashed match, so the Restore affordance silently stops firing for exactly the format mismatches it exists for. No error, just a duplicate.

**Honest cost:** a collision is real data needing a human merge decision, and until it is resolved the new index cannot be created and Step B cannot ship on that DB. Volume unknown until the report runs against the pilot.

**The DTO regex is not relaxed.** `^\+?[1-9]\d{7,14}$` (`CreateLeadRequestDto.java:23-28`) is a **controller-boundary** constraint — it fires from `@Valid` at `LeadController.java:46`, never on a service call, so the machine path never evaluates it. `canonical()` strips the leading zero and prepends the country code, so an IVR `022 1234 5678` arrives as `+912212345678` and would satisfy it anyway. It stays strict for humans; ingestion runs its own check and quarantines a phone that canonicalises to garbage (`anonymous`, `private`, empty) as `REJECTED_INVALID`.

### 11. `email` NOT NULL → nullable

**The service is already null-tolerant:** `validateNoDuplicates` guards `if (request.getEmail() != null)` (`:587`) and `checkTrashedForRestore` does the same (`:617`). Postgres partial unique indexes treat NULLs as distinct, so N anonymous IVR leads coexist under `uq_leads_email_tenant_open` **with no change to that index**.

Placeholder emails are rejected: a constant one collides on the second anonymous caller (scan :241); a phone-derived one dodges the collision but is a lie in a column humans read and mail-merge to, would be delivered to by the marketing module, and lets the email and phone dedup branches disagree about the same human.

**The delta is NOT one line (red-team, hole 1 — FATAL).** `LeadMapper.toEntity` does `.email(request.getEmail().toLowerCase())` **unguarded** at `LeadMapper.java:23`, and `createLead` calls it at `LeadServiceImpl.java:93`. Every anonymous phone-only lead NPEs **before the entity is built** — the flagship capability failing on its first request, and under the §9 contract an NPE is a 5xx, so the provider retries forever. The draft never opened `LeadMapper`; the scan had already warned it is hand-written with "no compile error if you forget" (:103).

Deltas:

- `Lead.java:50` — drop `nullable=false` from `email`.
- **`LeadMapper.java:23`** — null-guard the `toLowerCase()`. *(Also the mapper's other create-path dereferences: `getPhone()` at `:22` is safe today because a `Lead` with no phone is not a case the machine produces — ingestion quarantines a blank phone before `createLead`.)*
- `LeadServiceImpl.java:235` — `request.getEmail().toLowerCase()` in `updateLead` NPEs on null; make it null-safe.
- Keep `@NotBlank` on `CreateLeadRequestDto:31` — the human/machine asymmetry comes free precisely because bean validation does not run on a service call.
- `db/indexes.sql`, by hand (`ddl-auto` will **not** do it): `ALTER TABLE leads ALTER COLUMN email DROP NOT NULL;`
- FE: `LeadResponseDto.email` is now nullable — null-safety is required in the lead list/detail before this ships.

> **Deploy-order hazard (highest-likelihood failure in this subsystem).** If that `ALTER` is missed, every anonymous IVR insert fails with a raw constraint violation → 500 → infinite provider retries. Nothing enforces the order; it must be documented in `db/indexes.sql` itself.

### New files

| Path | Purpose |
|---|---|
| `lead/ingest/LeadActor.java` | `(Long userId, String displayName, LeadOrigin origin, Long integrationId)`. **Un-forgeable**: package-private ctor, factories `human(User)` / `integration(Long, String)` / `system(String)` only. |
| `lead/ingest/IngestPolicy.java` | `INTERACTIVE | MACHINE` — the **three**-branch flag (quota order, `checkTrashedForRestore`, assignment). |
| `lead/ingest/LeadIngestionService.java` | The `@Transactional` machine facade: match-or-create, quota→quarantine. Called **cross-bean** with `TenantContext` already set. **No retry loop, no publish** — both live in the gateway. |
| `lead/ingest/InboundLeadCommand.java` | Machine input: canonical phone, optional email, name, `LeadSource`, `LeadType`, integration id, ingest-event id. **No `leadStage` field** (§below). |
| `lead/ingest/LeadIngestOutcome.java` | `CREATED | APPENDED | QUARANTINED_QUOTA | REJECTED_INVALID` + `leadPublicId` + `pendingEvents()` (published after commit). |
| `lead/ingest/SystemLogCommand.java` | Input to `appendSystemLog`: comment, `activityKind`, `ingestEventId`, `sourceIntegrationId`, `authorDisplayName`. |
| `lead/entity/LeadActivityKind.java` | `NOTE | INBOUND_ENQUIRY | INBOUND_CALL` — new enum on a **new** column; Hibernate generates its check fresh, no `db/indexes.sql` block. |
| `common/util/PhoneCanonicalizer.java` | `@Component` binding `app.phone.default-country-code`, delegating to the pure static `PhoneNormalizer.canonical(raw, cc)`. |
| `lead/ingest/PhoneNormalizedBackfillRunner.java` | One-shot `@ConditionalOnProperty` backfill of `leads.phone_normalized` reusing `canonical()`; logs the per-tenant collision report. |

`lead/ingest/ActorOrigin.java` from the draft is **deleted** — the canon enum is `lead/enums/LeadOrigin.java` (owned by the data-model section).

### `leadStage` for inbound — hardcoded `NEW_LEAD`

`InboundLeadCommand` carries **no stage field at all**. `createLead` never calls `assertConversionStageTransitionAllowed` — that guard covers *transitions* only (`:425-437`, reached from `updateLead:240` and `updateLeadStage:394`) — so a create can legally set `CONVERTED`, and any inbound payload able to influence stage is a way to mint a CONVERTED lead with **no booking**, the exact invariant `:417-424` protects. Omitting the field is cheaper and stronger than validating it: an absent capability, not a default. `NEW_LEAD` is in `ACTIVE_STAGES` (`LeadStageGroups.java:39-40`), so the new lead immediately occupies the phone key and the next call from that number appends — decision 1 working.

### Touched files

| Path | Change | Risk |
|---|---|---|
| `lead/service/LeadService.java` | **Widens** — add `createLead(dto, LeadActor, IngestPolicy)`. | The public contract grows a method that takes a caller-supplied actor. Mitigated only by `LeadActor` being un-forgeable. |
| `lead/service/LeadServiceImpl.java` | 1-arg `createLead` delegates to the 3-arg one. Quota / trash / assignment / publish become policy branches. `recordAssignmentAudit` takes `LeadActor`, drops `currentUser()` at `:119`. `:108`/`:109` swapped (audit before publish). `:664` uses `getDisplayName()` + guards null `departCity`. `:235` `getEmail().toLowerCase()` null-safe. `:601` → `phone_normalized` **in Step B only**. | **HIGHEST BLAST RADIUS.** The 108/109 swap changes which side of `TenantContext.clear()` the audit lands on — a fix, but behaviour-changing for anything that (wrongly) relied on the cleared context. |
| `lead/mapper/LeadMapper.java` | **Null-guard `.email()` at `:23`** (FATAL if missed). New signature `toEntity(dto, LeadActor, phoneNormalized)` stamping `origin`, `sourceIntegrationId`, `phoneNormalized`. `toResponse` unchanged for now. | Hand-written, no compile safety (scan :103). If `origin` is not stamped, the data-model section's `SET NOT NULL` breaks **both** create paths. |
| `lead/controller/LeadController.java` | **Zero change.** `@PreAuthorize("LEAD_CREATE")` at `:44` untouched. | None. |
| `lead/repository/LeadRepository.java` | Add the `findFirstByPhoneNormalized…LeadStageNotInOrderByCreatedAtDesc` finder (`@EntityGraph("assignedUser")`) + the `phoneNormalized` `existsBy…` twins of `:81-82` / `:88-89`. | Low, additive, mirrors two existing idioms. **Must not** be a plain `Optional` finder. |
| `lead/entity/Lead.java` | Add `phoneNormalized` (`length=20`, nullable). Drop `nullable=false` from `email` at `:50`. (`origin`, `sourceIntegrationId` are the data-model section's columns.) | `ddl-auto` will **not** drop the email NOT NULL — manual, un-skippable. |
| `lead/entity/LeadLog.java` | Add 3 nullable columns: `ingestEventId`, `sourceIntegrationId`, `activityKind`. | Low — additive; `ddl-auto` handles the new column + fresh check. Existing rows read NULL → `NOTE` at the mapper. |
| `lead/service/LeadLogServiceImpl.java` | Add `appendSystemLog(Lead, SystemLogCommand)` — no `LeadAccessGuard`, no reminder branch, `addedByUserId=null`, `addedByName` = integration label. | Medium. Deliberately bypasses the guard (javadoc `:16-31` says every module goes through it). Safe **only** because it takes a resolved managed entity. **Never widen to a `UUID`.** |
| `lead/assignment/service/LeadAssignmentService.java` | Add `assignForInbound(Long tenantId, Long configuredOwnerUserId)`. Never throws on an empty pool. | Medium — shares the round-robin cursor with the human path (ACCEPTED RISK, §5). |
| `common/util/PhoneNormalizer.java` | Add pure static `canonical(raw, cc)` with the missing bare-country-code branch. `normalize()` (trim-only) untouched. | The javadoc at `:13-17` must be amended to say the migration is happening via a shadow column, not a rewrite — else the file contradicts itself and the next reader reverts one of the two. |
| `settings/service/WhatsAppMessagingService.java` | `normalize()` (`:145-156`) delegates to `canonical()`. | LIVE outbound path, 4 callers (scan :45). Behaviour for `919876543210` intentionally changes. Gated on a characterization test. |
| `settings/provider/InteraktWhatsAppSender.java` + `WhatsAppProperties` + `application.properties:184` | Retire `app.whatsapp.default-country-code`; `splitCountryCode` (`:98-109`) reads the single `app.phone.default-country-code`. | Operational: miss the property rename and the strip step sees null → wrong `countryCode` to Interakt. Boot fail-fast if unset. |
| `src/main/resources/db/indexes.sql` | `ALTER TABLE leads ALTER COLUMN email DROP NOT NULL;` then, **after** the Java backfill and a clean collision report, `uq_leads_phone_norm_tenant_open`; `uq_leads_phone_tenant_open` dropped only after that. | Operational, not a code deploy (no Flyway) — so it can be forgotten, and both omissions only surface on a real tenant. Creating the index before the backfill completes aborts on collisions. **Order is load-bearing and must be documented in the file itself.** |

### Dependencies on other sections

- **`LeadIngestEvent` idempotency** — `UNIQUE (integration_id, external_event_id)` (mirroring `existsByEventId`, scan :190-196). Decision 1 depends on it **absolutely**: without it every provider retry writes a duplicate `LeadLog`.
- **`LeadAttribution`** — campaign name / ad id / gclid / fbclid / recording URL. `LeadLog.ingest_event_id` points at the raw event, which purges at 30 days; it must never be the sole path to attribution.
- **The non-transactional gateway** — owns tenant resolution, `TenantContext`, the race retry, the `REQUIRES_NEW` quarantine write, and the post-commit publish.
- **Rate limiting on `/api/webhooks/**`** — `RateLimitFilter` covers auth paths only (`:52-56`, scan :69).
- **FE null-safety on `email`** — now nullable in `LeadResponseDto`.

### Open risks

1. **The email-collision rule (§7) needs the owner's yes.** It extends decision 1's key set beyond what the owner named.
2. **Collision volume is unknown** until `PhoneNormalizedBackfillRunner` runs against the pilot DB. Step B cannot be scheduled until it does.
3. **UNVERIFIED (scan :33): whether `leads_lead_source_check` exists on the pilot DB.** Not this section's column, but the same `leads` table — resolve it with `SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='leads'::regclass AND contype='c';` before any `leads` DDL ships.
4. **Plausible national length in `canonical()`** is a heuristic. For `+91` it is unambiguous (10 digits); for a tenant configured to a country with variable national lengths it can mis-fire. Single-country pilot makes this safe now; a second country code is a **research task**, not an assumption.

---

## Per-channel mechanics

### 0. The invariant every channel obeys

The adapter is the only per-channel class. Everything below the adapter is framework and **must not vary per channel** — tenant resolution, TenantContext ordering, the raw-payload store, dedup/append, quota, notification.

```
gateway (NON-transactional)
  resolve LeadSourceIntegration            ← derived finder on the token hash / external_account_id
  verify per row.verificationMode          ← fails CLOSED
  adapter.parse(RawInbound) → InboundParseResult
  TenantContext.setTenantId(row.getTenantId())    ← UNCONDITIONAL overwrite, BEFORE the tx
  call the @Transactional pipeline               ← filter latches HERE
  return; ContextCleanupFilter clears
```

**TenantContext must be set before entering any transactional method.** `TenantFilterAspect:34-37` returns without enabling `tenantFilter` on a null tenant, and the advice is `@Before` on `@Transactional` (`:22`) — setting the context inside the service is too late for that entire transaction, and the failure is silent. Verified verbatim.

**The overwrite is unconditional and the inbound context is never read.** `JwtAuthFilter` runs on permitAll paths and will authenticate a Bearer token sent to a webhook, setting TenantContext from the caller's own token (scan `:75`, `JwtAuthFilter:89-90`). An adapter that trusts an inherited context processes an attacker-chosen tenant. Do not clear manually — `ContextCleanupFilter` is auto-registered at `/*` at `HIGHEST_PRECEDENCE+1` with an unconditional `clear()` in `finally` (`:52,59-64`).

**The resolver's global probe is deliberate and must be javadoc'd.** It runs before any context exists, so the tenant filter is off and the query spans all tenants. That is safe **because `ingest_token_hash` is globally unique**, not because a filter scoped it. `findByIngestTokenHashAndDeletedAtIsNull` is not `findById`, so `TenantIsolationArchTest:61` does not fire — but the intent is the same bypass and reviewers deserve the note. Do not solve any resolution problem with `findById`: the test's javadoc at `:52-55` forbids the `EXEMPT_CLASSES` escape (verified).

**Adapters are PURE and ArchUnit-enforced** — no repository, no `EntityManager`, no `TenantContext`, no Spring data access under `leadsource.adapter..`. Adapter purity *is* the tenant-isolation argument. Consequences for placement (red-team MINOR):

| Artifact | Package | Why not `adapter..` |
|---|---|---|
| Webhook controllers | `leadsource/web/` | must set TenantContext → fails the purity rule on day one |
| Entities | `leadsource/domain/` | repository access → fails the purity rule |
| Graph / provider HTTP clients | `leadsource/client/` | IO; called by the fetcher, never by `parse` |
| Adapters | `leadsource/adapter/<vendor>/` | pure functions only |

**Adapters never receive the managed entity.** `RawInbound` carries body bytes + lazy typed accessors + an **immutable `IntegrationCredentials` view**. The resolver is non-transactional and `spring.jpa.open-in-view=false`, so the entity is **detached** — any lazy field an adapter touched would throw `LazyInitializationException` on the ingest thread, a failure no byte[]-fixture unit test reproduces. The immutable view makes detachment moot.

**Raw bytes are mandatory.** Meta's HMAC is over the exact received bytes; a re-serialized body breaks it. `RazorpayWebhookController:29-33` binds `@RequestBody byte[]` with `consumes = ALL_VALUE` for exactly this reason (verified).

**There is no adapter base class.** "One adapter class and nothing else" is achieved by making the adapter *ignorant*, not by making it *inherit*. A base class would let an author override the TenantContext sequencing — the one thing that must never vary. The only shared helper worth having is a field-alias lookup for the marketplace group, holding no tenant/transaction/context logic.

---

### Channel roster

| Slug | Mode | URL | Verification | Phase |
|---|---|---|---|---|
| `justdial` `indiamart` `tradeindia` `sulekha` | TOKEN | `/api/webhooks/leads/{channel}/{token}` | research task | 2 |
| `google_ads` | TOKEN | `/api/webhooks/leads/google_ads/{token}` | `SharedSecretInBody` | 2 |
| `travel_marketplace` | TOKEN | `/api/webhooks/leads/travel_marketplace/{token}` | research task | 2 |
| `ivr_call` | TOKEN *(provisional)* | `/api/webhooks/leads/ivr_call/{token}` | research task | 2 |
| `meta_ads` | PROVIDER_ACCOUNT | `/api/webhooks/leads/meta_ads` | `HmacHeader("X-Hub-Signature-256","HmacSHA256",HEX,"sha256=")` | 3 |
| `website_form` | TOKEN (siteKey) | `/api/ingest/forms/{siteKey}` | `TokenOnly` | 3 |
| `instagram_dm` `fb_messenger` | PROVIDER_ACCOUNT | — | — | out of scope |
| `whatsapp` | — | — | — | out of scope |

`LeadSourceChannel.fromSlug(pathSegment)` looks up the registry; the path segment is compared to `row.getChannel()` (a String) — **mismatch → 404, never 400**. A 400 confirms the token exists for a different channel.

---

### A) Marketplace push — `justdial` `indiamart` `tradeindia` `sulekha`

Per-adapter surface: **the field map, and nothing else** (~20 lines each).

**All four payload shapes, delivery guarantees and auth mechanisms are RESEARCH TASKS.** They are not knowable from this codebase and are not invented here. The field map is the research deliverable.

`dedupKey` should be the provider's own lead id. Fall back to a body hash **only** when there is none — the Razorpay precedent's `"sha256:" + hash(rawBody)` fallback (`SaasPaymentServiceImpl:190-196,396-401`) wrongly dedupes a provider redelivering a byte-identical payload for a genuinely new event (scan `:60`).

**One flag that may dissolve this group:** IndiaMART is widely believed to be a **pull/poll API**, not a webhook push. **RESEARCH TASK.** If true it is not a group-A channel at all — it needs a polling scheduler with a per-integration cursor, a different mechanism at a different cost. **Verify before estimating group A as one homogeneous unit.**

---

### B) `google_ads` — Lead Form Extensions

`verification() = SharedSecretInBody("google_key")` — a shared secret, not an HMAC. Two consequences:

1. **Constant-time compare, never `equals`.** Reuse the `MessageDigest.isEqual` idiom at `RazorpayGatewayClient:160-166`. Fail closed on a null/blank stored secret, exactly as `verifyWebhookSignature:117-120` does.
2. **The secret is inside the thing we archive.** `secretFieldPaths() = Set.of("google_key")` — redacted before `lead_ingest_events.raw_payload` is persisted. Meta's secret is never in the body, so "store the raw payload" has a Google-shaped exception.

`google_key` lives in `credentials_enc` via `AesSecretCipher` (AES-256/GCM, 12-byte IV, key validated at boot — `:25-27,45-60`). **Landmine:** there is no `AttributeConverter` and no `@Convert` anywhere — encryption is opt-in per call site, so a new secret column silently stores **plaintext** if a site forgets (scan `:47`). Adopt the write-only-secret contract verbatim (`apiKeyChanged` + `apiKeySet`, `WhatsAppConfigService:55-58`).

Mapping: `lead_id` → dedupKey; `user_column_data[].{column_id → string_value}` → name/phone/email/city/requirement via a **per-integration column_id map** in `config_json` (plaintext, FE-returnable); `campaign_id`/`form_id`/`gcl_id` → `LeadAttribution`. Google's standard column_id set is a **RESEARCH TASK** — ship a default map plus a per-integration override, because column_ids are per-form.

Returns `Complete`. Google expects a fast 200; the pipeline's work is short, so synchronous is correct here — unlike Meta.

---

### C) `meta_ads` — PROVIDER_ACCOUNT

**Owner decision 2 is honoured, not excepted.** The body supplies `entry[0].id` (a page id) as an **account key, never a tenantId**. That key is only a lookup into a `LeadSourceIntegration` row an **authenticated tenant created**, and the platform HMAC proves the body's authenticity **before** the lookup runs. This is the Razorpay variant the scan names as the only one an attacker cannot steer (`SaasPaymentServiceImpl:237-241`). `accountKey(RawInbound)` is a **pure key-extractor the gateway calls** — not an adapter that resolves. There is **no MetaPageBinding entity**: Meta is a row with `resolution_mode=PROVIDER_ACCOUNT` and `external_account_id=<page_id>`.

**`page_id` is public. It must never authenticate anything.** The HMAC is the entire gate. If verification is ever weakened — "skip when the header is absent" is the classic — any internet caller injects leads into any tenant by guessing a page id. `verificationMode` is stored on the row, so a payload arriving **without** a signature header is **REJECTED, not downgraded**; otherwise the strongest channel degrades to the weakest at the attacker's option.

**The security posture is a real downgrade and must be stated:**

| | TOKEN channels | `meta_ads` |
|---|---|---|
| Secret | per tenant | **one platform app secret** |
| Compromise blast radius | one tenant | **forge inbound for every tenant** |
| Storage | `credentials_enc`, per row | **env-only; never in DB, never per-tenant** |

**The GET handshake.** `SecurityConfig.java:93` is `.requestMatchers(HttpMethod.POST, "/api/webhooks/**")` — verified. GET falls to `.anyRequest().authenticated()` (`:108`) and 401s. Fix is one narrow line: `.requestMatchers(HttpMethod.GET, "/api/webhooks/leads/**").permitAll()`. **Do not drop the `HttpMethod.POST` qualifier at `:93`** — that opens Razorpay's webhook and every future webhook to unauthenticated GET.

The handshake returns `Echo(byte[], "text/plain")` — the bare `hub.challenge`, **not** `ApiResponse`. This is a documented envelope exception and must carry a javadoc, or a reviewer will "fix" it to the envelope and silently break re-registration.

**Deferred, and where the handle lives.** The webhook carries only `leadgen_id` + `page_id` + `form_id`; the lead body needs a Graph fetch. `parse` returns `Deferred(List<FetchHandle>)`; **the pipeline — not the adapter — writes the durable row**, which is `LeadIngestEvent` with `status=DEFERRED`, `attempt_count`, `next_retry_at`. **There is no separate job entity** (the canon's five entities are exhaustive); the handle is reconstructed by re-parsing `raw_payload`, which is exactly why `parse` is specified replayable and pure.

`MetaLeadAdsFetcher implements LeadSourceFetcher` does the Graph call on a background thread in its own transaction. `FetchHandle` persists **only the provider pointer, never credentials** — the drain scheduler re-resolves the integration row per attempt (inside the tenant scope, **outside** the transaction, **cross-bean**, copying `UsageAlertScheduler:36-46`), so a tenant who reconnects Meta after a token expiry has queued handles succeed. A soft-deleted or disabled integration **abandons** its pending handles with a terminal status, **not FAILED** — it must not retry.

**PROVISIONAL — three research tasks on the critical path, and the section says so plainly rather than laundering them into fact:**

1. **Is the callback URL app-level only?** This is the premise for PROVIDER_ACCOUNT mode existing at all.
2. **What are the actual failure-count / subscription-disable semantics?** The claim that repeated 5xx causes Meta to disable the subscription **for the whole app** — one tenant's expired token killing inbound for every tenant — is the sole justification for always-ack-200 + the drain path. The scan confirms retry infra is absent everywhere (`Retry / backoff / DLQ | BUILD | Nothing anywhere has attempt_count, next_retry_at, or max_attempts`), so this is a greenfield cost booked on an unconfirmed premise.
3. **Page-token expiry semantics.** The ~60d figure is true of long-lived *user* tokens; *page* tokens derived from one are commonly non-expiring. **Do not build a 60-day assumption into a scheduler.** Treat a Graph `401`/subcode `190` as the **authoritative** liveness signal and `credentials_expire_at` as a hint.

**We do not auto-refresh.** Minting a new long-lived token requires fresh user authorization; it cannot be done unattended. An auto-refresh job would be a lie that fails exactly when it matters. Detect and escalate: on 401/190 set `status=DEGRADED`, surface it on the Integrations card (a dead integration must be **visible**, not notified once), and fire a NotifyEvent — `IN_APP` alone, explicit `.tenantId(...)`, explicit `.recipientUserIds(...)`, **never `EMAIL`** (see cross-cutting).

---

### D) `ivr_call`

Normalized shape: caller phone, called DID, direction, start time, duration, status, recording URL, agent. **All provider specifics (Exotel / MyOperator / Knowlarity) are RESEARCH TASKS** and the three are *not* interchangeable behind one adapter.

**The routing question is per-provider and decides IVR's cost. RESEARCH TASK.** If the provider lets a tenant configure a webhook URL per DID, TOKEN mode works and IVR is group-A-shaped. If not, IVR needs PROVIDER_ACCOUNT with `external_account_id = <DID>` and `caller_did` on the attribution row. Verify before estimating.

**Email: nullable, not synthesized.** The draft's synthetic `<e164>@no-email.invalid` claimed an emergent property that **does not exist**, and the claim is deleted. Traced: a second call from the same number produces the same synthetic address, hits `validateNoDuplicates` whose **email branch is checked first** (`LeadServiceImpl:587-596`), and throws `DuplicateLeadException` → 409. If the service check is skipped, `uq_leads_email_tenant_open` throws a raw `DataIntegrityViolationException` → 500 → the provider retries forever. A third rejection the draft never mentioned: a synthetic address whose lead is in Trash throws `RestoreAvailableException` (409) from `checkTrashedForRestore:616-639` — which a machine cannot act on. **Dedup is a rejection mechanism; a collision never produces an append** (scan finding #4). The append fires because the pipeline **looks the lead up by `phone_normalized` first** — the index contributes nothing but a rejection.

Canon settles it: **`leads.email` drops NOT NULL** and `NormalizedLead.email` is nullable. This is argued from verified code — `LeadServiceImpl:587` and `:617` both already guard `if (email != null)`, so the service needs no change — and Postgres partial unique indexes treat NULLs as **distinct**, so N anonymous IVR leads coexist under `uq_leads_email_tenant_open` (`src/main/resources/db/indexes.sql:82-84`) with no index change. Dropping the NOT NULL is a hand-written `ALTER` in `indexes.sql`; `ddl-auto=update` will not perform it.

**Phone matching is the real dependency.** IVR delivers E.164. Lead dedup passes the phone **raw** (`LeadServiceImpl:601`, verified) with no normalisation, and the DTO regex `^\+?[1-9]\d{7,14}$` (`CreateLeadRequestDto:24-27`) rejects the spaces, dashes and leading zeros telephony sends. So `+919876543210` and `9876543210` are different leads, the append never fires, and there is **no error** — just a silent duplicate. `phone_normalized` (canon, on `Lead`) is the fix and the repeat-lookup runs on it. This is a hard dependency of this subsystem, owned by the pipeline section; named here as a consequence.

**Store the recording URL, never the bytes.** The portal-bytea precedent does not transfer: those are traveler-uploaded documents we are the system of record for. A call recording is provider-hosted with its own retention. Fetching means an authenticated multi-MB download on an ingest thread, with no retry infra, consuming the tenant's **metered storage byte quota** — inbound calls would silently eat the storage cap. So `recording_url` + `recording_url_expires_at` on `lead_attributions` (expiry semantics **UNVERIFIED per provider**). Archiving goes behind a per-integration flag, deferred.

**Owner decision, not a code decision:** an Indian call recording is personal data under the DPDP Act. Who may replay it, for how long, and does a provider-hosted URL on a lead row satisfy that? Flagged, not resolved.

---

### E) `website_form` — `/api/ingest/forms/{siteKey}`

`{siteKey}` is `external_account_id`, is **PUBLIC by construction**, and is **NEVER the ingest token**. Reusing the token would publish a tenant's webhook credential on their own homepage.

**A browser POST is blocked today and cannot be unblocked by whitelisting `*`.** Verified: `.requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()` is **GET-only** (`SecurityConfig.java:95`) — a `POST /api/public/…` compiles, deploys and **401s** via `:108`. And the one global CORS bean at `/**` sets `allowCredentials=true` (`:128-139`), which makes `*` categorically illegal in browsers. Resolution: a new `IngestSecurityConfig` at `@Order(0)` with `securityMatcher("/api/ingest/**")`, copying `PortalSecurityConfig`.

**The embed asset MUST be served from `GET /api/ingest/v1/lead-form.js` — under the securityMatcher, not `/static/`.** Verified: `src/main/resources/static/` does not exist, and a grep for `WebSecurityCustomizer|web.ignoring|PathRequest|StaticResourceRequest|spring.web.resources|static-locations` returns **zero hits** across the repo — **nothing exempts static resources from the staff chain**. An asset at `/ingest/v1/lead-form.js` does not match `/api/ingest/**`, falls through to the `@Order(2)` chain, and hits `.anyRequest().authenticated()` at `:108`. Every anonymous visitor's browser would receive 401 + an ApiError envelope, the script would never execute, no form would ever be intercepted, and **zero leads would be ingested — while every server-side test of the POST endpoint passes**. This is the `SecurityConfig.java:95` trap reproduced one prefix over. Serve it from a controller under `/api/ingest/**`, set `Cache-Control` explicitly (Boot's resource-handler caching is forfeited), and **add an integration test that GETs the asset with NO Authorization header and asserts 200 + a JS content type.**

**CORS: a plain object, and the true rationale.** Pass it explicitly:

```java
http.securityMatcher("/api/ingest/**")
    .cors(c -> c.configurationSource(ingestCors()));   // NOT a @Bean
```

The draft's rationale — that a second `CorsConfigurationSource` bean fails startup with `NoUniqueBeanDefinitionException` — is **false, and was never verified**; reading two `@Configuration` files cannot establish framework-internal resolution. `CorsConfigurer.getCorsFilter` resolves **by bean NAME**: explicit `configurationSource` → `corsFilter` → the bean literally named `corsConfigurationSource` → MVC fallback. There is no `getBeanNamesForType` call. `SecurityConfig.java:129` declares exactly that name, so a second bean named `ingestCors` is **inert and the app boots**. Keep the plain object (it is correct and clearer), but the **real hazard is any future `@Configuration` declaring a bean named `corsConfigurationSource`**, which silently swaps global CORS for the staff and portal chains. `allowCredentials=false` here is what makes a per-integration origin allowlist expressible at all, and is correct — an embedded form has no session to send.

**The honest posture: anyone can POST leads to that tenant's endpoint.** CORS is not a control — a plain HTML `<form>` POST and any server-to-server POST are not subject to it (scan `:160`). Origin allowlist + rate limit + honeypot raise the cost of casual abuse; **nothing authenticates the submitter.** True of every embedded-form SaaS; not a defect; must be conscious and documented.

- **Honeypot:** hidden field injected by the JS; non-empty ⇒ drop, silently 200 so bots learn nothing.
- **Timing:** trivially forged unless the render timestamp is server-issued and signed. Shipping a client-stamped timestamp as a *gate* is theatre. Honeypot + rate limit are the real v1 defenses; timing is telemetry.
- **Captcha:** nothing exists — grep across both repos returns zero. Design a `CaptchaVerifier` SPI with a no-op default via `@Bean` + `@ConditionalOnMissingBean` **inside a `@Configuration`** (`PortalPaymentConfig:10-23`; its javadoc warns the idiom is "only reliable in the configuration phase" — **never on a `@Component`**). Provider choice is the owner's.

**Embed snippet — static asset, backend-generated block.** A JS snippet, not a raw `<form>`: a raw form-post cannot inject a honeypot dynamically, has no async success state, needs a hosted thank-you page, and is not subject to CORS at all. The **asset is static and versioned** (per-tenant generated JS is uncacheable and a code-injection surface for no benefit — the site key is a `data-` attribute). The **copy-paste block is backend-generated**, because *the frontend constructing a public URL is already a known failure mode here*: `getShareLink()` emits the PDF API URL and the backend never generates `/q/{publicId}` at all (`QuotationServiceImpl:494-502`, verified).

**The base URL is a NEW `app.ingest.base-url` — never `app.public-base-url`.** The latter is doubly-bound: the share-link base (`QuotationServiceImpl:66`) **and** the Razorpay webhook base (`application.properties:206-209,268`), so repointing it breaks the registered payment webhook. `ProductionConfigValidator:104-108` already hard-stops boot on a localhost/`http://` value for it — `app.ingest.base-url` deserves the same guard.

---

### F) `weblink_enquiry` — **canon conflict, unresolved**

**OPEN RISK — needs an architect ruling before this channel can be specified.** Canon fixes **three** URL shapes and states *"publicId appears in none of these."* The draft's `POST /api/ingest/quotations/{publicId}/enquiry` is a **fourth URL and carries publicId** — it cannot be rendered as canon. Two readings, and I will not invent the answer: either the quotation enquiry is out of scope for the canonical URL set, or canon needs a fourth shape. **Flagged, not resolved.**

What is settled and survives either ruling:

- **It appends; it does not create.** `Quotation` already carries `leadId` (`:56-57`), `leadPublicId` (`:60-61`) and `customerName/Phone/Email` (`:109-116`), so the quotation's publicId already identifies tenant + customer + lead. `leadId != null` → append a `LeadLog` to that lead. `leadId == null` → create with `source=WEBLINK_ENQUIRY`. The append is the **primary path**, not a fallback — the lead already exists, and creating one would manufacture the exact duplicate owner decision 1 exists to prevent.
- **No ingest token, and adding one would be decorative.** The page's security posture already *is* the capability URL — `PublicQuotationController`'s javadoc says so outright (`:23-30`). A second credential guarding a door whose key is already the URL, delivered *in that same URL*, buys nothing.
- **`LeadLog` needs no schema change** — verified: `addedByUserId` is nullable (`:50-51`) and `addedByName` is a free String (`:54-55`), so `addedByName = "Quotation web view"`, `addedByUserId = null`. It extends `BaseTenantEntity`, so the builder needs an explicit `.tenantId(...)`. `WeblinkAnalyticsService:30-43,64` is the directly reusable shape for writing tenant data from a public thread.
- **`origin = SYSTEM`**, no integration row, always-on — matching the owner's definition of SYSTEM ("machine-made but have no integration row"). If a tenant-facing toggle is wanted it belongs on `TenantSettings`, not a fake integration row. `origin` is shared with the pipeline section and needs their agreement.
- **The honest counterweight.** `getShareLink()` emits the PDF URL, not `/q/{publicId}` (`:494-502`), and `app.public-base-url` cannot be repointed at the frontend origin without breaking the Razorpay webhook. **Tenants may not be distributing the page that hosts this CTA at all** — cheapest to build, possibly lowest-volume. Judge it on what it de-risks, not on lead count, and consider fixing share-link generation alongside it.
- **FE cost:** the page makes exactly one network call today and every CTA is a `tel:`/`wa.me`/`mailto:` deep link (`QuotationWebView.jsx:646-662`, `:94`). This is its first POST — no error-handling or toast precedent exists on the page, and these pages are not on the shared `apiError`/toast contract.

---

### G) `whatsapp` / `instagram_dm` / `fb_messenger` — out of scope for v1

**WhatsApp: participate, do not migrate.** This framework is **inbound ingestion**; the existing WhatsApp stack is **outbound messaging** with four unrelated callers (OTP, quotation send, booking reminder, marketing dispatch). Migrating it would couple **OTP delivery — traveler login — to the lead pipeline**, so a change to lead ingestion could break authentication. Categorically the wrong direction. The scan's "the SPI is a single-`@Primary` swap unusable as a registry" is true and **not a defect**: `@Primary` + `@ConditionalOnProperty` (`InteraktWhatsAppSender:34-36`) is the correct shape for one provider per deployment.

Blocked on two missing prerequisites, not on design:
1. **No provider message id is captured on send** — `InteraktWhatsAppSender` discards the response body, so nothing correlates a callback to a log row (scan `:55`). This is precisely what `PaymentTransaction.gatewayOrderId` does for Razorpay and why that webhook *can* resolve a tenant.
2. **`TenantSettings.whatsAppPhone` is permanently null** — the scan verified by grep that **no `setWhatsAppPhone` call exists anywhere**; `WhatsAppConfigRequest` has no phone field and the FE form has no input (`:56`).

Without (2) an inbound WhatsApp webhook **cannot resolve a tenant at all**. Roadmap: add a phone field + FE input + a **globally-unique index on `tenant_settings.whatsapp_phone`** by hand — doubly needed because `tenant_settings` has **no unique constraint on `tenant_id` either** (one-per-tenant is convention via `findByTenantId` + `orElseGet`, so a race creates duplicates and `findByTenantId` then throws, scan `:203`). `WHATSAPP` stays **manually selectable** (owner decision 4) and human-typed — `origin = MANUAL` — until that mapping exists.

> Do not mistake `features/leads/pages/WhatsAppPanel.jsx` for existing capability: the scan verified it is 100% mock — hardcoded `useState`, a `wa.me` deep link, **zero API calls**.

**Instagram DM / Messenger: scoped out for a structural reason, not a scheduling one.** The framework's identity key is the phone number; **a DM does not have one.** Owner decision 1's append rule, the partial unique indexes (`src/main/resources/db/indexes.sql:82-87`) and the repeat-caller match all key on phone. A DM's identity is a page-scoped IGSID/PSID — no phone, no email, often no name beyond a handle. It joins nothing and decision 1 has nothing to match on. Supporting it needs a new identity table and a rethink of what "the same person" means (`Lead` has no `customerId`; the only link is `Customer.createdFromLeadId:81`, one-directional).

**And there is no "this is a lead" event.** A form payload is lead-shaped — name, phone, email, requirement are *fields*. A message stream is not; "is this a lead?" is a **judgment**. Ingestion would need an intent decision: first message? human triage? a classifier? (The Groq/Disha stack is parked behind `disha.enabled=false`.)

**The good news:** they share Meta's entire transport — same PROVIDER_ACCOUNT resolution, same `X-Hub-Signature-256` over raw bytes, same GET handshake, same row shape. A correct `meta_ads` is their on-ramp. Their cost is the **identity and intent model**, not the plumbing.

---

### Cross-cutting

**Body-size cap must cover BOTH prefixes, and the webhook prefix needs it most.** `POST /api/webhooks/**` is permitAll prefix-wide (`:93`) and needs **no token to reach** — an anonymous caller POSTs a 2GB chunked body to `/api/webhooks/leads/justdial/anything`, the controller binds `@RequestBody byte[]`, and the JVM OOMs for every tenant **before token resolution ever runs**. `meta_ads` is worse: it binds raw bytes for HMAC and batches `entry[].changes[]`, so it is the largest body in the system on the one endpoint whose signature check happens **after** the bytes are already in heap. Verified: no cap exists — `application.properties` configures only multipart, and there is no `server.tomcat.max-http-post-size`. So register the size guard **globally** (like `ContextCleanupFilter`, at `HIGHEST_PRECEDENCE`, skipping non-ingest paths) or add it to the staff chain **as well as** the ingest chain — scoping it to `/api/ingest/**` alone protects the one prefix that needs it least. `Content-Length` alone is insufficient: **chunked transfer encoding omits it**, so wrap the stream with a counting guard. nginx `client_max_body_size` is defence in depth, not the control.

**Rate limiting — and `meta_ads` must not be omitted.** Verified `RateLimitFilter`: `shouldNotFilter:52-56` returns true for everything except `/api/auth/` and `/api/portal/auth/` — webhooks and public paths are **unthrottled**; limits are hardcoded constants (`:25-31`, and note `LOGIN_MAX = 50` despite the "3 login attempts" comment) selected by an `isSignup` ternary (`:68-70`); its `FilterRegistrationBean` is **disabled** (`SecurityConfig:155-159`), so it runs **only inside chains that add it explicitly** — **`IngestSecurityConfig` must `addFilterBefore(rateLimitFilter, …)` or ingest is unthrottled while looking throttled.** The extension: a `RateLimitPolicy` (path → limit/window); `shouldNotFilter` becomes `policy.forPath(uri) == null`; the ternary becomes a lookup. Cover `/api/webhooks/leads/` **including `/api/webhooks/leads/meta_ads`** — omitting it lets an attacker burn CPU on HMAC verification per request at zero cost. **Key on the token / site key, not the IP** — providers post from shared NAT, so IP-keying lets one busy tenant throttle another. `RateLimitService.isAllowed(key, max, window)` is key-agnostic (verified) and `CampaignDispatchService:140` proves tenant+channel keying. For the browser form use **two independent buckets**: site key (tenant fairness) *and* IP (abuse).

> **Policy-lookup bug = silent loss of brute-force protection on login.** `RateLimitFilter` guards `/api/auth/`. A policy returning null for it disables the limiter with no error. Test that path explicitly.

**The XFF distinction — do not get this wrong.** `ClientIp.resolve` returns the first `X-Forwarded-For` hop **unconditionally** (`:17-21`, verified) and its own javadoc says "(not a security control)" (`:5-8`). As a rate-limit key that means an attacker rotates the header for an unlimited fresh bucket per request. **Use `RateLimitFilter.resolveClientIp:84-96`** (verified), which honours XFF only when the direct peer is in `app.ratelimit.trusted-proxies`. Extract it to a shared helper — **never by relaxing `ClientIp`.**

> **Deployment landmine, pre-existing.** `app.ratelimit.trusted-proxies` defaults to **empty** (`RateLimitFilter:39`). Behind nginx, `remoteAddr` is then `127.0.0.1` for **every** request → one shared bucket throttling the entire world. This already affects the login limiter; ingest would inherit and amplify it. **Set it.**

**Notification: publish LAST — for the true reason.** The common "clearing the context makes every later query span all tenants" claim is **wrong**, and being wrong here is not harmless: a contributor who tests it (publish mid-method, query after, observe correct scoping) concludes the rule is superstition and drops it. Verified `TenantFilterAspect:38-40` binds the tenantId **by value** at enable time — `session.enableFilter("tenantFilter").setParameter("tenantId", tenantId)` — so once the `@Before` advice has run, `NotifyEventListener`'s `TenantContext.clear()` **cannot un-bind it**; the rest of that transaction stays correctly scoped. Keep the rule; ground it in what actually happens:

1. **`TenantEntityListener.prePersist` reads the live ThreadLocal.** Any `BaseTenantEntity` persisted after the publish without an explicit `.tenantId(...)` throws — and the listener swallows it into an ERROR log (scan `:123`). The notification or the `LeadLog` **vanishes silently**.
2. **Any subsequent cross-bean `@Transactional(REQUIRES_NEW)` call** gets a fresh `@Before` with a null context and **genuinely does span all tenants**.

When publishing: **`IN_APP` alone** (the dispatcher hardcodes `null` at `NotifyEventListener:31`, making `SseNotificationChannel`'s guard dead code, so `IN_APP + SSE` double-pushes); **explicit `.tenantId(...)`**; **explicit `.recipientUserIds(...)`** (the implicit fallback in `InAppNotificationChannel:49-51` resolves `TENANT_ADMIN` only and drops every MANAGER); **never `EMAIL`** (`EmailNotificationChannel.send()` self-invokes `sendAsync`, so `@Async` never applies and its retry loop sleeps ~6s **synchronously** on the publisher's thread).

**Boundary to settle with the pipeline section:** the append path does not go through `createLead`, so `LEAD_CREATED` will not fire and this subsystem must publish its own event — but if the pipeline routes **new** inbound leads through a create path that already publishes `LEAD_CREATED` (`LeadServiceImpl:641-680`), publishing again **double-notifies**.

**Every channel is blocked on one unverified DDL fact.** Each new `LeadSource` constant needs a `leads_lead_source_check` refresh, and `src/main/resources/db/indexes.sql` (**note: the canon and scan both cite `db/indexes.sql`; the real path is under `src/main/resources/`**) has ~20 such blocks and **zero for `leads`**. Whether the constraint exists on the pilot DB is **UNVERIFIED** and invisible in a fresh dev DB — a new DB generates it from the current enum and works perfectly. One query settles it, and it must be run against the pilot DB **before any channel ships**:

```sql
SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
WHERE conrelid='leads'::regclass AND contype='c';
```

Applying `indexes.sql` is an **operational step, not a deploy** — there is no Flyway.

**Multi-node.** `RateLimitService` is in-memory per-JVM by documented design, so ingest throttling is per-node. Correct on the single-node KVM2 pilot; the moment there are two nodes every limit silently doubles (scan open question 11).

---

### The honest per-channel cost

"One adapter class and nothing else" is **true for the marketplace group and false everywhere else.** The owner will budget from the headline, so the headline is the matrix:

| Channel | Artifacts |
|---|---|
| `justdial` `indiamart` `tradeindia` `sulekha` `travel_marketplace` | **1 adapter** + 1 `leads_lead_source_check` line + 1 FE catalog refresh |
| `google_ads` `ivr_call` | 1 adapter + config + credential handling |
| `website_form` | ~6: controller, `IngestSecurityConfig`, CORS object, embed generator, static asset, captcha SPI + 2 properties |
| `meta_ads` | ~7: adapter, key-extractor, controller, fetcher, Graph client, drain scheduler, health scheduler + `SecurityConfig` line + 2 env-only properties |

Adding SULEKHA touches the DB **not at all** — the `channel` column is a plain VARCHAR with no check constraint, and the registry is the constraint (and a strictly stricter one: it rejects an unknown slug at save time *and* again at ingest, whereas a CHECK only validates spelling and would happily accept a `meta_ads` row on a node with no Meta adapter deployed). The `leads_lead_source_check` line is a `LeadSource`-enum cost, not a channel-table cost. **The promise is worth keeping as an aspiration for the marketplace group specifically — it is falsified, not demonstrated, by claiming it for Meta.**

---

## Security model

Every surface this framework adds is unauthenticated by construction. Two sentences carry the whole model:

1. **A single non-transactional chokepoint** (`LeadIngestGateway`) resolves the token, verifies the payload, establishes `TenantContext`, and only then enters a transaction.
2. **Adapters are pure** — no repository, no `EntityManager`, no `TenantContext` — and that purity is ArchUnit-enforced.

Isolation is therefore a property of the framework, not of adapter-author discipline. That is what makes "one adapter class" safe rather than merely convenient.

---

### 1. The two gates, in order

Both run before any state is touched. Signature-first is the house order (`SaasPaymentServiceImpl.java:176-180`).

| Gate | Answers | Mechanism |
|---|---|---|
| **1. Tenant resolution** | "Where does this go?" | TOKEN mode: SHA-256 probe of the path token. PROVIDER_ACCOUNT mode: `accountKey(in)` → `external_account_id` lookup. |
| **2. Authenticity** | "Did the provider send it?" | `adapter.verification()` — the mandatory declarative `InboundVerification` on the adapter. |

**The token is gate 1, not gate 2.** It names a tenant unguessably; it authenticates nobody.

**There is no separate `InboundVerifier` SPI and no verifier bean-registry.** Verification is `InboundVerification verification()` on `LeadSourceAdapter` — a mandatory method with no default, returning a sealed value (`HmacHeader | SharedSecretInBody | TokenOnly`). Rationale, and it is the decisive one: **a miss in a verifier registry fails OPEN**, which is the exact failure mode being designed against; a missing `verification()` is a compile error. A separate registry would also add a second bean per channel, falsifying the adapter-count claim in §10.

**`TokenOnly()` is a compile-time declaration, never a runtime fallback.** Declaring weakness in code makes it visible in review; deriving it from a null secret makes it invisible in production.

**Every verifier fails CLOSED when its secret is unset** — the precedent is verified verbatim at `RazorpayGatewayClient.java:117-120` (returns `false` on a null/blank secret) and `UnavailablePaymentGatewayClient.java:43-45`. *A verifier that returns OK because it found no secret is a backdoor a config typo silently opens.*

**Downgrade is blocked.** The declared verification mode lives on `lead_source_integrations`. Once a tenant has configured a secret, a payload arriving **without** the signature header is REJECTED, not downgraded to token-only. Otherwise the strongest channel degrades to the weakest at the attacker's option, by omitting a header.

**Constant-time compares only** — `MessageDigest.isEqual` (`RazorpayGatewayClient.java:160-166`). Reuse that helper's shape; it is currently private to the payment client, so a general `hmacSha256Hex` + `constantTimeEquals` pair lifts into `leadsource/verify/`.

**Raw bytes are mandatory.** `@RequestBody byte[]` with `consumes = ALL_VALUE` (`RazorpayWebhookController.java:29-33`) — an HMAC is over the exact received bytes, and re-serializing a parsed object changes them.

**The response never leaks.** A constant envelope per the canon status table (§4). No tenant data, no echo of input, no confirmation of what matched.

---

### 2. The ingest token — a bearer credential, stated honestly

Canon: whole-token SHA-256, `lsk_` + Base64URL-unpadded(32 bytes `SecureRandom`), stored as 64-char hex in `lead_source_integrations.ingest_token_hash`. Reveal-once. **Two nullable hash columns on the same row** (`ingest_token_hash`, `ingest_token_hash_previous`), each with its own partial unique index `WHERE ... IS NOT NULL`, plus `token_previous_revoke_at`.

**Why not a child `lead_source_ingest_tokens` table** — this is the load-bearing constraint, not a preference. A child table has no `tenantId`, so verifying a signature (which needs the integration row's decrypted secret) before the tenant is known forces `leadSourceIntegrationRepository.findById(...)`. `TenantIsolationArchTest.java:60-61` bans `findById` on any repository typed over a `BaseTenantEntity`; the rule body walks **every** production class (`:89-117`); and its javadoc at `:52-55` states verbatim *"If this test fails, do not add your class to EXEMPT_CLASSES."* There is no legal move. **Two columns on one row cannot produce a `findById`.**

**Why unsalted SHA-256 and not BCrypt.** The hash *is* the indexed lookup key. BCrypt's per-row salt makes it unindexable, and ~100 ms on an unauthenticated endpoint is a CPU-exhaustion amplifier. The OTP module needs BCrypt because a 6-digit code is ~20 bits; this input is 256 bits of `SecureRandom` — there is nothing to brute-force. **`AesSecretCipher` is a technical impossibility here**: verified at `AesSecretCipher.java:50-51`, a fresh 12-byte random IV per encrypt makes ciphertext non-deterministic and un-lookupable.

**Lookup — two derived finders, called from a NON-transactional resolver:**

```java
Optional<LeadSourceIntegration> findByIngestTokenHashAndDeletedAtIsNull(String hash);
Optional<LeadSourceIntegration> findByIngestTokenHashPreviousAndDeletedAtIsNull(String hash);
```

**Non-transactional is a contract, not a style.** `TenantFilterAspect`'s pointcut is `@Before("@annotation(...Transactional)")` (`:22`) — method-level only. Outside a transaction the aspect never fires and the probe is legitimately global. If the resolver *were* transactional **and** a caller attached a Bearer token to the permitAll webhook (`JwtAuthFilter.java:89-90` authenticates it and sets `TenantContext`), `tenantFilter` would scope the lookup to the *caller's* tenant and a valid token for tenant B would resolve to **empty** — intermittent, unreproducible 404s.

**Neither finder filters `enabled`.** Resolve first, authorize second, so a delivery to a disabled connection is logged against the **right tenant** instead of vanishing into a 404.

**Uniqueness is on the token, never on (tenant, channel)** — many connections per (tenant, channel) is the point. The indexes are **not** partial on `deleted_at`: a retired token stays reserved forever, because reissuing it silently redirects a provider that still has the old URL pasted in its console.

**Overlapping rotation.** Regenerate moves current → previous and sets `token_previous_revoke_at = now + 72h`. Instant cutover drops every lead in the human gap while the tenant goes and pastes the new URL into JustDial's console. The FE token panel must render **both** live tokens during the window with the previous one's revoke time (see the frontend section).

**It will leak. Enumerate honestly:** nginx `access_log` logs the full URI by default — **the access log is a credential store**; the tenant pasting it into IndiaMART's portal, where their support staff can read it; provider-side logs and backups; a screenshot in a support ticket; `Referer` for any browser-originated call.

**Full-token logging is BANNED everywhere.** Log `ingest_token_prefix` only. Two concrete in-tree sites the shipped change must fix — see §3 and §7.

> **RESIDUAL RISK — accepted, not solved.** For every `TokenOnly` channel, whoever holds the URL can inject unlimited fake leads into that tenant until it is rotated. They cannot read anything. They cannot reach another tenant. Worst case is pipeline poisoning, quota exhaustion, and attacker-chosen PII written into the CRM. It is **not** acceptable for a channel that offers a signature — which is why `verification()` has no default.

**Anomaly surfacing is the only detection that exists for `TokenOnly` channels**, so `lead_count` / `last_lead_received_at` / `token_last_used_at` are load-bearing, not cosmetic. A spike is what a stolen token looks like; a stale `last_lead_received_at` is what an expired credential looks like.

---

### 3. Unknown-token deliveries — DROP, do not persist

**401, WARN, and no row in any table.** An unknown token is an unauthenticated stranger; persisting their bodies is an unauthenticated write primitive and a disk-fill DoS on a prefix that is unthrottled today. `WebhookEvent` sets the precedent — it stores no raw body at all (scan reuse table, "Raw payload / inbound audit log").

401 (not 404) is deliberate: a provider that retries a dead URL forever is a real operational cost, and with a 256-bit token, selector enumeration is not the threat the 404 would defend against.

The WARN carries **`ingest_token_prefix` + client IP only** — never the full token, never `getRequestURI()`. The IP comes from `RateLimitFilter.resolveClientIp` (`:84-96`), which honours `X-Forwarded-For` only when the direct peer is a configured trusted proxy (`:88`). **Never `ClientIp.resolve`** — its own javadoc says *"(not a security control)"* (`ClientIp.java:5-8`) and it trusts XFF unconditionally (`:17-21`), letting an attacker rotate the header for an unlimited fresh bucket.

**The asymmetry that makes this safe:** a delivery to a **known** token on a **disabled** connection **is** recorded, as a `lead_ingest_events` row against the right tenant with status `IGNORED` — so the tenant sees "you turned this off and are dropping leads" rather than nothing.

---

### 4. HTTP status contract

Canon law, published before any adapter ships. The gateway owns every one of these.

| Status | When |
|---|---|
| **401** | Bad / unknown / revoked token, or an unresolvable account key. The provider should stop. |
| **202** + outcome in body | Quarantined or deferred — quota, plan entitlement, or a Meta-style `Deferred` fetch. |
| **200** | Processed, appended, duplicate, ignored, or unparseable-but-recorded. A 200 on unparseable stops a provider retrying forever a payload we can never parse; we recorded it, replay is manual. |
| **429** + `Retry-After` | The single hard rate ceiling (§5). |
| **413** | Body over cap — written by the filter itself (§6). |
| **5xx** | Genuine transient fault only. Retry is correct. |

**The 202 row is what the entitlement re-check needs and could not otherwise express.** Without it an over-quota tenant falls through to 200 (lead silently dropped, nobody notified) or 5xx (provider retries forever against a cap that will not move).

**The entitlement re-check is retained and is load-bearing.** `ModuleAccessFilter` gates on `TenantContext != null` (`:80-81`) and therefore **no-ops on every webhook thread** — without an in-handler re-check after resolution, a tenant whose plan excludes `LEADS` still gets inbound leads processed.

**Meta's GET handshake is the one documented `ApiResponse` exception** — `InboundParseResult.Echo(byte[] body, String contentType)` returns bare `text/plain`. A hub-challenge wrapped in an envelope fails the provider's verification.

---

### 5. Rate limiting

Today `RateLimitFilter.shouldNotFilter:52-56` returns true for everything except `/api/auth/` and `/api/portal/auth/` — **webhooks are completely unthrottled**. The filter *does* reach them once allowed; it is registered on the staff `@Order(2)` catch-all chain (`SecurityConfig.java:110`).

**ONE hard limit. Tier 1 is deleted.** An earlier draft proposed a soft tier that 200-ACKs an over-limit burst, stores the raw payload, and defers lead creation to a drain scheduler. **No drain scheduler exists** — the scan is unambiguous that retry infrastructure is greenfield (`attempt_count` / `next_retry_at` / `max_attempts` appear nowhere). A 200-ACK into a table nobody drains is **strictly worse than a 429**, because the provider never retries: a 400-lead campaign burst would silently land 180 leads with no error anywhere. So: real 429 + `Retry-After`, and **provider re-delivery is the recovery mechanism** — zero new infrastructure. Tier 1 becomes buildable in Phase 3 only because Meta's `Deferred` fetch forces a drain scheduler into existence anyway (`lead_ingest_events` already carries `attempt_count` / `next_retry_at` for that reason). Not before.

**Do NOT extend the existing ternary.** Extract a `RateLimitPolicy` resolver — path prefix → `{keyFn, limit, window, logFn}`; `shouldNotFilter` becomes `policy.forPath(uri) == null`. Three defects at `RateLimitFilter:73` make the naive extension actively harmful, and all three are **created by the shipped change**, not pre-existing warts:

```java
String key = "rate_limit:" + path.replace('/', ':') + ":" + ip;   // :73 — verified
```

1. **The control does not work.** For `/api/webhooks/leads/justdial/{token}` the attacker-chosen token **is part of the bucket key**. Every probe with a random token gets a fresh bucket — `RateLimitService.isAllowed` (`:25-32`) takes the `existing == null` branch every time and returns `true`. The IP limit never fires, on exactly the case it was assigned.
2. **Every live token becomes a long-lived map key.** `RateLimitService.windows` is a `ConcurrentHashMap<String, Window>` (`:21`) — visible in any heap dump. §2 bans logging the token; writing it into a process-lifetime map is the same leak by another door.
3. **`log.warn("… path={}", ip, path)` at `:76` writes the live token to the application log** on every throttle event.

**FIX, shipped in the same change:**
- Ingest branch keys on `"rate_limit:ingest:" + ip` — **never the path**. This is the unresolved/invalid-token limit: tight, cheap, pre-DB.
- The **per-connection** limit runs in the **service, after resolution**, keyed on the resolved integration's id. The filter cannot see the mapping without a DB hit. `CampaignDispatchService:140` already proves `RateLimitService` is key-agnostic and callable from a service. IP-keying alone punishes the wrong party — a provider shares IPs across tenants.
- The ingest log line emits `integrationPublicId` + `ingest_token_prefix`, **never `getRequestURI()`**.
- **Regression test:** assert no log statement on this path receives `getRequestURI()`.
- **Coverage includes the tokenless PROVIDER_ACCOUNT route** `/api/webhooks/leads/{channel}` — an unthrottled Meta endpoint burns an HMAC of CPU per request at zero attacker cost.

`RateLimitService` is in-memory per-JVM by documented design (`:10-17`). Single-node is fine for the pilot; >1 node needs a Redis-backed limiter. Flag, do not build.

---

### 6. Payload size cap — on BOTH prefixes

**Verified:** `application.properties:319-320` configures **multipart only** (`max-file-size=11MB`, `max-request-size=13MB`). There is no `max-http-request-size` and no `max-http-post-size`. A JSON body is bounded only by heap, and the webhook binds `@RequestBody byte[]` **fully into memory** (`RazorpayWebhookController.java:31`). An anonymous caller POSTing a huge chunked body to `/api/webhooks/leads/justdial/anything` **OOMs the JVM for every tenant, before token resolution runs**. Meta is worse: largest body, raw bytes bound for HMAC, signature checked only after the bytes are already in heap. This is live on `/api/webhooks/razorpay` today, independent of this framework.

**Cap: 256 KB**, per-channel override via `ChannelCatalogEntry`. A call recording is a **URL, never a body upload**.

**Registered as a global servlet filter at `HIGHEST_PRECEDENCE`, covering BOTH `/api/webhooks/**` and `/api/ingest/**`** (skipping everything else). An earlier draft placed it on `/api/ingest/**` only — the one prefix that needs it least.

- Reject `Content-Length > cap` with **413** before reading the body.
- For chunked encoding or an absent/lying `Content-Length`, wrap the stream in a counting limiter.
- **The filter writes the 413 itself** via `ApiErrorWriter` with `ErrorCode.PAYLOAD_TOO_LARGE` — which already exists (`ErrorCode.java:45`) and already maps to 413. This is the `RateLimitFilter.rejectRequest:102-107` idiom. **Never abort a wrapped `InputStream` mid-read**: the `IOException` is caught by Spring's `RequestResponseBodyMethodProcessor` and rethrown as `HttpMessageNotReadableException` → **400**, and the tenant gets "malformed request" with nothing to act on.

Not the container (Tomcat's `max-http-post-size` governs form-encoded parsing, not a raw JSON body); not the controller (by then the bytes are in heap, which is the thing being prevented).

---

### 7. Tenant isolation on the ingest path — the crux

The webhook thread arrives with **both isolation layers off simultaneously**. Verified verbatim:

```java
Long tenantId = TenantContext.getTenantId();
if (tenantId == null) {
    return;                       // ← tenantFilter never enabled
}
```
`TenantFilterAspect.java:34-37` — **fails OPEN**. And the advice is `@Before` on `@Transactional` (`:22`), so **setting the context inside a transactional method is too late** — the filter is already decided and stays off for the whole transaction.

Worse, it *looks* right: `softDeleteFilter` is enabled **unconditionally** (`:30-32`), so an unscoped session still hides trashed rows. This bug class is invisible in single-tenant dev testing.

**The defence is one move: establish `TenantContext` BEFORE entering any transactional method.**

#### `LeadIngestGateway` — non-transactional, the only class on this path permitted to touch `TenantContext`

```
0. clear SecurityContextHolder + PlatformContext        ← see below
1. resolve   — platform-scoped derived finder by token hash / account key (tenant not yet known)
2. verify    — adapter.verification(), over the exact raw bytes, BEFORE any state is touched
3. map       — LeadSourceIntegration → immutable ResolvedIntegration / IntegrationCredentials
4. TenantContext.setTenantId(resolved.tenantId());      ← UNCONDITIONAL
5. LeadIngestOutcome outcome = ingestService.ingest(...);   // cross-bean → @Transactional
6. publish NotifyEvent from the outcome                  ← AFTER commit, outside the tx
7. finally { TenantContext.clear(); }
```

Shape copied from `TrashPurgeScheduler:42-52` — `setTenantId` outside the transaction, cross-bean delegate, `clear()` in `finally`. **Cross-bean is mandatory**: self-invocation defeats both the tx proxy and the tenant filter.

**Step 0 — clear the security context.** `permitAll` disables *authorization*, not `JwtAuthFilter`. Two consequences. (a) `AuditorAware` returns `auth.getName()` for any authenticated principal (`AuditingConfig.java:17-27`), so an attacker attaching their own valid JWT to a webhook POST would have **their email stamped as `createdBy` on another tenant's rows**. (b) The path may touch `Customer` during FK resolution, and `Customer` **is** `Ownable` — `OwnershipEntityListener` would stamp the attacker as row-level owner. (Note: `Lead` itself is **not** `Ownable` — its owner dimension is the existing `assignedUser` FK, per `Ownable.java` javadoc — so the Lead half of that concern is void.) Clear `PlatformContext` too: `JwtAuthFilter:91-93` enters it for a SuperAdmin token, and `ContextCleanupFilter:59-64` treats the pair as one unit.

**Step 4 `setTenantId` is UNCONDITIONAL — never `if (getTenantId() == null)`.** `JwtAuthFilter:89-90` sets `TenantContext` from a caller-supplied Bearer token even on a permitAll path. A conditional set would let an attacker **choose the tenant** by attaching their own JWT to a webhook POST. Log a WARN if a non-null context is seen on entry; it is never legitimate here. `ContextCleanupFilter` covers the thread (`:52,59-64`), but per its own javadoc the gateway must not *assume* a clean entry.

#### What step 4 actually buys — stated precisely

**The READ filter re-arming is the whole defence.** With `TenantContext` set before the transaction, `TenantFilterAspect:38-40` enables `tenantFilter` and every generated query carries `WHERE tenant_id = ?`.

**The write guard is NOT a cross-tenant assertion here — ACCEPTED RISK, stated plainly.** `TenantEntityListener:24-30` throws only when `contextTenant != null && !contextTenant.equals(entity.getTenantId())`. On this path both operands derive from the *same* `ResolvedIntegration`, so they are equal by construction; the `SecurityException` can only fire if a third source of tenantId exists, and none does. The explicit `.tenantId(...)` stamp on every builder stays — but as **belt-and-braces against the listener's silently-accepting fourth branch** (explicit tenantId + null context matches no branch and persists with **zero validation**, `:24-30`), not as a live cross-tenant assertion. **What actually catches a wrong-tenant write is `TwoTenantIngestIT`** (§9) — which makes that test load-bearing rather than confirmatory.

#### Publish AFTER commit — and the correct reason

`ingest()` **returns** a `LeadIngestOutcome` carrying recipient ids and the pre-rendered title/message. The non-transactional gateway publishes at step 6. Nothing is published from inside the transaction. This also fixes, for free, that `InAppNotificationChannel` joins the caller's tx and SSE-pushes **before commit**.

**The mechanism, corrected — the widely-repeated rationale is wrong and being wrong here is not harmless.** `NotifyEventListener` is a plain synchronous `@EventListener` that calls `TenantContext.clear()` in `finally` on the publisher's own thread (`notification/infrastructure/NotifyEventListener.java:37-39`), and `TenantContext` is a bare `ThreadLocal<Long>` with no stack. But **clearing does not disable an already-enabled Hibernate filter**: `TenantFilterAspect:38-40` binds the value at enable time (`.setParameter("tenantId", tenantId)`) and never re-reads the ThreadLocal. Queries in the *same* transaction stay correctly scoped. A contributor who tests the folklore version, sees correct scoping, and concludes the rule is superstition will drop it.

The **true** post-publish hazards:
- Any `BaseTenantEntity` persisted afterwards without an explicit `.tenantId()` throws in `TenantEntityListener:16-21` — and the listener's per-channel catch **swallows it into an ERROR log** (`NotifyEventListener:32-35`). The notification or the `LeadLog` vanishes silently.
- Any subsequent cross-bean `@Transactional(REQUIRES_NEW)` call **re-enters the aspect with a null context and genuinely does span all tenants** — which is exactly what `recordAssignmentAudit` does today (`LeadServiceImpl:109`).

Notification rules for this path: **`IN_APP` only** (SSE double-pushes; EMAIL sleeps ~6 s synchronously on the publisher thread), **`recipientUserIds` always explicit** (the implicit fallback resolves `TENANT_ADMIN` only and silently drops every MANAGER), `referenceType = "LEAD"`. Reuse `LEAD_CREATED` for machine-created leads. `LEAD_ACTIVITY_APPENDED` (the append path) goes to the lead's **owner only** — an append already has an owner, and fanning every repeat call to every admin is what gets the bell muted.

**Fix the rendering in the same change.** `LeadServiceImpl:664` string-concatenates the enum — `@JsonValue` does not affect concatenation, so it renders `DIRECT_CALL`, not `Direct Call` — and concatenates `departCity`, which is nullable, producing `"DIRECT_CALL lead from null assigned to X"`. **Webhook leads never have a `departCity`**, so this hits 100% of inbound and goes from cosmetic to the default.

#### Adapters cannot reach any of this

The gateway maps the row to an immutable `ResolvedIntegration` / `IntegrationCredentials` record at step 3 and **never passes the managed entity past the resolver**. Two reasons:

1. **It is detached.** Verified `spring.jpa.open-in-view=false` (`application.properties:59`) and the resolver is non-transactional, so each repository call opens and closes its own `EntityManager`. Any lazy field touched during `verification()` or `parse()` throws `LazyInitializationException` on a webhook thread → 500 → provider retry storm. It survives today only because the entity happens to be flat scalars — that is luck, not design, and a `byte[]`-fixture unit test would never catch the first lazy association added.
2. **`RawInbound` carries an immutable credentials view by SPI contract**, not the entity.

`parse(RawInbound)` returns the sealed `InboundParseResult` (`Complete | Deferred | Ignored | Echo`). Pure, no IO, replayable from the stored raw payload. IO belongs to `LeadSourceFetcher`, which runs on a background thread inside its own `TenantScope`.

---

### 8. Credentials and PII at rest

**`AesSecretCipher` is the right tool and needs no change** — AES-256/GCM, 12-byte `SecureRandom` IV, 128-bit tag, `Base64(iv||ct+tag)`, key validated to 16/24/32 bytes **at construction, fail-fast at boot** (`AesSecretCipher.java:32-42`). GCM is authenticated: tampered ciphertext fails to decrypt rather than returning garbage.

| Data | Treatment |
|---|---|
| `credentials_enc` (adapter secret bag) | **Encrypted** — AES-GCM, via one chokepoint |
| `ingest_token_hash` / `_previous` | **Hashed** — different requirement (verify, not recover) |
| `config_json` | **Neither** — non-secret by definition, FE-returnable wholesale |
| `raw_payload` | **Neither — redacted** (below) |

**The plaintext trap.** There is no `AttributeConverter` and no `@Convert` anywhere in this codebase — encryption is opt-in *per call site*, so a new entity with a credential field **silently stores plaintext** if anyone forgets. Defence: one `IntegrationSecretService` chokepoint, plus the write-only-secret contract copied verbatim from `WhatsAppConfigService.java:55-58` — an explicit `secretChanged` flag so a save that does not touch the key preserves the ciphertext, and the DTO exposes only `configured`/`secretSet`, never the value. `requiredCredentialKeys` on `ChannelCatalogEntry` is validated at **save time**, fail-fast, which is the only mitigation the un-typed credential blob gets.

**Key rotation: there is none, and `key_version` SMALLINT ships now.** `app.encryption.key` is one value; `decrypt()` throws `IllegalStateException("Failed to decrypt secret")` on a wrong key (`AesSecretCipher.java:74-76`) — loud, not silent, which is the only mercy. Changing the key makes every ciphertext permanently unreadable. `ddl-auto=update` adds the column for free, so a future multi-key cipher can decrypt-old/encrypt-new. **Do not build the machinery; do not paint into the corner.**

**Correction to the draft — VERIFIED, no action needed.** The draft flagged as SHOULD-VERIFY that `ProductionConfigValidator` "should assert" the committed default key (`application.properties:161`) differs from prod's. **It already does**: `DEV_ENCRYPTION_KEY` is pinned at `:47` and `rejectDevValue(...)` fires on it at `:79`, as a `BeanFactoryPostProcessor` that hard-stops **before** the DataSource opens (`:28-33`). Delete the concern.

#### `lead_ingest_events.raw_payload` is a PII store

It carries names, phones, emails, free-text messages, `gclid`, and recording URLs, for every inbound lead. It is invisible until it is a problem.

**Redaction, not encryption.** The adapter declares `secretFieldPaths()` (`default Set.of()`); the raw store redacts those paths **before persist**, alongside the 64 KB cap and the `payload_truncated` flag. The default is acceptable *here* — unlike `verification()` — because the fail-open direction is "redact nothing", which is **visible in the stored payload** rather than silent. Signature headers are kept: they are MACs, not secrets.

Whole-blob encryption is **rejected**: the app decrypts on demand with a key in the same process, so it buys little against the realistic threat (a dump from a compromised app host); it makes the blob un-greppable for the debugging use case that is its *entire* justification; and it inherits the no-rotation problem with **no re-enter path** — a key change would permanently destroy the store, unlike a credential a tenant can retype.

**Retention — the trash purge does NOT apply, and this matters.** `TrashPurgeScheduler:36` purges on `deletedAt`, which is set by a **user's** delete action, and `TrashableType` is a closed registry of user-trashable **business** entities whose declaration order encodes master-data FK purge ordering. A raw payload is never user-deleted → it would never acquire a `deletedAt` → **it would live forever**. Registering it in `TrashableType` would also surface raw PII in the tenant's Trash UI as a *restorable item*. Wrong on both counts.

→ A dedicated `RawPayloadRetentionScheduler`, reusing the *shape* exactly (`TrashPurgeScheduler:42-52`): per-tenant loop, `setTenantId` **outside** the `@Transactional` delegate, `clear()` in `finally`, one tenant's failure never blocks others.

**Two-tier retention is the key move.** Attribution (`campaign_name`, `ad_id`, `form_id`, `gclid`/`fbclid`) lives on `lead_attributions` **forever**; the raw blob purges at 30 days. **Purging raw ≠ losing attribution** — which is what makes a short, defensible window politically possible. This is also why canon forbids `LeadLog.ingest_event_id` from being the sole path to attribution: it points at a row that purges.

**Access control:** TENANT_ADMIN only, behind a **new dedicated permission `INTEGRATION_RAW_READ`** — deliberately **not** bundled into any `CRM_FULL`-style grant, which is already known to over-grant (SUB_AGENT leaks through exactly that). Never on the Lead detail response. Own endpoint, `ApiResponse`-wrapped, **audit-logged on read**.

> **COMPLIANCE — flagging, not scoping. Counsel scopes this, not me.** A new personal-data store with no lawful-basis or retention story today. Three concrete gaps: (1) a data-subject deletion request must now purge raw payloads, and **no such mechanism exists anywhere in this codebase**; (2) call recordings carry consent requirements the CRM cannot verify and does not record; (3) retention must be documented. India's DPDP Act 2023 is the likely regime; GDPR if any EU traveler is involved. **Residual: a DB dump is 30 days of inbound PII.**

---

### 9. Mechanical enforcement

**Three new ArchUnit rules ship with Phase 1**, next to `TenantIsolationArchTest` — whose javadoc already states our exact rationale (`:41-45`): *"it compiles, passes every unit test, and returns the right answer on a single-tenant dev database. It misbehaves only in production, with real tenants, as a data leak."*

1. **`TenantContext.setTenantId` callable only from an allowlist** — `LeadIngestGateway`, the existing schedulers, `JwtAuthFilter`, `TravelerAuthServiceImpl`. Today's guard is opt-in and trivially bypassed by calling the setter directly; nothing catches it.
2. **`leadsource.adapter..` may not depend on `..repository..`, `TenantContext`, or `EntityManager`.** This is what mechanically enforces the purity the entire isolation argument rests on. It is only enforceable if `leadsource.adapter..` contains adapters and **nothing else** — hence the canon packaging: controllers → `leadsource/web/`, entities → `leadsource/domain/`, Graph client → `leadsource/client/`.
3. **No class-level `@Transactional`.** Zero violations today, so it codifies an existing convention at zero cost and permanently retires the question from every future design doc: `TenantFilterAspect:22` matches `@annotation` — method-level only — so a class-level annotation would **silently never enable the tenant filter**.

**Plus `TwoTenantIngestIT`, mandatory and load-bearing** (§7): inject via tenant A's token; assert `lead.tenantId == A` **and zero rows for B**. It must be two-tenant — **a single-tenant test cannot observe this bug class by construction**, and per §7 it is the only thing that catches a wrong-tenant write.

The ~9 existing hand-rolled `setTenantId` sites are **not** migrated — out of scope, allowlisted as-is.

---

### 10. Filter-chain and CORS

| Route | Auth change needed |
|---|---|
| `POST /api/webhooks/leads/{channel}/{token}` and `POST /api/webhooks/leads/{channel}` | **None** — `SecurityConfig.java:93` already permits `POST /api/webhooks/**` prefix-wide. |
| `GET /api/webhooks/leads/{channel}/{token}` and `GET /api/webhooks/leads/{channel}` | **One narrow addition** (below). |
| `POST /api/ingest/forms/{siteKey}`, `GET /api/ingest/v1/lead-form.js` | New `IngestSecurityConfig` chain, `@Order(0)`, `securityMatcher("/api/ingest/**")`. |

**The GET permit — verified and narrow.** `:93` is `.requestMatchers(HttpMethod.POST, "/api/webhooks/**").permitAll()` — **POST-only**. A GET falls to `.anyRequest().authenticated()` at `:108` and **401s**, so a provider's subscription-verification handshake could never register the webhook in the first place. Add:

```java
.requestMatchers(HttpMethod.GET, "/api/webhooks/leads/**").permitAll()
```

**Do NOT drop the POST qualifier at `:93`** — that would open Razorpay's webhook to GET. Also update the now-stale comment at `:91-92` ("Payment-gateway webhooks").

**`WEBSITE_FORM` must not go on `/api/public/`.** The permitAll at `:95` is **GET-only**, so a `POST /api/public/…` compiles, deploys, and **401s at runtime**. The prefix merely *looks* permitted. The embed asset **must** live under `/api/ingest/**` or `IngestSecurityConfig`'s `securityMatcher` does not cover it and it 401s.

**CORS — one addition, and it is not an access control.** The global bean pins `allowedOrigins` with `allowCredentials=true` (`SecurityConfig.java:131-134`), making `*` categorically illegal in browsers — so a tenant's own website origin is blocked. `UrlBasedCorsConfigurationSource` accepts **multiple path registrations on the same bean** (`:136-137` currently registers only `/**`). Ingest uses no cookie and no `Authorization` header, so `allowCredentials=false` makes `*` legal and safe:

```java
source.registerCorsConfiguration("/api/ingest/**", ingestCors);   // BEFORE "/**"
source.registerCorsConfiguration("/**", config);
```

> ⚠️ **Registration order is load-bearing — verify match precedence before merging.** If the source returns the first match by insertion order, a wrong order silently reinstates the credentialed `/**` config and the embed breaks in production only.

**CORS does not apply to a plain HTML form POST or a server-to-server call.** The `siteKey` + `Origin` check is the real gate, done server-side. **`Origin` is unspoofable by browser JS but trivially forged by any server-to-server caller** — it is anti-casual-abuse, not authentication. `{siteKey}` is `external_account_id`, is **public by construction**, and is **never** the ingest token. Do not let tenant-facing copy claim otherwise.

**`GlobalExceptionHandler` — one real site, verified.** `:120` is `log.warn("No resource found for path: {}", ex.getResourcePath())`. A malformed ingest URL that misses the mapping — an extra segment, wrong casing, a tenant pasting the URL with a trailing slash into JustDial's console — **writes the live token to the Log4j2 output**. Redact there: truncate at the third path segment for `/api/webhooks/leads/**`, or gate the line on the prefix. **Verified good news, record it:** `ApiError` carries no path/URI field (`ApiError.java:43-49` — status, code, message, fieldErrors, details, traceId, timestamp only), so **the token never reaches the caller's response body**. No change needed on the response side.

---

### 11. SSRF — hard rule

> **No URL taken from an inbound payload is ever fetched by the server.**

Two real vectors, both payload-steered outbound fetches: a Graph-style two-step fetch keyed off `leadgen_id`/`page_id`, and IVR providers that send a recording URL (full SSRF — `169.254.169.254` metadata, internal admin ports, `file://`).

- **Graph fetches** — host is a **compile-time constant**; only the id goes into the path, validated `^\d{1,25}$`. Never `URI.create(payload.get("url"))`. A constant host also protects the tenant's Graph token, which rides on that request and would otherwise leak straight to an attacker-chosen host.
- **IVR recordings** — stored as an **opaque string** in `lead_attributions.recording_url` (+ `recording_url_expires_at`), rendered as a link for the **browser** to fetch. Costs nothing, eliminates the vector entirely, and makes URL expiry the provider's problem.

**We do not archive recording bytes in v1.** The provider is the system of record with its own retention, and fetching multi-MB audio on an ingest thread would silently consume the tenant's metered storage quota. **The portal-`bytea` precedent does not transfer** — those are traveler-uploaded documents the tenant owns, with no other system of record.

If server-side fetching is ever required it needs: per-integration host allowlist configured during authenticated setup, https-only, DNS resolution + literal-IP checks against RFC1918/loopback/link-local/metadata **re-evaluated after every redirect** (DNS rebinding), redirects disabled, timeouts, size caps. A large machine for no product gain → **the default is DON'T FETCH**, and the allowlist is an explicit owner decision.

---

### 12. Research tasks — provider mechanics are NOT facts

The codebase cannot supply provider contracts. Everything below was asserted as fact in an earlier draft and is **demoted to a named Phase 0 research task with an owner**, scheduled where it is free and parallel and where it gates Phase 2/3's shape — not discovered mid-build. A reviewer who sees an honest UNVERIFIED list elsewhere in a document reasonably concludes the *unflagged* items are verified; that is the trap this section closes.

| ID | Question | Why it is security-shaped |
|---|---|---|
| **R1** | Meta: is the callback app-level only? Failure-count / disable semantics? Page-token expiry? Batching envelope? Exact signature header, algorithm, and prefix? | Decides whether `META_ADS` can exist on the token route at all, and the `HmacHeader(header, algo, enc, prefix)` values. |
| **R2** | Google LFE: **is the shared secret carried in the body?** | This is the **entire** forcing argument for `secretFieldPaths()` + the in-body verifier. The mechanism stays — it is cheap and conditional — but the certainty goes. Restated conditionally: *IF* the secret is in the body, the raw store **must** redact it before persist, or every inbound lead persists a live credential in plaintext next to the PII. |
| **R3** | IndiaMART: push or pull? | If pull, it is a scheduler + per-integration cursor, **not** a webhook channel, and Phase 2's size is wrong. |
| **R4** | JustDial / IVR: payload shape, HTTP method, whether a per-account custom webhook URL is permitted. | Decides whether the token route is even expressible per channel, and whether `TokenOnly` is honest or lazy. |

Also assumption, not fact: the "Meta = several FB pages, IVR = several DIDs, JustDial = possibly several city accounts" enumeration. The **many-connections-per-(tenant, channel)** decision is right on first principles — the token, not the pair, is the key — but the enumeration behind it is unverified.

`canon.spiSignature` is deliberately shaped to survive **any** answer R1–R4 return: `Echo` / `Deferred` / `Complete(List)` / `PROVIDER_ACCOUNT` cost one enum and one sealed case whether or not Meta needs them, and another channel may need them anyway.

---

### 13. What a new channel actually costs — the honest number

**Not one file.** Claim the true number everywhere:

| File | Always? |
|---|---|
| `SulekhaAdapter` (in `leadsource/adapter/`) | Yes |
| One `LeadSourceChannel` constant (slug + `leadSource()`) | Yes |
| One `LeadSource` constant | Only if no existing constant fits |
| One FE presentation entry | Only if the channel needs custom copy/logo |

**The database is untouched.** `lead_source_integrations.channel` is a plain VARCHAR holding `channel.slug()` — no `@Enumerated`, no CHECK, no `db/indexes.sql` block. The registry is the constraint **and a strictly stricter one**: it rejects an unknown slug at save time *and* again at ingest, whereas a CHECK only validates spelling and would happily accept a `META_ADS` row on a node with no Meta adapter deployed.

The `leads_lead_source_check` refresh is a **one-time Phase 0 cost for all 25 constants**, not a per-channel cost. Nullable enum columns need the `IS NULL OR` disjunct (`db/indexes.sql:205` is the in-tree template). **UNVERIFIED and worth one query before Phase 0:** whether `leads_lead_source_check` exists on the pilot DB at all — Hibernate generates it silently and it is declared nowhere in the repo, so grep finds nothing, which misleadingly reads as "no constraint."

```sql
SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
 WHERE conrelid = 'leads'::regclass AND contype = 'c';
```

**Registry:** `Map<String, LeadSourceAdapter>` keyed by `channel.slug()`, folded from `List<LeadSourceAdapter>` in the constructor (the `OtpSenderResolver:20-24` idiom) but **throwing on a duplicate slug claim** — the copied idiom's bare `put` is last-one-wins by Spring bean ordering, which here is a **wrong-tenant-attribution bug on a public endpoint**. Both `List<LeadSourceAdapter>` and `List<LeadSourceFetcher>` inject via `ObjectProvider` defaulting to `List.of()`, or Phase 1 cannot boot (zero fetchers exist until Phase 3). Boot-fails if any adapter can return `Deferred` with no fetcher registered for its channel.

---

## Frontend

Frontend root is `D:/CRM PROJECT/travelcrmfrontend` — **not** the `D:/CRM PROJECT/frontend` in CLAUDE.md, which does not exist (scan Area 5). Shared HTTP client is `import API from '@shared/api/http'`; there is no `services/axiosInstance.js`. Stack: Vite + React 18 + **Tailwind v4** — no `tailwind.config.js`; tokens live in an `@theme` block at `index.css:17-30` (verified).

Three deliverables, in value order. Ship in this order.

---

### 1. Enum metadata endpoint + `useLeadSources` (Phase 1)

#### The live bug, restated on the true mechanism

`LeadInformation.jsx:6-9` hardcodes **8** source strings; the backend enum has **9** — `OTHER` is missing (`LeadSource.java:6-15`). At 25 constants that goes from 1 orphan to 15.

The red-team's trace is correct and the draft's headline was wrong. **`LeadInformation.jsx:325` is `{...register("leadSource")}` — uncontrolled, no `value` prop, no `Controller`** (verified). `EditLead.jsx:279` gates the form behind `loadingLead`, so `reset()` at `:106` runs **before** `LeadInformation` mounts. RHF's `_formValues` therefore already holds `"Google Ads"`; `handleSubmit` clones `_formValues`, so `transformFormData` (`leadService.js:9`) sends the seeded string. **The round-trip is already byte-identical today.**

So the live failure is **display-only, and still serious**: the select renders the placeholder because no `<option>` matches, the user believes the field is unset, picks something, and **overwrites a correct source**. There is no silent auto-rewrite and no automatic 400.

> **The ship-order gate "every lead carrying a new-vintage source becomes un-editable" is NOT established** (scan Area 5 asserts it; the RHF trace contradicts it). Do not state it as fact. **Write the failing test FIRST** against the real component — open an `OTHER`-source lead, assert the select *displays* "Other", assert an untouched save round-trips `"Other"` — and let it decide whether the 400 claim is real. The endpoint ships in Phase 1 regardless: the overwrite hazard alone justifies it, and 15 orphans is 15× today's exposure.

#### The endpoint

`GET /api/leads/meta/sources` → `ApiResponse<List<LeadSourceOptionDTO>>`. Adopt **only** `MarketingFieldCatalog:30-54`'s derive-from-`.values()` shape and `SegmentController:37-40`'s envelope — **explicitly NOT** its `OptionDTO` argument order. Do not copy `VendorController:45-52` (bare `Map`, bypasses the mandatory envelope).

```java
public record LeadSourceOptionDTO(
    String value,          // getDisplayName() — "Google Ads". THE WIRE VALUE. Not name(). Do not "fix" this.
    String label,          // getDisplayName() — identical to value on purpose; see below
    String code,           // name() — "GOOGLE_ADS". Stable FE key. NEVER an <option value>.
    String selectability   // MANUAL_SELECTABLE | MACHINE_ONLY | LEGACY_READ_ONLY
) {}
```

**`value == label` is the truth, not redundancy.** `LeadSource` carries `@JsonValue` on `getDisplayName()` (`LeadSource.java:6-15`, scan Area 2), so `GET /leads` emits `leadSource: "Google Ads"` and `EditLead.jsx:106` seeds that string. `MarketingFieldCatalog.java:31` is literally `new OptionDTO(t.name(), t.getDisplayName())` — copying it verbatim emits `value="GOOGLE_ADS"`, matches nothing, and **ships the exact bug this endpoint exists to kill**. The POST direction masks it: `@JsonCreator fromValue` accepts both vocabularies case-insensitively (`LeadSource.java:28-37`), which is why the bug survives casual review. **Carry the comment on the DTO field or someone will "correct" it to `name()`.**

**One field, not two booleans.** `selectability` is the three-state `SourceSelectability { MANUAL_SELECTABLE(10), MACHINE_ONLY(12), LEGACY_READ_ONLY(3) }`. The draft's `catalog.filter(o => o.selectable && !o.deprecated)` cannot arithmetically yield the owner's 10 — either `!deprecated` is dead code or the filter returns 7. Deleted.

**`origin` is NOT on this DTO.** Owner decision 4 makes `GOOGLE_ADS`/`WHATSAPP` both manually selectable and machine-stamped, so a *per-constant* origin is meaningless for exactly the two constants that need it. `LeadOrigin` is a **column on `Lead`** — it belongs on `LeadResponseDto`, which is where the expand-panel attribution line needs it anyway (§3).

**No styling fields on the wire.** Tailwind v4 scans *source files* for literal class strings; a server-sent `"bg-orange-100 text-orange-700"` is purged from the bundle and renders unstyled. Icons are React components. **Styling is structurally FE-side** — this is not a preference.

**Backend assertion required:** assert at startup that all 25 `displayName`s are distinct. `fromValue` returns the first declaration-order match with no compile error on a collision (scan Area 2), and two `<option>`s sharing a value is a silently wrong save. `'Website'` vs a future `WEBSITE_FORM` label is the realistic collision.

#### `useLeadSources` — preserve-unknown

```js
// features/leads/lib/useLeadSources.js  → { catalog, selectable, loading, error, withCurrent }
const selectable = catalog.filter(o => o.selectability === 'MANUAL_SELECTABLE');   // exactly 10

const withCurrent = (current) =>
  !current || selectable.some(o => o.value === current)
    ? selectable
    : [{ value: current, label: labelFor(current) ?? current, orphan: true }, ...selectable];
```

- **The orphan option is NOT `disabled`.** `AllLeads.jsx:479-481` — the idiom this copies — prepends a plain, selectable option (`stageOptions = STAGES.includes(lead.leadStage) ? STAGES : [lead.leadStage, ...STAGES]`) and does not disable it. Match it. (A `JUSTDIAL` lead re-tagged by hand cannot be tagged back — correct; a human must never mint a `JUSTDIAL` lead with no integration row. That is a product consequence, not a reason to fight the DOM.)
- **The current value must be synthesized as an option at/before mount, while `loading === true`.** With `register` the field renders **blank** even though `_formValues` is correct — the state is fine, the UI lies, and the user "fixes" it. Rendering the option late is the whole failure mode, for a different reason than the draft claimed.
- On fetch failure: fall back to **the current value alone + an inline error**. **Never** to a hardcoded list — that reintroduces the drift.
- Show a `disabled` "Loading sources…" placeholder while pending; **it must not be the only option**.

#### Caching: in-memory promise, NOT localStorage

```js
// features/leads/api/leadMetaService.js
let cache = null;
export const getLeadSourceCatalog = () =>
  (cache ??= API.get("/leads/meta/sources").then(r => r.data.data)
     .catch(e => { cache = null; throw e; }));   // never cache a rejection
```

Argued against the `access.js` precedent: `userPermissions`/`tenantModules` sit in localStorage because `hasPermission()` is called **synchronously during first render** by the router `Guard` and the Sidebar — it cannot await. The source catalog has no such consumer; every one already async-loads its lead. localStorage buys one request per hard reload and costs **permanent staleness** — a deploy adding a constant leaves browsers holding a stale catalog forever, reintroducing the orphan bug for the new constant. ~25 rows / <2 KB per SPA session is not a hot path.

#### `leads.email` is now nullable

The architect settled `leads.email` dropping NOT NULL, so `LeadResponseDto.email` becomes nullable. **`AllLeads` is already null-safe** — `CopyableEmail` (`:410`, used at `:648`), `:920`, and `lead.email?.toLowerCase()` (`:1442`) — verified. **No FE change is required for this**, but any new email render must assume null. Re-verify `EditLead`/`LeadInformation` email display when the column lands; the DTO keeps `@NotBlank` at the controller boundary so the human form is unchanged.

---

### 2. Integrations (Phase 2; WEBSITE_FORM surface Phase 3)

#### IA: two levels, keyed on the CONNECTION

`LeadSourceIntegration` is a **CONNECTION row — MANY per (tenant, channel)** (canon). A single `/integrations/:channel` detail page cannot address one: two JustDial city accounts = two credential bags, two ingest tokens, two enable switches, one form. **The FE moves; the data model does not.**

| Route | Renders |
|---|---|
| `/integrations` | Channel catalog grid. One `ChannelCard` per channel from the registry, summarising **N connections**: connection count + **worst status** across them + aggregate `leadCount` + newest `lastLeadReceivedAt`. No per-card Toggle. Action: Connect (0 rows) / Manage (≥1). |
| `/integrations/:channel` | LIST of that channel's connections (label, status Badge, prefix, lastLeadReceivedAt, leadCount, enabled Toggle **per row**) + **Add connection**. |
| `/integrations/:channel/:connectionPublicId` | Credentials, token panel, verification mode, test, recent inbound, delete. |

`:channel` is `LeadSourceChannel.slug()` — lowercase, url-safe, permanent (canon). `:connectionPublicId` is the row's **`publicId` UUID** — never the Long `id`, **never the ingest token** (project convention; token in a route would put a bearer secret in browser history).

#### The token is a one-time reveal, not a field

Canon: whole-token SHA-256 in `ingest_token_hash`; the raw token exists **exactly once, in the mint/rotate response body**, and is never re-derivable. Every design that renders "the webhook URL" on page load is unbuildable.

- **`TokenRevealPanel`** (replaces the draft's `WebhookUrlBox` as the reveal surface): rendered **only** from a mint/regenerate response. Dismissible, copy button, explicit **"I've saved this"** acknowledgement. Copies the **full URL**, not the bare token.
- **Every subsequent load**: `ingest_token_prefix` (masked display only — canon says it is **never a lookup key**, so the FE must never send it back as one) + `token_last_used_at` + a **Regenerate** action. Nothing else.
- **Rotation is overlapping and the FE needs a concept for it.** Regenerate moves current→`ingest_token_hash_previous` and sets `token_previous_revoke_at = now + 72h`. The panel therefore lists **up to two live tokens**: the new one (prefix + "active") and the previous one (prefix + **"stops working in 71h"**, live countdown) with a **Revoke now** action. Rationale: the tenant must go paste the URL into JustDial's console by hand; instant cutover drops every lead in that human gap. A UI that shows one token during the window is lying about what the server accepts.
- **Full-token logging is BANNED** — that includes `console.log`, error toasts, analytics, and the page title. Log/display `ingest_token_prefix` only.
- The draft's threat model ("anyone who can screenshot the page has the ingest URL") **is deleted** — it describes a design nobody is building. The real control is that Regenerate exists and is one click away.

#### Per-resolution-mode rendering — the card is not uniform

Canon gives two webhook shapes. **The connection detail must branch on `resolution_mode`:**

| `resolution_mode` | URL shown | Identity field |
|---|---|---|
| `TOKEN` (JUSTDIAL, INDIAMART, TRADEINDIA, SULEKHA, IVR_CALL, GOOGLE_ADS, TRAVEL_MARKETPLACE) | `POST /api/webhooks/leads/{channel}/{token}` — reveal-once panel; later loads show prefix only | — |
| `PROVIDER_ACCOUNT` (META_ADS; later INSTAGRAM_DM/FB_MESSENGER) | `POST /api/webhooks/leads/{channel}` — **no token segment**, so it is a constant, safely re-renderable on every load | `external_account_id` (the FB page id) — a plain visible field, the thing that identifies the row |

**A `PROVIDER_ACCOUNT` connection has no token to reveal and no Regenerate action.** A single `WebhookUrlBox` with one slot for "the URL" is wrong for both modes.

#### `EmbedSnippet` is fine — and only because the site key is public

Canon: `POST /api/ingest/forms/{siteKey}` where `{siteKey}` **is `external_account_id`, is PUBLIC by construction, and is NEVER the ingest token**. So the snippet carries no secret and **is** re-renderable on every load — unlike the draft's own file note ("contains the ingest token"), which is deleted. Snippet text is still **backend-generated**; the FE never constructs it and never builds a token.

The embed asset is `GET /api/ingest/v1/lead-form.js` — **under `/api/ingest/**`**, because the new `IngestSecurityConfig` chain's `securityMatcher` is `/api/ingest/**`; anywhere else and `.anyRequest().authenticated()` (`SecurityConfig.java:108`) 401s it at every anonymous visitor's browser.

**State plainly on the page**, next to the snippet: *because the site key is public, anyone who views your page source can post leads to this form endpoint. Origin allowlist + rate limit + honeypot are the controls; none is an authenticator.* Inherent to every embedded-form SaaS, not a defect — but the tenant must be told, and it is why this surface is Phase 3.

#### Where it lives: a new `features/integrations/`

Not `settings/`. `CARDS[]` (`CompanySettings.jsx:19-113`) is **94 lines for 3 cards** — each descriptor hand-authors colour tokens, a features checklist, quickStats and JSX icons. At 8–12 channels that is a ~400-line static literal edited by hand every time an adapter lands — a direct violation of *one adapter class and nothing else*. `settings/` is also 3 flat pages on a hand-rolled Toast and the deprecated `err?.response?.data?.message` idiom (scan Area 5); growing it inherits all of that.

The grid is driven by the backend channel catalog (`adapter.catalog()`, canon). The FE holds only `lib/channelPresentation.js`: `code → {icon, headerGradient}` **with a `|| default`**, mirroring `stagePill` (`AllLeads.jsx:53`) / `typePill` (`:69`).

> **Honest matrix — the "zero FE change per channel" claim is false and must not be repeated.** Tailwind v4 cannot emit a class string it never saw in source, and icons are React components. For group A (JustDial / IndiaMART / Sulekha / TradeIndia): 1 adapter class + 1 `LeadSource` constant + 1 `LeadSourceChannel` constant + **1 optional FE presentation entry — POLISH, not wiring**. The `|| default` guarantees a channel shipping with no FE edit renders generically instead of failing. Meta is ~7 classes + DDL + config and was never a one-liner.

**CompanySettings keeps its shape** and gains one `CARDS[]` entry → `navigate('/integrations')`. Its header already reads "Configuration Modules" (`:348`); a 4th module is exactly this.
- Grid at `:356` is `grid-cols-1 sm:grid-cols-2 lg:grid-cols-3` — a 4th card wraps to a lone second row. Bump to `xl:grid-cols-4`.
- The `Promise.allSettled` destructure at `:238` is **positional** — **append** a leg for the integrations quickStat, never insert.

#### The kit: FORK `marketingUi` — declared cost

`marketing/index.js` exports 5 pages and **not** the kit (verified — the barrel lists only the pages). Importing `marketing/components/marketingUi.jsx` from another feature breaches `FEATURE-STRUCTURE.md:81`. Two legal moves: fork, or promote to `shared/`. Promotion touches marketing's 5 pages and has nothing to do with lead sources; `shared/ui/` deliberately holds 3 files (`gridTable.jsx`, `toast.jsx`, `WhatsAppIcon.jsx` — verified).

**Decision: fork** into `features/integrations/components/integrationsUi.jsx` ← `Page, Panel, PanelHead, Badge, Btn, IconBtn, Modal, Field, Select, Toggle, inputCls, EmptyBlock, Loading`. Drop the table/pager/grid half.

**Cost, stated plainly: ~450 duplicated LOC, the 7th per-feature kit; a design-system change then needs 7 edits.** Forking is the established idiom (six kits exist) so this is not a new sin — it is a compounding one. **Promotion trigger: when a third feature wants this kit, promote to `shared/ui/` and reduce all copies. Not before.**

Note `marketingUi.jsx:156-164`'s `ChannelBadge` is a binary WHATSAPP/EMAIL ternary — do not carry it across; the fork needs a lookup map with a default from day one.

#### Three gotchas deliberately not copied

1. `WhatsAppConfiguration.jsx:213` derives `isConfigured` from **form** state — Test fires against unsaved config. **Here: server state only; Test disabled until a save round-trips.**
2. `WhatsAppConfigService.java:86-88` returns **HTTP 200 with `{success:false}`** on failure. **Branch on `res.data.data.success`, never on the 2xx.**
3. `settings/` hand-rolls a Toast and uses `err?.response?.data?.message`. **Use `@shared/ui/toast` + `getErrorMessage` from `@shared/api/apiError`** (the `AllLeads.jsx:6-7` idiom).

**Write-only secrets** mirror `WhatsAppConfigService.java:55-58`: render `••••••••` + an explicit **Replace**; untouched → `credentialsChanged: false` preserves the ciphertext. Fields come from the channel descriptor's `requiredCredentialKeys` / field descriptors (`adapter.catalog()`), never a hardcoded per-channel form.

**Research task (not fact):** Meta's connect flow. Whether a tenant pastes a page id + long-lived token, or the FE must run an OAuth dialog and a page-subscription step, cannot be known from this codebase — no OAuth, refresh-token storage, `expires_at` or re-auth exists anywhere (scan reuse inventory). `credentials_expire_at` exists on the row, so the detail page must at minimum render an **expiry warning + Reconnect**; the exact dialog is a Phase-3 research output.

---

### 3. Source badge + attribution + the donut (Phase 1, with the enum)

**No 8th column.** `LEAD_GRID_COLS` (`AllLeads.jsx:103`) is `'28px 1.6fr 0.95fr 0.95fr 0.9fr 0.85fr 124px'`, and the comment at `:101-102` protects it as the one source of truth for header + rows + skeleton. An 8th column squeezes the 1.6fr Lead cell below readable width and forces 4 edits. **`LEAD_GRID_COLS` must not be touched.**

**The badge is a chip inside the existing Lead cell**, beside the `typePill` chip at `:510-512`. `leadType` and `leadSource` are the same class of row metadata; render them identically. Desktop row (`:492`) and mobile row (`:560`) are **duplicated markup** — the chip goes in **both** or mobile silently lacks it. **Do not refactor the 1761-line file to dedupe under this change.**

**One map, two projections** — the draft's "colours from `SOURCE_PILL`" is unimplementable: `SOURCE_PILL` holds Tailwind class strings, `Dashboard.jsx:258` feeds a hex to a chart fill.

```js
// features/leads/lib/leadSource.jsx   (leads/lib/ exists — lib/whatsapp.js)
const SOURCE_TONE = {
  'JustDial':  { cls: 'bg-blue-100 text-blue-700 border-blue-200', hex: '#60a5fa' },
  'IndiaMART': { cls: 'bg-teal-100 text-teal-700 border-teal-200', hex: '#34d399' },
  // …
};
const DEFAULT_TONE = { cls: 'bg-slate-100 text-slate-600 border-slate-200', hex: '#94a3b8' };
export const sourcePill = (s) => (SOURCE_TONE[s] || DEFAULT_TONE).cls;
export const sourceHex  = (s) => (SOURCE_TONE[s] || DEFAULT_TONE).hex;
export function SourceBadge({ source }) { /* pure sync render */ }
```

Keyed by **displayName** — exactly `STAGE_PILL`/`stagePill` (`:44-53`) and `TYPE_PILL`/`typePill` (`:60-69`), both already keyed by displayName with a `||` default. That makes `<SourceBadge>` a **pure synchronous render with no catalog dependency** — critical at 100 rows. **Accepted risk, stated plainly:** a backend displayName rename degrades the badge to default grey, silently. Non-crashing, and identical to the existing `STAGE_PILL` exposure. Two projections in one map is what makes "the donut matches the badges" enforceable rather than a claim.

**Expand panel** (`:652`): replace `<Cell label="Lead Source" value={lead.leadSource}/>` with the badge + the campaign attribution line (campaign name / ad id / call-recording link when present) + the `origin` marker. `Cell` (`:369`) takes `{label, value, divider}` — it needs a `children` escape hatch. Attribution and `origin` must come from `LeadResponseDto` (canon: attribution lives on `lead_attributions`; `LeadLog.ingest_event_id` purges at 30 days and **must never be the sole path to attribution**). `recording_url_expires_at` is on the row — render an expired recording as plain text, not a dead link.

#### Dashboard donut — fixed in Phase 1, because the enum expansion causes the regression

`Dashboard.jsx:253-258`, verified: `SRC_COLORS` has **6** entries and `.slice(0,7)` takes **7** — the 7th is *always* fallback grey. At 25 sources ~18 vanish with no Other bucket. Worse: `const s = l.leadSource || "Other"` uses the literal `"Other"`, which is also the displayName of the real `OTHER` constant — **null-source rows silently merge into genuine OTHER leads today**, so the donut over-reports OTHER and "unknown" is indistinguishable from "the user chose Other".

Fix all three together: `.slice(0,6)` + a real **`Other (N)`** remainder bucket; rename the null bucket to **`Unknown`**; colours from `sourceHex()` via the leads barrel. ~10 lines.

> **The collision fix CHANGES existing numbers.** It is a correction; it will read as a regression to anyone who memorised the old chart. Say so in the PR.

**Label it "Top sources (recent 100 leads)".** `Dashboard.jsx:443` calls `leadService.getAllLeads()` → defaults `page=0, size=100` (`leadService.js:45`). A donut reads as a total; this one is a breakdown of the first 100 rows by whatever the backend's default sort is. **This resolves the double standard the red-team caught** — the filter is rejected for lying over a 100-lead window, so the donut may not claim to be a total either.

---

### 4. The source filter — does not ship. Say it plainly.

`leadService.js:45-46` is `getAllLeads(page = 0, size = 100)` and `AllLeads` filters that array **entirely client-side** (scan finding #6). Past 100 leads **every existing filter already lies**. A source filter would not create that bug — it would inherit it. But it is uniquely dangerous: **inbound integrations are exactly what push a tenant past 100 leads**, so "show me my JustDial leads" returns a *confidently wrong* answer to the very user who just paid for the integration.

**Do not ship it as a stopgap.** The badge on every row plus the existing (equally truncated) search box delivers most of the value with none of the lie.

Unblocking, scoped honestly — **larger than the badge and the Integrations page combined**:
1. `LeadRepository` does not extend `JpaSpecificationExecutor` (verified) → add it.
2. The list endpoint takes `page/size/sortBy/sortDir` only (scan Area 2) → add filter params.
3. Move **all** of AllLeads' filters server-side **together** — half-server/half-client produces incoherent counts (server pages 20, client filters to 3, UI says "3 of 240").
4. TanStack Table → `manualPagination` + `manualFiltering`.
5. The hero stat cards are computed over the fetched array — they become "stats of the current page". Needs a summary endpoint.

**Cheapest honest pull-forward:** a **source-summary aggregate endpoint**. One GET unblocks a truthful donut without touching AllLeads' filter or pagination at all.

---

### 5. Design-system compliance — real class strings

The font is **loaded but not applied globally**: `index.css:1` imports Plus Jakarta Sans and nothing sets `body { font-family }`. Every page sets it itself or uses a kit's `<Page/>`. **Use `<Page/>`.**

| Token | Literal string | Source |
|---|---|---|
| Page shell | `min-h-screen bg-gradient-to-br from-slate-50 via-blue-50/30 to-slate-100` | `marketingUi.jsx:30` = `CompanySettings.jsx:293` |
| Font | `style={{ fontFamily: "'Plus Jakarta Sans',system-ui,sans-serif" }}` | `marketingUi.jsx:31` |
| Glass card | `bg-white/80 backdrop-blur-md rounded-2xl border border-slate-200/60 shadow-sm` | `marketingUi.jsx:121-123` |
| Primary btn | `bg-gradient-to-r from-blue-600 to-indigo-500 hover:from-blue-700 hover:to-indigo-600 text-white shadow-md shadow-blue-200` | `marketingUi.jsx:175+` |
| Input | `w-full px-3.5 py-2.5 rounded-xl border border-slate-200 … focus:border-blue-400 focus:ring-2 focus:ring-blue-50` | `marketingUi.jsx:222` |
| Gold accent | `#eeda92` = `--color-gold-300` → `bg-gold-300`, `text-gold-700` | `index.css:17-30` |
| Row hover | `#eeda9218` | `AllLeads.jsx:496-497` |

**Integrations uses the default blue-600→indigo-500 primary.** Per-channel colour lives *only* in each card's header gradient. Accent sprawl is already real (the WhatsApp page went green, accounting emerald) — a page hosting 8+ brand-coloured channels **cannot have "an accent"**. No new tokens: `gold-*` plus Tailwind defaults.

---

### 6. Routing, sidebar, permission

**Router** (`router.jsx`) — `lazyPage` off the barrel, never a deep import (`:13`):

```js
const integrations       = () => import("@features/integrations");          // near :88
const Integrations       = lazyPage(integrations, "Integrations");
const ChannelConnections = lazyPage(integrations, "ChannelConnections");
const ConnectionDetail   = lazyPage(integrations, "ConnectionDetail");

// near :290-292
<Route path="integrations"                    element={<Guard allow={hasPermission(P.SETTINGS_MANAGE)}><Integrations/></Guard>}/>
<Route path="integrations/:channel"           element={<Guard allow={hasPermission(P.SETTINGS_MANAGE)}><ChannelConnections/></Guard>}/>
<Route path="integrations/:channel/:connectionPublicId" element={<Guard allow={hasPermission(P.SETTINGS_MANAGE)}><ConnectionDetail/></Guard>}/>
```

Path lowercase — matches the newer `/marketing`, `/accounting`, `/fleet` convention over the legacy `/CompanySettings` (`:290`) neighbours.

**Sidebar** — a sibling flat `<li>` right after the Settings link (`Sidebar.jsx:691-704`), same gate, `handleLinkClick('Integrations')`. `Sidebar.jsx` is 700+ lines of hand-written JSX driven by an `activeTab`/`openDropdown` state machine; converting Settings into the Marketing-style dropdown risks that machine for cosmetic gain. **Flat link. Revisit the dropdown when a third settings page lands.**
- **Icon: `Network` is already taken** — imported at `:5` and already rendered for Sub-Agents at `:594` (verified; the draft's "already imported, free" reads as available and is not). Import a distinct one — `Plug`/`Cable`/`Webhook` from lucide — or the sidebar shows the same glyph twice.

**Permission: reuse `P.SETTINGS_MANAGE`.** It already gates `EmailConfiguration` and `WhatsAppConfiguration` (`router.jsx:291-292`) — per-tenant credentialed channel config, structurally identical. It appears in **no** non-admin role default in `access.js`, so it is TENANT_ADMIN-only in practice — the right blast radius for "who may connect a lead source". A new `INTEGRATIONS_MANAGE` costs a backend `Permission` constant + `defaultsFor(Role)` + a permission-enum check-constraint refresh (the `users_role_check` class of bug, `db/indexes.sql:143-147`) + a `UserPermissions` row, for one page.

**No `hasModule("INTEGRATIONS")` gate.** The reason is `hasModule`'s **fail-open + no-admin-bypass asymmetry, verified**: `access.js` returns `true` on unknown/failed fetch (its own javadoc: "FAIL-OPEN … even TENANT_ADMIN is bound by it (no role bypass here, unlike hasPermission)") while `hasPermission` short-circuits on `isTenantAdmin()`. So a module gate **shows** the menu to every tenant pre-load and **hides** it from the org admin whose plan excludes it — backwards on both ends.

> The draft's "more decisive" argument — that `ModuleAccessFilter` no-ops without a `TenantContext` (`:80-81`) so a FE gate would be theatre over an unenforced backend — **is deleted.** The ingestion design re-checks plan entitlement **inside the handler after resolving the tenant**, so entitlement *is* enforced on the ingest path. The conclusion survives on the verified asymmetry alone. **Real trigger:** if integrations becomes a paid add-on, the FE gate is only safe once `hasModule` gains a TENANT_ADMIN-visible "excluded — upgrade" state instead of a boolean hide.

---

### Ship order

1. **Metadata endpoint + `useLeadSources` + `LeadInformation` fix** — with or before the 16 new constants. Justified by the overwrite hazard, **not** by the unproven "un-editable" claim. Failing test first.
2. **`SourceBadge` + AllLeads chips (desktop **and** mobile) + expand-panel attribution + the Dashboard donut fix** — same phase as the enum, because the enum expansion is what breaks the donut.
3. **Integrations pages** (Phase 2) — channel grid → connection list → connection detail; token reveal panel; rotation window UI.
4. **WEBSITE_FORM surface** (Phase 3) — `EmbedSnippet` + the public-site-key posture statement.
5. **Server-side lead list → then the source filter.** Its own project. Pull the source-summary endpoint forward alone if the donut's honesty matters sooner.

---

### New files

| Path | Purpose |
|---|---|
| `travelcrmfrontend/src/features/integrations/index.js` | Feature barrel — the ONLY cross-feature entry. Exports `Integrations`, `ChannelConnections`, `ConnectionDetail`, `integrationService`. |
| `…/features/integrations/pages/Integrations.jsx` | Channel catalog grid. One `ChannelCard` per registry channel, summarising N connections. |
| `…/features/integrations/pages/ChannelConnections.jsx` | `/integrations/:channel` — connection rows + per-row enable Toggle + **Add connection**. |
| `…/features/integrations/pages/ConnectionDetail.jsx` | `/integrations/:channel/:connectionPublicId` — credentials, token panel, verification mode, test, recent inbound, delete. Branches on `resolution_mode`. |
| `…/features/integrations/components/integrationsUi.jsx` | FORK of `marketingUi.jsx` (~450 LOC, declared cost). Table/pager/grid half dropped. |
| `…/features/integrations/components/ChannelCard.jsx` | Header gradient + icon, connection count, worst status Badge, aggregate leadCount, newest lastLeadReceivedAt, Connect/Manage. No Toggle. |
| `…/features/integrations/components/ConnectModal.jsx` | Field-schema-driven credentials modal from the channel descriptor. Secrets write-only (masked + explicit Replace → `credentialsChanged`). |
| `…/features/integrations/components/TokenRevealPanel.jsx` | Post-mint/rotate one-time reveal: full URL, copy, **"I've saved this"** ack. Also the steady-state view: prefix + `token_last_used_at` + Regenerate + the **two-live-tokens** rotation window with `token_previous_revoke_at` countdown and Revoke-now. |
| `…/features/integrations/components/EmbedSnippet.jsx` | WEBSITE_FORM only. Copyable backend-generated snippet carrying the **public** site key (never the ingest token) + the public-key posture statement. |
| `…/features/integrations/lib/channelPresentation.js` | `code → {icon, headerGradient}` with `|| default`. FE-side because Tailwind v4 only emits class strings present in source and icons are React components. |
| `…/features/integrations/api/integrationService.js` | axios off `@shared/api/http`: channel catalog, list/create/update/delete connections, test, regenerate + revoke-previous, toggle enabled, recent inbound. |
| `…/features/leads/api/leadMetaService.js` | `GET /leads/meta/sources`, module-level in-flight promise cache that nulls itself on rejection. |
| `…/features/leads/lib/useLeadSources.js` | `{catalog, selectable, loading, error, withCurrent}`; `selectability === 'MANUAL_SELECTABLE'`; preserve-unknown prepend per `AllLeads.jsx:479-481` (**not** disabled). |
| `…/features/leads/lib/leadSource.jsx` | `SOURCE_TONE` (`{cls, hex}`) + `sourcePill()` + `sourceHex()` + `<SourceBadge>` — pure sync, `|| default`, mirroring `STAGE_PILL`/`stagePill` at `AllLeads.jsx:44-53`. |

### Touched files

| Path | Change | Risk |
|---|---|---|
| `features/leads/components/LeadInformation.jsx` | Delete `LEAD_SOURCES` (`:6-9`). Source `<select>` (`:325`, `register`) options from `useLeadSources()` → `withCurrent(currentValue)`. Disabled "Loading sources…" placeholder while pending — never the only option. Error → current value + inline error, never a hardcoded list. | **Highest value, highest risk.** Shared by CreateLead and EditLead; a regression breaks lead creation outright. With `register` the field renders blank if the option list is empty at mount. |
| `features/leads/pages/EditLead.jsx` | No change to `:106`. Verify only: the form is gated behind `loadingLead` (`:279`) so `reset()` precedes mount, and `_formValues` already holds the seeded string. | Ordering: the select must render the orphan option at mount or the field looks empty though state is correct. |
| `features/leads/pages/AllLeads.jsx` | `<SourceBadge>` chip beside `typePill` at `:510-512` (desktop) **and** in the mobile row (~`:572`). At `:652` swap `<Cell label="Lead Source" .../>` for a children-based cell rendering the badge + attribution line + origin. `Cell` (`:369`) gains a `children` escape hatch. | Desktop (`:492`) / mobile (`:560`) are duplicated markup — **both or mobile silently lacks the badge**. **`LEAD_GRID_COLS:103` must NOT be touched.** Do not dedupe the 1761-line file here. |
| `features/leads/index.js` | Export `leadMetaService`, `useLeadSources`, `SourceBadge`, `sourcePill`, `sourceHex`. | Low — the barrel already exports `leadService` for dashboard/reports/quotation. |
| `features/dashboard/pages/Dashboard.jsx` | `:253-258` → `.slice(0,6)` + real `Other (N)` remainder; null bucket renamed `Unknown`; colours from `sourceHex()` via the leads barrel. Retitle the panel "Top sources (recent 100 leads)". | The `|| "Other"` collision fix **changes existing numbers** — a correction that will read as a regression. |
| `features/settings/pages/CompanySettings.jsx` | One `CARDS[]` entry (id `integrations`, route `/integrations`) + a `cardOverride.integrations` fed by an **appended** `Promise.allSettled` leg (connected count / leads this month). Bump `:356` to `xl:grid-cols-4`. | `:238`'s destructure is **positional** — append, never insert. |
| `app/router.jsx` | `const integrations = () => import("@features/integrations");` + 3 `lazyPage` consts near `:88`; 3 routes near `:290-292` under `hasPermission(P.SETTINGS_MANAGE)`. | Low, additive. Lowercase `/integrations` deliberately diverges from `/CompanySettings`. |
| `app/chrome/Sidebar.jsx` | Sibling `<li>` after the Settings link (`:691-704`), same `P.SETTINGS_MANAGE` gate, `handleLinkClick('Integrations')`. **Import a new icon** — `Network` (`:5`) is already used at `:594` for Sub-Agents. | Hand-written 700+ line JSX with an `activeTab`/`openDropdown` machine. Keep it a flat `<Link>`. |

### Open risks

- **The 400-on-save claim is unproven.** The RHF trace says the round-trip is already correct; only the failing test settles it. If the test shows a real 400, the ship-order gate hardens from "should" to "must" — do not assume either way.
- **Backend management contract not owned here.** The FE assumes the connection CRUD/test/regenerate endpoints key on `connectionPublicId` and return `ApiResponse<T>`, that mint/rotate returns the raw token exactly once, and that the channel catalog is served from `adapter.catalog()`. Canon fixes the ingest URLs, not the management API — reconcile with the API section before implementation.
- **Meta connect flow is a research task**, not a design (no OAuth precedent exists anywhere in the codebase).
- **Displayed `leadCount` / `lastLeadReceivedAt` freshness** depends on the backend maintaining `lead_count` / `last_lead_received_at` on the row. If those are lazily updated, the card is stale by an unknown margin — the FE cannot detect it.

---

## Phasing + blast radius

### The correction that reorders everything

Two beliefs shaped the earlier phasing and both are false. They are stated here first because three designers reasoned from them.

**False belief 1 — "the integration layer is the hard part."** It is not. The registry idiom exists twice and is copyable (`OtpSenderResolver:18-39`, `LeadAssignmentStrategyResolver:17-32`). Credential encryption exists and is mature (`AesSecretCipher:25-27,45-60`). The webhook mechanics exist (`RazorpayWebhookController:29-33`). What does not exist is **the ability for any non-human to create a lead at all** — `createLead` hard-requires an authenticated tenant `User` at three verified points (`LeadServiceImpl.java:83` tenant id, `:102` → `LeadAssignmentService:327-330` current user, `:119` audit current user). **The framework is S. The Lead module is L.** The owner's Phase 1 has them backwards.

**False belief 2 — "`db/indexes.sql` is applied by a human, and nothing in the build fails if they don't."** Verified false. `application.properties:84-87`:

```
spring.sql.init.mode=always
spring.sql.init.schema-locations=classpath:db/indexes.sql
spring.sql.init.continue-on-error=true
spring.jpa.defer-datasource-initialization=true
```

`application-prod.properties` does not override it (verified — no `sql.init` key). The file **ships inside the jar and Spring Boot runs it on every startup**, after Hibernate schema generation. CLAUDE.md's "go in `db/indexes.sql` by hand" means hand-**authored**, not hand-**applied**; the scan and CLAUDE.md are both wrong on this.

**DELETED, therefore:** the "#1 operational risk," the deploy-runbook mitigation, the "put the apply-step above the deploy-step" ordering item, and Phase 0's entire status as a deploy gate. None of them were ever real.

**The real risk is the inverse, and nobody was watching it.** `continue-on-error=true` means `DROP CONSTRAINT IF EXISTS` succeeds, the `ADD CONSTRAINT` fails on a violating row, and **the app boots green with NO constraint at all — weaker than before the change, logging nothing.** The codebase already documents this exact swallow, in its own words, at `UserDetailsServiceImpl.java:28-30`: *"db/indexes.sql runs with continue-on-error=true, so a failed index creation leaves no constraint and logs nothing at startup."*

The consequence that should end the argument: Phase 0's originally proposed acceptance test — hand-`INSERT` a `'JUSTDIAL'` row on a restored pilot copy — **passes vacuously**, because with no constraint everything inserts. The demo was incapable of detecting its own failure mode.

---

### PHASE 0 — Survey + research — **S** (days, parallel; gates *sizing*, not *authoring*)

Phase 0 does not gate the deploy. It gates the **estimate**. The `leads_lead_source_check` / `leads_lead_stage_check` DROP-then-ADD blocks are idempotent and ship regardless (the shape is already in-tree at `indexes.sql:143-151`, whose comment documents this bug happening to this team before).

**(a) Data survey** against a restored pilot copy — three queries, not a constraint survey:

```sql
SELECT DISTINCT lead_source FROM leads;                    -- proves the 25-constant ADD cannot fail
SELECT DISTINCT lead_stage  FROM leads;                    -- proves the 8-stage ADD cannot fail
SELECT canonical_phone, COUNT(*) FROM leads
 WHERE deleted_at IS NULL AND lead_stage NOT IN ('CONVERTED','LOST')
 GROUP BY canonical_phone HAVING COUNT(*) > 1;             -- the phone-collision report
```

**(b) The boot-time constraint assertion — the single highest-leverage item in the program.** A startup check that `leads_lead_source_check` EXISTS and contains all 25 constants, **failing loudly**. This is the only control that converts `continue-on-error`'s silent swallow into a signal.

> ⚠️ **Mechanical correction to the ruling, from reading the file.** This assertion **cannot live in `ProductionConfigValidator`**. It is a `BeanFactoryPostProcessor` and its javadoc (`ProductionConfigValidator.java:28-32`) says it is deliberately one *"so that it runs before any regular singleton is instantiated — in particular before the DataSource and Hibernate."* It has no DataSource to query. It is also `@Profile("prod")` (`:37`), so it would never run on the pilot or in dev. **The assertion ships as a separate `ApplicationRunner`, unprofiled** — `ApplicationRunner` runs after `defer-datasource-initialization` has executed `indexes.sql`, which is exactly the ordering required. Reuse `ProductionConfigValidator`'s collect-all-violations-then-hard-stop *style*, not its bean type.

**(c) Research tasks R1–R4** — free, parallel, and they gate Phase 2/3's **size**, not Phase 1's **shape**. `canon.spiSignature` is deliberately built to survive any answer they return: `Echo` / `Deferred` / `Complete(List)` / `PROVIDER_ACCOUNT` cost one enum and one sealed case whether or not Meta needs them.

| ID | Question | Gates |
|---|---|---|
| **R1** | META: is the callback app-level only? failure-count/disable semantics? page-token expiry? batching envelope? | Phase 3 size |
| **R2** | GOOGLE LFE: is the shared secret in the body? *(the entire forcing argument for `secretFieldPaths` + a body-secret verifier — the mechanism stays because it is cheap and conditional; the certainty goes)* | Phase 3 |
| **R3** | INDIAMART: **push or pull?** If pull it is a scheduler + per-integration cursor and **NOT group A** | **Phase 2 size** |
| **R4** | JUSTDIAL / IVR: payload shape, HTTP method, is a per-account custom webhook URL permitted? | Phase 1 + 2 |

**Every provider mechanic in this document is a research task, not a fact.** "Meta posts to one app-level callback," "Google puts `google_key` in the body," "IndiaMART is push," and **"many Indian IVR providers call back with GET"** are all UNVERIFIED from this codebase. The IVR-GET claim was the sole reason IVR ranked above JustDial and it carried no hedge — it is now R4. The near-term cost ranking is **provisional until R1–R4 land**.

The same discipline applies to the many-connections-per-tenant enumeration ("Meta = several FB pages, IVR = several DIDs, JustDial = possibly several city accounts"). The decision is right on first principles — **the token is the key, never the (tenant, channel) pair** — but the enumeration is assumption.

**Two free findings:**
1. **`REOPENED` was added to `LeadStage` (`LeadStage.java:14-15`) with no check refresh.** If a `lead_stage` check predates it on the pilot, **`REOPENED` is already broken in production and nobody has noticed.** The same query answers it.
2. **The phone-collision count is the one number that makes Phase 1's estimate knowable.** Zero → Phase 1 is L as sized. Non-zero → two live open leads for one human are human merge decisions with their own calendar, and `uq_leads_phone_norm_tenant_open` cannot be created until they resolve. This is not workaroundable by "write it anyway."

---

### PHASE 1 — Lead-module surgery + the frozen SPI + **two** channels — **L**

The surgery. Bundling three channels with it makes an untestable XL blob with no demonstrable midpoint.

| Item | Forced by |
|---|---|
| **`LeadActor` refactor — ONE shared `createLeadInternal`, not a second path** | `grep 'createLead('` → **exactly one call site**: `LeadController.java:49` (+ `LeadService.java:15`, `LeadServiceImpl.java:82`). `DevDataSeeder` bypasses the service entirely |
| Three-arm assignment policy incl. `assignForInbound` | `LeadAssignmentService:327-330` throws; `:285-292` throws on an empty pool |
| `LeadMapper` null-guard + `origin` / `phoneNormalized` / `sourceIntegrationId` stamping | hand-written `@Component` (`LeadMapper:16-17`) — a forgotten field is a null, not a compile error |
| `leads.email` NOT NULL drop | `uq_leads_email_tenant_open` (`indexes.sql:82-84`) |
| Phone normalization + `phone_normalized` shadow column | `LeadServiceImpl:601` passes the phone **raw** |
| **Publish-after-commit gateway** | `NotifyEventListener:38` clears `TenantContext` on the publisher's thread |
| **The `:108`/`:109` swap** + remove `currentUser()` from `recordAssignmentAudit` | verified below |
| `LeadSource` 9 → 25 + `SourceSelectability` + metadata endpoint + FE preserve-unknown + Dashboard donut | **one change wearing four hats — ONE deploy** |
| `canon.spiSignature` **FROZEN** | `LeadSourceAdapter` / `LeadSourceFetcher` / `InboundParseResult` / `InboundVerification` |
| Gateway + `TenantScope` + **three ArchUnit rules** + the **two-tenant integration test** | `TenantFilterAspect:34-37` fails OPEN |
| `RateLimitPolicy` resolver; global body-size guard; `TravelerAuthServiceImpl` ordering fix | `/api/webhooks/**` is unthrottled today |

#### The fork is rejected

The second-create-path decision rested on "createLead is used by the entire app." It has **one caller**. The fork's only offered argument is wrong by an order of magnitude, and the fork is what *gives birth to* the double-notify problem it then hand-rolls a fix for, plus quota/dedup drift in month 3. `LeadActor` parameter, one shared implementation, human path unchanged at the caller.

#### Two latent bugs fixed in the same edit

`LeadServiceImpl:108-109` verified verbatim: `publishLeadCreatedNotification` **precedes** `recordAssignmentAudit`. Publishing wipes `TenantContext` on the publisher's own thread, so **the audit's `REQUIRES_NEW` write already runs today with a null context** — `TenantFilterAspect:34-37` (`if (tenantId == null) return;`) leaves the tenant filter **OFF for that whole write**. It survives only on the explicit `.tenantId(tenantId)` at `:125`. **Swap them; publish last.**

And **remove `currentUser()` from `recordAssignmentAudit` entirely** rather than moving it inside the try at `:120`. The actor is now a parameter, and `LeadAssignmentAudit.createdByUserId`/`createdByName` are already nullable (`:45-49`), so the audit row can honestly say `"JustDial — Mumbai"`. Moving the call inside the try fixes the rollback but leaves the audit saying nothing useful about a machine creator.

#### The metadata endpoint — do NOT copy `MarketingFieldCatalog`

**Verified: `MarketingFieldCatalog.java:31` is `new OptionDTO(t.name(), t.getDisplayName())` → `value="GOOGLE_ADS"`.** That is the defect, not the precedent. `LeadSource.java:23-26` carries `@JsonValue getDisplayName()`, so `GET /leads` emits `leadSource: "Google Ads"`. An option set keyed `value="GOOGLE_ADS"` matches nothing. The bug survives review because `fromValue` (`:29-37`) accepts **both** vocabularies case-insensitively — so the POST direction works and only the seed direction breaks. (`LoyaltyTier:23-26` has the same latent bug and escapes it only because segment values never round-trip through `@JsonValue`.)

**CANON:** `GET /api/leads/meta/sources` → `ApiResponse<List<LeadSourceOptionDTO>>` with `value = getDisplayName()`, `label = getDisplayName()`, `code = name()` (stable FE key), `selectability`. **`value == label` looks redundant and IS the truth: the wire vocabulary is the display name.** The DTO carries a comment saying so, or someone will "fix" it to `name()`. Adopt only the derive-from-`.values()` + `ApiResponse` envelope. Plus a **startup assertion that all 25 displayNames are distinct** — `fromValue` returns the first declaration-order match, so a collision compiles fine and silently mis-resolves. `WEBSITE`/`WEBSITE_FORM`, `DIRECT_CALL`/`PHONE_MANUAL`, `MANUAL`/`OTHER` are live candidates.

**Restate the FE mechanism honestly.** `LeadInformation.jsx:325` is **uncontrolled** via react-hook-form `register`, so `_formValues` holds the seeded `"Google Ads"` and `handleSubmit` sends it — **the round-trip is already byte-identical.** The claim "every lead carrying a new-vintage source becomes un-editable" is **NOT established and must not be stated as fact.** The live bug is display-only and still bad: the select renders **blank**, and the user, believing the field unset, picks a value and **overwrites it**. Synthesize the current value at/before mount; **drop `disabled` on the orphan option** (`AllLeads.jsx:479`, the idiom claimed as precedent, prepends a plain selectable option and does not disable it). **Write the failing test against the real component first and let it decide the 400 claim.**

The 8-entry hardcoded `LEAD_SOURCES` at `LeadInformation.jsx:6-9` against a 9-constant backend is the live proof the hardcoding already failed — `OTHER` is unselectable today.

#### Two channels, not one

**WEBLINK_ENQUIRY** is genuinely the cheapest thing in the program: `Quotation` already carries `tenantId` + `leadId` + `leadPublicId` + the customer's contact details, so one `findByPublicIdAndDeletedAtIsNull` resolves everything — zero new mechanism, zero CORS, zero credential storage, and the append fires with **no matching ambiguity** because the lead is already known.

**But it is not the phase's honest demo, and the earlier draft rested Phase 1's justification on exactly that.** Two reasons:
1. **It exercises none of the three constraints the SPI exists to satisfy** — no token resolution, no HMAC over raw bytes, no GET route, no `PROVIDER_ACCOUNT` mode. Freezing an unexercised interface is the worst of both worlds.
2. **Its demo cannot be shown.** `getShareLink` (`QuotationServiceImpl:494-501`) emits `{base}/api/public/quotations/{publicId}/pdf` — **a PDF** — and that is what `sendWhatsApp` transmits (`:470-478`). The `/q/{publicId}` web view hosting the CTA exists **only in the FE router** (`router.jsx:188`). **No tenant is distributing the page the form lives on, because the product never emits its URL.**

So Phase 1 ships **WEBLINK + JUSTDIAL**. JustDial is server-to-server POST: no CORS, no browser, no bot problem, token-in-URL exactly as designed. It is the only way the token path, the `byte[]`/`consumes=ALL_VALUE` binding, the registry and the resolver **meet a real payload before the interface freezes**.

Share-link generation for `/q/{publicId}` is pulled into Phase 1 **with its own property `app.web-base-url`** — **never reuse `app.public-base-url`**, which is doubly-bound as both the quotation share base and the Razorpay webhook base (`application.properties:206-209,268`) and cannot be repointed without breaking the registered payment webhook — **or WEBLINK is knowingly built for a page nobody receives.**

#### Resolution is a framework step, not a method on the adapter

The "non-negotiable" constraint that adapters resolve their own tenant is **REJECTED**. It requires a repository lookup by definition, which breaks the ArchUnit purity rule that **is** the tenant-isolation argument, and it makes tenant resolution depend on parsing — collapsing `LeadIngestEvent`'s ability to be a `BaseTenantEntity`. The adapter contributes only the **pure** `accountKey(RawInbound)`. Kept from that constraint: **`@RequestBody byte[]` + `consumes=ALL_VALUE` from day one** (`RazorpayWebhookController:29-33`) and **GET + POST routing**.

> 🔴 **`SecurityConfig.java:93` is `.requestMatchers(HttpMethod.POST, "/api/webhooks/**").permitAll()` — POST only, verified.** A GET falls to `.anyRequest().authenticated()` at `:108` and **401s**. The permit is added as `.requestMatchers(HttpMethod.GET, "/api/webhooks/leads/**").permitAll()` — a **narrow** addition that **does NOT drop the POST qualifier at `:93`** (dropping it would open Razorpay's webhook to GET).

**Demonstrably working at the end:** a real JustDial payload (live account, or a replayed capture — *materially weaker, must not be reported as done*) lands in the Kanban with campaign attribution, assigned, notified, no duplicate; a second contact from the same phone **appends an activity** instead of creating a lead; and the two-tenant integration test proves tenant B cannot see tenant A's inbound lead.

---

### PHASE 2 — Group A + IVR — **M**

`INDIAMART` (**size contingent on R3** — if pull, it is a scheduler + cursor and **not group A**, and this estimate is wrong), `IVR_CALL`, the **website-form server-to-server relay variant** (free — rides TOKEN mode), `TRADEINDIA`/`SULEKHA` **only if a real tenant asks by name**.

Also lands: the **tenant-facing** connection layer — the Settings → Integrations grid, the connection CRUD/test surface, and the `TokenRevealPanel` reveal/regenerate flow (`CompanySettings.jsx:19-113,236-283` is already exactly this pattern). Note the `LeadSourceIntegration` entity and `IngestTokenService` themselves ship in **Phase 1** (see Phase 1 → New files), because JustDial cannot resolve a tenant without them and **overlapping rotation ships with the token, not after it**; what Phase 2 adds is the self-serve surface over them, not the mechanism.

**Do not pre-pay TRADEINDIA/SULEKHA.** An adapter written against a payload nobody has seen, for an account nobody holds, cannot be verified and will be rewritten when the first real payload arrives. **The framework's whole promise is *one class, later*. Prove the promise; don't pre-pay it.**

#### The honest "one adapter class" matrix

The headline claim is **falsified, not demonstrated, by claiming it for Meta.** Tailwind v4 purges any class string not literally in source, so an FE presentation entry is structural, not conditional.

| Group | True cost |
|---|---|
| **A** (JustDial, IndiaMART, Sulekha, TradeIndia) | 1 adapter + 1 `LeadSource` constant + 1 `LeadSourceChannel` constant + **1 optional FE presentation entry**. **DB untouched** (String slug, no CHECK — the registry is the stricter constraint). The FE entry is **polish, not wiring** |
| Google / IVR / WEBSITE_FORM | adapter + config |
| **META** | **~7 classes + DDL + config** |

The FE presentation map **must** have a `|| default` fallback (the existing `SOURCE_PILL`/`STAGE_PILL:44-53` idiom) so a channel shipping without an FE edit **degrades visually rather than failing**. The `leads_lead_source_check` refresh is a **one-time Phase 0 cost for all 25 constants**, not a per-channel cost. Claim the true number everywhere.

---

### PHASE 3 — META_ADS + WEBSITE_FORM widget — **L**

**META stays in scope here (owner-confirmed)** and `canon.spiSignature` already accommodates it: `Deferred(List<FetchHandle>)` → `LeadSourceFetcher` → Graph fetch for the two-step shape; `Echo(byte[], contentType)` for the `hub.challenge` handshake; `Complete(List<NormalizedLead>)` for batched `entry[].changes[]`; `PROVIDER_ACCOUNT` + `external_account_id` for app-level resolution. The circularity is broken by ordering: **platform-level HMAC over raw bytes first** (Meta's app secret is platform config, not per-tenant, so no row is needed to verify) → pure `accountKey()` → row lookup.

**Meta is the channel that forces retry/backoff/DLQ into existence.** That cost belongs on **Meta's line item, not amortised silently** — the scan is unambiguous that nothing in the codebase has `attempt_count`/`next_retry_at`/`max_attempts` today. Plus the entire OAuth stack this codebase has never had: no refresh-token storage, no `expires_at`, no refresh job, no 401-triggered re-auth, no expiry surfacing in the UI.

**WEBSITE_FORM widget** lands here, not Phase 1. The owner's "easy Phase 1 channel" is the **most expensive near-term channel** — it budgeted variant (c) at variant (a)'s price:

| Variant | Cost |
|---|---|
| **(a)** server-to-server relay from the tenant's backend | free, rides TOKEN mode — **Phase 2** |
| **(b)** plain HTML `<form>` POST | cheap, but **CORS does not apply to form posts at all**, so the origin allowlist is not even a speed bump — and the UX is a page navigation nobody wants |
| **(c)** the JS snippet tenants actually ask for | its own `/api/ingest/**` chain at `@Order(0)`, its own CORS source with `allowCredentials=false` (the global bean at `SecurityConfig:128-139` pins `allowCredentials=true` on `/**`, making `*` **categorically illegal** there), backend-generated snippet, honeypot + rate limit |

`POST /api/ingest/forms/{siteKey}` + `GET /api/ingest/v1/lead-form.js`. **The asset MUST live under `/api/ingest/**` or the `securityMatcher` does not cover it and it 401s.** `{siteKey}` is `external_account_id`, is **PUBLIC by construction**, and is **NEVER the ingest token** — reusing it would publish every tenant's webhook credential on their own homepage.

> **State the posture plainly rather than paper over it:** because the site key is public, **anyone can POST leads to that tenant's form endpoint.** Origin allowlist + rate limit + honeypot are the only controls and **none is a real authenticator.** That is inherent to every embedded-form SaaS and is not a defect — but it must be **conscious**, and it is why (c) is Phase 3. No captcha/honeypot exists anywhere in either repo (grep: zero).

---

### PHASE 4 — INSTAGRAM_DM / FB_MESSENGER — **NOT SCHEDULED**

Deferred for a **structural** reason, not a scheduling one. **Every mechanic in this design keys on a phone number, and a DM has none** — its identity is a page-scoped IGSID/PSID with no phone, often no email, often no real name. They need a `LeadIdentity(channel, externalUserId)` table and an intent decision ("which message is a lead?") that nobody has made. They share Meta's entire transport, so **Phase 3 is their on-ramp** when the product question is answered.

`features/leads/pages/WhatsAppPanel.jsx` *looks* like a working inbound chat inbox and is **100% mock** — do not let it read as existing capability.

---

### Ranking — **provisional until R1–R4**

```
WEBLINK < JUSTDIAL ≈ INDIAMART < IVR_CALL < WEBSITE_FORM(widget) < GOOGLE_ADS(LFE) < META_ADS << INSTAGRAM_DM/FB_MESSENGER
```

The cost driver is **not** conceptual complexity — it is **whether tenant identity already exists in a local row**. For WEBLINK it does, which is precisely the Razorpay variant (`SaasPaymentServiceImpl:237-241`) the scan names as the only tenant-resolution variant an attacker cannot steer.

**GOOGLE_ADS is two projects wearing one enum constant.** Lead Form Extensions fits the token design almost perfectly — **S**, Phase 3 (pending R2). The Ads API / gclid offline-conversion path needs the whole OAuth stack — **XL**. **Owner must say which.**

---

### Size and risk by phase

| Phase | Size | Rewrite risk |
|---|---|---|
| **0 — Survey + research** | **S** | None. Gates *sizing*, not authoring. Its own deliverable (the boot assertion) is the program's highest-leverage item |
| **1 — Surgery + SPI + WEBLINK + JUSTDIAL** | **L** | **THE HIGH ONE.** The only phase touching live human-path code. Plus a full FE slice that is **not optional**. Mitigated — the SPI now meets a real payload before it freezes |
| **2 — Group A + IVR** | **M** | Low if Phase 1's SPI is right. **Unknown if R3 says pull** |
| **3 — META + widget** | **L** | None **if** Phase 1 was right. Total **if** it wasn't. Owns retry/backoff/DLQ + OAuth |
| **4 — DM channels** | — | Not scheduled; product question open |

**Ranked by risk, not size:**
1. **The Phase 1 SPI** — rewrite risk. Budget the senior review **here**, not on Meta.
2. **`indexes.sql`'s `ADD CONSTRAINT` silently swallowed** by `continue-on-error=true` → boots green with **no** constraint. Mitigated *only* by the boot assertion.
3. **`SecurityConfig`** — the app's single authorization surface, **already modified and uncommitted** (git status). Every change needs its own review.
4. **Phone canonicalisation** — silently defeats owner decision 1 while looking done.
5. **The round-robin pessimistic lock** meeting its first burst on a webhook thread.

**Do not read Meta as "the expensive one." Phase 1 is where this program is won or lost.**

---

### Not doing — plainly

**Inbound WhatsApp.** Not a migration — building it from scratch. No inbound webhook exists; `InteraktWhatsAppSender` discards the response body so no provider message id is stored to correlate a callback; and **`TenantSettings.whatsAppPhone` is permanently null** — verified by grep, three occurrences (entity `:62`, DTO `:19`, one read at `WhatsAppConfigService:124`), **no setter call anywhere**, no request-DTO field, no FE input. An inbound WhatsApp webhook **cannot resolve a tenant at all** today. `WHATSAPP` stays manually selectable (owner decision 4). That is the whole scope.

**The server-side source filter — and the double standard, named.** `LeadRepository:21` extends `JpaRepository<Lead,Long>` only — **no `JpaSpecificationExecutor`** (verified). `AllLeads.jsx` is 1761 lines filtering client-side over one `getAllLeads(0,100)` fetch. Ship the **badge** and the client-side filter, **and say the honest thing:** a client-side filter over a 100-row window is a lie at any real inbound volume, **and inbound is exactly what raises the volume**. A source filter is uniquely dangerous — *"show me my JustDial leads"* would return a **confidently wrong answer to the user who just paid for the integration**. Unblocking is Specifications + **all** server-side filters moved together (a half-server/half-client set produces incoherent counts: server pages 20, client filters to 3, UI says "3 of 240") + `manualPagination` + a summary endpoint — larger than the badge and the Integrations page combined. **Cheapest honest pull-forward: a source-summary aggregate endpoint**, which unblocks a truthful donut without touching `AllLeads` at all.

> The donut ships over the same 100-row window the filter is rejected for. **That double standard is real.** It is resolved by **labelling** — *"Top sources (recent 100 leads)"* — not by shipping the filter. A donut reads as a total; this one is not one.

**Migrating the ~9 hand-rolled `TenantContext` sites.** Add `TenantScope`, mandate it for **new** code, leave working code alone. Zero user value; the failure mode of getting one wrong is a **silent cross-tenant leak with no error and no log** (`TenantFilterAspect:34-37`). A separate audit-and-test task with its own budget — not a rider on a feature program.

**Making `TenantFilterAspect` fail closed.** The obvious fix, and wrong here: `PublicQuotationController` + `QuotationServiceImpl:342-349` **depend** on fail-open, and the aspect enables `softDeleteFilter` unconditionally for SuperAdmin/global-master reads. Out of scope. *(Unrelated latent issue, flagged not fixed: `:22` is `@annotation(Transactional)` — **method-level only**. A class-level `@Transactional` service gets **no tenant filter at all**. ArchUnit rule 3 below codifies this at zero cost.)*

---

### The three ArchUnit rules + the two-tenant test (Phase 1)

They ship next to `TenantIsolationArchTest`, whose javadoc already states our exact rationale: *"it compiles, passes every unit test, and returns the right answer on a single-tenant dev database; it misbehaves only in production, as a data leak."*

1. `TenantContext.setTenantId` callable **only** from an allowlist (`LeadIngestGateway` + existing schedulers + `JwtAuthFilter` + `TravelerAuthServiceImpl`). Without it, `TenantScope`'s guard is opt-in and trivially bypassed.
2. Classes under `leadsource.adapter..` may not depend on `..repository..`, `TenantContext`, or `EntityManager`. **This is what mechanically enforces the purity the whole isolation argument rests on.**
3. **No class-level `@Transactional`** — verified zero violations today, so it codifies an existing convention at zero cost and permanently retires the question.

**Plus a mandatory two-tenant integration test. A single-tenant test cannot observe this bug class by construction.**

---

### Blast radius — every existing file touched, all subsystems

#### Backend

| File | Change | Risk |
|---|---|---|
| `db/indexes.sql` | `leads_lead_source_check` (25) + `leads_lead_stage_check` (8) DROP-IF-EXISTS + ADD blocks; `uq_leads_phone_norm_tenant_open`; token partial uniques on `ingest_token_hash` / `ingest_token_hash_previous` `WHERE ... IS NOT NULL`; `lead_attributions` unique on `lead_id` | **HIGH — SILENT.** Auto-applied (`application.properties:84-87`), **not** a runbook step. `continue-on-error=true` swallows a failed ADD → boots with **no** constraint. Token indexes are **NOT partial on `deleted_at`**: a retired token stays reserved forever, or reissuing it silently redirects a provider that still has the old URL pasted in its console |
| `ProductionConfigValidator.java` | **Do NOT add the DB assertion here.** New unprofiled `ApplicationRunner` instead | `BeanFactoryPostProcessor` (`:37`) runs **before the DataSource** by design (`:28-32`), and is `@Profile("prod")` — it cannot query, and would skip the pilot |
| `lead/enums/LeadSource.java` | 9 → 25 + `SourceSelectability{MANUAL_SELECTABLE(10),MACHINE_ONLY(12),LEGACY_READ_ONLY(3)}`. **The 9 are NEVER deleted** | **MEDIUM.** `@Enumerated(STRING)` + `fromValue` throwing on unknown means deleting one breaks **reading** old leads. `fromValue:29-37` returns the **first declaration-order** displayName match — collisions compile fine. Needs the uniqueness assertion. `REPEAT_CUSTOMER`'s displayName is **"Repeat Enquiry"** — `LeadType.REPEAT_CUSTOMER("Repeat Customer")` exists at `LeadType.java:9` and the create form would offer one identical string in two dropdowns meaning two different things |
| `lead/entity/Lead.java` | **+3 columns only**: `origin` VARCHAR(20) (`@Builder.Default`=MANUAL), `source_integration_id` BIGINT logical, `phone_normalized` VARCHAR(20). **Does NOT map the inverse side of `LeadAttribution`** | **MEDIUM.** A nullable inverse `@OneToOne` cannot be proxied without bytecode enhancement → an extra SELECT per lead, re-creating the exact N+1 the table exists to avoid. `origin` gets its **own** Hibernate CHECK on an existing table → needs its own refresh block from day one. Lead is not `@Audited` — no Envers fallout |
| `lead/enums/LeadStage.java` | none | `REOPENED` (`:14-15`) may already be broken in prod — Phase 0 answers it |
| `lead/entity/LeadLog.java` | +3 nullable: `ingest_event_id`, `source_integration_id`, `activity_kind` (new enum) | LOW. New column ⇒ Hibernate generates its check fresh, **no indexes.sql block needed**. `ingest_event_id` points at a log that **purges at 30 days** — it must never be the sole path to attribution |
| `lead/mapper/LeadMapper.java` | map `origin`/`phoneNormalized`/`sourceIntegrationId` both directions | **MEDIUM-SILENT.** Hand-written (`:16-17`) — a forgotten field is a **null in the response**, no compile error |
| `lead/service/LeadServiceImpl.java` | `LeadActor` param, one `createLeadInternal`; **swap `:108`/`:109`**; **remove `currentUser()` from `recordAssignmentAudit`**; return `LeadIngestOutcome`, publish after commit | **HIGH.** THE create path. **Do not touch `validateNoDuplicates` (:580-608) or `checkTrashedForRestore` (:616-639)** — the human path's semantics stay byte-identical |
| `lead/service/LeadAssignmentService.java` | `assignForInbound` — per-tenant default assignee | **HIGH.** `currentUser()` throws at `:327-330`; `resolveRecommended` throws on an empty pool (`:285-292`) — an out-of-hours enquiry into a tenant with no eligible user **hard-fails and the provider sees a 500**. The round-robin cursor holds a **pessimistic lock to commit** (`LeadServiceImpl:98-102`) — a webhook thread now contends for it |
| `lead/dto/CreateLeadRequestDto.java` | phone `@Pattern` (`:24-27`) rejects spaces/dashes/parens/leading-zero; email `@NotNull` | **HIGH / TWO-SIDED.** Telephony sends exactly the rejected formats. But `DevDataSeeder` writes phones **with spaces** via the repository — existing rows already violate it |
| `lead/repository/LeadRepository.java` | tenant-scoped `phone_normalized` finders | **MEDIUM.** Terminal stages **release** the natural key → a phone maps to a **chain** of leads; a plain `Optional` finder throws `NonUniqueResultException`. ArchUnit (`TenantIsolationArchTest:60-61`) **bans** `findById`/`getReferenceById` and its javadoc (`:52-55`) **forbids** the `EXEMPT_CLASSES` escape |
| `auth/security/SecurityConfig.java` | **narrow** `.requestMatchers(HttpMethod.GET, "/api/webhooks/leads/**").permitAll()`; register `IngestSecurityConfig` @Order(0) ahead of it (Phase 3) | **HIGHEST IN THE PROGRAM.** Single authorization surface, **already modified and uncommitted**. Three traps: (1) `:93` is POST-only — a GET 401s via `:108`; **do not drop the POST qualifier** or Razorpay's webhook opens to GET; (2) `:95` is GET-only for `/api/public/**` — a POST compiles, deploys and 401s at runtime; (3) the CORS bean (`:128-139`) is **one** config on `/**` with `allowCredentials=true` — a careless edit widens CORS for the **entire authenticated API** |
| `common/filter/RateLimitFilter.java` | extend from `/api/auth/` + `/api/portal/auth/` (`:52-56`) to the ingest prefixes | **MEDIUM. A bug here throttles LOGIN.** Use `RateLimitFilter.resolveClientIp` (`:84-96`), **NOT `ClientIp.resolve`** — the latter trusts `X-Forwarded-For` unconditionally and its own javadoc says *"(not a security control)"* (`ClientIp.java:5-8`). `RateLimitService` is in-memory per-JVM by documented design — **incorrect behind >1 node** |
| `common/listener/TenantEntityListener.java` | **NONE — read-only dependency** | **DO NOT TOUCH.** Its fourth combination (explicit tenantId + null context) **matches no branch and persists with ZERO validation** (`:24-30`); `preUpdate`'s cross-tenant guard is short-circuited by a null context (`:36`). On a webhook thread **both isolation layers are off simultaneously.** The design must not rely on it catching anything |
| `common/aspect/TenantFilterAspect.java` | **NONE — explicitly out of scope** | **DO NOT TOUCH.** Fails OPEN (`:34-37`) **and its `@Before` advice fires before the method body** — so **setting `TenantContext` inside a `@Transactional` method is TOO LATE.** Context must be established **before** entering any transactional service method |
| `notification/**` | **NONE — reuse as-is** | **DO NOT TOUCH, but obey:** IN_APP alone (SSE double-pushes; `NotifyEventListener:31` hardcodes null); **never EMAIL** (self-invocation defeats `@Async` → ~6s **synchronously on the publisher's thread**; its named executor bean does not exist); **always set `recipientUserIds` explicitly** (the implicit fallback resolves TENANT_ADMIN only, silently dropping MANAGERs); `referenceType` `"LEAD"` is valid — unknown strings persist as NULL with no log (`SaasPaymentServiceImpl:384` already bitten live with `"BILLING"`); payload is silently dropped for IN_APP. **Reuse `LEAD_CREATED` for machine leads** (a lead created by JustDial is a lead created); add **`LEAD_ACTIVITY_APPENDED` to the lead's OWNER ONLY** — fanning every repeat call to every admin is what gets the bell muted. **Fix the rendering:** `LeadServiceImpl:664` string-concatenates the enum → `"DIRECT_CALL"` not `"Direct Call"` (`@JsonValue` does not affect concatenation) and `departCity` is nullable → *"DIRECT_CALL lead from null"*. **Webhook leads never have a departCity — this hits 100% of inbound and goes from cosmetic to default** |
| `settings/crypto/AesSecretCipher.java` | **NONE** — call explicitly from the `credentials_enc` write path | **SILENT-PLAINTEXT.** Plain `@Component` with explicit `encrypt()`/`decrypt()` — **no `AttributeConverter`, no `@Convert` anywhere** (`:25-27,45-60`). A forgotten call site stores **plaintext**. Nothing catches this. *(Also why AES is impossible for the token: a random 12-byte IV per encrypt makes ciphertext non-deterministic and unlookupable.)* |
| `application.properties` | ingest rate-limit keys, per-channel toggles, **`app.web-base-url`** (new), ingest base URL | **MEDIUM.** `app.public-base-url` is **doubly-bound** — quotation share base **and** Razorpay webhook base (`:206-209,268`). It **cannot** be repointed for ingest or web URLs |
| `TravelerAuthServiceImpl` | ordering fix (context leak on the header-less OTP path) | LOW |

#### Frontend (`D:/CRM PROJECT/travelcrmfrontend`)

| File | Change | Risk |
|---|---|---|
| `features/leads/components/LeadInformation.jsx` | `LEAD_SOURCES` (`:6-9`) → fetched options; select at `:325-328`; preserve-unknown | LOW code / **HIGH consequence.** The 8-entry array vs a 9-constant backend is live proof the hardcoding already failed. Hardcoded in a **form component**, not exported from the barrel |
| `features/leads/pages/EditLead.jsx` | `:106` seeds `leadSource` into the select | **MEDIUM.** With `register` the value **round-trips correctly**; the bug is the field rendering **blank** and the user overwriting it. **The 400/bricking claim is NOT established — let the test decide** |
| `features/leads/pages/CreateLead.jsx` | `:31` defaults `leadSource` to `"Direct Call"` | **MEDIUM-SEMANTIC.** That constant is **deprecated for new tagging**. Changing it to `MANUAL`/`PHONE_MANUAL` **silently shifts the source distribution of every hand-created lead from that day**. **Tell the owner before it lands**, or the dashboard "changes" overnight for no visible reason |
| `features/dashboard/pages/Dashboard.jsx` | `:253-258`: `.slice(0,6)` + real **"Other (N)"** remainder; null bucket → **"Unknown"**; label **"Top sources (recent 100 leads)"** | **MEDIUM.** Phase 1 **causes** this regression — 25 sources → 7th slice always fallback grey, ~18 vanish. **Third defect verified:** `l.leadSource \|\| "Other"` collides with the real `OTHER("Other")` (`LeadSource.java:15`) — null-source rows **merge into genuine OTHER leads**, so the donut over-reports OTHER and "unknown" is indistinguishable from "the user chose Other". **Not deferrable** |
| `SOURCE_TONE` map (new, shared) | **ONE map, two projections**: `{ 'JustDial': { cls:'bg-blue-100 …', hex:'#60a5fa' } }` — `sourcePill()` reads `.cls`, the donut reads `.hex` | The earlier "source colours from SOURCE_PILL" fix is **unimplementable**: `SOURCE_PILL` holds Tailwind classes, the donut needs hex. Without one map the badge/donut match is a claim with nothing enforcing it. **Needs `\|\| default`** (`STAGE_PILL:44-53` idiom) |
| `features/leads/pages/AllLeads.jsx` | source badge on the row (`leadSource` renders only in the expanded panel at `:652`) | **MEDIUM.** 1761 lines, client-side over one `getAllLeads(0,100)` fetch. `:479` is the orphan-option idiom — **it does not `disabled` the option** |
| `features/leads/api/leadService.js` | `:9` `formData.leadSource \|\| ""` | LOW. The `\|\| ""` is what converts an unknown source into a guaranteed 400 |
| `features/quotation/pages/QuotationWebView.jsx` | add the enquiry form | **MEDIUM.** The page makes **exactly one** network call today (GET at `:94`); every CTA is a `tel:`/`wa.me`/`mailto:` deep link (`:646-662`). **New transport on a public page.** Copy the **whitelist-projection** discipline of the public DTO (`:352-371`) — never blacklist-strip |
| `features/settings/pages/CompanySettings.jsx` | add the Integrations card to `CARDS` (`:19-113`) | LOW. Already a channel-card grid with live-status merge via `Promise.allSettled` + `cardOverride` (`:236-283`). The pattern, not a rewrite |
| `features/marketing/components/marketingUi.jsx` | **copy or promote to `shared/`** — do NOT import from `settings/` | LOW / BOUNDARY. Not exported from `marketing/index.js` → importing it breaches `FEATURE-STRUCTURE.md:81`. Its `ChannelBadge` is a binary WHATSAPP/EMAIL ternary (`:156-164`) a third channel forces into a lookup map anyway |
| `features/leads/components/LeadSummary.jsx` | `:44` `watch("leadSource")` | LOW. Verified it reads the raw value; confirm no source-keyed icon/colour map is added downstream |
| `features/reminders/pages/Reminders.jsx` | `:205` reads `b.leadSource` with a `—` fallback | LOW. Already tolerant. Listed for completeness |
| `CLAUDE.md` | **`D:/CRM PROJECT/frontend` DOES NOT EXIST** — the repo is `travelcrmfrontend`. `src/services/axiosInstance.js` does not exist — the shared client is `src/shared/api/http.js`. Stack is React 18 + Tailwind **v4** (`@theme` block in `index.css:17-30`, **no `tailwind.config.js`**). Also correct "indexes.sql by hand" → hand-**authored**, auto-**applied** | **NONE, and skipping it is a real cost.** Every agent and every new dev reads CLAUDE.md as ground truth and it is currently **wrong about the entire frontend** — and its indexes.sql wording is what produced this program's biggest phantom risk |

#### New files (canon names)

`lead/ingest/` — `LeadIngestGateway` (non-transactional; resolve → verify → stamp → call → publish after commit), `LeadIngestionService`, `LeadIngestOutcome`, `NormalizedLead`, `RawInbound`, `LeadSourceAdapter`, `LeadSourceFetcher`, `InboundParseResult`, `InboundVerification`, `IntegrationCredentials`, `FetchHandle`, `LeadSourceChannel`, `LeadSourceAdapterRegistry` (folds `List<LeadSourceAdapter>` by `channel.slug()` via `ObjectProvider` defaulting to `List.of()`, **throwing on a duplicate slug** — `OtpSenderResolver:20-24`'s bare put is last-one-wins by bean ordering, which here is a **wrong-tenant-attribution bug on a public endpoint**; and **boot-fails if any adapter can return `Deferred` with no fetcher for its channel**), `LeadIngestEvent`, `LeadIngestStatus`, `adapter/WeblinkEnquiryAdapter`, `adapter/JustDialAdapter`.
`lead/enums/LeadOrigin.java` — MANUAL | INTEGRATION | SYSTEM, **one enum** (`ActorOrigin` is deleted).
`lead/entity/LeadAttribution.java` — **an entity, not an `@Embeddable`**; `lead_id` is a **logical FK with no DB constraint** (the fix for the trash-purge rollback hole).
`integration/` — `LeadSourceIntegration`, `IngestTokenService` (`lsk_` + Base64URL-unpadded(32 bytes `SecureRandom`), **whole-token SHA-256 hex**, reveal-once, overlapping rotation with `token_previous_revoke_at` = now + 72h; **full-token logging is BANNED — log `ingest_token_prefix` only**).
`common/context/TenantScope.java` — `withTenant(tenantId, Supplier)`, save/restore around the bare `ThreadLocal` (it has **no stack** — nested set/clear destroys the outer value). New code only.
`lead/dto/LeadSourceOptionDTO.java` + `GET /api/leads/meta/sources`.
`ingest/IngestSecurityConfig` (Phase 3, `@Order(0)`, `securityMatcher("/api/ingest/**")`).
FE: `features/settings/pages/Integrations.jsx`, `features/leads/api/leadMetaService.js`.

---

### Accepted risks

- **The near-term cost ranking is provisional.** R1–R4 gate Phase 2/3's size. Phase 2 is sized **M on the assumption IndiaMART is push**; if R3 says pull, that number is wrong and IndiaMART is not group A.
- **The public site key means anyone can POST leads to a tenant's form endpoint** (Phase 3). Origin allowlist + rate limit + honeypot are the only controls and none authenticates. Inherent to embedded-form SaaS; accepted consciously.
- **The client-side source filter over a 100-row window is a dated ceiling, not a solved problem** — and inbound is exactly what raises the volume past it.
- **The ~9 hand-rolled `TenantContext` sites are not migrated.** They work; touching them risks a silent cross-tenant leak for zero user value.
- **`TenantFilterAspect` keeps failing open.** `PublicQuotationController` depends on it.
- **`RateLimitService` is in-memory per-JVM** — the ingest throttle is incorrect behind more than one node. Accepted for the single-VPS pilot; it is a real gap the day a second node appears.

---

## What we are deliberately NOT doing

Each of these was proposed, considered, and rejected. They are recorded so nobody re-proposes them as an oversight.

### Tenant-context hygiene

**Migrating the ~9 hand-rolled `TenantContext` sites to `TenantScope`.** Zero user value, nonzero regression risk, and the failure mode of getting one wrong is a silent cross-tenant leak with no error and no log (`TenantFilterAspect.java:34-37` — verified: `if (tenantId == null) return;`). They are mostly schedulers that already `clear()` in `finally` with no outer context to destroy (`DocumentExpiryReminderScheduler:44-59`, `CampaignDispatchScheduler:30-42`). Add the helper, mandate it for new code via the ArchUnit allowlist, **leave the working code alone**.

**ONE carve-out:** `TravelerAuthServiceImpl` (`:67` `@Transactional`, `:77` `setTenantId`) is a live latent defect of exactly the too-late ordering class — the aspect is `@Before` on `@Transactional` (`TenantFilterAspect.java:22`, verified), so the filter decision is already latched by the time `:77` runs. `TenantScope`'s guard would throw on it. That is a bug fix, not hygiene, and **it ships with Phase 1**.

**Making `TenantFilterAspect` fail closed.** The obvious fix and the wrong one. `PublicQuotationController` + `QuotationServiceImpl:342-349` depend on fail-open to serve the public share link, and the aspect enables `softDeleteFilter` unconditionally (`:30-32`) for SuperAdmin/global-master reads. The ingest path is defended by **establishing context BEFORE the transaction** plus three ArchUnit rules — not by changing an aspect every subsystem sits on.

**Adding a stack to `TenantContext`.** It is a bare `ThreadLocal<Long>` and changing it touches every subsystem. `TenantScope.call/run` gives new code the one frame of save/restore it needs, additively.

### Scope

**Migrating WhatsApp into the framework.** It is not a migration, it is building inbound WhatsApp from scratch against three verified gaps:
- no inbound webhook exists (scan Area 1);
- `InteraktWhatsAppSender` discards the response body, so no provider message id is stored to correlate a callback;
- `TenantSettings.whatsAppPhone` is **permanently null** — grep: zero setter calls anywhere, no field on the request DTO, no FE input (`TenantSettings.java:62`, `WhatsAppConfigService.java:124`).

So an inbound WhatsApp webhook **cannot resolve a tenant at all**. Migrating it would also couple OTP delivery — i.e. traveler login — to the lead pipeline, so a lead-ingestion change could break authentication. The single-`@Primary` SPI is the correct shape for its actual job (one provider per deployment). `WHATSAPP` stays a manually-selectable constant per owner decision 4; that is the whole scope.

**Building TRADEINDIA or SULEKHA before a real tenant asks by name.** The constants ship free in Phase 1. An adapter written against a payload shape nobody has seen, for an account nobody holds, cannot be verified and will be rewritten when the first real payload arrives. The framework's promise is that this is **one class later** — prove it, do not pre-pay it.

**The server-side lead-source FILTER.** Blocked on moving the lead list server-side: `LeadRepository` extends `JpaRepository` only (no `JpaSpecificationExecutor`, verified), `AllLeads` is 1761 lines filtering client-side over one `getAllLeads(0,100)` fetch (`leadService.js:45-46`), and the fix is Specifications + **all** filters moved together + manualPagination + a summary endpoint. Larger than the badge and the Integrations page combined. The badge on every row plus the existing (equally truncated) search delivers most of the value with none of the lie.

**Auto-refreshing Meta page tokens.** It needs a fresh user authorization and cannot be done unattended; an auto-refresh job would be a lie that fails at exactly the moment it matters. Detect 401/expiry → `status=DEGRADED` + `NotifyEvent` to tenant admins → the tenant reconnects.

**Archiving IVR call recording bytes.** The provider is the system of record with its own retention. Fetching means an authenticated multi-MB download on an ingest thread with no retry infra, unbounded growth against the tenant's metered storage quota, and an SSRF vector. Store the URL, render it as a link, let the browser fetch it. Deferred behind a per-integration `archiveRecordings` flag.

### Data model / schema

**Encrypting the raw payload blob.** The app decrypts on demand with a key in the same process, so it buys little against the realistic threat (a DB dump from a compromised app host), makes the blob un-greppable for the debugging use case that is its entire justification, and inherits the no-rotation problem with no re-enter path — a key change would **permanently destroy** the store. Adapter-declared `secretFieldPaths()` redaction before persist is the control. **Signature headers are KEPT: they are MACs, not secrets.**

**A `LeadSourceChannel` check constraint on `lead_source_integrations.channel`.** The registry is a *stricter* constraint than a CHECK: a CHECK validates spelling and would accept a `META_ADS` row on a node with no Meta adapter deployed; the registry rejects an unknown slug **at save time AND at ingest**. Adding SULEKHA touches the DB not at all.

**Multi-touch attribution (`touch_seq`).** Not asked for, and it complicates every "read this lead's attribution" call site with "which one?". The forward path is purely additive.

**An unassigned-lead inbox.** `assigned_user_id` is NOT NULL with `optional=false` (`Lead.java:65-72`), so it needs a hand `ALTER` plus changes to `ownerId()`, every `ScopeResolver` path, `LeadAccessGuard.assertVisible` and the workload `GROUP BY`s — enormously more than "assign to a configured default owner, else lowest-id active TENANT_ADMIN".

### Framework shape

**A separate `InboundVerifier` bean-registry.** A miss in a verifier registry **fails OPEN** — the exact failure mode being designed against. Verification is the mandatory declarative `verification()` on the adapter, with no default.

**A new `INTEGRATIONS_MANAGE` permission.** `SETTINGS_MANAGE` already gates `EmailConfiguration` and `WhatsAppConfiguration` — per-tenant credentialed channel config, structurally identical — and is verified absent from every non-admin role default, so it is TENANT_ADMIN-only in practice, which is the right blast radius for "who may connect a lead source". A new key costs a `Permission` constant + `defaultsFor(Role)` + a check-constraint refresh + a `UserPermissions` UI row, for one page.

**A new `AssignmentStrategyType` constant for inbound.** `strategy_used` is `@Enumerated(STRING)` on an **existing** column, so a new constant walks into the check-constraint trap (scan finding #5) — and it only bites databases whose table predates the change, i.e. never in dev. A configured default owner IS a manual override → `MANUAL`; the algorithmic pick → `LOAD_BASED`. `createdByName` carries the nuance.

**A captcha provider.** None exists in either repo (grep: zero). Phase 3 ships honeypot + rate limit with the `CaptchaVerifier` SPI stubbed.

**Generalising `CompanySettings`' `CARDS[]` into the Integrations grid.** It is 94 lines for 3 hand-authored descriptors (`CompanySettings.jsx:19-113`) carrying ~12 colour tokens each; at 8–12 server-driven channels it becomes a 400-line static literal hand-edited per adapter — **which is precisely the goal violation**. `CompanySettings` gains exactly ONE card linking to `/integrations`.

---

## Open questions for the owner

Six. Ordered by cost of a wrong answer, not by size.

### 1. Do inbound leads consume the plan lead quota (`Tenant.maxLeads`)? — **BLOCKING**

**Why it matters.** `enforceLeadQuota` throws a 403 (`LeadServiceImpl.java:505-515`) that a webhook caller cannot act on — it retries forever or the enquiry vanishes. Bypassing makes `maxLeads` meaningless the moment a tenant connects JustDial, and inbound is exactly the volume the plan is priced on. This is a **pricing decision wearing an engineering costume**, and Phase 1's machine-create path cannot ship without the answer because quota ordering lives inside the shared `createLeadInternal`.

**Recommendation.** Inbound **does** consume quota. Over-cap **quarantines** (`QUARANTINED_QUOTA`, raw payload retained, 202, admin notified, replayable). Never bypass, never non-2xx. Append consumes no quota (it creates no `Lead` row), so the machine path orders **match FIRST, then quota, then create** — inverting today's order for the machine arm only.

### 2. Is FIRST-TOUCH attribution final?

A repeat inbound contact's campaign is deliberately **not** promoted into the attribution model — it survives only in the raw payload log, which purges at 30 days.

**Why it matters.** Owner decision 1 says a second contact is a follow-up on an existing acquisition, so the acquisition is attributed once. But "first seen on campaign A, re-engaged via campaign B" is a real reporting question; it is cheap now (a `touch_seq` column and a changed unique index) and a **data migration** later — and after 30 days the second touch is gone for good.

**Recommendation.** Ship first-touch. The forward path is purely additive. Say no now and revisit only when a tenant asks a multi-touch question out loud.

### 3. Raw payload retention and call recordings

30 days for the raw blob, attribution fields kept on the lead forever, IVR recording URLs stored but audio never archived. Acceptable under DPDP, and may tenants shorten it?

**Why it matters.** `lead_ingest_events` stores customer name/phone/email and call-recording URLs; an Indian call recording is personal data. There is **no purge path today** and no retention clock anywhere in the framework. Two-tier retention is what makes a short, defensible window politically possible — purging raw is not losing attribution. Compliance decision, not a code decision.

**Recommendation.** 30 days for raw, configurable downward per tenant, capped upward. Attribution persists indefinitely (product data the tenant paid for). Recording URLs stored as opaque strings, rendered browser-side, **never fetched server-side**. Raw payload reads gated behind a NEW TENANT_ADMIN-only `INTEGRATION_RAW_READ` permission — **never bundled into `CRM_FULL`**, which already over-grants to `SUB_AGENT` — and audit-logged on read.

### 4. `GOOGLE_ADS`: Lead Form Extensions webhook (small, Phase 2) or Ads API / gclid offline conversions (XL, Phase 4)?

These are two different projects wearing one enum constant.

**Why it matters.** The constant ships free in Phase 1 either way, but the two readings differ by an **order of magnitude** and the roadmap currently prices only one. LFE is a push webhook that fits TOKEN mode; the Ads API is OAuth + token refresh + a whole subsystem that does not exist anywhere in this codebase (scan reuse inventory: OAuth/token refresh = **BUILD**, nothing exists).

**Recommendation.** LFE webhook only, Phase 2, and **say so explicitly so nobody sells the other one**. Offline conversion upload is a separate program with its own budget.

### 5. Is Lead Source Integrations a paid add-on?

**Why it matters.** If yes, entitlement must be designed **backend-first** — `ModuleAccessFilter` no-ops without a `TenantContext` (`:80-81`), so the webhook path bypasses it entirely and needs the in-handler re-check the design already specifies. The FE gate can only follow, and it **cannot use `hasModule` as-is**: it is fail-open (unknown/failed fetch returns true) and has no TENANT_ADMIN bypass, so it would show the menu to every tenant pre-load and hide it FROM the org admin whose plan excludes it.

**Recommendation.** Not an add-on for the pilot. Ship no FE module gate. If it becomes one, the in-handler re-check is already in the design and `hasModule` needs a TENANT_ADMIN-visible "excluded, upgrade" state before the FE can gate on it.

### 6. Should a channel a tenant has NOT connected appear on the Integrations grid (greyed, "Connect"), or be hidden?

**Why it matters.** Showing all is a soft upsell surface; hiding makes the page empty on day one, which is the day the tenant is most likely to look at it. Pure product call, **zero engineering cost either way**.

**Recommendation.** Show all, greyed, with "Connect". An empty page teaches the tenant the feature is not for them.

---

## Risks accepted

What the owner is agreeing to by approving this design. None of these is a defect to be fixed later; each is a posture.

**The ingest token is an honest bearer capability.** It authenticates nobody and **it will leak** — into nginx access logs (unfixable from Java; an ops decision to suppress the path for this prefix), into provider consoles, into screenshots. Mitigations are reveal-once storage, prefix-only logging, `token_last_used_at` for leak detection, overlapping rotation, and a 401 that does not confirm existence. We mitigate rather than pretend.

**For `WEBSITE_FORM` (Phase 3), the site key is public by construction** — it is in the page source of the tenant's own website. Anyone can POST leads to that tenant's form endpoint. Origin allowlist + rate limit + honeypot are the only controls and **none is a real authenticator**. This is inherent to every embedded-form SaaS and is not a defect, but it is a conscious posture — and it is why the site key must never be the ingest token.

**`META_ADS` requires ONE platform-level app secret.** Compromise means forged inbound for **every tenant** — a strictly larger blast radius than the per-tenant token model. There is no per-tenant variant; Meta's single-callback model forecloses it. The owner has confirmed Meta stays in scope, so this is accepted, not open.

**First-touch attribution loses a repeat contact's campaign at the 30-day raw purge.** The second delivery's payload is queryable until then; after that it is gone. Forward path is additive (`touch_seq` + a changed unique index).

**A backend `displayName` rename silently degrades the source badge to default grey** (`SOURCE_TONE` is keyed by displayName). Non-crashing, and identical to the existing `STAGE_PILL`/`TYPE_PILL` risk we already live with. Mitigated by the startup assertion that all 25 displayNames are distinct (`LeadSource.fromValue` returns the first declaration-order match on a collision — `LeadSource.java:28-37`).

**The raw payload store is LOSSY if a provider ever sends a non-UTF-8 body** (stored as TEXT, decoded, 64KB cap with a truncation flag). TEXT not `bytea` because support must read it (`AiAuditLog.tool_params` is the in-house precedent, `:46-48`). HMAC verification happens **before** persistence over the exact received bytes, so the stored copy is for debug and re-parse only, **never re-verification**.

**`credentials_expire_at` is schema-ready but INERT until Phase 3** — there is no refresh job and no expiry sweep before Meta exists. A connection with an expiring credential will simply go dark until the DEGRADED detector ships with Meta.

**`key_version` is added now, nullable and unused.** Rotating `app.encryption.key` silently breaks every stored secret, and after this change the failure mode becomes "leads stop arriving" rather than "boot fails". Adding the column now is free; adding it after tenants have connected accounts is a migration. **This is the one place we deliberately pre-pay.**

**Phase 1 ships `phone_normalized` WRITE-ONLY; the append rule (owner decision 1) is therefore INOPERATIVE for cross-format matches until the collision report is clean and `uq_leads_phone_norm_tenant_open` exists.** This must be stated to the owner in exactly those words, or **Phase 1 looks done while decision 1 is dead**. The machine path works from day one against machine-created leads; the human-typed backlog is what waits.

**`LeadLog.ingest_event_id` becomes a dangling pointer 30 days after the append**, because `lead_ingest_events` purges. Accepted because it is a **debug pointer, not the attribution path** — attribution lives on `lead_attributions` and persists indefinitely. **The FE must render a missing raw event as "payload expired", not as an error.**

**`leads` and `customers` stay differently keyed** until a later slice extends `phone_normalized` to `customers.phone` — so `BookingServiceImpl.resolveOrCreateCustomer` (`:321-363`) can still spawn a duplicate customer on conversion. Pre-existing, not a regression, but this slice makes the asymmetry look permanent.

**Processing is SYNCHRONOUS in Phase 1.** Worst case is a provider timeout followed by a retry our dedup absorbs. The alternative (an in-memory executor) **drops leads on JVM restart with zero trace**, because `TenantContext` does not propagate across threads and no durable queue exists (scan reuse inventory: retry/backoff/DLQ = **BUILD**). Retry beats loss for revenue-bearing data.

**The near-term channel cost ranking is PROVISIONAL until research tasks R1–R4 land.** If IndiaMART is a pull API, it is not group A and Phase 2's size is wrong. Stated rather than hidden behind an intuition-shaped ordering.
