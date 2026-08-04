# Lead Module — Bug List

**Audit date:** 2026-08-04
**Scope:** the Lead module only — backend `lead/` (87 sources: core, claim, assignment, alert, SLA, attribution, logs, ingest), the `leadsource/` gateway path that creates leads, and every frontend screen that reads or writes lead data (`features/leads/` plus the lead touchpoints in app chrome, dashboard, reports, settings and the lead→quotation/booking/reminder hand-offs).
**Repos:** backend `D:\CRM PROJECT\travelcrmbackend`; frontend `D:\CRM PROJECT\travelcrmfe\travelcrmfrontend` (cited below as `[FE]`).

> This file previously held a repository-wide audit (15 findings across boot, portal, notifications, billing, storage). That content was replaced on request and remains retrievable from git at commit `3d986d6`.

## Method and limits

- Five parallel readers covered the module, each restricted to a slice and required to read whole files rather than grep excerpts.
- Every candidate finding was then re-checked by an independent verification pass that re-opened the cited source, confirmed the line numbers, and looked for a guard the finder may have missed (a caller-side check, a `@PreAuthorize`, a repository `WHERE` clause, or an existing test). **1 candidate was rejected** as a false positive and is not listed. **2 were merged** into the findings they duplicated. Line citations corrected during verification are reflected below.
- Findings are marked `Confirmed` where the failure follows directly from the cited code, and `Plausible` where the mechanism is real but the impact is bounded or depends on configuration.
- **This is a static audit. No test suite was run and nothing was reproduced against a live database.** Existing lead tests were read to avoid reporting covered behaviour — note there is no `LeadServiceImplTest` anywhere under `src/test/java/com/crm/travelcrm/lead/`, so the core create/update paths have no unit coverage at all.
- Design decisions recorded as deliberate in the code are **not** reported as bugs: the tenant-wide claim-window widening (`LeadAccessGuard.java:53-98`), the unpaged Kanban board fetch, enum `displayName` wire values, and name-based city resolution.

## Severity → priority

| Priority | Severity | Meaning |
|---|---|---|
| **P1** | Blocker / Critical | Breaks a supported build or boot path, exposes credentials, or crosses a tenant/customer boundary. |
| **P2** | High | Missing authorization, entitlement bypass, or major data-integrity / silent-data-loss failure. |
| **P3** | Medium | Concurrency, reliability, or operational defect with a bounded blast radius. |
| **P4** | Low | Correctness defect with limited immediate impact. |

**No P1 finding was identified in the lead module.** Nothing here crosses a tenant boundary, bypasses authentication, or prevents boot. The highest band is P2.

> **One judgement call worth the owner's attention.** `LEAD-01` and `LEAD-02` let a franchise **sub-agent** read and disrupt the parent agency's leads. Both verification passes rated these **High**, on the grounds that a sub-agent is a user *inside* the tenant, so no tenant boundary is crossed. If you regard a franchise partner as a separate commercial party — they are a different business sharing the tenant, and the codebase states the exclusion as an invariant in three separate places — then these two escalate to **P1** and should be fixed before anything else. That is a product call, not a code question.

---

## Prioritized summary

| ID | Priority | Area | Finding |
|---|---|---|---|
| LEAD-01 | P2 | Claim / authorization | `SUB_AGENT` can lock any open lead in the parent tenant via `POST /{id}/contacted` — the exclusion enforced on `claim()` is missing on the contact path. |
| LEAD-02 | P2 | Alerts / row scope | The open-lead feed and the tenant-wide SSE broadcast hand a `SUB_AGENT` the parent agency's raw enquiries, with phone numbers and budgets. |
| LEAD-03 | P2 | Update / claim window | `PUT /api/leads/{id}` writes `leadStage` directly, so the claim window never closes on the edit path — and the leads-list Stage dropdown is that path. |
| LEAD-04 | P2 | Assignment / authorization | `PUT /api/leads/{id}` changes the lead owner through a validator that checks only tenant + active, bypassing the eligible pool and `LEAD_REASSIGN_LOCKED`. |
| LEAD-05 | P2 | Machine ingest | Ingest handles 2 of the 4 exceptions `createLead` throws; the other two silently discard paid inbound leads and answer the provider `200`. |
| LEAD-06 | P2 | Alerts / app chrome | ~~The global new-lead alert host is permanently dead — it reads context keys the provider does not publish, so it renders `null` on every page.~~ **Fixed** in FE `408cd8b`; the audit read a pre-commit stash. See the resolution note in the LEAD-06 section. |
| LEAD-07 | P2 | Follow-ups / reports | Follow-up "Mark complete" and bulk-complete never call the server; they mutate local state and report success. |
| LEAD-08 | P2 | Leads list | The main lead list fetches one 100-row page and does all search, filtering and stat maths in memory — older leads are unreachable and uncounted. |
| LEAD-09 | P3 | Claim / CONVERTED invariant | `markContacted` has no terminal-stage guard, so "Mark Contacted" flips a `CONVERTED` lead back into the live pipeline. |
| LEAD-10 | P3 | Machine ingest / validation | Machine-created leads bypass `@Valid`, producing leads the edit form can never save again — or rolling back the whole delivery. |
| LEAD-11 | P3 | Assignment / transactions | The `REQUIRES_NEW` pointer provisioner takes a second pooled connection on every create while holding a pessimistic lock, against a pool of 10. |
| LEAD-12 | P3 | Alerts / SLA tiles | `LeadAlertService.stats()` compares tenant-zone wall clocks against JVM-zone timestamps, so New-Today and SLA-Breach are both wrong by the offset. |
| LEAD-13 | P3 | SSE / session lifetime | SSE emitters have no server-side timeout and are never revalidated, so a deactivated user's open tab keeps receiving tenant-wide lead PII. |
| LEAD-14 | P3 | Activity logs | `getLogSummary` and `getLogStats` load every log row in the tenant into memory; `perPage=-1` returns a 500. |
| LEAD-15 | P3 | Ingest / notifications | `IngestPolicy.MACHINE` still publishes `LEAD_CREATED` inside the ingest transaction — double notification plus a pre-commit SSE push. |
| LEAD-16 | P3 | List / board reads | Lead list and Kanban board bypass `PageSupport`: unvalidated `sortBy` (500), unclamped page size, no stable sort tiebreaker. |
| LEAD-17 | P3 | Create / soft delete | The restore-available check ignores the terminal-stage rule, so a trashed `CONVERTED`/`LOST` lead blocks the same customer's next enquiry. |
| LEAD-18 | P3 | Save path (FE↔BE) | The lead form's phone rule accepts values the backend `@Pattern` refuses — the field's own placeholder is one of them. |
| LEAD-19 | P3 | Activity logs | The routed `/AddLeadLog` page never calls the backend: it fakes a 1-second delay and reports "Log saved successfully". |
| LEAD-20 | P3 | Logs routing | `/LeadLogs/:id` and `/AddLeadLog/:id` are navigated to but never registered, and the bare routes call the API with `undefined`. |
| LEAD-21 | P3 | Lead reports | Geographic Distribution crashes to the route error boundary when a search is typed and any row has a null country. |
| LEAD-22 | P3 | Dashboard | The dashboard fallback presents tenant-wide lead totals computed from a 100-row page, under a green "Live Data" badge. |
| LEAD-23 | P4 | Create / lead code | `LeadCodeGenerator` recovers from a lost insert race by re-reading inside the same, now-aborted, transaction. |
| LEAD-24 | P4 | Assignment | Forced self-assignment skips the assignable-pool rule, so a lead can be created owned by a user who cannot read leads. |
| LEAD-25 | P4 | Claim / audit trail | Post-lock reassign is check-then-act with no version guard, so two concurrent reassigns record a handover that never happened. |
| LEAD-26 | P4 | Logs / reminders | Follow-up reminders are timed with the server's zone despite the comment promising the tenant's 09:00. |
| LEAD-27 | P4 | Claim SSE | The Incoming-Leads LIVE/OFFLINE pill latches to OFFLINE after the first transient error and never recovers. |
| LEAD-28 | P4 | Alert transport | Every tab opens two SSE connections — the Navbar bell and the lead-alert provider each build their own `EventSource`. |
| LEAD-29 | P4 | Dashboard | Four hero cards render hardcoded trend percentages next to a "Live Data" badge. |
| LEAD-30 | P4 | Logs enum drift | The lead-logs stage filter offers a phantom "Ready to Book" stage and omits the real "Reopened". |
| ~~LEAD-31~~ | ✅ Fixed | Leads list | ~~The Import button opens a file picker whose `<input type="file">` has no `onChange`.~~ Bulk CSV/Excel import shipped — see below. |

---

# P2 — High

## LEAD-01 — `SUB_AGENT` can lock any open lead in the parent tenant

**Priority:** P2 · **Severity:** High · **Confidence:** Confirmed

### Evidence

- `lead/claim/controller/LeadClaimController.java:60-61` — `POST /api/leads/{publicId}/contacted` is gated `@PreAuthorize("hasAuthority('LEAD_UPDATE')")` and nothing else. The method annotation overrides the class default, as the class javadoc at `:26-29` states.
- `permission/enums/Permission.java:357-358` — `SUB_AGENT` holds `LEAD_UPDATE` by default, so it passes that gate.
- `lead/claim/service/LeadClaimService.java:155` — `markContacted` resolves via `leadAccessGuard.requireVisibleOrClaimable(...)`; `lead/service/LeadAccessGuard.java:84-86` returns the lead **before** `assertVisible` for any open lead, so no row-scope check runs.
- `lead/claim/service/LeadClaimService.java:106` and `:395-403` — `claim()` calls `assertMayOwnLeads`, which 403s anyone outside the assignable pool. `markContacted` (`:154-157`) and `stampFirstContact` (`:172-220`) have **no equivalent check** — only `isEngaged(targetStage)` and the SQL preconditions.
- `lead/assignment/service/AssignableUserResolver.java:44` — the pool filters out `Role.SUB_AGENT`; the class javadoc at `:17-21` states "a sub-agent can only ever own its own leads".
- `db/migration/V2__lead_code.sql:3743-3745` — "Deliberately NOT SUB_AGENT: … a claim grant would be a second door into exactly what that excludes." The contact endpoint is that second door, reached with `LEAD_UPDATE` instead of `LEAD_CLAIM`.
- `lead/repository/LeadRepository.java:341-359` — `markContacted` matches on `id + tenantId + deletedAt IS NULL + firstContactedAt IS NULL`, with no owner or role predicate.
- `lead/claim/controller/LeadClaimController.java:86-95` — the only undo, `/reopen-claim`, requires `LEAD_REASSIGN_LOCKED` (MANAGER/TENANT_ADMIN only), so the affected agents cannot reverse it themselves.
- `subagent/service/SubAgentServiceImpl.java:84` creates these users with `.role(Role.SUB_AGENT)` inside the parent's tenant — the scenario is reachable in the shipped product.
- Not covered by tests: `LeadClaimServiceTest` exercises `markContacted` at `:210-217`, `:383-393`, `:414-426`, `:436-444`, `:460-465`, `:478-483` (stage guard, SLA preservation, lost race) but never an ineligible actor. `LeadClaimPermissionDefaultsTest.java:34-37` asserts the exclusion **on the claim path only**.

