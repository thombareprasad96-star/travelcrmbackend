# Lead Module — Bug List

**Audit dates:** 2026-08-04 (LEAD-01 … LEAD-31) · 2026-08-05, second pass (LEAD-32 … LEAD-62)
**Scope:** the Lead module only — backend `lead/` (87 sources: core, claim, assignment, alert, SLA, attribution, logs, ingest), the `leadsource/` gateway path that creates leads, and every frontend screen that reads or writes lead data (`features/leads/` plus the lead touchpoints in app chrome, dashboard, reports, settings and the lead→quotation/booking/reminder hand-offs).
**Repos:** backend `D:\CRM PROJECT\travelcrmbackend`; frontend `D:\CRM PROJECT\travelcrmfe\travelcrmfrontend` (cited below as `[FE]`).

> This file previously held a repository-wide audit (15 findings across boot, portal, notifications, billing, storage). That content was replaced on request and remains retrievable from git at commit `3d986d6`.

## Method and limits

- Five parallel readers covered the module, each restricted to a slice and required to read whole files rather than grep excerpts.
- Every candidate finding was then re-checked by an independent verification pass that re-opened the cited source, confirmed the line numbers, and looked for a guard the finder may have missed (a caller-side check, a `@PreAuthorize`, a repository `WHERE` clause, or an existing test). **1 candidate was rejected** as a false positive and is not listed. **2 were merged** into the findings they duplicated. Line citations corrected during verification are reflected below.
- Findings are marked `Confirmed` where the failure follows directly from the cited code, and `Plausible` where the mechanism is real but the impact is bounded or depends on configuration.
- **This is a static audit. No test suite was run and nothing was reproduced against a live database.** Existing lead tests were read to avoid reporting covered behaviour — note there is no `LeadServiceImplTest` anywhere under `src/test/java/com/crm/travelcrm/lead/`, so the core create/update paths have no unit coverage at all.
- Design decisions recorded as deliberate in the code are **not** reported as bugs: the tenant-wide claim-window widening (`LeadAccessGuard.java:53-98`), the unpaged Kanban board fetch, enum `displayName` wire values, and name-based city resolution.

### Second pass — method (2026-08-05)

- Four parallel readers re-covered the module on a hard slice — backend lead core; backend satellites (`alert/ claim/ assignment/ bulkimport/ ingest/ attribution/` plus the whole `leadsource/` package); the frontend feature plus its router and access wiring; and a docs-conformance reader whose sole job was to check every factual claim in the seven lead docs against the code. **184 files were read** and **67 candidate findings** produced.
- A single adversarial verifier then re-opened every cited line and tried to **refute** each finding, defaulting to rejection when uncertain. **4 were rejected outright**, roughly a third were re-graded downward, and duplicates across the four readers were merged — leaving **44 confirmed**.
- Of those 44, **13 were already logged** as LEAD-01 … LEAD-31 and are not repeated; the **31 new** findings are LEAD-32 … LEAD-62.
- Two rejections are worth recording because they change how this file should be read:
  1. *"`useLeadAlerts.isMine` falls back to a `userName` key almost nothing writes"* — **false**. `[FE] AdminLogin.jsx:439` writes it on every staff login. The reader took this from `CLAUDE.md`'s localStorage inventory, which is stale and additionally cites a line number that does not exist. **`CLAUDE.md` manufactured a finding that does not exist** — which is itself now logged as LEAD-40.
  2. *"Editing a lead to `Converted` bypasses the booking flow"* — **half false**. `updateLead` 409s via `assertConversionStageTransitionAllowed`; only the **create** path is open. Logged with corrected scope as LEAD-35.
- Same limits as the first pass: static reading only, no test suite run, nothing reproduced against a live database. There is still no `LeadServiceImplTest`.

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

### Second pass — 2026-08-05

| ID | Priority | Area | Finding |
|---|---|---|---|
| LEAD-32 | P2 | Leads list | The list fetches a filtered server page into `leads`/`meta` and renders a different, unfiltered 200-row array — so stage edits snap back, deletes and imports don't show, and the truncation notice is unreachable. Supersedes the live half of LEAD-08. |
| LEAD-33 | P3 | Lead logs | All-Lead-Logs discards the server's `total`/`totalPages` and caps at `perPage=1000`; both hero counters are page figures dressed as tenant totals. |
| LEAD-34 | P3 | Lead form | `transformItinerary` rewrites a 0-night stop to 1 night on every save — including an inline stage change, which re-sends the whole lead. |
| LEAD-35 | P3 | Stage rules | A lead can be **created** already `Converted` with no booking; `createLead` has no stage guard (the edit path does). |
| LEAD-36 | P3 | Ingest | The phone-normalisation backfill uses `OFFSET` over a result set its own `UPDATE` shrinks, silently skipping half the rows and logging them as un-canonicalisable. |
| LEAD-37 | P3 | Lead sources | `WebsiteFormAdapter` offers an "Allowed website addresses" setting that no code enforces. |
| LEAD-38 | P3 | Lead list | `LeadSpecification` has no predicate for Active or Follow-ups, so those two tabs send `stage=Active` and 400. Blocks LEAD-32. |
| LEAD-39 | P3 | Lead logs | All-Lead-Logs' breadcrumb and "Back to Leads" both navigate to `"/allleads "` — a trailing space that matches no route and renders a blank page. |
| LEAD-40 | P3 | Docs | `CLAUDE.md` states `ddl-auto=update` and "No Flyway"; the tree boots on `validate` with V1+V2. Bites the first schema change. |
| LEAD-41 | P3 | Docs | `BACKEND-CONTRACT-LEAD-BOOKING.md`'s correction table is itself inverted on `LeadType` — following row 1 would 400 every lead save. |
| LEAD-42 | P3 | Docs | `LEADS-LIST-UI-REDESIGN.md`'s premise ("no server-side filtering exists") is false, and every §2 line citation is stale. |
| LEAD-43 | P3 | Time handling | Three different clocks in one feature — `getLogStats` (no zone), `getStatsSummary` (tenant zone), `LeadAlertService` (tenant zone vs JVM-zone timestamps). Extends LEAD-12. |
| LEAD-44 | P4 | Lead DTO | `PATCH /stage`, `PUT` and `POST /leads` return `latestQuotation` and `logCount` as null, so a row patched from the response loses both. |
| LEAD-45 | P4 | Assignment | `EligibleUserDto.activeLeads` carries the composite workload score but both assign dropdowns render it as "N active" leads. |
| LEAD-46 | P4 | Access wiring | Create/Edit Lead has no permission gate at route or page — the whole 30-field form is filled before the 403. |
| LEAD-47 | P4 | Access wiring | `AllLeads`' overview fetch is the one effect not gated on `LEAD_READ`, so a 403 toast lands on top of `AccessDenied`. |
| LEAD-48 | P4 | Leads list | The delete confirmation and type-change toast print the raw lead UUID instead of the lead code. |
| LEAD-49 | P4 | Dead code | `pages/EditLead.jsx` (1,275 lines) and three components are entirely dead — the Edit route resolves to `CreateLead`. |
| LEAD-50 | P4 | Dead code | `ConvertToBookingModal` (474 lines) is imported by `AllLeads` and never rendered. |
| LEAD-51 | P4 | Dead code | `QuotationsModal`'s preview-design picker is unreachable, so `webViewStyle` is permanently null. |
| LEAD-52 | P4 | Dead code | Six `leadService` methods have zero callers, including `getLeadLogStats` — the endpoint that would fix LEAD-33. |
| LEAD-53 | P4 | Dead code | Five dead members in the lead module, including a second `ApiResponse` envelope that contradicts the house rule. |
| LEAD-54 | P4 | Lead entity | `Lead.origin`, `sourceIntegrationId` and `customerLinkedAt` are written on every create and read by nothing. |
| LEAD-55 | P4 | Lead logs | `/logs/summary` silently widens on an unparseable `stage` or `userId`; `/api/leads` 400s on the same input. |
| LEAD-56 | P4 | Bulk import | CSV row numbers count records, not lines, so the report points at the wrong row when the file has blank lines or multi-line cells. |
| LEAD-57 | P4 | Bulk import | The assignee lookup does not filter `isActive` although the error message says "active" — a READY preview row then fails at commit. |
| LEAD-58 | P4 | Authorization | `LeadMetaController` has no class-level `@PreAuthorize`, so a method added there would fail open. |
| LEAD-59 | P4 | Leads list | Row selection is fully wired but has no bulk action — the checkboxes only feed a counter. |
| LEAD-60 | P4 | Routing | `/WhatsAppPanel` is routed with no lead: an unclosable overlay pointing at an empty `wa.me` link. |
| LEAD-61 | P4 | Accessibility | No `AllLeads` modal closes on Escape, none has a dialog role, and no close button has an accessible name. |
| LEAD-62 | P4 | Docs / comments | Board comments cite seven columns and a deleted `LeadKanban.jsx`; `LeadStage` has eight values. `LeadSource` cites a removed `LeadType` constant. |

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

