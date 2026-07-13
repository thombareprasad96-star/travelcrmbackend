# BUG_AUDIT.md — Logical Bug Hunt (Phase 1: crown jewels)

**Scope of this phase:** the six highest-blast-radius domains — cross-tenant isolation, auth/authz
realms, money paths, the Disha AI tools, the traveler portal, and soft-delete/state-machine/async.
Read-only. Nothing was changed. Every asserted finding was traced end-to-end and then re-traced by an
independent adversarial verifier instructed to *refute* it. Business-intent-dependent items are quarantined
under **Needs my call** rather than asserted.

Method: I derived the invariants below from the code (not from docs), built a threat model, then fanned
adversarial tracers across the six domains with per-finding skeptical verification. Two findings (F4, F7)
are my own direct traces that the domain hunters only footnoted as "known caveats."

---

## Invariants I derived (what must be true for this system to be correct)

- **I1 — Tenant isolation.** Every tenant-scoped read/write must be constrained to `TenantContext.getTenantId()`.
  The Hibernate `@Filter("tenantFilter")` is enabled **only inside `@Transactional` methods** (`TenantFilterAspect`
  runs `@Before` them) and **never** applies to `EntityManager.find`/`repository.findById`/`getReferenceById`
  (PK loads bypass filters). Spring-Data derived queries (`findByX…`) get the filter only when the caller is
  `@Transactional`. → A non-`@Transactional` read of a tenant entity, or a `findById(Long)` on one, is a candidate leak.
- **I2 — Cross-aggregate FKs** (`Booking.customerId/leadId`, `serviceItemId`, `vendorId`, …) must be validated with a
  tenant-scoped finder before persist, else a client attaches another tenant's row by id.
- **I3 — Auth realms are cryptographically separate.** Staff (`jwt.secret`) vs portal (`portal.jwt.secret`); SuperAdmin
  has no `tenantId` claim. Portal queries scope by `principal.customerId` (object-level ownership: foreign publicId → 404).
- **I4 — `TenantContext` lifecycle.** ThreadLocal; set on entry, cleared in `finally`. `@Async`/`@Scheduled`/SSE threads
  don't inherit it and must set/clear it per tenant.
- **I5 — Money consistency.** `paidAmount`/`totalPayable`/`paymentStatus`, quotation totals, and vendor
  outstanding must stay internally consistent and update **atomically**. Only `Vendor` has `@Version`; `Booking`,
  `Quotation`, etc. have **no optimistic lock**, so read-modify-write on money can lose updates.
- **I6 — Identifiers.** `publicId` (UUID) is the only external id; internal `Long id` never exposed nor accepted from a client.
- **I7 — Soft-delete.** Reads must exclude `deletedAt != null` (opt-in `softDeleteFilter` for master data, explicit
  `…DeletedAtIsNull` finders for core CRM). Restore must not resurrect rows under a still-trashed parent.
- **I8 — Permissions.** Per-user access gate + row-level scope + tenant-admin bypass (Lead is the reference). The gate
  must live where **every** entry path passes through it — not only on the REST controller.

## Threat model / crown jewels

1. Cross-tenant read/write (the SaaS killer). 2. Privilege escalation (impersonation, role/claim, missing authz).
3. Money manipulation (payments, quotation totals, vendor outstanding, concurrency). 4. PII / margin leakage
(traveler documents, `vendorCost`/`netProfit` crossing to a customer surface). 5. The AI assistant as a **confused
deputy** — steered past a tenant/authz boundary the REST layer would enforce.

## Assumptions (if any is false, re-open the affected findings)

- The Hibernate `tenantFilter` reliably activates on `@Transactional` methods (I verified `TenantFilterAspect`
  ordering). If AOP self-invocation or a missing `@Transactional` silently disables it, isolation conclusions shift.
- `spring.jpa.hibernate.ddl-auto=update` means the live schema matches the entity mappings I read.
- **F8** assumes a plan can actually be configured as *LEADS-enabled, BOOKINGS-disabled*. **F6/F10** depend on
  business intent I can't settle statically.
- Concurrency findings (**F4**) assume a real multi-threaded/multi-node deployment with no external serialization
  in front of the payment endpoints.

---