### Failure scenario

Franchise sub-agent `S` reads a lead's `publicId` from `GET /api/leads/alerts/open` (see LEAD-02) and sends `POST /api/leads/{L}/contacted` with an empty body. `requireVisibleOrClaimable` short-circuits on `isOpenToClaim`, no eligibility check runs, and the UPDATE matches: `first_contacted_at = now`, `first_contacted_by_user_id = S`, `first_response_seconds ≈ 30`, `lead_stage = CONTACTED`, `claim_version + 1`. The lead vanishes from every agent's claim feed, stays owned by an agent who never spoke to the customer, and the tenant's SLA tile records a 30-second first response that never happened. Iterating the open feed disables the parent agency's entire incoming-lead pipeline.

### Recommended fix

Apply the eligibility gate `claim()` already uses, inside `stampFirstContact` (`LeadClaimService.java:173`) rather than `markContacted`, so the Kanban drag path (`LeadServiceImpl.java:701`) is covered too: `assertMayOwnLeads(tenantId, actor)`. If a non-assignable user must legitimately be able to mark contact, at minimum call `leadAccessGuard.assertVisible(lead, WRITE_SCOPE_KEY)` for any actor outside `AssignableUserResolver.resolve(tenantId, LEAD_READ)`.

---

## LEAD-02 — The open-lead feed and SSE broadcast expose the parent agency's enquiries to a `SUB_AGENT`

**Priority:** P2 · **Severity:** High · **Confidence:** Confirmed

### Evidence

- `lead/alert/LeadAlertController.java:27` — class-level `@PreAuthorize("hasAuthority('LEAD_READ')")` is the only gate; neither `/alerts/open` (`:33`) nor `/alerts/stats` (`:40`) overrides it, and neither requires `CRM_FULL`.
- `lead/alert/LeadAlertService.java:49-56` — the feed is tenant-wide with no caller narrowing; `:69-95` computes tenant-wide tiles.
- `lead/alert/LeadAlertAssembler.java:47-71` — the returned `LeadAlertDto` carries `customerName`, `phone`, `value` (budget), `destination`, pax counts, `ownerName`, `leadCode`.
- `notification/infrastructure/sse/SseEmitterRegistry.java:121-137` — `pushToTenant` iterates every emitter registered under the tenant with no per-recipient filter; `lead/alert/LeadAlertBroadcaster.java:38-50` routes `NEW_LEAD`/`CLAIMED`/`LOCKED` through it.
- `permission/enums/Permission.java:357-358` — `SUB_AGENT` holds `LEAD_READ`. `permission/service/ScopeResolver.java:92` — `case SUB_AGENT -> Scope.OWN; // franchise partner: strictly its own records`.
- `auth/enums/Role.java:33-36` — "Fail-closed: NO CRM_FULL. A sub-agent gets ZERO coarse authority." The house pattern for tenant-wide roll-ups is to gate on `CRM_FULL` precisely to block sub-agents — `BookingController.java:228,237,252`, `CustomerController.java:122,129` and `CalendarController.java:50` all carry that gate with an explicit "blocks sub-agents" comment. `LeadAlertController` carries none.
- `lead/claim/service/LeadClaimService.java:326` — `history()` (`GET /{id}/assignment-history`, `LEAD_READ`) resolves through the same widened guard, so the ownership timeline of any open lead is reachable too.

### Failure scenario

Sub-agent `S` calls `GET /api/leads/alerts/open` and receives up to 200 of the parent agency's unclaimed enquiries — prospect name, phone number, destination, party size and budget — for customers `S` has no relationship with and can never own. With a tab open, `S` also receives the `lead-alert` SSE push for every new enquiry in real time, ahead of the agents meant to work it. `/alerts/stats` additionally exposes tenant-wide `newToday`, `openToClaim`, `avgFirstResponse` and `slaBreaches`.

The tenant-wide feed itself is the owner-approved widening and is **not** the defect. The defect is the inclusion of a principal the codebase states in three places may never own or claim these rows: the widening's own justification (`LeadAccessGuard.java:57-60`) is that an OWN-scoped agent who cannot see the lead cannot claim it — a principal that can *never* claim gets the widening's reach with none of its rationale.

### Recommended fix

Exclude principals outside the assignable pool from the feed and the broadcast: add `hasAuthority('CRM_FULL')` alongside `LEAD_READ` on `LeadAlertController` (matching `BookingController.java:228` / `CalendarController.java:50`), and give `SseEmitterRegistry` a tenant-broadcast audience filter that skips emitters whose user is absent from `AssignableUserResolver.resolve(tenantId, LEAD_READ)`. Apply the same exclusion to `LeadClaimService.history` (`:326`).

---

## LEAD-03 — `PUT /api/leads/{id}` never closes the claim window

**Priority:** P2 · **Severity:** High · **Confidence:** Confirmed

### Evidence

- `lead/service/LeadServiceImpl.java:469-470` — `updateLead` calls `assertConversionStageTransitionAllowed(...)` then `lead.setLeadStage(request.getLeadStage())`. Nothing between `:445` and `:546` touches `firstContactedAt`, `firstResponseSeconds` or `claimVersion`. The only gate on the method is `requireVisible(publicId, "LEAD_UPDATE")` at `:449`.
- `lead/service/LeadServiceImpl.java:700-712` — the sibling door `updateLeadStage` routes an engaged stage through `leadClaimService.stampFirstContact(...)`, commented: *"a direct save would move the lead to Qualified while leaving it advertised in the claim feed with its SLA clock still running, and two people would keep being able to take it."*
- `lead/claim/service/LeadClaimService.java:159-171` — *"Both doors MUST come through here."* `updateLead` is a third door that does not.
- **The frontend only ever uses the broken door.** `[FE] src/features/leads/pages/AllLeads.jsx:1523-1550` — the row Stage dropdown's `handleStageChange` builds `completePayload = {...leadToUpdate, leadStage: newStage}` and calls `leadService.updateLead(...)`; `[FE] src/features/leads/api/leadService.js:275-276` maps that to `API.put('/leads/{publicId}')`. The purpose-built `@PatchMapping("/{publicId}/stage")` (`LeadController.java:105-115`) is called from **nowhere** in the feature. `[FE] src/features/leads/pages/EditLead.jsx:1036` seeds `leadStage` into the edit form, which posts the same way.
- `lead/repository/LeadRepository.java:184-196` — `findOpenToClaim` filters on `firstContactedAt IS NULL AND leadStage NOT IN :terminalStages`, so the stage change alone does not remove the lead from the feed.
- `lead/repository/LeadRepository.java:309-325` — `claimLead` succeeds on any row with `firstContactedAt IS NULL`, non-terminal stage and a matching `claimVersion`, and transfers `assignedUser`.
- `lead/repository/LeadRepository.java:258-272` — `countSlaBreaches` counts such a lead as breached indefinitely.
- Neither repair path works: `reopenClaimWindow` (`LeadClaimService.java:288-297`) rejects it as "already open to claim", and `reassign` (`:234-239`) rejects it as `NOT_LOCKED`.

### Failure scenario

Agent A picks up a new lead and sets its Stage dropdown from "New Lead" to "Contacted" — the primary path agents actually use. The row becomes `lead_stage = CONTACTED` with `first_contacted_at` still `NULL`. The lead therefore stays in `/leads/incoming` with its SLA countdown running and its "Claim & override" button armed; Agent B takes ownership of a customer A has already phoned. The lead's name, phone and budget keep being broadcast tenant-wide past the point the design says the window closes, and `first_response_seconds` is never written, so the SLA average is permanently wrong for every lead advanced this way.

### Recommended fix

Two halves, both needed. **Frontend:** add `updateLeadStage: (publicId, leadStage) => API.patch('/leads/' + publicId + '/stage', { leadStage })` to `leadService.js` and call it from `handleStageChange` — this also stops the row dropdown re-posting ~40 unrelated fields through the create-shaped `transformFormData` (see LEAD-18). **Backend:** extract the stage-transition logic at `LeadServiceImpl.java:683-714` into one private method that both `updateLead` and `updateLeadStage` call, so a fourth write path cannot reintroduce the gap. Note `stampFirstContact` runs a bulk UPDATE that clears the persistence context — re-read the entity after calling it.

---

## LEAD-04 — `PUT /api/leads/{id}` changes the owner without the eligible-pool or `LEAD_REASSIGN_LOCKED` rules

**Priority:** P2 · **Severity:** High · **Confidence:** Confirmed

### Evidence

- `lead/service/LeadServiceImpl.java:474` — `lead.setAssignedUser(resolveAssignedUser(request.getAssignedUserId(), tenantId))` on the update path.
- `lead/service/LeadServiceImpl.java:854-878` — `resolveAssignedUser` forces self-assignment only when the **caller** is a sub-agent, then accepts any `findByPublicIdAndTenantIdAndDeletedAtIsNull` user that is `isActive`. No `AssignableUserResolver` membership check, no caller-role check, no locked-lead check.
- Every other ownership path enforces the rule: create forces self for non-privileged callers (`lead/assignment/service/LeadAssignmentService.java:144-151`) and runs `assertInPool` on a privileged caller's explicit choice (`:189-191`, `:404-412`); post-lock reassign is gated `@PreAuthorize("hasAuthority('LEAD_REASSIGN_LOCKED')")` (`LeadClaimController.java:71-80`) and resolves the owner through `resolveAssignableUser` (`LeadClaimService.java:406-417`).
- `lead/assignment/service/AssignableUserResolver.java:44` excludes `SUB_AGENT`; `:58-62` filters on effective `LEAD_READ`.
- `permission/enums/Permission.java:298-303` — `TRAVEL_AGENT` holds `LEAD_UPDATE` and deliberately **not** `LEAD_REASSIGN_LOCKED`: *"once a colleague has spoken to the customer, taking the lead is a manager's call, not a peer's."*
- `lead/controller/LeadController.java:117-118` — the endpoint's only gate is `hasAuthority('LEAD_UPDATE')`.
- `updateLead` writes no `LeadAssignmentEvent`; the only recorder calls are in `createLead` (`:183-184`) and inside `LeadClaimService.recordEvent`.

### Failure scenario