> **Superseded in part.** Most of this fix has since been *written* — server-side filtering shipped on the backend and `leadService.listLeads` + `fetchLeads()` were added on the frontend. It is not *wired*: the table still renders a different array. See **LEAD-32**, which replaces this entry as the live description of the defect.

---

## LEAD-32 — The leads list fetches a filtered server page and then renders a different, unfiltered array

**Priority:** P2 · **Severity:** High · **Confidence:** Confirmed · **Supersedes the live half of LEAD-08**

### Evidence

- `[FE] src/features/leads/pages/AllLeads.jsx:1642-1670` — `fetchLeads()` calls `leadService.listLeads({page, size, sortBy, sortDir, q: debouncedSearch, ...serverParams})`, guards against out-of-order responses with `reqId`, and writes the result: `setLeads(body.data)` (`:1656`), `setMeta(body.pagination)` (`:1657`).
- `[FE] AllLeads.jsx:1846` — `const safeLeads = useMemo(() => (Array.isArray(overviewLeads) ? overviewLeads : []), [overviewLeads]);` — **the table's data source is `overviewLeads`, not `leads`.**
- `[FE] AllLeads.jsx:1715-1727` — `overviewLeads` is a one-shot `leadService.getAllLeads(0, 200)` in a `useEffect` with an empty dependency array: no filters, no search, no paging, and it never refetches.
- `[FE] AllLeads.jsx:1987-2040` → `:2055` → `:2066` — `filteredLeads` re-applies search, the date window and the tab **client-side** over `safeLeads`, then TanStack re-paginates it with `getPaginationRowModel()` (`:2063`), and `pageRows` renders that.
- `meta` is written at `:1657` and **read nowhere** — the only other occurrence is the `useState` declaration at `:1559`.
- `setTotalCount` is called at exactly one site, `:1820`, and it is a *decrement* inside `handleDelete`. `totalCount` is therefore permanently `null`, so `listTruncated` (`:1876`) is always `false` and the truncation notice at `:2270-2275` — the honesty fix LEAD-08 asked for — **can never render**. The header badge (`:2139`) and the "All" tab count (`:2325`) silently fall through to `safeLeads.length`, i.e. the 200 cap.
- The four optimistic patches all target the array nothing renders: `openLeadWeblinkWithStyle` (`:1744`), `handleStageChange` (`:1777`), `handleTypeChange` (`:1804`), `handleDelete` (`:1818`), plus `handleLogAdded` (`:1837`). All call `setLeads`.
- `ImportLeadsModal onImported={() => { fetchLeads(); fetchStats(); }}` (`:2103`) refetches `leads` — which is not on screen.
- Backend confirms the server side is complete: `LeadController.java:92-102` declares `search`, `stage`, `leadType`, `fromDate`, `toDate`; `LeadSpecification.java:43-94` implements each predicate in SQL, AND-ed with the tenant predicate and the row scope; `leadService.js:282-286` maps `q` → `search` and strips blanks.

### Failure scenario

Three distinct user-visible failures, all from the same line:

1. **Stage changes appear to fail.** An agent picks "Qualified" in the row's Stage dropdown. The PUT succeeds, `setLeads` patches `leads`, and the `<select>` — which is controlled on `lead.leadStage` read from `overviewLeads` — re-renders with the **old** stage. The agent sees the value snap back and changes it again. Identical for the Type dropdown.
2. **Deleted leads stay on screen, imported leads never appear.** `handleDelete` filters `leads`; the row is rendered from `overviewLeads`, so it remains until a hard reload. After a successful bulk import, `fetchLeads()` runs and the new leads are invisible.
3. **Filtering is still truncated, and the notice that was written to admit it is unreachable.** Two full list requests fire on mount; the useful one is discarded. Search and the tabs narrow 200 in-memory rows, and past 200 leads the behaviour is exactly LEAD-08 with a larger number — except `listTruncated` can never be true, so nothing says so.

### Recommended fix

**Do not simply swap `safeLeads` to `leads` at `:1846`** — that makes it worse: the page would fetch server page N and then client-filter and re-paginate *within* it, so page 2 of a filtered result renders empty and the counter reports the page size. Split the two datasets explicitly:

1. Feed the table `leads` directly; delete the `filteredLeads` predicates that duplicate the server params already sent at `:1626-1634` and `:1651`.
2. Remove `getPaginationRowModel()` (`:2063`) — with sorting and paging both server-side, TanStack is doing nothing on this page except adding the `row.original` footgun already commented at `:2414`. Render `leads` and key on `lead.id`.
3. Drive `CommonPagination` from `meta.totalElements` / `meta.totalPages`, and set `totalCount` from `body.pagination?.totalElements` inside `fetchLeads` (`:1657`).
4. Keep `overviewLeads` **only** as the pre-summary fallback for the cards, or delete it once `/leads/stats/summary` is trusted — and gate it on `LEAD_READ` either way (**LEAD-47**).
5. Make the five local patches `setLeads`, and prefer patching from the mutation response once **LEAD-44** completes that DTO.

Two prerequisites, or this ships broken: **LEAD-38** (the `Active` and `Follow-ups` tabs have no server-side predicate and currently send `stage=Active`, which `LeadStage.fromValue` throws on) and **LEAD-16** (`sortBy` has no whitelist and the page size is unclamped).

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

## LEAD-33 — All-Lead-Logs throws away the server's total and caps the dataset at 1000

**Priority:** P3 · **Severity:** Medium · **Confidence:** Confirmed

### Evidence

- `[FE] src/features/leads/pages/AllLeadLogs.jsx:192` — `leadService.getLeadLogSummary({ page: 1, perPage: 1000 })`.
- `[FE] AllLeadLogs.jsx:194-197` — `const body = res.data?.data ?? res.data;` then only `body.leads` is read into state. `body.total`, `body.totalPages`, `body.page` and `body.perPage` are never touched.
- `lead/service/LeadLogServiceImpl.java:151-158` — the server builds `.total(total).totalPages(totalPages)` on every response, and applies **no** cap to `perPage`, so the 1000 is the client's own ceiling.
- `[FE] AllLeadLogs.jsx:235` — `const totalLogs = data.reduce((s, l) => s + l.logCount, 0);` and `:317-318` — `<HeroCard value={data.length} label="Total Leads"/>`. Both hero figures are computed over the truncated array.
- `[FE] AllLeadLogs.jsx:210-222` — search and the stage/user filters are client-side over the same array.
- `GET /api/leads/logs/stats` exists for exactly these two figures (`LeadController.java:196`) and has zero callers (**LEAD-52**).

### Failure scenario

A tenant past 1,000 leads-with-logs opens All-Lead-Logs. "Total Leads" and "Total Logs" describe the first 1,000 rows while reading as tenant totals, with no truncation notice. A lead outside the window is unfindable on this screen — the search box narrows the same truncated array — so an agent looking up an older customer's call history concludes none was recorded. The correct total is already in the payload and is discarded.

### Recommended fix

Read `body.total` into state and render it on the Total Leads card; call the already-written `leadService.getLeadLogStats()` for the hero figures. Then either page server-side using `body.totalPages`, or render a truncation notice while `body.total > data.length`. Same family as LEAD-08 / LEAD-32 / LEAD-22.

---

## LEAD-34 — `transformItinerary` silently rewrites a 0-night stop to 1 night on every save

**Priority:** P3 · **Severity:** Medium · **Confidence:** Confirmed

### Evidence