## Top 5 things that will actually hurt you in production

1. **F1 — Disha AI tools skip the per-module permission gate (P1).** Any authenticated tenant user can ask the chatbot
   for booking financials, quotations, reminders, and **tenant-wide revenue/analytics** that the REST API guards behind
   `BOOKING_READ` / `QUOTATION_READ` / `CRM_FULL`. A low-privilege sales agent reads company money the UI would 403.
2. **F2 — `PATCH /bookings/{id}/status` bypasses every lifecycle guard (P1).** A `BOOKING_UPDATE` user can cancel a
   booking **without** reopening the linked lead or reclaiming the conversion-created customer (lead stuck `CONVERTED`,
   customer orphaned), resurrect a terminal `COMPLETED`/`CANCELLED` booking, and do so at a **lower** authority than the
   real cancel endpoint (`BOOKING_CANCEL`).
3. **F3 — Payment ledger drifts from `booking.paidAmount`; invoices contradict themselves (P2).** Three code paths write
   `paidAmount`; only one writes a ledger row. An invoice can print "Paid ₹50,000" with an **empty** Payments-Received
   table and `GET /payments` returning nothing.
4. **F4 — Lost-update race on `paidAmount` (P2).** `Booking` has no `@Version`; `addPayment`/`deletePayment` do
   read-modify-write. Two concurrent receipts (or a double-click) lose one increment and can slip past the overpayment guard.