A `TRAVEL_AGENT` owning an already-contacted lead sends `PUT /api/leads/{L}` with the body the edit form produces and `assignedUserId` set to an active `SUB_AGENT`. `requireVisible` passes (they own it), `resolveAssignedUser` accepts the target (same tenant, active), and the transfer persists. The franchise sub-agent's OWN row-scope now includes the lead, so the parent agency's customer name, phone, email, budget, notes, itinerary and quotations become visible in the sub-agent's own list — the exact outcome `AssignableUserResolver` exists to prevent. The same request with any other user performs a post-lock reassignment without holding `LEAD_REASSIGN_LOCKED`. Because no `LeadAssignmentEvent` is written, `GET /{id}/assignment-history` still shows the original assignee.

### Recommended fix

Route the update path's owner change through the same validation as create/reassign: resolve the requested assignee via `AssignableUserResolver.resolve(tenantId, Permission.LEAD_READ)` membership, and refuse an owner **change** on a locked lead unless the caller holds `LEAD_REASSIGN_LOCKED`. Simplest correct option: ignore `assignedUserId` in `updateLead` entirely and require callers to use `POST /{id}/claim` or `/reassign`. Where the owner does change, write a `LeadAssignmentEvent`.

---

## LEAD-05 — Machine ingest silently discards inbound leads and answers the provider `200`

**Priority:** P2 · **Severity:** High · **Confidence:** Confirmed

### Evidence

- `leadsource/gateway/LeadIngestService.java:88-100` — `try { return create(...) } catch (LeadQuotaExceededException) {...} catch (NoEligibleAssigneeException) {...}`. No other exception is handled.
- `lead/service/LeadServiceImpl.java:126` and `:129` — `createLead` runs `validateNoDuplicates` then `checkTrashedForRestore` on **every** path, including `MACHINE`.
- `lead/service/LeadServiceImpl.java:919-928` throws `DuplicateLeadException` on an open email match; `:930-939` on a raw-string phone match; `:948-970` throws `RestoreAvailableException` when a **soft-deleted** lead shares the email or phone. The latter is an interactive affordance ("Restore it instead of creating a duplicate") with no meaning on a webhook thread.
- `leadsource/gateway/LeadIngestGateway.java:167-176` — the outer `catch (Exception e)` records `FAILED` and returns `IngestResponse.accepted()` (HTTP 200 at `:374-375`), explicitly so the provider does **not** retry.
- The append-on-repeat path does not shield either branch: `LeadIngestService.java:110-116` probes `phoneNormalized` while `validateNoDuplicates` compares the raw phone string.
- Contrast: the quota case was given a designed answer — quarantine at `:90-94` plus an admin/manager notification at `:365-377` ("the enquiry is VISIBLE rather than lost"). These two paths get neither.

### Failure scenario

An agent trashes a lead for `+919876543210`. The same customer enquires again through JustDial/WhatsApp. `findOpenLeadByPhone` finds nothing (deleted rows excluded), `validateNoDuplicates` passes (deleted rows excluded), then `checkTrashedForRestore` throws. The gateway writes `FAILED` and answers `200`. No lead, no log, no notification, no provider retry — and **every future enquiry from that number fails identically, forever**. Second reachable case: two family members submit a Google Ads / website form from one shared mailbox; the second has a different phone so append does not fire, `validateNoDuplicates` sees the first one's open lead on that email, and the enquiry is discarded.

### Recommended fix

Catch `DuplicateLeadException` and `RestoreAvailableException` in `LeadIngestService.ingest` and give each a machine-appropriate outcome: for the duplicate case degrade to the existing `append()` on the matched open lead; for the trashed-match case return a quarantined outcome that notifies admins/managers exactly as `quotaQuarantine` does. `checkTrashedForRestore` should be skipped entirely for `IngestPolicy.MACHINE`.

---

## LEAD-06 — The global new-lead alert host is permanently dead

**Priority:** P2 · **Severity:** High · **Confidence:** Confirmed · **Status: FIXED** (see Resolution below)

### Evidence

- `[FE] src/app/chrome/LeadAlertHost.jsx:93-94` — `const toasts = ctx?.toasts; const dismissToast = ctx?.dismissToast;`
- `[FE] src/features/leads/hooks/useLeadAlerts.jsx:362-381` — the provider's context value publishes `{ leads, stats, loading, connected, cards, refresh, refreshStats, applyResult, patchLead, dropLead, dismissCard, isMine, … }`. There is **no** `toasts` and **no** `dismissToast`. The broadcast state is `cards` (`:66`) and its remover is `dismissCard` (`:358-360`).
- `[FE] src/app/chrome/LeadAlertHost.jsx:97` — `if (!toasts?.length || !dismissToast) return;` so the chime/auto-dismiss effect returns on its first line.
- `[FE] src/app/chrome/LeadAlertHost.jsx:109` — `if (!ctx || !toasts?.length) return null;` — `undefined?.length` is `undefined`, so the component renders `null` unconditionally.
- `[FE] src/app/Layout.jsx:99,142` — the provider wraps the chrome and `<LeadAlertHost/>` is mounted on every authenticated page.
- The provider's `cardUpsert` (`useLeadAlerts.jsx:206-222`, called from the SSE handler map at `:227-261`) builds a card on every `lead-alert` / `lead-claimed` event, and **nothing anywhere renders `cards`** — the `/leads/incoming` page reads `leads`/`stats`/`isMine`/`canClaim` and never touches them.
- `lead/alert/LeadAlertBroadcaster.java:38-40` with `SseEmitterRegistry.java:121-137` — the backend really does broadcast to every connected user, so the data arrives; only the render is missing.
- Corroborating drift: `[FE] LeadAlerts.jsx:563-565` advertises "Alt + C — Claim the newest alert card, from any page" and "Alt + X — Dismiss"; `LeadAlertHost` registers no keydown listener at all.

### Failure scenario

A new lead is ingested. The backend fans `lead-alert` out to every open tab; the provider pushes a card into `cards`; the host evaluates `ctx?.toasts` → `undefined` and renders nothing. No popup, no chime, no "Claim & override" button — on any page. An agent on the bookings screen never learns the lead exists and the SLA countdown runs down unnoticed. Optional chaining swallows the mismatch, so there is no error, no console warning and no crash: the feature looks shipped and monitors clean. `claimFromToast` (`:111-124`) would additionally throw `ctx.dismissToast is not a function` at `:122` if it were reachable.

### Recommended fix

Rename the host's reads to the contract the provider publishes (`ctx?.cards`, `ctx?.dismissCard`), key the effect and render on `card.leadPublicId` rather than the non-existent `toastId` (use `arrivedAt` to decide whether to re-chime), and replace `ctx.dismissToast(alert.toastId)` at `:122`, `:156` and `:204` with `ctx.dismissCard(alert.leadPublicId)`. Since this is a cross-module contract with no compile-time check, export a named selector (e.g. `useLeadAlertCards()`) from the leads barrel so chrome cannot silently drift again.

### Resolution — **Fixed**

The audit read the working tree as it stood at stash `9810767` (4 Aug, 00:55). Frontend commit `408cd8b` (4 Aug, 10:39) replaced `LeadAlertHost.jsx` with a rewrite that already carries the fix; the file has never been committed in the broken form. Verified against the current tree:

- The host reads `ctx?.cards` / `ctx?.dismissCard` and keys every effect, timer and DOM node on `card.leadPublicId`, restarting a card's clock off `arrivedAt` (`LeadAlertHost.jsx:134-135,166-180`). `toasts` / `dismissToast` / `toastId` no longer appear anywhere in the repo.
- Every key the host touches — `cards`, `dismissCard`, `applyResult`, `patchLead`, `markClaimIneligible`, `canClaim` — is published by the provider's context value (`useLeadAlerts.jsx:407-426`).
- Every card field the host renders — `leadPublicId`, `leadCode`, `customerName`, `phone`, `source`, `sourceKey`, `destination`, `value`, `ownerName`, `claimVersion`, `slaSecondsRemaining` — exists on `LeadAlertDto`, so the popup renders real data rather than a card of blanks.
- The corroborating drift is closed too: `Alt+C` / `Alt+X` / `Alt+I` are registered on `document` (`LeadAlertHost.jsx:272-295`), matching what `LeadAlerts.jsx:750-752` advertises.

The recommended drift guard was implemented as a dev-only contract assertion in the host (`LeadAlertHost.jsx:40-58,131-146`) rather than a named barrel selector: the shipped host needs six context keys, not just `cards`, so a `useLeadAlertCards()` selector would not have covered the surface that broke. `import.meta.env.DEV` folds the check out of the production bundle. The frontend has no test runner (`package.json` has no `test` script and no vitest/jest dependency), so the contract test suggested at the end of this document remains open as infrastructure work.

---

## LEAD-07 — Follow-up "Mark complete" and bulk-complete never reach the server

**Priority:** P2 · **Severity:** High · **Confidence:** Confirmed

### Evidence

- `[FE] src/features/reports/pages/FollowupReports.jsx:713-724` — the only server call in `handleComplete` is commented out at `:718`; the `try` awaits nothing, the `catch` is unreachable, and `showToast("Task marked as completed.")` always fires.
- `[FE] src/features/reports/pages/FollowupReports.jsx:727-731` — `handleBulkComplete` is not even `async`: it maps local state and toasts `"${selected.size} tasks marked as completed."` with no request.
- `[FE] .../FollowupReports.jsx:1091`, `:1248`, `:1348` — the bulk button and both desktop and mobile row buttons wire straight to those handlers.
- `[FE] src/features/reports/api/followupReportService.js:152-154` and `:173-175` — `markComplete(id)` → `PATCH /reports/followup/tasks/{id}/complete` and `bulkComplete(ids)` are already implemented and imported nowhere; the page uses the service only for `getTasks` (`:613`).
- `report/followup/controller/FollowupReportController.java:54-57` and `:59-62` — both endpoints exist server-side and are live.
- `[FE] .../FollowupReports.jsx:601-602` — `mapToTask` re-derives `completed` from the server response and already stores `reminderId: r.publicId || r.id`, the exact id the endpoint takes.

### Failure scenario

An agent ticks 20 overdue lead follow-ups and clicks bulk complete. A green toast confirms "20 tasks marked as completed", the rows grey out and the counters drop. Nothing was sent. On refresh — or for any other user, or on the next `fetchData()` from the Refresh button — all 20 rows are back as overdue, because `mapToTask` re-derives `completed` from the server. The agent cannot tell which follow-ups they already actioned; managers reading the same report chase leads that were already contacted.

### Recommended fix

`await followupReportService.markComplete(task.reminderId)` before mutating state, and let the `catch` revert the optimistic row. Make `handleBulkComplete` async and call `bulkComplete([...selected].map(id => tasks.find(t => t.id === id)?.reminderId).filter(Boolean))`, then drive the toast from the response's `completed`/`failed` counts. Note the local `id` is a synthetic row index assigned at `:579` — the server needs `reminderId`, not `t.id`.