- `[FE] src/features/leads/api/leadService.js:173-175` — `// A row that exists is at least one night. The Nights input allows 0 and the backend's @Min(1) rejected it, taking the whole lead down with it.` followed by `nights: Math.max(1, Number(nights) || 0),`.
- `[FE] src/features/leads/pages/CreateLead.jsx:1721` — the Nights input is `min={0}`, so the form genuinely offers the value the transformer refuses to transmit.
- `[FE] src/features/leads/pages/AllLeads.jsx:1770-1775` and `:1797-1802` — `handleStageChange` and `handleTypeChange` pass `leadToUpdate.itinerary` back through `leadService.updateLead`, i.e. through the same transformer.

### Failure scenario

A clerk records a transit stop as 0 nights. It saves as 1N, and the row's total-nights figure and the form's day count are both one too high. Worse, it happens with no edit at all: changing a lead's stage from the AllLeads dropdown re-sends the whole lead, so a lead that was *correctly* stored with a 0-night leg has its stored itinerary silently rewritten the first time anyone touches its stage. The coercion was added to stop a `@Min(1)` 400 from taking the whole save down — it fixed the crash by falsifying the data.

### Recommended fix

Make the form agree with the wire contract rather than papering over it: set `min={1}` on the Nights input (`CreateLead.jsx:1721`) and on `blankRow()`, and surface a field-level validation error for 0. If a 0-night leg is genuinely meaningful, relax the backend `@Min(1)` instead — but do not keep a transformer that changes stored values behind the user's back. Related: the stage-change path should be a `PATCH /{publicId}/stage` (LEAD-03), which would stop sending the itinerary at all.

---

## LEAD-35 — A lead can be *created* already Converted, with no booking behind it

**Priority:** P3 · **Severity:** Medium · **Confidence:** Confirmed

### Evidence

- `[FE] src/features/leads/pages/CreateLead.jsx:836-839` — `const LEAD_STAGES = ["New Lead", "Contacted", "Follow Up", "Qualified", "Proposal Sent", "Converted", "Reopened", "Lost"];`, rendered as a plain `<select>` at `:1796-1798`.
- `[FE] src/features/leads/pages/AllLeads.jsx:67-70` states the opposite rule for the same field and excludes Converted: *"conversion runs through the Convert-to-booking flow, not a manual pick"*.
- `lead/mapper/LeadMapper.java:74` — `createLead`'s path is `.leadStage(request.getLeadStage())` with **no** validation.
- Contrast `lead/service/LeadServiceImpl.java:524` — `updateLead` calls `assertConversionStageTransitionAllowed` (`:809-821`), which throws a 409 on any non-`CONVERTED` → `CONVERTED` transition.

**Scope correction.** An earlier reading of this claimed that *editing* a lead to Converted also works. It does not — `updateLead` 409s. The hole is **create-only**, and the backend is where it must be closed; a frontend-only fix leaves the API open.

### Failure scenario

A lead is created with stage Converted. `convertedBookingPublicId` is null, so `AllLeads.jsx:622` treats it as converted and renders "Booked ↗" linking to `/BookingDetails/` with an empty publicId; the Convert action disappears, so the lead can never be converted properly. `getStatsSummary`'s converted count and the conversion-rate card both include a lead that produced no revenue.

### Recommended fix

Backend first: reject `request.getLeadStage() == CONVERTED` in `createLead`, reusing `assertConversionStageTransitionAllowed`'s message. Frontend: drop "Converted" from `LEAD_STAGES` in `CreateLead.jsx` while still prepending the lead's real stage when it falls outside the set — the idiom already at `AllLeads.jsx:655`.

---

## LEAD-36 — The phone-normalisation backfill offsets over a shrinking result set and skips half the rows

**Priority:** P3 · **Severity:** Medium · **Confidence:** Confirmed

### Evidence

- `lead/ingest/LeadPhoneNormalizationBackfill.java:87-89` — `SELECT id, phone FROM leads WHERE phone_normalized IS NULL AND phone IS NOT NULL ORDER BY id LIMIT <BATCH> OFFSET <total>`.
- `:100` — `jdbc.batchUpdate("UPDATE leads SET phone_normalized = ? WHERE id = ?", batch)` — the UPDATE removes those rows from the very predicate the next page offsets into.
- `:105` — `total += rows.size();` with the comment *"OFFSET advances by rows READ, not rows written"*.
- Traced on 2,000 canonicalisable rows: batch 1 writes rows 1–500 and advances `OFFSET` to 500, so batch 2 reads what are now rows 1001–1500. Exactly 1,000 rows are left `NULL` and the loop exits.
- `:73-75` — the boot log then reports the skipped rows as *"un-canonicalisable (phone has no recognisable form)"*, which is false and hides the defect.

### Failure scenario

Leads skipped by the backfill never match `findOpenLeadByPhone` (`LeadIngestService.java:153`), so a repeat WhatsApp or JustDial enquiry from an existing customer is not appended to their open lead — it is quarantined as a duplicate or opens a second lead. Mitigating: each restart processes another slice, so it converges over repeated boots; the window is finite but silent, and the log actively misdescribes it.

### Recommended fix

Keyset paging on the primary key: track `lastId`, query `WHERE phone_normalized IS NULL AND phone IS NOT NULL AND id > :lastId ORDER BY id LIMIT 500`, and advance `lastId` to the maximum id **read** in the batch. That is correct and still immune to the infinite loop on un-canonicalisable rows that the OFFSET was working around.

---

## LEAD-37 — `WebsiteFormAdapter` advertises an `allowedOrigins` restriction that nothing enforces

**Priority:** P3 · **Severity:** Medium · **Confidence:** Confirmed

### Evidence

- `leadsource/adapter/WebsiteFormAdapter.java:126-129` — `.configFields(List.of(new ChannelCatalogEntry.ConfigField("allowedOrigins", "Allowed website addresses", false, "Comma-separated, e.g. https://yoursite.com. Leave blank to accept from anywhere.")))`.
- A repo-wide grep for `allowedOrigins` across `src/main` returns exactly three hits: this declaration and two in `SecurityConfig.java` (`:52`, `:142`) for unrelated browser CORS.
- Neither `LeadIngestGateway` nor `WebsiteFormAdapter.parse` ever reads the value. It is stored as connection JSON and echoed back to the settings UI only.

### Failure scenario

A tenant fills in "Allowed website addresses" believing they have restricted who may post to their ingest URL — the helper text says blank means "accept from anywhere", so a non-blank value must mean otherwise. It does not. A website form's ingest URL is by definition embedded in a public page, so this is precisely the case where a tenant reaches for the control. The ingest token is still required, so this is a defence-in-depth gap and a false promise rather than an open door — which is why it is P3 and not P2.

### Recommended fix

Enforce it or remove it. To enforce: have `LeadIngestGateway` read the connection's parsed config and, when `allowedOrigins` is non-empty, reject deliveries whose `Origin`/`Referer` falls outside the list, using the same rejection shape as every other ingest failure. Otherwise delete the config field so nothing false is offered.

---

## LEAD-38 — `GET /api/leads` cannot express the "Active" or "Follow-ups" filter at all

**Priority:** P3 · **Severity:** Medium · **Confidence:** Confirmed

### Evidence

- `lead/specification/LeadSpecification.java:43-49` — `filter()` accepts only `tenantId, visibleUserIds, search, stage, leadType, fromDate, toDate`, and `stage` is a **single** `LeadStage`.
- `:66-68` — the stage predicate is `cb.equal(root.get("leadStage"), stage)`. There is no NOT-IN-terminal predicate and no `followUpDate` predicate anywhere in the file.
- `lead/controller/LeadController.java:92-102` declares the same five filter params and nothing more.
- `[FE] src/features/leads/pages/AllLeads.jsx:1626-1634` — `serverParams` maps every non-`Fresh` tab to `p.stage = activeTab`, so the `Active`, `Follow-ups` and `Hot` tabs currently send `stage=Active`, `stage=Follow-ups`, `stage=Hot`.
- `LeadSpecification.parseStage` (`:102-104`) has no catch, and `LeadStage.fromValue` (`LeadStage.java:39`) throws `IllegalArgumentException` on an unknown value → 400.

### Failure scenario

Today this is invisible only because the table renders a different array (**LEAD-32**) — the 400 is discarded along with the response. The moment LEAD-32 is fixed, the two tabs the dashboard cards click into break outright, and `Hot` breaks because it is a `leadType`, not a stage. There is no server-side representation to fall back to, so the alternative is client-side filtering over one page — which is the exact truncation `getStatsSummary` was built to eliminate, and it guarantees the card count and the list it opens will disagree.