5. **F5 — Public quotation share link leaks the agent's markup/margin (P2).** The unauthenticated `/api/public/quotations/{id}`
   reuses the internal DTO and strips only `createdBy`+`leadId`; `totals.markup`/`pricing.markup` (the agent's profit) stay
   in the JSON for anyone with the forwarded link.

---

## Findings table

| ID | Sev | Confidence | Location | One-line |
|----|-----|-----------|----------|----------|
| F1 | P1 | Confirmed | `ai/tool/BookingTools.java` (+ Quotation/Reminder/Dashboard tools), `ai/controller/ChatController.java:35` | AI tools bypass the per-module authority gate the REST controllers enforce (intra-tenant privilege escalation) |
| F2 | P1 | Confirmed | `booking/service/BookingServiceImpl.java:420` (`updateStatus`) | `PATCH /bookings/{id}/status` skips all transition guards → cancel w/o lead-reopen, resurrect terminal, lower authority |
| F3 | P2 | Confirmed | `booking/service/BookingServiceImpl.java:584` vs `BookingPaymentServiceImpl.java:87-91` | Divergent payment write-paths → `paidAmount` diverges from the ledger; invoice shows Paid with no receipts |
| F4 | P2 | Confirmed | `booking/service/BookingPaymentServiceImpl.java:59-91,110-120` | `paidAmount` read-modify-write with no `@Version` on `Booking` → lost-update / overpayment-guard bypass under concurrency |
| F5 | P2 | Confirmed | `quotation/service/QuotationServiceImpl.java:333-337` | Public share link leaks agent `markup` (margin) to the customer |
| F6 | P2 | Suspected¹ | `booking/repository/BookingRepository.java:65,71,77,81,83` | Revenue/pending/profit/GST/TCS stats include CANCELLED & REFUNDED bookings |
| F7 | P3 | Confirmed | `booking/service/BookingPaymentServiceImpl.java:142-146` | `derivePaymentStatus` never emits REFUNDED → a ledger add/delete silently clobbers a `REFUNDED` booking back to PAID/PARTIAL/UNPAID |
| F8 | P3 | Confirmed | `platform/entitlement/filter/ModuleAccessFilter.java:46,95` | `convert-to-booking` lives under `/api/leads/**` → gated by the LEADS entitlement, not BOOKINGS |
| F9 | P3 | Confirmed | `trash/TrashServiceImpl.java:104` | Restoring one quotation while its parent lead is still trashed → live child under a trashed parent |
| F10 | P3 | Suspected¹ | `quotation/repository/QuotationRepository.java:116-124` | Restoring a lead resurrects quotations the user had deleted independently earlier |

¹ *Suspected = mechanically traced and reachable, but "is this wrong?" depends on business intent → see **Needs my call**.*

---

## Findings — detail

### F1 · P1 · Confirmed — Disha AI tools bypass the per-module authority gate
**What's wrong.** `ChatController` is gated only by `@PreAuthorize("isAuthenticated()")` (`ai/controller/ChatController.java:35`).
`ChatOrchestrationService` wires every tool bean (`:126`). The `@Tool` methods (`BookingTools`, `QuotationTools`,
`ReminderTools`, `DashboardTools`) carry **no** `@PreAuthorize`, and the services they call (`BookingServiceImpl.getAll`
`:303`, `ReportService.getSummary` `:51`) carry none either — the per-module authority is enforced *only* on the REST
controllers (`BOOKING_READ` `:37`, `QUOTATION_READ`, `REMINDER_READ`, `CRM_FULL` `:19`). Tenant isolation still holds
(services are `@Transactional` so the tenant filter applies), so this is **intra-tenant** privilege escalation, not a
cross-tenant leak. `LeadTools` is *not* affected — `LeadServiceImpl` self-enforces via `scopeResolver`/`leadAccessGuard`.
**Repro.** A user whose role grants `LEAD_READ` but not `BOOKING_READ`/`CRM_FULL` asks Disha "list all bookings" or
"give me this month's dashboard counts" and receives booking financials and tenant-wide revenue/analytics the equivalent
REST calls would 403.
**Blast radius.** 4 of 5 tool families; DashboardTools is the sharpest (tenant-wide revenue behind `CRM_FULL`). Every tenant.
**Fix direction.** Enforce the same module authority inside the tool methods or push the check down into the shared service
so REST and AI paths share one gate; `AccessDeniedException` is already audited as DENIED by `AiAuditService.recordToolCall`.

### F2 · P1 · Confirmed — `PATCH /bookings/{id}/status` bypasses all lifecycle guards
**What's wrong.** `updateStatus()` does `booking.setStatus(request.getStatus())` with zero validation
(`BookingServiceImpl.java:420`; endpoint `BookingController.java:108`, authority `BOOKING_UPDATE`). The sibling path
`applyStatusOnUpdate()` (`:392-410`, used by the general PUT) explicitly forbids exactly this: it refuses `CANCELLED` via a
field edit (cancel must run `cancel()` → `moveBackToLead` + `handleDerivedCustomerOnCancel`, `:447/455`) and locks terminal
`COMPLETED`/`CANCELLED`. The PATCH path applies none of them, and `BOOKING_UPDATE` is a lower bar than the `BOOKING_CANCEL`
the real cancel endpoint (`:121`) requires.
**Repro.** A `BOOKING_UPDATE` user PATCHes `status=CANCELLED` on a `CONFIRMED` booking → booking flips to CANCELLED while
the linked lead stays `CONVERTED` and the conversion-created customer is orphaned. Same path allows `CANCELLED→CONFIRMED`
(un-cancel) and `COMPLETED→PENDING` (reopen terminal).
**Blast radius.** Every booking; lead↔booking state desync, orphaned derived customers, terminal states become mutable.
**Fix direction.** Route `updateStatus()` through the same shared transition validator as `applyStatusOnUpdate()` (refuse
`CANCELLED` → force `cancel()`; lock terminal states) and require `BOOKING_CANCEL` for a cancel transition.

### F3 · P2 · Confirmed — Payment ledger drifts from `booking.paidAmount`; invoices contradict themselves
**What's wrong.** `paidAmount` is mutated by three independent paths but only one writes a `BookingPayment` ledger row:
`BookingPaymentServiceImpl.addPayment` (`:87-91`, writes a row + increments) vs `BookingServiceImpl.updatePayment`
(`PATCH /payment`, `:584`, increments, **no row**) vs `create()`/`update()`→`calculateAndApplyFinancials` (`:803`, sets an
**absolute** value, no row). `BookingDocumentServiceImpl.buildModel` renders `paidAmount`/`pendingAmount` from the booking
(`:135-136`) but builds the "Payments Received" table from ledger rows (`:110-118`), and `getPayments()` lists only rows.
The ledger is therefore not the source of truth and can't be reconciled to `paidAmount`.
**Repro.** Agent records payment via the create form or `PATCH /payment`, then generates the Invoice PDF → it shows
"Paid ₹50,000 / Balance ₹X" while the receipts table is empty and `GET /payments` returns nothing. `deletePayment` only
subtracts its own row, so an inflated `paidAmount` can never be brought back to the ledger sum.
**Blast radius.** Every booking whose payments were entered anywhere except exclusively through `POST /payments`; affects
invoices, the payments list, `paymentStatus`, `pendingAmount`.
**Fix direction.** Make the ledger the single source of truth — recompute `paidAmount = SUM(active rows)` on add/delete —
and either retire the `PATCH /payment` + absolute-set-on-update paths or have them emit a ledger row.

### F4 · P2 · Confirmed — Lost-update race on `paidAmount` (no optimistic lock)
**What's wrong.** `addPayment`/`deletePayment` are `@Transactional` and do `newPaid = booking.getPaidAmount() ± amount`
then `bookingRepository.save(booking)` (`BookingPaymentServiceImpl.java:59-91,110-120`). `Booking` has **no `@Version`**
(I grep'd: only `vendor/entity/Vendor.java` carries `@Version`) and there is no pessimistic lock or atomic `UPDATE … SET
paid = paid + :amt`. Two concurrent transactions both read the same `paidAmount`; last commit wins, one increment is lost.
The overpayment guard `newPaid > totalPayable` is computed from the stale read, so two near-simultaneous receipts can also
jointly exceed `totalPayable`.
**Repro.** A customer's two receipts are posted at the same instant (or an agent double-clicks "Add payment"): both read
`paidAmount=0`, each writes `500`, the booking ends at `500` instead of `1000` — money silently lost from the ledger total.
**Blast radius.** Any concurrent/retried payment write on any booking; silent under-count of collected money and guard bypass.
**Fix direction.** Add `@Version` to `Booking` (retry on `OptimisticLockException`), or do the increment as an atomic DB
`UPDATE`, or take a pessimistic row lock for the read-modify-write. (Overlaps with F3's "recompute from ledger" fix.)

### F5 · P2 · Confirmed — Public quotation share link leaks the agent's markup/margin
**What's wrong.** `getPublicByPublicId()` builds the full internal `QuotationResponseDto` and nulls only `createdBy` and
`leadId` (`QuotationServiceImpl.java:335-336`) before returning it on the unauthenticated `GET /api/public/quotations/{id}`
(`SecurityConfig.java:92` permitAll). The DTO still serializes `totals.markup` (`QuotationResponseDto.java:89`, emitted
unconditionally via `nz()`) and `pricing.markup` (`:277`) — the agent's profit added over base cost
(`QuotationMapper.java:354,556`; `afterDiscount = subtotal − discount + markup`, `:539`).
**Repro.** A customer (or anyone the WhatsApp/email link is forwarded to) `curl`s the endpoint and reads `totals.markup`,
learning the agency's profit on the trip. Confidentiality leak, not integrity.
**Blast radius.** Every publicly-shared quotation on every tenant.
**Fix direction.** Map the public payload through a dedicated customer-safe **whitelist** DTO (don't blacklist-strip the
internal one); at minimum null the markup fields and reconsider exposing raw `discount`/`tax` pricing inputs.

### F6 · P2 · Suspected (business intent) — Money stats include CANCELLED & REFUNDED bookings
**What's wrong.** The stats aggregates filter only on `deletedAt IS NULL`, never on `status`
(`BookingRepository.java:65,71,77,81,83`). `cancel()` deliberately retains the booking (`deletedAt` stays null,
`status=CANCELLED`) with its money fields intact (`BookingServiceImpl.java:460`), so `sumTotalPending
= SUM(totalPayable − paidAmount)` counts a cancelled booking's unpaid balance as receivables, and revenue/net-profit/GST/TCS
include cancelled+refunded rows. `getPageSummary` has the same shape via `BookingSpecification.isActive()` (soft-delete only).
The code *is* status-aware elsewhere (counts break out cancelled/refunded; `sumTotalRefund` filters `status='REFUNDED'`),
which is what makes the status-blind money aggregates look like an oversight.
**Repro.** Book ₹500,000 (paid ₹100,000), then cancel → `/stats` still reports ₹400,000 pending and ₹500,000 revenue + its
profit/GST/TCS. Finance dashboard overstates receivables, revenue, and profit.
**Blast radius.** `/stats` and `/page-summary` for any tenant with cancelled/refunded bookings.
**Fix direction.** Exclude CANCELLED (and probably REFUNDED) from revenue/pending/profit/tax aggregates; align page-summary.
→ *Confirm intended semantics first (see Needs my call).*

### F7 · P3 · Confirmed — `derivePaymentStatus` silently clobbers a REFUNDED booking
**What's wrong.** `derivePaymentStatus` only ever returns `UNPAID`/`PARTIAL`/`PAID` (`BookingPaymentServiceImpl.java:142-146`),
but `PaymentStatus` includes `REFUNDED` and it is a reachable state (used by the refund flow and `sumTotalRefund`). Every
`addPayment`/`deletePayment` overwrites `booking.paymentStatus` from this function.
**Repro.** A booking is `REFUNDED`; an agent later deletes an old receipt (or adds an adjustment) → `paymentStatus` flips
away from `REFUNDED` to `PAID/PARTIAL/UNPAID`, losing the refund state and (via F6) re-entering "revenue".
**Blast radius.** Any refunded booking touched by a ledger op afterward. Latent landmine.
**Fix direction.** Guard the derivation so it never downgrades a terminal `REFUNDED` (or model refund as its own ledger entry).

### F8 · P3 · Confirmed — `convert-to-booking` gated by the LEADS entitlement, not BOOKINGS
**What's wrong.** `LeadConversionController` maps `POST /api/leads/{id}/convert-to-booking`
(`LeadConversionController.java:28,36`) and creates a Booking (`:42`). `ModuleAccessFilter.requiredModule()` iterates
`RULES` in insertion order and returns on the first prefix match (`ModuleAccessFilter.java:95`); `/api/leads` (`:46`, LEADS)
precedes `/api/bookings` (`:47`, BOOKINGS), so this booking-creating endpoint is only checked against LEADS. `@PreAuthorize`
`BOOKING_CREATE` still applies (per-user), so it's a **plan/module-entitlement** bypass, not an authz bypass.
**Repro.** A tenant on a plan with LEADS but not BOOKINGS, whose user holds `BOOKING_CREATE`, converts a lead and creates a
booking while the BOOKINGS module is "off."
**Blast radius.** Billing/entitlement enforcement for any LEADS-without-BOOKINGS plan.
**Fix direction.** Add an explicit rule for the convert path requiring BOOKINGS, or host the endpoint under `/api/bookings`.

### F9 · P3 · Confirmed — Restoring one quotation under a still-trashed lead → dangling live child
**What's wrong.** `TrashServiceImpl.restore()` un-deletes a row without checking its logical parent is live
(`:104`). Quotation is its own `TrashableType`, so a quotation cascade-trashed with its lead
(`QuotationEventListener.java:34-43`) appears as an independent Trash row and can be restored alone; the lead stays trashed.
**Repro.** Trash a Lead (quotations cascade to Trash), then restore just one Quotation from the Trash UI → the quotation is
live (`QuotationRepository.findAllByLeadIdAndTenantIdAndDeletedAtIsNull`) while the lead stays hidden — a live child under a
trashed parent. Recoverable, no boundary crossed.
**Fix direction.** On restoring a child whose parent is soft-deleted, refuse (with a clear message) or restore the parent too.

### F10 · P3 · Suspected (documented tradeoff) — Restoring a lead resurrects independently-deleted quotations
**What's wrong.** `restoreByLeadId` restores **every** trashed quotation for the lead
(`QuotationRepository.java:116-124`, `WHERE leadId=? AND tenantId=? AND deletedAt IS NOT NULL`) with no way to tell
cascade-trashed rows from ones the user soft-deleted independently earlier. `LeadRestoredEvent` therefore un-deletes rows
that were never part of the lead-delete cascade.
**Repro.** User deletes quotation V1 on its own; weeks later trashes the whole Lead (cascades V2, V3); restoring the Lead
brings V1 back too — reversing the earlier explicit deletion. Fully recoverable (re-delete).
**Fix direction.** Capture the id-set cascaded at lead-delete time (or a cascade flag) and restore only those.
→ `QuotationEventListener.java:45-50` documents this as an accepted tradeoff — confirm you still accept it (see below).

---

## Systemic patterns (bug shapes that repeat)

1. **Two write-paths for one piece of state, only one guarded.** Payments: ledger vs `PATCH /payment` vs
   create/update-absolute (**F3**). Booking status: guarded PUT (`applyStatusOnUpdate`) vs unguarded `PATCH /status`
   (**F2**). The guarded path lulls review into thinking the invariant is enforced; the twin path quietly isn't.
2. **Authorization enforced at the edge (controller), not at the core (service).** The AI tools reach ungated services
   (**F1**); the entitlement filter's prefix ordering lets a booking action ride the wrong module rule (**F8**). Any new
   entry path (AI tool, batch job, internal caller) silently inherits *no* gate. Push authority into the service.
3. **External surface reuses an internal DTO and blacklist-strips.** The public quotation payload strips two fields and
   ships the rest (**F5**). Whitelisting the customer-safe fields is the only durable fix.
4. **Money mutated by read-modify-write with no optimistic lock.** `Booking` has no `@Version` (only `Vendor` does), so
   every `paidAmount`/status mutation is a lost-update candidate (**F4**, and it amplifies **F3**).
5. **Soft-delete cascade is one-directional and restore has no parent-live invariant** (**F9**, **F10**).

**Respected clean areas** (traced, no reachable logic bug): cross-tenant isolation across booking/customer/lead/vendor/
quotation/master/report/fleet/reminder (tenant-scoped finders + the `@Transactional`→`tenantFilter` mechanism hold up);
the traveler portal (object-level `customerId` ownership everywhere, PII bytes only via the ownership-checked download,
whitelist portal DTOs, enumeration-safe OTP); the AI worker-thread security-context hand-off (`submit`→`process`); the
staff/portal realm cryptographic separation; and all four per-tenant schedulers (context set/cleared per iteration).
The AI surface's *only* defect is F1's missing authority gate — isolation there is intact.

---

## Needs my call (looks wrong, depends on business intent I don't have)

- **F6 — Should CANCELLED/REFUNDED bookings count toward revenue, receivables, profit, and tax on the dashboard?**
  If "no" (the intuitive answer for `totalPending`), F6 is a real P2. If the dashboard is meant to show gross booked
  volume, it's by-design.
- **F10 — Is co-restoring independently-deleted quotations with their lead acceptable?** The authors documented it as an
  intended tradeoff; I'm flagging it because it silently reverses an explicit user deletion.
- **Impersonation `end()` is audit-only, not revocation.** `end()` writes an `IMPERSONATION_END` row but does **not**
  invalidate the token — it stays valid until its ~30-min TTL (early kill relies on bumping the target `User.tokenVersion`).
  Acceptable for a time-boxed god-mode session, or do you want `end()` to hard-revoke?
- **Portal same-contact-across-tenants login.** `resolveCustomer` uses `findFirstBy…OrderByIdAsc` cross-tenant, so if the
  same email/phone exists as a customer in two tenants, OTP login always lands on the lowest-id tenant's customer. OTP is
  still delivered only to the real mailbox owner and everything downstream is scoped to the resolved customer, so it's an
  availability quirk, not a data-boundary crossing — but confirm it's acceptable.

---

## Coverage & what's NOT yet audited (proposed Phase 2)

This phase deliberately front-loaded the crown jewels. **Not yet deeply traced:** the master-data CRUD surface
(hotel/sightseeing/airline/cruise/addon/geography beyond isolation), the fleet module internals (fuel/maintenance/
document scheduler math), reports beyond tenant-scoping (aggregation correctness, date-bucketing, CSV/PDF), the lead
pipeline stage transitions & itinerary `@ElementCollection`, customer dedup/merge, notification templating/delivery
edge cases, `bookingreminder`/`reminder` scheduling math, settings/company/activity, and the heavily-changed
`DevDataSeeder` (dev-only, low prod risk).

**Hard stop.** No code was changed. Tell me: (a) which findings you want me to write fix plans for, (b) your calls on the
four **Needs my call** items, and (c) whether to proceed to Phase 2.