---

## LEAD-08 — The leads list operates on the newest 100 leads and presents them as the whole pipeline

**Priority:** P2 · **Severity:** High · **Confidence:** Confirmed

> The two verification passes split on severity here (Medium vs High). It is recorded as High because the dominant failure — search reporting "no leads" for a lead that exists — leads directly to duplicate lead creation, which is a data-integrity outcome rather than a display one.

### Evidence

- `[FE] src/features/leads/pages/AllLeads.jsx:1468` — `useEffect(() => { fetchLeads(); }, [])`: loads once, no dependency on any filter.
- `[FE] .../AllLeads.jsx:1470-1481` — `leadService.getAllLeads()` with no arguments; no `pagination` meta is read or stored.
- `[FE] src/features/leads/api/leadService.js:267-268` — `getAllLeads: (page = 0, size = 100) => API.get('/leads?page=' + page + '&size=' + size)`.
- `lead/controller/LeadController.java:78-93` — the endpoint pages with `sortBy=createdAt, sortDir=desc`, so the client receives only the 100 **newest** leads, and returns a `PaginationMeta` the frontend discards.
- `[FE] .../AllLeads.jsx:1623-1662` — `filteredLeads` applies `searchTerm` (name/email/phone/leadCode/publicId), `dateFilter`, `startDate`/`endDate` and `activeTab` as in-memory predicates.
- `[FE] .../AllLeads.jsx:1610-1620` — the stat cards (`bookings`, `conversion`, `winRate`) are computed from the same truncated array; `:1762` renders `{safeLeads.length} total`; `:1883-1885` the tab badges.
- `[FE] .../AllLeads.jsx:1676-1690` — TanStack paginates `filteredLeads` client-side, so the pager can never request page 2 from the server; `:1702-1704` — a filter change only resets `pageIndex`, with no refetch path anywhere in the component.

### Failure scenario

An agency has 400 leads. An agent searches the phone number of a customer whose lead was created four months ago. The search runs over the 100 newest rows in memory, the grid shows "No Leads Found", and no page exists that contains the lead. The agent concludes the customer has no record and **creates a second lead** — or tells the customer their enquiry was never logged. The same truncation drives the funnel cards: a tenant that converted 40 of 250 leads is shown 40% instead of 16%, with nothing on screen indicating truncation.

### Recommended fix

Move the list server-side: pass `pagination.pageIndex`/`pageSize` into `getAllLeads`, set `manualPagination: true`, and drive `totalElements`/`totalPages` from the `PagedApiResponse` pagination meta. Push search, the date window and the stage/type tab to the backend as query params (`/leads/logs/summary` is the existing filtered-list pattern), and source the stat cards from a server aggregate — `/leads/stats/by-stage` already exists and is unconsumed. Until then, at minimum show "showing the newest 100 of N" when the returned count equals the requested size. Same root cause as LEAD-22.

---

# P3 — Medium

## LEAD-09 — `markContacted` has no terminal-stage guard, so a `CONVERTED` lead can be pushed back into the pipeline

**Priority:** P3 · **Severity:** Medium · **Confidence:** Confirmed

### Evidence

- `lead/claim/service/LeadClaimService.java:153-157` — `markContacted` calls `stampFirstContact(lead, LeadStage.CONTACTED, note)` with no stage check; `:172-201` — `stampFirstContact` validates only `isEngaged(targetStage)`, never the lead's **current** stage.
- `lead/repository/LeadRepository.java:341-359` — the `markContacted` UPDATE carries **no** `leadStage NOT IN :terminalStages` predicate. Compare `claimLead` (`:309-325`), `reopenClaimWindow` (`:372-388`) and `reassignLockedLead` (`:423-437`), which all have it. `markContactedPreservingSla` (`:398-414`) has the same omission.
- `lead/service/LeadServiceImpl.java:754-766` — `assertConversionStageTransitionAllowed`, the invariant: `CONVERTED` is *"ENTERED only via convert-to-booking and LEFT only via cancelling that booking"*. It is enforced on `updateLead` and `updateLeadStage` only.
- `booking/service/BookingServiceImpl.java:619-625` — conversion sets `CONVERTED`, `convertedAt` and `convertedBookingPublicId` and never touches `firstContactedAt`, so a converted lead with a NULL contact stamp is ordinary.
- `lead/entity/Lead.java:33-40` — `uq_leads_phone_tenant_open` / `uq_leads_email_tenant_open` are partial indexes `WHERE deleted_at IS NULL AND lead_stage NOT IN ('CONVERTED','LOST')`, so the flip re-inserts the row into those indexes.

### Failure scenario

A lead converted straight to a booking has `lead_stage='CONVERTED'`, `converted_booking_public_id` set and `first_contacted_at` NULL. Its owner (a `TRAVEL_AGENT` with default `LEAD_UPDATE`) sends `POST /api/leads/{L}/contacted`. The UPDATE matches on `firstContactedAt IS NULL` alone and writes `lead_stage='CONTACTED'` — an active-pipeline lead still carrying `convertedAt` and `convertedBookingPublicId`. Knock-ons: `first_response_seconds` is computed as `createdAt → now`, so a lead converted three months ago contributes a multi-million-second "first response" to the SLA average; the lead re-enters `ACTIVE_STAGES` for workload scoring and the duplicate check, blocking the customer's next enquiry; and if that customer already has a live open lead, the UPDATE pushes a second row into the partial unique index and dies on a `DataIntegrityViolationException`.

Blast radius is bounded today: the frontend only renders Mark Contacted for rows in the open-claim feed, so this currently requires a direct API call.

### Recommended fix

Add `AND l.leadStage NOT IN :terminalStages` to `markContacted` and `markContactedPreservingSla`, matching their three siblings, and pass `LeadStageGroups.TERMINAL_STAGES` from `stampFirstContact`. Belt-and-braces: reject early in `stampFirstContact` when `!LeadStageGroups.isActive(lead.getLeadStage())` so the caller gets a 409 naming the reason rather than the generic lost-race diagnosis.

---

## LEAD-10 — Machine-created leads bypass `@Valid` and become un-editable

**Priority:** P3 · **Severity:** Medium · **Confidence:** Confirmed

### Evidence

- `leadsource/gateway/LeadIngestService.java:181-201` — `CreateLeadRequestDto` is built programmatically and handed straight to `leadService.createLead(...)`, so no bean validation runs; `request.setPhone(normalized.phoneRaw())` is copied verbatim.
- `leadsource/spi/NormalizedLead.java:36-39` — `phoneRaw` is contractually "passed through untouched"; adapters must not canonicalise.
- `lead/dto/CreateLeadRequestDto.java:28-33` — `@Pattern(regexp = "^\\+?[1-9]\\d{7,14}$")` on phone; `:24-26` `@Size(min = 2, max = 150)` on name; `:38-40` `@Email`.
- `lead/controller/LeadController.java:117-121` — `PUT /api/leads/{publicId}` binds the **same** `@Valid CreateLeadRequestDto`, so every constraint is enforced on edit.
- `lead/entity/Lead.java:66-67` — `phone varchar(20) NOT NULL`; `:63-64` name 150; `:88-89` email 150. These are the only remaining guards and they fail as a `DataIntegrityViolation`, not a validation error.
- Partial awareness: `LeadIngestService.java:307-314` defines `clip()` — *"The DTO caps these at 100 chars; a provider's answer must not fail validation on length"* — and applies it to `departCity`/`departCountry` at `:248-249` and nothing else.

### Failure scenario

JustDial delivers the very common Indian local format `09876543210`. It is 11 characters so it persists, but it fails `^\+?[1-9]\d{7,14}$` (leading zero). The agent opens the lead and tries to set a travel date or move the stage — `PUT /api/leads/{publicId}` returns 400 "Enter a valid phone number" every time, and the frontend surfaces 400s silently by design. The same lock-out follows a provider placeholder email like `not-provided` or a 1-character name. Recoverable (the agent can retype the phone), and `PATCH /{id}/stage`, `/claim` and `/contacted` are unaffected because they do not bind this DTO. The rarer path — a >20-character phone — overflows `varchar(20)`, rolls back the whole ingest transaction, and the gateway records `FAILED` and returns 200, losing the lead.

### Recommended fix

Validate the constructed DTO programmatically on the MACHINE path before calling `createLead` — inject a `Validator` and map violations to a quarantined outcome that notifies admins, rather than throwing. **Note:** the DTO also carries `@NotNull` on `assignedUserId`, which `LeadIngestService.java:195-196` deliberately leaves null because the machine path routes to `assignForInbound` — a programmatic run must exclude that constraint or use a validation group. At minimum apply `clip()` to phone/email/name as it already is to `departCity`, and canonicalise the phone to E.164 rather than storing a string the edit form will refuse.

---

## LEAD-11 — The pointer provisioner takes a second pooled connection on every create, while holding a pessimistic lock

**Priority:** P3 · **Severity:** Medium · **Confidence:** Confirmed

### Evidence

- `lead/assignment/service/LeadAssignmentPointerProvisioner.java:27-37` — `@Transactional(propagation = REQUIRES_NEW) public void ensureExists(Long tenantId)`, with the fast-path `if (repository.existsByTenantId(tenantId)) return;` **inside** the new transaction. The second connection is therefore acquired on every invocation, not only the first-ever call for a tenant.
- `lead/assignment/service/LeadAssignmentService.java:163` and `:258` — `ensureExists` is called unconditionally on the privileged create and inbound paths, before `findByTenantId` takes its `PESSIMISTIC_WRITE` lock.
- `lead/service/LeadServiceImpl.java:136-140` — the outer transaction has already taken the lead-code counter's pessimistic lock (*"a pessimistic lock on the tenant's counter row that must be held to commit"*), so it owns a connection **and** a row lock when it suspends to grab a second connection.
- `application.properties:45,49` — `maximum-pool-size=${DB_POOL_MAX_SIZE:10}`, `connection-timeout=30000`; `application-prod.properties:27,31` repeats 10 and sets `leak-detection-threshold=60000` (too high to flag this).
- The same author identified this exact hazard elsewhere and avoided it: `leadsource/gateway/LeadIngestDeliveryLogger.java:25-27` — *"its transaction commits SEQUENTIALLY, before the ingest transaction opens — never nested, because holding two pooled connections at once against a Hikari pool of 10 is its own hazard."*
- Two further nested `REQUIRES_NEW` hops exist in the same open transaction: `recordAssignmentAudit` and `recordAssignmentHistory` (`LeadServiceImpl.java:183-184`).

### Failure scenario