### Recommended fix

Add two optional parameters to `LeadSpecification.filter()` and to the controller:

- `Boolean activeOnly` → `cb.not(root.get("leadStage").in(LeadStageGroups.TERMINAL_STAGES))`
- `LocalDate followUpDueBy` → `cb.and(cb.isNotNull(followUpDate), cb.lessThanOrEqualTo(followUpDate, date))`, combined with `activeOnly` so it matches what `countFollowUpsBefore` / `countFollowUpsInRange` already count for the card.

Then change the frontend mapping to send `activeOnly=true` / `followUpDueBy=<today>` instead of `stage=Active` / `stage=Follow-ups`, and route `Hot`/`Warm`/`Cold`/`Fresh` to `leadType`. Blocking prerequisite for **LEAD-32**.

---

## LEAD-39 — All-Lead-Logs' breadcrumb and "Back to Leads" both navigate to `"/allleads "` with a trailing space

**Priority:** P3 · **Severity:** Medium · **Confidence:** Confirmed

### Evidence

- `[FE] src/features/leads/pages/AllLeadLogs.jsx:288` — `<span … onClick={() => navigate("/allleads ")}>Leads</span>`.
- `[FE] AllLeadLogs.jsx:295` — `<button onClick={() => navigate("/allleads ")}> … Back to Leads`.
- `[FE] src/app/router.jsx:282` registers `path="allleads"` with no trailing space, and there is **no catch-all**: the tree uses `<Routes>` (`router.jsx:203`) with no `path="*"`, so an unmatched URL renders `null`.
- `[FE] src/features/leads/pages/LeadLogs.jsx:128` and `:173` get the same navigation right, so this is a typo, not a convention.

### Failure scenario

Both the breadcrumb and the page's primary escape hatch dead-end at `/allleads%20`, which renders a completely blank page — not a 404, not the layout chrome, nothing. The only way out is the sidebar or the browser Back button.

### Recommended fix

Remove the trailing space in both literals. While in `router.jsx`, add a `path="*"` catch-all — a blank screen is currently the app's 404, which is also what makes **LEAD-20** so hard to notice.

---

## LEAD-40 — `CLAUDE.md` tells every reader `ddl-auto=update` and "No Flyway"; the tree boots on `validate` with two migrations

**Priority:** P3 · **Severity:** Medium · **Confidence:** Confirmed

### Evidence

- `CLAUDE.md` — the configuration table states `spring.jpa.hibernate.ddl-auto=update | Schema is auto-managed`, and the Pitfalls section states *"No Flyway: Schema is managed by `spring.jpa.hibernate.ddl-auto=update`. Do not add Flyway migrations unless switching to `validate` for production."*
- `src/main/resources/application.properties:64` — `spring.jpa.hibernate.ddl-auto=${JPA_DDL_AUTO:validate}`; `application-prod.properties:47` the same.
- `application.properties:93-100` configures `spring.flyway.*`, and `src/main/resources/db/migration` holds `V1__baseline_schema.sql` and `V2__lead_code.sql`.
- `docs/BACKEND-CONTRACT-LEAD-BOOKING.md` row 4 already records the correct position, so the two docs contradict each other.

### Failure scenario

This bites the first lead change that adds a column. Following `CLAUDE.md`, a developer adds a field to `Lead.java` expecting Hibernate to create it; the application then hard-fails at boot on schema validation with no obvious link back to the doc that caused it. `CLAUDE.md` is the first file anyone (or any agent) reads, and it states the inverse of the truth.

### Recommended fix

Correct both `CLAUDE.md` entries: `ddl-auto` defaults to `validate` (override via `JPA_DDL_AUTO`); schema changes are appended as a new PART to `V2__lead_code.sql` and applied by hand while `spring.flyway.enabled` defaults to false. While in the file, fix the localStorage key inventory: it claims only `MyProfile.jsx` writes `userName`, but `[FE] AdminLogin.jsx:439` does — that stale line manufactured a false finding during this audit and was only caught on verification.

---

## LEAD-41 — `BACKEND-CONTRACT-LEAD-BOOKING.md`'s correction table is itself inverted on `LeadType`

**Priority:** P3 · **Severity:** Medium · **Confidence:** Confirmed

### Evidence

- `docs/BACKEND-CONTRACT-LEAD-BOOKING.md:21` — row 1 reads: *"It claims LeadType is already Fresh/Hot/Warm/Cold; the FE list is stale | **Truth:** LeadType.java is `FRESH_LEAD("Fresh Lead")`, `REPEAT_CUSTOMER`, `CORPORATE`, `VIP`. The FE matches the backend exactly. | **Consequence of believing it:** Changing the FE array 400s every lead save."*
- `lead/enums/LeadType.java:32-35` at HEAD — `FRESH("Fresh"), HOT("Hot"), WARM("Warm"), COLD("Cold")`, with a javadoc describing the completed migration.
- `[FE] src/features/leads/pages/CreateLead.jsx:835` — `const LEAD_TYPES = ["Fresh", "Hot", "Warm", "Cold"]`. Frontend and backend already agree.
- `docs/BACKEND-CONTRACT-LEAD-BOOKING.md:55` (§3) — *"Lead drops 20 of the 41 fields the form sends"* is also complete: `DepartureMode.java` exists and `Lead.java:190-278` persists `anniversaryDate`, `followUpDate`, `departureMode`, `pickupDateTime` and the special-assistance block.

### Failure scenario

This is a correction table whose entire purpose is to stop someone acting on stale information, and it has itself become the stale one — with its stated consequence exactly inverted. Reverting the frontend array to `Fresh Lead / Repeat Customer / Corporate / VIP` per this row would 400 every lead save against the current `LeadType.fromValue`.

### Recommended fix

Strike row 1 of §1; mark §3 and §4 DONE with pointers to `LeadType.java:32-35`, `DepartureMode.java` and `Lead.java:190-278`. Keep row 4 (`ddl-auto=validate`) — it is the one entry still correct, and **LEAD-40** shows `CLAUDE.md` contradicts it.

---

## LEAD-42 — `LEADS-LIST-UI-REDESIGN.md`'s central premise is false and every §2 line citation is stale

**Priority:** P3 · **Severity:** Medium · **Confidence:** Confirmed

### Evidence

- `docs/LEADS-LIST-UI-REDESIGN.md:93-96` — *"Backend confirms there is no server-side alternative today: `GET /api/leads` (`LeadController.java:78-93`) accepts only `page`, `size`, `sortBy`, `sortDir`."*
- `lead/controller/LeadController.java:92-102` also declares `search`, `stage`, `leadType`, `fromDate` and `toDate` and passes all nine to the service; `lead/specification/LeadSpecification.java:43-94` implements every predicate in SQL, AND-ed with the tenant predicate and the row scope.
- `docs/LEADS-LIST-UI-REDESIGN.md:53` — *"All line numbers refer to `AllLeads.jsx` as it stood before any change (2024 lines)."* The file is **2468 lines** at HEAD. Spot-checked drift: `LEAD_COLUMNS` cited `:143-161` is `:193-210`; `colorForIndex` `:49` is `:50`; `filteredLeads` `:1625-1664` is `:1987-2040`; the selection strip `:1928-1933` is `:2366-2371`.
- §2.3 (four tabs, no stage dropdown) — nine tabs exist at `:2324-2341`. §2.11 (four gradient cards, client-side `setInterval` count-up) — nine cards fed by `/leads/stats/summary` + `/leads/alerts/stats`, `:1897-1984`. §2.9 (dead `margin`/`viewCount`) — fixed; the row reads both at `:646` and `:650`.

### Failure scenario

The false premise is load-bearing for §2.2, for §3.9's "Backend needed: server-side filtering with paging", and for §10 item 2 — so an implementer following the doc rebuilds a filtering layer that already exists, or re-declares the params and breaks `LeadSpecification`'s scope AND. The stale line numbers mean every §2 citation must be re-found by grep, and the doc reads as authoritative right up to the point it sends someone to the wrong code.

### Recommended fix