Ten concurrent `POST /api/leads` in one tenant is the worst case: all ten check out a connection for their `@Transactional`, one wins the lead-code counter lock, the other nine block on that row **while still holding connections**, and the winner then asks Hikari for an eleventh connection at `LeadAssignmentService.java:163` and cannot get one. Everything blocks for the 30 s `connection-timeout` and then fails with `CannotCreateTransactionException`; every lead creation rolls back. The pool cannot self-heal because holders and waiters are the same threads.

### Recommended fix

Move the existence check out of the new transaction: call `pointerRepository.existsByTenantId(tenantId)` on the caller's own connection and invoke `ensureExists` only when it returns false, so the `REQUIRES_NEW` hop happens once per tenant instead of once per lead. Better still, provision the pointer row at tenant creation alongside the other per-tenant singletons and drop runtime provisioning entirely. Review the two recorder hops at `:183-184` the same way.

---

## LEAD-12 — The alert tiles compare tenant-zone wall clocks against JVM-zone timestamps

**Priority:** P3 · **Severity:** Medium · **Confidence:** Confirmed

### Evidence

- `lead/alert/LeadAlertService.java:74-78` — `ZoneId zone = tenantTimeZone.forTenant(tenantId); LocalDate today = LocalDate.now(zone); … LocalDateTime now = LocalDateTime.now(zone);` — bare `LocalDateTime`s in the **tenant's** wall clock, passed straight to the queries at `:82-93`.
- `lead/repository/LeadRepository.java:210-220` and `:258-278` — those parameters are compared against `l.createdAt` / `l.firstContactedAt` and drive `timestampdiff(SECOND, l.createdAt, :now)`.
- `common/entity/BaseEntity.java:43-45` — `@CreatedDate LocalDateTime createdAt`; `common/config/JpaConfig.java:19-21` declares `@EnableJpaAuditing(auditorAwareRef = "auditorAware")` with **no** `dateTimeProviderRef`, so Spring's default provider stamps it in the **JVM's** zone.
- The codebase already compensates for exactly this elsewhere: `calendar/service/CalendarServiceImpl.java:112-117` converts day bounds into `ZoneId.systemDefault()` with a comment naming the same hazard.
- The sibling read uses the opposite convention on the same rows: `lead/alert/LeadAlertAssembler.java:44` uses `LocalDateTime.now()` (JVM zone) to feed `slaPolicy.secondsRemaining`, so the feed row's countdown and the breach tile disagree for one lead.

### Failure scenario

`Dockerfile:27` sets `TZ=Asia/Kolkata` and `TenantTimeZone.java:42` makes Asia/Kolkata the fallback, so for a default-configured tenant the two clocks coincide and the tiles are correct. The defect bites any tenant whose stored timezone differs from the container's — e.g. Asia/Kathmandu (+15 min vs IST, a zone `TenantTimeZone.java:22-23` explicitly names as in scope). `timestampdiff` then over-reports elapsed time by 900 s against a 300 s target, so **every** uncontacted active lead created today is counted as an SLA breach the moment it arrives, while its feed row shows a healthy countdown. "New Today" is likewise shifted by the offset.

### Recommended fix

Convert the tenant-local day boundaries into the JVM wall-clock zone before querying, exactly as `CalendarServiceImpl.java:115-117` does, and pass `LocalDateTime.now()` (JVM zone) as `:now`. The durable fix is to register a UTC `DateTimeProvider` and migrate `created_at`, but that is a separate migration; until then every comparison against `createdAt` must use the JVM zone.

---

## LEAD-13 — SSE emitters are never revalidated, so a revoked session keeps receiving lead PII

**Priority:** P3 · **Severity:** Medium · **Confidence:** Confirmed

### Evidence

- `notification/infrastructure/sse/SseEmitterRegistry.java:57` — `new SseEmitter(0L); // no server-side timeout`.
- `SseEmitterRegistry.java:180-191` — `@Scheduled(fixedRate = 25_000) heartbeat()` actively keeps every emitter alive; a connection is dropped only when a send fails.
- `notification/web/NotificationController.java:130-157` — the JWT is validated exactly once, at connect time; nothing re-checks the token, the user's `isActive` flag or their permissions for the life of the stream, and there is no `deregisterUser` hook anywhere under `notification/`.
- `SseEmitterRegistry.java:121-137` — `pushToTenant` applies no per-recipient authorization.
- `lead/alert/LeadAlertAssembler.java:47-71` with `LeadAlertBroadcaster.java:38-50` — the broadcast payload carries `customerName`, `phone` and `value` (budget). Before this feature an emitter only ever received its own user's notifications, so the lead broadcast materially widened what a stale connection can see.

### Failure scenario

An agent is terminated, the admin deactivates the user and their JWT is invalidated — but their browser tab is still open. The emitter stays registered under the tenant and the 25 s heartbeat keeps it healthy. Every new lead the tenant receives — name, phone, budget — continues streaming to that machine until the tab is closed or the process restarts. Deactivation, permission revocation and JWT expiry all have zero effect on an already-open stream.

### Recommended fix

Give the emitter a finite timeout (slightly under the JWT TTL) so the client must reconnect and re-present a token — the frontend `subscribeToSSE` already rebuilds the `EventSource` with a fresh token on close, so this costs nothing. Additionally expose `deregisterUser(userId)` on the registry and call it from user deactivation and permission revocation to complete open emitters immediately.

---

## LEAD-14 — The log summary and stats endpoints load every log row in the tenant into memory

**Priority:** P3 · **Severity:** Medium · **Confidence:** Confirmed

### Evidence

- `lead/service/LeadLogServiceImpl.java:176-189` — `visibleLogs()` calls `leadLogRepository.findAllForTenantWithLead(tenantId)` unbounded, then applies the caller's row scope with a Java stream filter.
- `lead/repository/LeadLogRepository.java:30-34` — that query is `SELECT ll FROM LeadLog ll JOIN FETCH ll.lead l LEFT JOIN FETCH l.assignedUser … ORDER BY ll.createdAt DESC` with **no** `Pageable`.
- `lead/service/LeadLogServiceImpl.java:114-141` — grouping, search, stage/user filtering and pagination all happen over that in-memory list; `page`/`perPage` only slice at the end.
- `lead/service/LeadLogServiceImpl.java:162-171` — `getLogStats()` calls the same unbounded loader purely to compute two counts, and `LeadController.java:176-181` exposes it as a separate endpoint the same screen hits alongside `/logs/summary`.
- `lead/controller/LeadController.java:163-174` — `page` and `perPage` are accepted as raw ints with no clamping (`perPage` defaults to 12 at `:169`).
- `lead/service/LeadLogServiceImpl.java:138-141` — with `perPage = -1`: `from = max(0, 0 * -1) = 0`, `to = min(0 + -1, total) = -1`, the `from >= total` guard is false for any non-empty result, and `cards.subList(0, -1)` throws.

### Failure scenario

A tenant live for a year has ~60k lead logs across ~8k leads. Every load of the All-Lead-Logs grid materialises 60k `LeadLog` entities plus their `Lead` and `User` graphs **twice** (summary + stats) to render 12 cards; a handful of concurrent users exhausts the heap. Separately, `GET /api/leads/logs/summary?perPage=-1` throws `IndexOutOfBoundsException` and returns a 500.

### Recommended fix

Push grouping and paging into SQL — a query returning one row per lead with its latest log and count, accepting a `Pageable` and the search/stage/user predicates as parameters — and use a `COUNT` / `COUNT(DISTINCT lead_id)` pair for `getLogStats` instead of loading entities. Clamp `perPage` (as `PageSupport` already does elsewhere) before it reaches the slicing arithmetic.

---

## LEAD-15 — `IngestPolicy.MACHINE` still publishes `LEAD_CREATED` inside the ingest transaction

**Priority:** P3 · **Severity:** Medium · **Confidence:** Confirmed

### Evidence

- `lead/service/LeadServiceImpl.java:193` — `publishLeadCreatedNotification(savedLead, tenantId)` is called unconditionally; the `switch (policy)` at `:154-165` is the only place the policy is consulted.
- `lead/ingest/IngestPolicy.java:29-33` — MACHINE's documented contract is the opposite: *"Nothing is published here. Events are handed back for the non-transactional gateway to publish after commit … would also fire an SSE push for a lead a later rollback removes."*
- `leadsource/gateway/LeadIngestService.java:213-224` returns `TYPE_LEAD_CREATED` with `adminAndManagerIds(tenantId)`, and `LeadIngestGateway.java:267`, `:317-334` publishes it a **second** time after commit.
- The audiences overlap: `LeadServiceImpl.java:973-989` targets `AssignableUserResolver.resolve(tenantId, LEAD_READ)`, which excludes only `SUPERADMIN` and `SUB_AGENT` and therefore includes tenant admins and managers.
- `NotifyEventListener` is a synchronous `@EventListener` and `InAppNotificationChannel` persists then pushes, all inside the ingest transaction — the DB row rolls back, the push does not.
- The sibling deliberately defers: `lead/alert/LeadAlertBroadcaster.java:56-73` publishes `afterCommit` for exactly this reason, so the two paths disagree on the same invariant.

### Failure scenario

One JustDial lead arrives. A tenant admin in the assignable pool receives **two** persisted `LEAD_CREATED` rows and two SSE pushes for one enquiry, with different message bodies. Worse, the first push leaves the server before commit: if the ingest transaction then rolls back, every connected agent has already been toasted about a lead that does not exist, and clicking it 404s.

### Recommended fix

Gate the publish on the policy — `if (policy == IngestPolicy.INTERACTIVE) publishLeadCreatedNotification(...)` — and let the gateway remain the sole publisher for MACHINE, as `IngestPolicy` documents. If the wider assignable-pool audience is wanted for inbound leads too, move that recipient resolution into the outcome `LeadIngestService.create` returns rather than publishing in-transaction.

---

## LEAD-16 — Lead list and Kanban board bypass `PageSupport`

**Priority:** P3 · **Severity:** Medium · **Confidence:** Confirmed

### Evidence

- `lead/service/LeadServiceImpl.java:368-372` — `Sort.by(sortBy)` built straight from the request param and `PageRequest.of(page, size, sort)`: no whitelist, no clamp, no `id` tiebreaker.
- `lead/controller/LeadController.java:78-93` — the four params come off the query string unmodified.
- `common/util/PageSupport.java:22` — `MAX_PAGE_SIZE = 200; /** … stops ?size=100000 from becoming an OOM. */`; `:47-56` whitelist + trailing `id DESC`; `:59-63` clamp. All of it exists for this and is unused here.
- `common/exception/GlobalExceptionHandler.java:446-449` — Spring Data's `PropertyReferenceException` is not an `IllegalArgumentException`, so an unknown sort property falls to the catch-all and returns a 500 rather than a 400.
- `lead/service/LeadServiceImpl.java:593-600` — `getLeadBoard` fetches with no `Pageable` at all, then builds a full DTO per lead (`:617`) and a batched quotation lookup keyed by every lead id (`:623-627`). `LeadRepository.java:36-37` documents this as deliberate ("Full unpaged fetch for the Kanban board") — context, not the defect.

### Failure scenario

`GET /api/leads?sortBy=customer_name` (snake_case or any renamed column) returns a 500 "unexpected error" instead of a 400. `GET /api/leads?size=1000000` from any authenticated `LEAD_READ` user materialises every lead of the tenant plus its eager collections and builds a DTO per row — a heap-pressure request any logged-in user can repeat. And paging on a non-unique column (`sortBy=leadStage`) lets Postgres order tied rows differently per page, so one lead appears on two pages while another never appears — currently latent, since the only consumer fetches a single page (LEAD-08).

### Recommended fix

Replace `:368-372` with `PageSupport.pageRequest(page, size, PageSupport.buildSort(sortBy, sortDir, ALLOWED_LEAD_SORTS))`, declaring an explicit whitelist of sortable lead properties. For `getLeadBoard`, accept a per-column limit (top N per stage plus a true count from an aggregate query) — the roll-ups it returns do not require shipping every lead entity.

---

## LEAD-17 — A trashed `CONVERTED`/`LOST` lead blocks the same customer's next enquiry

**Priority:** P3 · **Severity:** Medium · **Confidence:** Confirmed

### Evidence

- `lead/service/LeadServiceImpl.java:948-971` — `checkTrashedForRestore` looks up `findFirstByEmail…DeletedAtIsNotNull…` / `findFirstByPhone…DeletedAtIsNotNull…` and throws `RestoreAvailableException` for **any** trashed match, with no stage predicate.
- `lead/service/LeadServiceImpl.java:912-940` — the sibling `validateNoDuplicates` deliberately passes `TERMINAL_STAGES` to every `exists*` call: *"Only an OPEN lead is a conflict. A CONVERTED/LOST lead for the same contact is kept for history and must NOT block the customer's next inquiry — that block was what made repeat business impossible."*
- `lead/repository/LeadRepository.java:154-158` — the trashed finders take only `(email|phone, tenantId)`; there is no stage-aware variant.
- `lead/entity/Lead.java:33-40` — the partial unique indexes are scoped `WHERE deleted_at IS NULL AND lead_stage NOT IN ('CONVERTED','LOST')` for the same reason.
- `lead/service/LeadServiceImpl.java:126-129` — `createLead` runs `validateNoDuplicates` then `checkTrashedForRestore`, so the trashed check is what rejects the request.

### Failure scenario

A lead for `+919812345678` was converted to a booking in January and later moved to Trash during a clean-up. Within the 30-day retention the customer calls back for a new trip. `validateNoDuplicates` passes (no open lead), then `checkTrashedForRestore` throws → 409 *"A lead with phone … is in Trash. Restore it instead of creating a duplicate."* Following that instruction restores a lead that is already `CONVERTED` and points at last trip's booking — it is not the new enquiry. The agent can only proceed by restoring the old lead (or waiting for the purge) and re-submitting.

### Recommended fix

Give the trashed lookup the same stage predicate as the duplicate check — add `…AndLeadStageNotIn(email/phone, tenantId, TERMINAL_STAGES)` variants of the two `DeletedAtIsNotNull` finders — so only a trashed **open** lead triggers the restore offer.

---

## LEAD-18 — The form's phone rule accepts values the backend `@Pattern` refuses

**Priority:** P3 · **Severity:** Medium · **Confidence:** Confirmed

### Evidence

- `[FE] src/features/leads/pages/CreateLead.jsx:1318-1320` — `register("phone", { pattern: { value: /^[+\d\s\-()]{7,20}$/ } })`; `:1343` — `placeholder="+91 98765 43210"` (two spaces — passes the frontend rule).
- `[FE] src/features/leads/api/leadService.js:194` — `phone: formData.phone?.trim() || ""` — trims, never strips separators.
- `lead/dto/CreateLeadRequestDto.java:28-33` — `@Pattern(regexp = "^\\+?[1-9]\\d{7,14}$")`: no whitespace, no dashes, no parens, max 15 digits.
- `lead/controller/LeadController.java:119-121` — `updateLead` binds the same `@Valid` DTO, so **every** later PUT re-validates the stored phone.
- `[FE] src/features/leads/pages/AllLeads.jsx:1531-1542` and `:1561-1566` — the Stage and Type row dropdowns re-post the stored phone verbatim through that DTO.
- `leadsource/gateway/LeadIngestService.java:183` with `NormalizedLead.java:36` — machine-ingested leads can hold a separator-bearing phone (see LEAD-10).

### Failure scenario

A clerk types the number exactly as the placeholder shows it. The form accepts it, Save fires, bean validation rejects it, and `applyServerFieldErrors` stamps "Enter a valid phone number" under a field the form itself just declared valid. Worse: a lead ingested from JustDial/WhatsApp whose stored phone contains a space or dash can never be edited or have its Stage/Type changed from the list dropdowns — the full-lead PUT re-submits the stored phone and 400s every time, and `AllLeads.jsx:1548` only shows "Error updating lead stage. Please try again."

### Recommended fix

Normalise once at the transformer boundary: in `leadService.js` `transformFormData`, send `phone.replace(/[^\d+]/g, "")` (dropping a leading 0 or duplicate `+`), so what the clerk types is decoupled from what the API accepts. Keep the permissive input rule for typing comfort but align its max length with the backend's 15 digits. Fixing LEAD-03 (using `PATCH /stage`) also removes the row-dropdown half of this.

---

## LEAD-19 — The routed `/AddLeadLog` page never calls the backend

**Priority:** P3 · **Severity:** Medium · **Confidence:** Confirmed

### Evidence

- `[FE] src/features/leads/pages/AddLeadLog.jsx:72-92` — the whole `try` block of `handleSubmit` is a commented-out `leadLogsService.addLog` call (`:73-80`), `await new Promise(r => setTimeout(r, 1000))` (`:81`), `showToast("Log saved successfully! ✅")` (`:82`) and a navigate.
- `[FE] .../AddLeadLog.jsx:24-25` — the only API reference in the file is a commented-out import; the file imports no client at all.
- `[FE] src/app/router.jsx:344` — `<Route path="AddLeadLog" element={<AddLeadLog/>}/>` is a live route.
- `lead/controller/LeadController.java:140-151` — `POST /api/leads/{publicId}/logs` has existed all along, gated on `LEAD_UPDATE`.
- `[FE] src/features/leads/api/leadService.js:283-289` — `leadService.addLog(...)` already exists and is used correctly by the `AddLogModal` inside `AllLeads.jsx:1738`.

### Failure scenario

A user reaches `/AddLeadLog`, types a call summary, ticks "Create reminder for follow-up", picks a date and clicks Save. After a 1 s spinner they get a green success toast. No HTTP request is made: no `lead_log` row, no follow-up `Reminder`, no change to the lead's `logCount`. The user leaves believing the call is on record.

Real-world reach is small: the All-Lead-Logs "+" button routes to `/AddLeadLog/:id`, which matches no route (LEAD-20), so it never lands here — the working entry points are `LeadLogs.jsx:167`'s bare `navigate("/AddLeadLog")` and a typed URL, and the product's primary lead-log path is the `AllLeads` modal, which posts correctly.

### Recommended fix

Replace the fake await with a real `leadService.addLog(id, { comment, createReminder, followUpDate, stage })`. Since `AllLeads` already ships a working modal against the same endpoint, the simplest correct fix is to delete this page and its route and point the two entry points at that modal.

---

## LEAD-20 — `/LeadLogs/:id` and `/AddLeadLog/:id` are navigated to but never registered

**Priority:** P3 · **Severity:** Medium · **Confidence:** Confirmed

### Evidence

- `[FE] src/features/leads/pages/AllLeadLogs.jsx:239` — `navigate('/LeadLogs/' + lead.leadId + '?name=…')`; `:242-248` — `navigate('/AddLeadLog/' + lead.leadId + '…')`. `leadId` is the lead publicId (`LeadLogCardDto.java:12`).
- `[FE] src/app/router.jsx:343-345` — only `LeadLogs`, `AddLeadLog` and `AllLeadLogs` are registered; **no `:id` segment**, and a grep for `path="*"` returns nothing, so there is no catch-all and an unmatched location renders an empty outlet.
- `[FE] src/features/leads/pages/LeadLogs.jsx:~54` reads `useParams().id` and `:~70` fires `leadService.getLeadLogs(id)` unguarded.
- `lead/controller/LeadController.java:154-156` binds `@PathVariable UUID publicId`, so the literal `"undefined"` fails conversion and hits `GlobalExceptionHandler.java:266` → 400.
- The id-carrying navigations are commented out at `LeadLogs.jsx:166` and `AddLeadLog.jsx:325`.

### Failure scenario

**Path A:** on `/AllLeadLogs` the user clicks "View Lead Logs" on any card. React Router matches nothing, so the content area renders blank inside the chrome — no error, no 404. **Path B:** the user reaches `/LeadLogs` via the bare link; `useParams().id` is `undefined`, the page issues `GET /api/leads/undefined/logs`, and renders "Failed to load logs. Please try again." for a lead that has logs. Combined with LEAD-19, the entire standalone lead-logs area is non-functional.

### Recommended fix

Register `<Route path="LeadLogs/:id" …/>` and `<Route path="AddLeadLog/:id" …/>`, guard `LeadLogs.jsx`'s fetch with `if (!id) return;`, and restore the commented-out id-carrying navigate calls. Add a catch-all `<Route path="*">` so a future bad link surfaces as a 404 rather than a blank screen.

---

## LEAD-21 — Geographic Distribution crashes on a search when any row has a null country

**Priority:** P3 · **Severity:** Medium · **Confidence:** Confirmed

### Evidence

- `[FE] src/features/reports/pages/GeographicDistribution.jsx:182-187` — `data.filter(r => r.city.toLowerCase().includes(q) || r.country.toLowerCase().includes(q))`, no null guard, inside a `useMemo` that runs during render.
- `report/geographic/service/GeographicReportService.java:62-63` — the server coalesces city to `"Unknown"` but leaves country **null**. The asymmetry is the source.
- `report/geographic/repository/GeoReportRepository.java:22,32` — `aggregateByCity` groups on `l.departCity, l.departCountry`, so a lead with a null `departCountry` yields a null second column.
- `lead/entity/Lead.java:285-286` — `depart_country` is a plain nullable column; `lead/dto/CreateLeadRequestDto.java:128-129` carries only `@Size(max = 100)`. Machine ingest leaves it unset: `LeadIngestService.applyTravelIntent` returns early when `travel == null` (`:244-247`, commented as "the normal case").
- `[FE] .../GeographicDistribution.jsx:136` — rows are taken verbatim with no normalisation.