Rewrite §2.2: backend filtering is **DONE** (`LeadController.java:92-102` + `LeadSpecification.java:43-94`); the remaining gap is purely frontend wiring (**LEAD-32**) plus the two missing predicates (**LEAD-38**). Delete the "Backend needed" half of §3.9. Annotate §10 item 2 as "BE done — FE wiring only" and item 10 as "written but unreachable; `totalCount` is never set". Add a banner under §2 instructing readers to re-locate by symbol, not by line.

---

## LEAD-43 — Three different clocks inside one feature

**Priority:** P3 · **Severity:** Medium · **Confidence:** Confirmed · **Extends LEAD-12**

### Evidence

- `lead/service/LeadLogServiceImpl.java:198` — `String today = LocalDate.now().format(DATE_ONLY);` — no zone at all.
- `lead/service/LeadServiceImpl.java:868-869` — the opposite, and it says why: `ZoneId zone = tenantTimeZone.forTenant(tenantId); LocalDate today = LocalDate.now(zone);`.
- `lead/alert/LeadAlertService.java:74-93` — a third behaviour: tenant-zone `now` and day boundaries compared against `Lead.createdAt`, a plain `LocalDateTime` written by `@CreatedDate` in the **JVM** zone (`BaseEntity.java:43-45`), with the write side using `LocalDateTime.now()` (`LeadClaimService.java:176`).

### Failure scenario