### Failure scenario

A tenant has at least one webhook-ingested lead with no departure country. A user opens the report and types anything in the search box. For the first row whose city does not contain the term, `||` falls through to `r.country.toLowerCase()` and throws `TypeError: Cannot read properties of null`. The throw happens during render, so React unwinds and `RouteErrorBoundary` replaces the whole page — and it breaks again on the next keystroke. (With an empty search box `includes("")` is true, so country is never read — the crash needs a typed term.)

### Recommended fix

Guard both reads: `const q = search.toLowerCase(); data.filter(r => String(r.city ?? "").toLowerCase().includes(q) || String(r.country ?? "").toLowerCase().includes(q))`. Also coalesce server-side for consistency with `city` (`GeographicReportService.java:63`) so the CSV export and the table body render the same placeholder.

---

## LEAD-22 — The dashboard fallback presents 100-row lead totals as tenant totals

**Priority:** P3 · **Severity:** Medium · **Confidence:** Confirmed

### Evidence

- `[FE] src/features/dashboard/pages/Dashboard.jsx:463` — `fetchClientAnalytics` calls `leadService.getAllLeads()` with no arguments, beside a deliberately widened `bookingService.getAll(0, 500)` at `:464`.
- `[FE] src/features/leads/api/leadService.js:267-268` — defaults to `size=100`; `lead/controller/LeadController.java:78-93` honours it with no clamp.
- `[FE] .../Dashboard.jsx:311-314` and `:327-332` — `buildAnalytics` derives `totalLeads`, `convertedLeads`, `hotLeads`, `conversionRate` and the whole lead-source pie from that truncated array; nothing reads the pagination meta. `filterDashboardData` (`:302-308`) then narrows those ≤100 rows further by period.
- `[FE] .../Dashboard.jsx:577-586` — the fallback fires inside a bare `catch` around `dashboardService.getAnalytics(...)`, with no toast and no banner.
- `[FE] .../Dashboard.jsx:799`, `:809-810`, `:711-714` — rendered as "Total Leads", "Hot Leads" and "Conversion Rate" beneath a pulsing green "Live Data" badge.

### Failure scenario

An agency with 1,400 leads loads the Dashboard on a day when `/api/dashboard/analytics` fails — e.g. the module entitlement is off, so `ModuleAccessFilter` answers 403. The catch quietly runs the fallback, which fetches 100 leads. The hero card reports "Total Leads 100", the conversion rate is computed over those 100, and the source donut is built from the 100 most recent leads. No error is shown and the "Live Data" badge is still green.

### Recommended fix

Read the real total from `pagination.totalElements`, or page the fallback to completion. At minimum request a bounded-but-honest window matching the booking call (`getAllLeads(0, 500)`) and render an explicit "showing partial data" state when the fetched count equals the requested size. Same root cause as LEAD-08.

---

# P4 — Low

## LEAD-23 — `LeadCodeGenerator` re-reads inside an aborted transaction

**Priority:** P4 · **Severity:** Low · **Confidence:** Confirmed

**Evidence.** `lead/util/LeadCodeGenerator.java:77-92` — `createInitial` calls `sequenceRepository.saveAndFlush(...)` inside a `try` and catches `DataIntegrityViolationException`, then re-reads via `sequenceRepository.findByTenantId(tenantId)` — which `LeadSequenceRepository.java:19-20` confirms is a `@Lock(PESSIMISTIC_WRITE)` `SELECT … FOR UPDATE`, i.e. a new statement on the same connection. The class javadoc (`:28-29`) states it must run inside the caller's transaction, and `LeadServiceImpl.java:140` calls it inside `createLead`'s `@Transactional`. On Postgres the constraint violation aborts that transaction, so the catch's re-read fails with *"current transaction is aborted"*. The correct pattern is documented five classes away for the identical race: `LeadAssignmentService.java:160-163` — *"a same-txn catch-and-reread would poison it on Postgres."*

**Failure scenario.** A tenant with no row in `lead_sequences` (a brand-new tenant, or a database where the seed was skipped — reachable per the class javadoc at `:71-75`). Two users create the tenant's first lead simultaneously; the loser's insert violates `uk_lead_sequence_tenant`, the intended recovery cannot run, and the request returns 500. The retry succeeds because the row now exists.

**Fix.** Mirror `LeadAssignmentPointerProvisioner`: a small `@Transactional(REQUIRES_NEW)` provisioner that inserts the row (swallowing the duplicate-key loss in that inner transaction), called before `findByTenantId`; drop the same-transaction catch-and-reread. See LEAD-11 before adding another nested hop on the create path.

---

## LEAD-24 — Forced self-assignment skips the assignable-pool rule

**Priority:** P4 · **Severity:** Low · **Confidence:** Plausible

**Evidence.** `lead/assignment/service/LeadAssignmentService.java:144-151` takes the forced-self branch for any non-privileged creator and resolves the owner through `resolveSelf` (`:362-365`), which is only `findByIdAndTenantIdAndDeletedAtIsNull` — no `assertInPool`, unlike the privileged branch at `:189-194` / `:404-412` and unlike the claim path (`LeadClaimService.java:395-403`). `EffectivePermissionResolver.keysFor` (`:80-89`) treats a saved permission map as the source of truth with no dependency rule, so `LEAD_CREATE` without `LEAD_READ` is a savable configuration.

**Failure scenario.** An admin grants user U `LEAD_CREATE` but leaves `LEAD_READ` off. U creates a lead; it is assigned to U with no pool check, so U gets 403 on every lead read endpoint and cannot see the lead it just created, and the row sits outside the assignable pool that three other ownership paths reject.

**Why Low.** The omission is at least partly deliberate — the method javadoc (`:48-50`) says non-privileged roles are force-assigned to self specifically to preserve the sub-agent rule, and a pool check there would reject every sub-agent. The main effect (the creator cannot read the lead) is the literal intent of revoking `LEAD_READ`. The finder's claim that the lead becomes invisible to everyone is **wrong**: `ScopeResolver.java:48-54` puts U in a TEAM-scoped manager's `visibleUserIds` and `:77` gives `TENANT_ADMIN` `Scope.ALL`.

**Fix.** Route the resolved self through `assertInPool`, exempting `Role.SUB_AGENT` explicitly rather than by omission.

---

## LEAD-25 — Post-lock reassign is check-then-act with no version guard

**Priority:** P4 · **Severity:** Low · **Confidence:** Plausible

**Evidence.** `lead/claim/service/LeadClaimService.java:232-243` reads the lead, evaluates `isOpenToClaim`, snapshots `previousOwnerId`/`previousOwnerName` from that pre-update read, and only then issues the UPDATE. `LeadRepository.java:423-437` — `reassignLockedLead`'s WHERE clause carries **no** `claimVersion` and no expected-owner predicate, so two racing reassigns both match. `recordEvent` (`:256-259`) then writes the stale `from` values into the permanent timeline. The class contract at `:41-57` states: *"This service never reads a lead, decides, and then writes"* — `reassign` is exactly that shape. `ReassignLeadRequestDto.java:11-14` records the omission as a considered decision ("there is no concurrent contender to lose to").

**Failure scenario.** A manager and a tenant admin both reassign the same locked lead within milliseconds. Both read owner = P; both UPDATEs match and commit. `lead_assignment_events` records "P → X" then "P → Y": the timeline claims X held the lead when it never did, and the second row misreports the predecessor. The losing supervisor gets an HTTP 200 reporting a reassignment that was overwritten.

**Fix.** Extend `reassignLockedLead` with `AND l.assignedUser = :expectedCurrentOwner` (and/or a `claimVersion` predicate, adding `expectedClaimVersion` to the DTO), and route a 0-row result through the existing `diagnoseLostRace(..., expectedLocked = true)` so the loser gets a 409 naming the real current owner.

---

## LEAD-26 — Follow-up reminders use the server's zone, not the tenant's

**Priority:** P4 · **Severity:** Low · **Confidence:** Plausible

**Evidence.** `lead/service/LeadLogServiceImpl.java:201-205` — the comment reads *"Due at 09:00 local time on the chosen day"* and the code builds the instant with `ZoneId.systemDefault()`. The service's dependencies (`:47-51`) contain no `TenantTimeZone`, though that component exists for precisely this (its javadoc names `ZoneId.systemDefault()` as the bug it replaces) and is already injected into `LeadAlertService.java:37`.

**Failure scenario.** Smaller than it first appears: `Dockerfile:27` sets `TZ=Asia/Kolkata` and `TenantTimeZone.java:42` makes Asia/Kolkata the fallback, so for the pilot tenant the reminder fires at 09:00 IST exactly as promised. The defect bites only a tenant whose configured timezone differs from the container's — e.g. Asia/Kathmandu (+15 min), where the reminder lands at 09:15 local instead of 09:00.

**Fix.** Inject `TenantTimeZone` and use `tenantTimeZone.forTenant(TenantContext.getTenantId())` in place of `ZoneId.systemDefault()`, matching `LeadAlertService`. Fix alongside LEAD-12, which is the same class of defect with a sharper edge.

---

## LEAD-27 — The Incoming-Leads LIVE/OFFLINE pill latches to OFFLINE

**Priority:** P4 · **Severity:** Low · **Confidence:** Confirmed

**Evidence.** `[FE] src/features/leads/hooks/useLeadAlerts.jsx:127` — `onError: () => setConnected(false)`; `:130` — `setConnected(true)` runs once, synchronously after `subscribeToSSE` returns and **before** any connection has opened; `:135` — the effect's dependency array is `[canSee]`, a permission read that does not change during a session, so nothing ever sets it back. `[FE] src/features/reminders/api/notificationService.js:277` — `es.onopen` only resets the backoff; there is no `onOpen` callback in the handler map, so recovery is invisible to the subscriber, while `es.onerror` (`:279-288`) invokes `onError` on **every** error including the `CONNECTING` blips the browser self-heals. `[FE] leadAlertUi.jsx:238-254` renders "OFFLINE / Reconnecting…" whenever `connected` is false.

**Failure scenario.** A user leaves the page open across a laptop sleep or a backend redeploy. The stream errors once, the pill flips to OFFLINE, the browser reconnects moments later, and the pill stays OFFLINE for the rest of the session while alerts keep arriving. The inverse happens on load: the pill shows LIVE before the connection has opened, so a fully unreachable endpoint reads as LIVE until the first error.

**Fix.** Add an `onOpen` callback to `subscribeToSSE`'s handler map (fired from `es.onopen`) and wire `onOpen: () => setConnected(true)`; drop the unconditional `setConnected(true)` so the pill starts pessimistic. Optionally only flip to false when `es.readyState === EventSource.CLOSED`.

---

## LEAD-28 — Every tab opens two SSE connections

**Priority:** P4 · **Severity:** Low · **Confidence:** Plausible