Latent today only because the container pins `TZ=Asia/Kolkata` (`Dockerfile:27`) and `TenantTimeZone.DEFAULT` matches. For a Nepal tenant (NPT +05:45 — the case `TenantTimeZone`'s own javadoc cites), `now` runs 15 minutes ahead of every stored `createdAt`, so open leads read older than they are and the SLA-breach tile disagrees with the per-lead `slaBreached` flag. The All-Lead-Logs "today" card also shows a different date from the All-Leads cards for the same tenant. Changing the server timezone breaks every tenant at once.

### Recommended fix

One clock per comparison. Inject `TenantTimeZone` into `LeadLogServiceImpl` for the display date, and in `LeadAlertService` convert the tenant-local day boundaries into server-zone `LocalDateTime` before querying — `today.atStartOfDay(zone).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()` — so boundaries and stored values share a frame. LEAD-12 covers the alert tiles specifically; this entry is the feature-wide inconsistency, and LEAD-26 is the same class in the reminder scheduler.

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

## LEAD-44 — Mutation responses return a `LeadResponseDto` with `latestQuotation` and `logCount` always null

**Priority:** P4 · **Severity:** Low · **Confidence:** Confirmed

**Evidence.** `lead/service/LeadServiceImpl.java:766` and `:776` — both return paths of `updateLeadStage` end in `leadMapper.toResponse(updated)`, and `updateLead` ends the same way. Neither calls `enrichWithLatestQuotation` (`:692`) or `enrichWithLogCounts` (`:704`); only `getAllLeads` (`:447-448`), `getLeadById` (`:460-461`), `searchLead` and `getLeadBoard` (`:681-682`) do. `LeadMapper.toResponse` never sets either field, so `PATCH /{id}/stage`, `PUT /{id}` and `POST /leads` all answer with both keys null.

**Impact.** Any client that replaces a row with the mutation response blanks that row's Quote Value column (`lead.latestQuotation.grandTotal`) and its log-count badge until a full refetch — the row visibly loses data immediately after a successful save. It is also why the correct fix for LEAD-32's optimistic patches (patch from the response) is unavailable today, forcing a refetch instead.

**Fix.** In `updateLead` and both return paths of `updateLeadStage`, build the DTO then run the two lines `getLeadById` already uses: `enrichWithLatestQuotation(List.of(dto)); enrichWithLogCounts(List.of(dto), List.of(updated));`.

---

## LEAD-45 — `EligibleUserDto.activeLeads` carries the composite workload score but is rendered as a lead count

**Priority:** P4 · **Severity:** Low · **Confidence:** Confirmed

**Evidence.** `lead/assignment/service/LeadAssignmentService.java:110-113` — `new EligibleUserDto(u.getPublicId(), u.getName(), u.getEmail(), counts.getOrDefault(u.getId(), 0L))` where `counts = workloadScores(...)` → `workloadService.scoresFor(...)` (`:343-348`), documented at `LoadBasedAssignmentStrategy.java:16` as `UserWorkload.score()` = todo + inProgress + activeLeads + openReminders. The DTO field is `private long activeLeads; // current active-lead count` (`EligibleUserDto.java:19`). Live render sites: `[FE] CreateLead.jsx:1178` — `` label: typeof u.activeLeads === "number" ? `${u.name} · ${u.activeLeads} active` : u.name `` — and `[FE] LeadHistoryDrawer.jsx:295`.

**Impact.** An agent with zero leads but five open tasks and two reminders shows as "· 7 active" in the Assign To dropdown and the reassign picker. A manager choosing an assignee reads a number that is not what it claims, and routes leads away from the person with actual capacity. The DTO javadoc asserts the wrong meaning, so the next reader propagates it.

**Fix.** Rename the DTO field to `workloadScore` and update the two render sites to say "workload" — or, if the word "active" must stay, populate it from the lead-only component of `UserWorkload` rather than `score()`.

---

## LEAD-46 — Create Lead and Edit Lead have no permission gate at the route or the page

**Priority:** P4 · **Severity:** Low · **Confidence:** Confirmed

**Evidence.** `[FE] src/app/router.jsx:296` — `<Route path="createlead" element={<CreateLead />} />` and `:359` — `<Route path="/EditLead/:id" element={<EditLead />}/>`; neither is wrapped in `<Guard>`, unlike the sibling `leads/incoming` at `:286-293`. `[FE] CreateLead.jsx:827` imports `hasPermission`/`P` but uses them for exactly one thing, `canReadCustomers` at `:1928`. There is no `LEAD_CREATE`/`LEAD_UPDATE` check and no `AccessDenied` path, though `AccessDenied` exists in this feature and `AllLeads.jsx` uses it.

**Impact.** UX only — the backend `@PreAuthorize` is the real gate and holds. But an agent without `LEAD_CREATE` (or a viewer opening `/EditLead/:id` without `LEAD_UPDATE`) gets the full 30-field form, the assignment-recommendation call and the customer-lookup probes, fills everything in, presses Save, and only then receives a 403 with the typed record lost.

**Fix.** Wrap both routes in `<Guard allow={hasPermission(P.LEAD_CREATE)}>` / `<Guard allow={hasPermission(P.LEAD_UPDATE)}>` and add the same check inside the page before the loading branch, rendering `<AccessDenied/>`.

---

## LEAD-47 — `AllLeads`' overview fetch is the one effect not gated on `LEAD_READ`

**Priority:** P4 · **Severity:** Low · **Confidence:** Confirmed

**Evidence.** `[FE] AllLeads.jsx:1700-1703` and `:1705-1708` — the two effects above it are guarded (`if (!hasPermission(P.LEAD_READ)) return;`) with the comment *"without this a user who reaches /allleads by URL without LEAD_READ fires requests that all 403 — and the shared interceptor toasts each one on top of the access-denied screen"*. The effect at `:1714-1727` then does exactly that, ungated: `useEffect(() => { leadService.getAllLeads(0, 200).then(...).catch(() => setOverviewLeads([])); }, []);`. The route itself is unguarded (`router.jsx:282`), so this effect is the only thing between a URL and the request.

**Impact.** Opening `/allleads` by URL without `LEAD_READ` still fires `GET /api/leads?page=0&size=200`, the interceptor toasts the 403, and `AccessDenied` renders with a permission error floating over it — precisely the regression the two guards above were added to prevent.

**Fix.** Add `if (!hasPermission(P.LEAD_READ)) return;` as the first line of the effect. This code disappears entirely if **LEAD-32** removes `overviewLeads`.

---

## LEAD-48 — The delete confirmation and the type-change toast print the raw lead UUID

**Priority:** P4 · **Severity:** Low · **Confidence:** Confirmed

**Evidence.** `[FE] AllLeads.jsx:493` — `Are you sure you want to delete lead <span …>#{lead?.id} ({lead?.customerName || 'N/A'})</span>?` and `:1806` — `` showToast(`Lead #${leadToUpdate.id} set to ${newType}!`) ``. `LeadResponseDto.java:26` is `private UUID id;   // ← was Long, now UUID (exposes publicId)`, so `lead.id` **is** the raw UUID. The same file states the rule at `:624-631` (*"A UUID is not a lead reference anyone can read out on a call"*), and the sibling toasts at `:1781` and `:1822` already use `leadCode || customerName`.

**Impact.** The delete dialog reads "delete lead #296ebd28-af1f-40db-9e1f-f8d84f5ffb58 (Priya)". A user cannot verify they are deleting the right record from a UUID — on the one irreversible action on the page — and it is the exact anti-pattern the Lead ID column was fixed to avoid.

**Fix.** Use the neighbouring idiom: `{lead?.leadCode || lead?.customerName || 'this lead'}` at `:493`, and `` `Lead ${leadToUpdate.leadCode || leadToUpdate.customerName || ''}` `` at `:1806`.

---

## LEAD-49 — `pages/EditLead.jsx` (1,275 lines) and three components are entirely dead

**Priority:** P4 · **Severity:** Low · **Confidence:** Confirmed

**Evidence.** `[FE] src/features/leads/index.js:6` — `export { default as EditLead } from "./pages/CreateLead";` with the comment *"Create and Edit routes intentionally use one mode-aware page"*; `router.jsx:18` reads that named export. A repo-wide grep for `pages/EditLead` returns zero importers. The same holds for `components/LeadInformation.jsx`, `LeadSummary.jsx` and `ServicesSection.jsx` — every reference is inside a commented-out block in `CreateLead.jsx` (`:9-13`, `:212-311`, `:644-746`) or in `EditLead.jsx` itself.

**Impact.** 1,275 lines of plausible, partially-live edit-form code that nothing renders, duplicating the reset/prefill logic in `CreateLead.jsx`. It is the natural file to open when someone reports "the edit form loses X", and a fix applied there has no effect. `LeadInformation.jsx` is actively hazardous: during this audit it was cited as evidence for two separate "live" behaviours and both citations were wrong *because the file is dead*.

**Fix.** Delete `pages/EditLead.jsx`, `components/LeadInformation.jsx`, `components/LeadSummary.jsx` and `components/ServicesSection.jsx`, after confirming nothing in the commented blocks is still wanted.

---

## LEAD-50 — `ConvertToBookingModal` is imported by `AllLeads` but never rendered

**Priority:** P4 · **Severity:** Low · **Confidence:** Confirmed

**Evidence.** `[FE] AllLeads.jsx:32` — `import ConvertToBookingModal from "../components/ConvertToBookingModal";`. A repo-wide grep for the identifier returns exactly two hits: this import and the component's own `export default` at `ConvertToBookingModal.jsx:53`. Conversion actually runs through `handleConvertNavigate` (`:1832-1834`), `` navigate(`/CreateBooking/${lead.publicId || lead.id}`) ``, wired at `:2435`.

**Impact.** A 474-line dead component with its own conversion API calls ships in the leads chunk on every page load, and the live import makes it look wired — anyone maintaining the convert flow may read and edit the wrong file. Its `onConverted` callback is the in-place "flip the row to Booked" capability the navigate-away path gave up.

**Fix.** Delete the import at `:32` and delete `components/ConvertToBookingModal.jsx`. If the in-place conversion UX is wanted back, render it from `handleConvertNavigate` instead of navigating away.

---

## LEAD-51 — `QuotationsModal`'s preview-design picker is unreachable, so `webViewStyle` is permanently null

**Priority:** P4 · **Severity:** Low · **Confidence:** Confirmed

**Evidence.** `[FE] AllLeads.jsx:945` — `const [previewPickFor, setPreviewPickFor] = useState(null);`. Every reference to the setter is `setPreviewPickFor(null)` — at `:1252` inside `onSelect` and `:1254` as `onClose` — and both live inside the `{previewPickFor && (…)}` block at `:1245` that `previewPickFor` itself gates. Nothing ever sets it to a quotation. Consequently `webViewStyle` (`:946`) is only written at `:1250` inside that unreachable block, so `<QuotationWebView styleOverride={webViewStyle}/>` (`:1240`) and `copyLink(webViewQ, webViewStyle)` (`:1226`) always pass null.

**Impact.** The "preview a design without saving it" path that `mode="preview"` `QuotationStyleModal` advertises can never open. Reading the file, a maintainer concludes the web view supports a one-off style override; it does not — the overlay always renders the stored style, and Copy link never carries one. ~15 lines of misleading state and JSX. (Noted as "dead state" in `LEADS-LIST-UI-REDESIGN.md` §2.11 and still live.)

**Fix.** Either wire an entry point (a "Preview design" button calling `setPreviewPickFor(q)`) or delete `previewPickFor`, `webViewStyle`, the block at `:1245-1256`, and simplify `:1226` and `:1240`.

---

## LEAD-52 — Six `leadService` methods have zero callers, including one that exists to replace client-side maths

**Priority:** P4 · **Severity:** Low · **Confidence:** Confirmed

**Evidence.** Repo-wide greps excluding `leadService.js` itself return no callers for `getLeadLogStats` (`:324`), `getUserWorkload` (`:401`), `getLeadsByStagePerUser` (`:404`), `getLeadCountForUser` (`:407`) and `findLeadByPhone` (`:371`); `searchByPhone` appears only inside commented blocks (`CreateLead.jsx:146`, `:578`, and dead `EditLead.jsx:646`). Meanwhile `[FE] AllLeadLogs.jsx:235` computes `totalLogs` client-side over a capped page while `GET /leads/logs/stats` serves exactly that (`LeadController.java:196`). `GET /leads/board` has no service method at all.

**Impact.** The All-Lead-Logs stat cards report truncated client-side sums when an accurate server roll-up is one already-written call away — the same defect `/stats/summary` fixed on AllLeads (**LEAD-33**). The other five read as live API surface, so a reviewer assumes a workload dashboard exists somewhere.

**Fix.** Call `getLeadLogStats` from `AllLeadLogs` and render its figures on the hero cards (this is also LEAD-33's fix). Delete `getUserWorkload`, `getLeadsByStagePerUser`, `getLeadCountForUser` and `findLeadByPhone`, or comment each with the screen it is reserved for.

---

## LEAD-53 — Five dead members in the lead module, including a second `ApiResponse` envelope

**Priority:** P4 · **Severity:** Low · **Confidence:** Confirmed

**Evidence.** `lead/dto/ApiResponseDto.java:12` — `public class ApiResponseDto<T>` with success/failure factories; a grep across `src/main` and `src/test` finds references only inside its own file, while every lead endpoint uses `common.dto.ApiResponse`. Also zero-caller: `LeadRepository.java:30` `findAllByTenantIdAndDeletedAtIsNull(Long, Pageable)`, `LeadRepository.java:69` `findAllByTenantIdAndDeletedAtIsNullAndAssignedUser_IdIn(…, Pageable)` (only the `…OrderByCreatedAtDesc` sibling at `:72` is used), `LeadLogRepository.java:20` `countByLead_IdAndDeletedAtIsNull`, and `LeadNotFoundException` (thrown nowhere; only `GlobalExceptionHandler:90` handles it).

**Impact.** `ApiResponseDto` is a live trap: a developer adding a lead endpoint can pick the module-local envelope and ship a response shape no frontend unwrapper expects, violating the house rule. The two orphaned paged finders read as "the scope-filtered paged path lives here" when `getAllLeads` actually goes through `LeadSpecification` — which is where a scope change must be made.

**Fix.** Delete `ApiResponseDto.java`, `LeadNotFoundException.java` (and its handler branch), `LeadRepository:30` and `:69`, and `LeadLogRepository:20`. If `ApiResponseDto` must survive, mark it `@Deprecated` pointing at `common.dto.ApiResponse`.

---

## LEAD-54 — `Lead.origin`, `sourceIntegrationId` and `customerLinkedAt` are written on every create and read by nothing

**Priority:** P4 · **Severity:** Low · **Confidence:** Confirmed

**Evidence.** `lead/entity/Lead.java:102` — `@Enumerated(EnumType.STRING) @Column(name="origin", length=20) @Builder.Default private LeadOrigin origin = LeadOrigin.MANUAL;`, set from the actor by `LeadMapper.toEntity:70-71`. `grep -rn "getOrigin()"` across `src/main` and `src/test` returns **zero** hits; likewise `getSourceIntegrationId()` and `getCustomerLinkedAt()`. `LeadResponseDto` exposes none of the three, and no repository query filters on them.

**Impact.** `LeadOrigin`'s stated reason to exist is unrealised: its javadoc says that without it *"source-wise conversion reporting silently mixes machine-verified provenance with a human's guess"* — but no report, DTO or query reads the column. Same for `customerLinkedAt`, whose javadoc promises it distinguishes "checked, nobody matched" from "never checked". Three columns plus a CHECK-constraint maintenance burden with no reader, and a future reader will assume historical rows are populated.

**Fix.** Add `origin` (and optionally `sourceIntegrationId`) to `LeadResponseDto` + `LeadMapper.toResponse` and to the source breakdown in `getStatsSummary` — or delete the columns. Do not leave write-only columns.

---

## LEAD-55 — The log-summary endpoint silently widens on a bad `stage` or `userId`, while `/api/leads` 400s on the same input

**Priority:** P4 · **Severity:** Low · **Confidence:** Confirmed

**Evidence.** `lead/service/LeadLogServiceImpl.java:270-277` — `try { return LeadStage.fromValue(stage); } catch (IllegalArgumentException e) { return null;   // unknown filter value → no stage restriction }`. `:279-284` — `resolveUserFilter` maps an unresolvable publicId to `null`, i.e. also no filter. Both params are exposed on `GET /leads/logs/summary` (`LeadController.java:184-186`). `LeadSpecification.parseStage` (`:102-104`) has no catch, so the same bad stage on `GET /api/leads` throws and becomes a 400.

**Impact.** Latent rather than live — `AllLeadLogs` filters entirely client-side and sends neither param (which is also why the phantom "Ready to Book" produces an *empty* grid rather than an unfiltered one; see LEAD-30). But this is public API: `?stage=garbage` or a foreign `?userId=` returns the caller's full visible list instead of an empty one, and the two lead endpoints answer identical malformed input two different ways.

**Fix.** Throw on both: a `BadRequestException("Unknown stage: …")` in `parseStage`, and `ResourceNotFoundException` (or an empty-page sentinel) in `resolveUserFilter` when the publicId does not resolve. Never fall back to "no filter".

---

## LEAD-56 — CSV import row numbers do not match the user's file

**Priority:** P4 · **Severity:** Low · **Confidence:** Confirmed

**Evidence.** `lead/bulkimport/CsvLeadImportReader.java:56` — the parser is built with `.setIgnoreEmptyLines(true)`; `:78-81` — `// record.getRecordNumber() is 1-based and counts the header, which is exactly the line number the user sees in their editor.` then `new LeadImportSheet.LeadImportRow((int) record.getRecordNumber(), values)`. `getRecordNumber()` counts parsed **records**, not source lines: an ignored empty line produces no record, and a quoted field containing a newline spans several lines in one record. `ExcelLeadImportReader.java:99` uses the true index — `new LeadImportSheet.LeadImportRow(row.getRowNum() + 1, values)`.

**Impact.** The import report is the only way to locate a bad row in a file of up to 2,000, and the frontend prints the number verbatim. One blank line shifts every subsequent reported row by one; a Notes cell with a line break shifts it further. The user opens the named row and finds a different, valid lead — and the two readers disagree for byte-equivalent data.

**Fix.** Use the line-based accessor `record.getStartLineNumber()` (Commons CSV ≥ 1.9), or capture `parser.getCurrentLineNumber()` before each record, so the number matches the source file the way the Excel reader's already does.

---

## LEAD-57 — Bulk-import assignee lookup does not filter `isActive`, despite the error message promising it

**Priority:** P4 · **Severity:** Low · **Confidence:** Confirmed

**Evidence.** `lead/bulkimport/LeadImportRowMapper.java:192-197` — `Optional<User> user = userRepository.findByUsernameAndTenantIdAndDeletedAtIsNull(username, tenantId); if (user.isEmpty()) { errors.add("No **active** user with username \"" + username + "\" in this organisation"); }`. The finder checks `deletedAt IS NULL` only; `isActive` is never consulted.

**Impact.** A row naming a deactivated user passes preview as READY, then fails at commit inside `LeadAssignmentService.resolveSubmittedAssignee` ("Cannot assign lead to inactive user"), where the import catches it as a generic `RuntimeException` and reports SKIPPED with a message the preview never warned about. The preview's contract — a READY row will import — is broken for exactly this case.

**Fix.** Add `|| !Boolean.TRUE.equals(user.get().getIsActive())` to the check, so the row is flagged INVALID at preview time with the message that is already written.

---

## LEAD-58 — `LeadMetaController` has no class-level `@PreAuthorize`

**Priority:** P4 · **Severity:** Low · **Confidence:** Confirmed

**Evidence.** `lead/controller/LeadMetaController.java:26-29` — `@RestController @RequestMapping("/api/leads/meta") @RequiredArgsConstructor public class LeadMetaController {`, with no class annotation. Only `getLeadSources` carries `@PreAuthorize("hasAnyAuthority('LEAD_READ','LEAD_CREATE','LEAD_UPDATE')")` (`:42`). Both `LeadController` (`:37`) and `LeadClaimController` (`:35`) deliberately carry a class default, and `LeadClaimController`'s javadoc (`:26-29`) explains that a new method there "fails CLOSED".

**Impact.** The current single method is safe. The class, however, has no floor: a method added here without its own annotation is reachable by every logged-in user of every tenant — and unlike its two siblings there is no default to fall back to. This is the same fails-open shape `LeadController`'s own class comment warns about.

**Fix.** Add `@PreAuthorize("hasAnyAuthority('LEAD_READ','LEAD_CREATE','LEAD_UPDATE')")` at class level as the floor, keeping the method-level annotation as documentation.

---

## LEAD-59 — Row selection is fully wired but has no bulk action

**Priority:** P4 · **Severity:** Low · **Confidence:** Confirmed

**Evidence.** `[FE] AllLeads.jsx:1605` declares `selectedIds`; `:2074` and `:2075-2077` build it via `toggleSelect`/`toggleSelectAll`, driven by a per-row checkbox (`:2426-2427`) and a header select-all (`:2387`). Its only render consumer is `:2366-2371`: `{selectedIds.length > 0 && (… <span>{selectedIds.length} selected</span> <button onClick={() => setSelectedIds([])}>Clear</button> …)}`. No bulk delete, assign, stage change or export exists anywhere in the file.

**Impact.** Every row and the header carry a checkbox that promises a bulk operation and delivers a counter with a Clear button. Users tick twenty rows looking for an action bar and find nothing. It also costs a per-row re-render on a 16-column table for no functionality. (`LEADS-LIST-UI-REDESIGN.md` §2.6 records the same observation.)

**Fix.** Either add the bulk action the UI promises — bulk stage change and bulk delete both already have single-row service calls, and `leadAlertService.reassign` exists for bulk assign — or remove the select column, both checkboxes, `selectedIds` and the strip.

---

## LEAD-60 — `/WhatsAppPanel` is routed with no lead

**Priority:** P4 · **Severity:** Low · **Confidence:** Confirmed

**Evidence.** `[FE] src/app/router.jsx:361` — `<Route path="/WhatsAppPanel" element={<WhatsAppPanel/>}/>`, with no props. `[FE] WhatsAppPanel.jsx:84` — `export default function WhatsAppPanel({ lead, onClose })`, and `:98-100` — `` const phone = cleanPhone(lead?.phone); const name = lead?.customerName || lead?.name || "Lead"; const waBase = `https://wa.me/${phone}`; ``. The component is designed as an overlay and is rendered correctly that way from `AllLeads.jsx:2106`.

**Impact.** Navigating to `/WhatsAppPanel` produces a full-screen chat panel titled "Lead" whose Call and Open Chat anchors point at `https://wa.me/`, and whose Close button and backdrop both invoke an undefined `onClose` — the user is stuck until they press browser Back.

**Fix.** Delete the route (`router.jsx:361` and the `lazyPage` at `:22`) and the `WhatsAppPanel` export from `index.js:10`. `AllLeads` already renders it correctly as an overlay.

---

## LEAD-61 — No `AllLeads` modal can be closed with Escape, and none has an accessible name

**Priority:** P4 · **Severity:** Low · **Confidence:** Confirmed

**Evidence.** `ViewLeadModal` (`:394`), `DeleteConfirm` (`:487`), `QuotationsModal` (`:1129`), `AddLogModal` (`:1336`) and `LogsModal` (`:1469`) each register only an `onClick` on the backdrop. A grep for `onKeyDown|keydown|Escape|role="dialog"|aria-modal` across the whole 2,468-line file returns exactly one hit — `StatCard`'s clickable handler at `:341`. The close buttons carry neither `aria-label` nor `title`: `:414` `<button onClick={onClose} className="w-8 h-8 rounded-full bg-white/20 …"><X size={16} /></button>`, identically at `:1140`, `:1349` and `:1480`. Separately the row Stage and Type selects (`:901`, `:909`) combine `appearance-none` with `outline-none` and no `focus-visible` replacement. `[FE] LeadHistoryDrawer.jsx:105-115` shows the correct pattern inside the same feature.

**Impact.** Keyboard and screen-reader users cannot dismiss any lead modal without tabbing to a button that announces only as "button"; Escape does nothing, and in the destructive `DeleteConfirm` the only alternative is Tab-to-Cancel. Focus is neither trapped nor restored. On the table itself, the two fastest inline edits have no visible focus indicator and no dropdown affordance.

**Fix.** Extract `LeadHistoryDrawer`'s pattern into a `useEscapeClose(onClose)` hook and call it in all five modals; add `role="dialog" aria-modal="true" aria-label=…` on each panel and `aria-label="Close"` on the five X buttons; add `focus-visible:ring-2 focus-visible:ring-blue-400` to `:901` and `:909`.

---

## LEAD-62 — Board and enum comments cite seven columns, a deleted file, and a removed `LeadType` constant

**Priority:** P4 · **Severity:** Low · **Confidence:** Confirmed

**Evidence.** `lead/controller/LeadController.java:116` — `/** All leads grouped into the seven pipeline columns — powers LeadKanban.jsx. */`; `LeadBoardColumnDto.java:14-15` — "returns all seven `LeadStage` columns"; `LeadServiceImpl.java:659-660` — "all seven lanes". `LeadStage.java:8-16` has **eight** constants and `getLeadBoard` emits `Arrays.stream(LeadStage.values())` (`:661`). No `LeadKanban.jsx` exists in the frontend. Separately `LeadSource.java:83` documents `REPEAT_CUSTOMER` against `LeadType.REPEAT_CUSTOMER`, a constant that no longer exists (`LeadType` is FRESH/HOT/WARM/COLD).

**Impact.** A reader trusting "seven lanes" will size a UI or a test for seven and silently drop `REOPENED`. Low on its own, but it is the same drift class that let LEAD-35 (Converted offered in the create form) and LEAD-30 ("Ready to Book") survive: nobody re-checks the enum when it grows.

**Fix.** Say "one column per `LeadStage` (currently eight)" at `LeadController:116`, `LeadBoardColumnDto:14` and `LeadServiceImpl:659`; drop the `LeadKanban.jsx` reference; rewrite the `LeadSource.REPEAT_CUSTOMER` note to cite the historical `LeadType`. If the board is deleted (LEAD-16), all three comments go with it.

---

## Recommended repair order

*Revised 2026-08-05 to fold in LEAD-32 … LEAD-62.*

1. **Fix the three docs first — they are cheap and they are actively causing defects** (LEAD-40, LEAD-41, LEAD-42, and the comments in LEAD-62). This is not housekeeping: `CLAUDE.md`'s stale `ddl-auto` line will hard-fail the boot of the first schema change anyone makes for the items below, and its stale localStorage inventory already manufactured a false finding during this very audit. An hour here prevents rework in every step that follows.
2. **Decide the sub-agent question** (LEAD-01, LEAD-02). If franchise partners are a separate commercial party, these are P1 and lead the list. The fix is small — an eligibility check in `stampFirstContact` and a `CRM_FULL` gate on `LeadAlertController` — and it closes an invariant the codebase already states three times.
3. **Close the third stage-write door** (LEAD-03, then LEAD-09, then LEAD-35). One extracted private method fixes the claim window, the SLA stamp and the frontend's stage dropdown together; the missing `terminalStages` predicate is a one-line change on two queries; and while you are in `createLead`, reject a `CONVERTED` stage there the way `updateLead` already does.
4. **Stop losing writes** (LEAD-20 → LEAD-19, then LEAD-05, LEAD-07). Do LEAD-20 first: the routes have no `:id`, so the faked-save page is currently unreachable and fixing it in isolation proves nothing. These are the places where a user or a provider is told something succeeded that never happened. (LEAD-06 was already fixed — it was reported against a pre-commit stash.)
5. **Restrict `updateLead`'s owner change** (LEAD-04) — simplest correct fix is to ignore `assignedUserId` there entirely.
6. **Make the lead list actually server-side.** Order matters: **LEAD-38** (add the `activeOnly` / `followUpDueBy` predicates) and **LEAD-16** (whitelist `sortBy`, clamp the page size, add a stable tiebreaker) must land *before* **LEAD-32** (wire the table to the server page), or the fix ships with two tabs that 400 and an unstable sort. **LEAD-44** makes the optimistic patches correct instead of forcing a refetch; **LEAD-47** disappears with it. Then **LEAD-33** and **LEAD-22** — the same defect on the log screen and the dashboard — and **LEAD-52**, which supplies the endpoint LEAD-33 needs.
7. **Harden ingest** (LEAD-10, LEAD-15, LEAD-36) and the transaction/connection shape (LEAD-11). LEAD-36 is a self-contained keyset-paging change and can go any time.
8. **Timezone and session lifetime** (LEAD-43 as the umbrella, covering LEAD-12 and LEAD-26; then LEAD-13). Pick one clock per comparison rather than patching each site.
9. **Silent-widening and preview-contract fixes** (LEAD-55, LEAD-57, LEAD-56, LEAD-34). Each is small, and each is a place where the system quietly returns or stores something other than what was asked for.
10. **Delete the dead code, in one sweep** (LEAD-49, LEAD-50, LEAD-51, LEAD-52, LEAD-53, LEAD-54, LEAD-60). ~2,000 frontend lines and five backend members. Worth doing as a single pass rather than piecemeal: two findings in this audit were *based on* dead files and had to be rejected on verification, so this directly reduces the cost of the next audit.
11. **The remaining polish**, cheapest first: LEAD-39, LEAD-48, LEAD-30, LEAD-29, LEAD-27, LEAD-45, LEAD-58, LEAD-46, LEAD-59, LEAD-61, LEAD-37. (LEAD-31 is done.)

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

### Added by the second pass

- `createLead` with `leadStage = CONVERTED` → 409, parameterised alongside `updateLead` so both doors are asserted by one test (LEAD-35). The existing `assertConversionStageTransitionAllowed` test covers only the update path, which is why the create hole survived.
- `GET /api/leads?activeOnly=true` and `?followUpDueBy=<today>` returning exactly what `getStatsSummary`'s `activeLeads` and `followUpsOverdue + followUpsDueToday` count, for the same fixture (LEAD-38). If the Specification and the roll-up ever disagree, a card and the list it opens show two different pipelines — assert them against one another, not against literals.
- `GET /leads/logs/summary?stage=garbage` → 400 not a silently widened list, and `?userId=<foreign-uuid>` → empty or 404, never the caller's full list (LEAD-55).
- `LeadPhoneNormalizationBackfill` over 2,000 canonicalisable rows in one run → **zero** rows left with `phone_normalized IS NULL` (LEAD-36). The current OFFSET implementation leaves exactly half and reports them as un-canonicalisable, so a naive "did it log success?" assertion passes.
- A CSV fixture containing a blank line and a quoted multi-line Notes cell, asserting the reported row number equals the source line number and matches what `ExcelLeadImportReader` reports for the byte-equivalent sheet (LEAD-56).
- Import preview with a **deactivated** assignee username → INVALID at preview, not READY-then-SKIPPED at commit (LEAD-57).
- The mutation-response contract: `PATCH /{id}/stage`, `PUT /{id}` and `POST /leads` all return a non-null `logCount` and, where one exists, `latestQuotation` (LEAD-44). This is the test that makes optimistic row-patching safe.
- A frontend test — currently there are none for this feature — that the leads table renders the array `fetchLeads` populates, and that a successful stage change leaves the rendered row on the new stage (LEAD-32). The whole defect is one identifier on one line, and no test in either repo would have caught it.