**Evidence.** `[FE] src/app/chrome/Navbar.jsx:206-212` — the bell calls `notificationService.subscribeToSSE(fn)` (function form). `[FE] src/features/leads/hooks/useLeadAlerts.jsx:227-261` — the provider calls it again with a handler map. `[FE] notificationService.js:210-296` holds no module-level socket: `connect()` at `:291` runs `new EventSource(...)` at `:256` per invocation, so two callers produce two independent sockets each with its own reconnect timer — contradicting the file's own comment at `:199-206` (*"A second EventSource for them would double every tab's open connection"*) and `useLeadAlerts.jsx:5-8` ("One provider, one subscription").

**Why Low, not Medium.** `SseEmitterRegistry.register` (`:56-61`) files emitters with no per-user limit, so no lead-alert event is lost or misrouted — the second socket simply discards `lead-alert` frames it never subscribed to. The stated browser six-per-origin exhaustion holds only on HTTP/1.1; the split-origin HTTPS deployment normally serves HTTP/2, where that cap does not bite. What remains is doubled connection and heartbeat cost per tab, and it is the pre-existing bell's socket that is duplicative.

**Fix.** Give `notificationService` a single per-token multiplexed subscription: one module-level `EventSource` plus a set of handler maps, returning a `{ close() }` that detaches only its own handlers and tears the socket down when the last subscriber leaves. Both existing call shapes keep working unchanged — this is the design the file's comment already describes.

---

## LEAD-29 — Dashboard hero cards render hardcoded trend percentages

**Priority:** P4 · **Severity:** Low · **Confidence:** Confirmed

**Evidence.** `[FE] src/features/dashboard/pages/Dashboard.jsx:799-802` — `trend={12}` on Total Leads, `trend={0}` on Converted Leads, `trend={28}` on Agency Revenue, `trend={15}` on Net Profit: literals, not values off the data. `:84-90` — `HeroCard` renders the prop as an arrow pill identically to a computed metric. `:711-714` — a pulsing green "Live Data" badge sits directly above. Neither `EMPTY_D` (`:14-20`) nor `normalizeDashboardAnalytics` (`:436-459`) carries a delta field, and `report/dashboard/service/DashboardAnalyticsService.java:124-145` returns no period-over-period comparison — so no real value is being dropped; the number is invented in JSX.

**Failure scenario.** A tenant whose lead intake fell sees "Total Leads ↑ 12%". Changing the Period selector re-fetches the counts and the badge still reads 12%. A tenant with zero leads sees ↑12% on Total Leads and ↑28% on revenue. The lead-intake trend is the number an agency owner uses to judge marketing spend, and it can never show a decline.

**Fix.** Drop the `trend` prop from all four calls — `HeroCard` already renders nothing when `trend == null` (`:84`). If the indicator is wanted, add a previous-period comparison to `DashboardAnalyticsResponse` and surface it through `normalizeDashboardAnalytics`.

---

## LEAD-30 — The lead-logs stage filter has a phantom stage and omits a real one

**Priority:** P4 · **Severity:** Low · **Confidence:** Confirmed

**Evidence.** `lead/enums/LeadStage.java:8-16` — the eight display names are New Lead, Contacted, Follow Up, Qualified, Proposal Sent, Converted, **Reopened**, Lost. `[FE] src/features/leads/pages/AllLeadLogs.jsx:10-13` lists `"Ready to Book"` in `STAGES` and `:17-25` in `STAGE_CFG`, while omitting `Reopened`; `[FE] LeadLogs.jsx:25-36` duplicates the same map with the same phantom (`:31`) and the same omission. The filter is a raw string compare (`AllLeadLogs.jsx:216`) against `LeadLogCardDto.stage`, which `LeadLogCardDto.java:16` and `LeadLogServiceImpl.java:222` confirm is the enum `displayName`. The `<select>` is populated straight from `STAGES` (`:366`).

**Failure scenario.** The user picks "Ready to Book" from the Stage filter. No card can ever match, so the page renders "No Logs Found" with a "Clear Filters" button — indistinguishable from a tenant that genuinely has no logs at that stage. Separately, a lead flipped back to `REOPENED` shows the grey fallback badge and cannot be filtered for at all.

**Fix.** Delete the `"Ready to Book"` entries and add `"Reopened"` to both maps. Better: serve the stage vocabulary the way lead sources already are — a `/api/leads/meta/stages` sibling to `LeadMetaController`'s `/sources` (`:41-53`) consumed by a `useLeadStages` hook — so these four duplicated arrays across `AllLeads.jsx`, `AllLeadLogs.jsx`, `LeadLogs.jsx` and `CreateLead.jsx` cannot drift again.

---

## LEAD-31 — The Import button is inert — ✅ FIXED

**Priority:** P4 · **Severity:** Low · **Confidence:** Confirmed · **Status:** Resolved 2026-08-04

**Original evidence.** `[FE] src/features/leads/pages/AllLeads.jsx:1777-1780` — a `<label>` wrapping `<input type="file" accept=".csv,.xls,.xlsx,…" className="hidden" />` with no `onChange`, no `ref`, no `name` and no surrounding form; nothing in the file read it. `:1775` — the block was gated on `hasPermission(P.LEAD_CREATE)`, presenting it as a real capability. `[FE] leadService.js:260-372` — the service had no import method, and no bulk-import endpoint existed on the backend either.

### What shipped

Rather than deleting the control, bulk import was built. **`POST /api/leads/import/preview`** parses and validates the whole file and writes nothing; **`POST /api/leads/import`** then creates the rows; **`GET /api/leads/import/template`** returns the blank sheet. All three are gated on `LEAD_CREATE`.

- `lead/bulkimport/` — `LeadImportColumn` (the column contract, one source of truth for parser, template and error messages), `LeadImportFileReader` + `CsvLeadImportReader` / `ExcelLeadImportReader` + resolver, `LeadImportRowMapper`, `LeadImportService`, `LeadImportController`, `LeadImportTemplateWriter`.
- `[FE] features/leads/components/ImportLeadsModal.jsx` — drag-and-drop, preview table, per-row outcome badges; the Import button now opens it.

Two design points worth keeping in mind when touching this code:

1. **`LeadImportService` is deliberately not `@Transactional`.** Each row is created through `LeadService.createLead`, a different bean, so Spring's proxy opens a fresh transaction per row. Row 187 hitting a duplicate must not roll back the 186 leads already imported — and a batch-wide transaction would hold the lead-code counter's pessimistic lock for the whole file, blocking every other user of the tenant from creating a lead (the same hazard as LEAD-11).
2. **The import owns no lead logic.** Quota, duplicate detection, lead-code generation, customer matching, assignment, the claim window and notifications all stay in `createLead`, which the import calls exactly as the create form does. An imported lead is indistinguishable from a typed one, and none of those rules can drift for imports specifically.

It also avoids two defects this audit found elsewhere: the constructed DTO is run through the bean `Validator` (excluding `assignedUserId`, which `assignForCreate` resolves), so an imported lead cannot land in the un-editable state described in **LEAD-10**; and phone cells are stripped of spreadsheet formatting before validation, the normalisation **LEAD-18** recommends.

**Covered by** `CsvLeadImportReaderTest` (9 tests) and `LeadImportRowMapperTest` (15 tests) — 24 passing.

**Not covered / deliberate limits.** A leading-zero phone (`09876543210`) is reported rather than silently given a country code — guessing would put a wrong number on a real customer record. `dd/MM/yyyy` is read the Indian way, and US-style `MM/dd/yyyy` is rejected rather than guessed. Max 2,000 rows per file. There is no end-to-end test of the commit path against a database.

---

## Recommended repair order

1. **Decide the sub-agent question first** (LEAD-01, LEAD-02). If franchise partners are a separate commercial party, these are P1 and lead the list. The fix is small — an eligibility check in `stampFirstContact` and a `CRM_FULL` gate on `LeadAlertController` — and it closes an invariant the codebase already states three times.
2. **Close the third stage-write door** (LEAD-03, then LEAD-09). One extracted private method fixes the claim window, the SLA stamp, and the frontend's stage dropdown together; the missing `terminalStages` predicate is a one-line change on two queries.
3. **Stop losing writes** (LEAD-05, ~~LEAD-06~~, LEAD-07, LEAD-19). Four independent places where a user or a provider is told something succeeded that never happened. LEAD-06 is already fixed — it was reported against a pre-commit stash — leaving three.
4. **Restrict `updateLead`'s owner change** (LEAD-04) — simplest correct fix is to ignore `assignedUserId` there entirely.
5. **Make the lead list server-side** (LEAD-08, then LEAD-22 and LEAD-16 together) — one change to the fetch contract addresses all three.
6. **Harden ingest** (LEAD-10, LEAD-15) and the transaction/connection shape (LEAD-11).
7. **Timezone and session lifetime** (LEAD-12, LEAD-13, LEAD-26).
8. **The remaining P4 correctness cleanups**, cheapest first: LEAD-29, LEAD-30, LEAD-27. (LEAD-31 is done.)

## Regression tests to add

The lead module has **no `LeadServiceImplTest`** — `createLead`, `updateLead`, `updateLeadStage`, `getAllLeads` and `getLeadBoard` have no unit coverage at all. That gap is why LEAD-03, LEAD-04, LEAD-16 and LEAD-17 survived. Highest-value additions:

- A permission matrix over every lead and claim endpoint, with `SUB_AGENT` and a low-privilege `TRAVEL_AGENT` as explicit actors (covers LEAD-01, LEAD-02, LEAD-04).
- A test asserting that **every** write path that changes `leadStage` to an engaged stage also stamps `firstContactedAt` — parameterised over `updateLead`, `updateLeadStage` and `markContacted` so a fourth door fails the build (covers LEAD-03, LEAD-09).
- `markContacted` / `markContactedPreservingSla` against a `CONVERTED` lead (LEAD-09).
- Ingest tests for a trashed-lead match and a duplicate-email match, asserting the enquiry is quarantined and notified rather than dropped (LEAD-05), and for a provider payload with a leading-zero phone, asserting the resulting lead is still editable (LEAD-10).
- A frontend contract test that the lead-alert provider's context keys match what `LeadAlertHost` reads (LEAD-06) — the class of bug that optional chaining hides.
- Paged-list tests: `sortBy` outside the whitelist → 400 not 500; `size` above `MAX_PAGE_SIZE` clamped; `perPage=-1` on `/logs/summary` → 400 not 500 (LEAD-14, LEAD-16).
- A repeat-business test: create → convert → trash → create again with the same phone succeeds (LEAD-17).
- Concurrency: two simultaneous first-ever lead creations for a fresh tenant (LEAD-23); two concurrent reassigns of one locked lead (LEAD-25); ten concurrent creates in one tenant against a pool of 10 (LEAD-11).
