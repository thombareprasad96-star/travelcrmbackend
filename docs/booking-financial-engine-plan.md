# Booking Financial Engine — Profit / Revenue / Refund / Internal Costs

**Status:** Phase 1 (investigation) complete. Phase 2 (design) not started — awaiting owner review of this document.
**Date:** 2026-08-01
**Scope:** Req #3 (Booking Profit), Req #4 (Agency Revenue), Req #5 (Refund), Req #7 (Internal Cost Items). Req #6 (WIN Rate) is out of scope — see §9.

---

# PHASE 1 — INVESTIGATION FINDINGS

## 0. Executive summary — the five things that change the shape of this build

1. **The refund bug is CONFIRMED**, at exactly one backend site, and it is worse than the spec describes: partial refunds contribute **zero**. There are also **two more disagreeing refund figures** in the backend and **three more** in the frontend. `DashboardAnalyticsService.java:91-93`.
2. **Almost every term the spec asks for already exists, verbatim**, in the cancellation engine — `chargeBase`, `gstOnCharge`, `tcsRetained`, `totalRetained`, `refundDue`, `sunkVendorCost`, `vendorRecoverable`, and even `revisedNetProfit`. This is not a greenfield build; it is mostly a **wiring** job. See §1 and §5.
3. **Req #7's `totalInternalCosts` collides head-on with the codebase's single most emphatically documented invariant.** Three separate places (entity javadoc, DTO javadoc, migration comment) state that per-booking cost ledgers must NOT roll into `Booking.vendorCost` / `Booking.netProfit`. There are already **four unreconciled cost surfaces**. See §3 — this is the biggest open decision.
4. **`customerAmount` is GST-exclusive by contract but not by guarantee.** Every Java consumer treats it as pre-tax; the only live write path seeds it from a **tax-inclusive quotation `grandTotal`**. Verdict: PARTIALLY_CORRECT. See §4.
5. **TCS law changed on 1 Apr 2026.** The codebase carries 5%/20% at a ₹7L threshold; current law is **flat 2%, no threshold**, under a renamed section. Also, the booking engine charges TCS at 5% on **100% of bookings including domestic ones**, which is a live compliance issue independent of this build. See §6.

Two things worth saying plainly before the detail:

- **The stated convention "Flyway for all schema migrations" does not hold today.** Flyway is on the classpath but **disabled on every profile and cannot be enabled** — `FLYWAY_ENABLED=true` fails to boot on a documented `flyway` ↔ `entityManagerFactory` circular `depends-on`. `ddl-auto=validate` everywhere, so Hibernate creates nothing, and DDL is applied by hand via `psql`. Any new table needs a decision, not a default. See §7.1.
- One requested investigation lane (an adversarial re-check of the BookingExpense overlap) **did not complete** — it hit a session limit. §3's verdict therefore rests on a single thorough investigation pass plus my own reading, not on an independent second opinion. It is flagged as such.

---

## 1. Terminology reconciliation (spec ↔ codebase)

The spec's Req #3/#4/#7 "retained cancellation amount" and Req #5's `totalRetained` **do already resolve to the same number** — the cancellation engine computes exactly `totalRetained = chargeBase + gstOnCharge + tcsRetained` (`CancellationCalculator.java:114`). No reconciliation defect. But the mapping is not one-to-one across the quote/record boundary:

| Spec term | Exists? | On `CancellationQuote` (DTO) | On `BookingCancellation` (entity/column) |
|---|---|---|---|
| `chargeBase` | ✅ | `chargeBase` (`:52`) | **`finalChargeBase`** / `final_charge_base` (`:119-120`) ⚠️ renamed |
| — | ✅ | `systemComputedChargeBase` | `systemComputedChargeBase` (pre-override figure) |
| `gstOnCharge` | ✅ | `gstOnCharge` | `gst_on_charge` |
| `tcsRetained` | ✅ | `tcsRetained` | `tcs_retained` |
| `totalRetained` | ✅ | `totalRetained` (`:62`) | `total_retained` (`:139`) |
| `refundDue` | ✅ | `refundDue` (`:66-67`, **signed**) | `refund_due` (`:147-148`) |
| `remainingRefundable` | ✅ | computed in refund service | not stored — derived |
| `refundedAmount` | ✅ | — | **`Booking.refundedAmount`** (`Booking.java:148-150`) |
| `sunkVendorCost` | ✅ | `sunkVendorCost` | `sunk_vendor_cost` |
| `vendorRecoverable` | ✅ | `vendorRecoverable` (`:77`) | `vendor_recoverable` (`:166-167`) |
| `cancelledProfit` | ≈ | **`revisedNetProfit`** (`:78`) | `revised_net_profit` (`:169`) |
| `retainedCancellationAmount` | ❌ | — | — (use `totalRetained`) |
| `retainedAmount` | ❌ **NOT FOUND** | — | — |
| `totalInternalCosts` | ❌ **NOT FOUND** | — | — |
| `sunkInternalCosts` | ❌ **NOT FOUND** | — | — |
| `agencyRevenue` | ❌ **NOT FOUND** | — | — |

**Naming conflicts to resolve (do not silently rename):**

- **`chargeBase` vs `finalChargeBase`.** The split is deliberate — `systemComputedChargeBase`/`finalChargeBase` encode override provenance. Unifying means a Flyway column rename on `booking_cancellations` **and** its Envers twin `booking_cancellations_aud`. Recommendation: **keep the split.**
- **`cancelledProfit` vs `revisedNetProfit`.** The spec's formula is `retainedCancellationAmount − sunkVendorCost − sunkInternalCosts`. The existing `revisedNetProfit` is `chargeBase − sunkVendorCost` — i.e. it uses the **pre-tax charge base, not `totalRetained`**, and has no internal-cost term. That is arguably *more* correct than the spec (see §6.5: GST/TCS are never profit), but the two formulas differ and one must win. **Open question OQ-1.**
- **The FE already renders these names** (`CancelBookingModal.jsx:213-216` labels `chargeBase` "Cancellation charge" and `totalRetained` "Total retained"), so any rename is a two-repo change.

---

## 2. Current state map

### 2.1 `Booking` financial model — `Booking.java:118-198`

Eight money columns, all `numeric(12,2) NOT NULL`, defaulted `BigDecimal.ZERO`:

| Field | Owner | How it is set |
|---|---|---|
| `customerAmount` | **client** | create / update / conversion DTO |
| `vendorCost` | **client** | create / update / conversion DTO |
| `gst` | server | `customerAmount × 0.05` |
| `tcs` | server | `customerAmount × 0.05` |
| `totalPayable` | server | `customerAmount + gst + tcs` |
| `netProfit` | server | `customerAmount − vendorCost` |
| `paidAmount` | payment ledger | 5 write sites |
| `refundedAmount` | refund flow | cancel-reset + refund accrual |
| `pendingAmount` | — | `@Transient`, `totalPayable − paidAmount` |

**The whole derived-money engine is nine lines** — `BookingServiceImpl.recomputeTotals()`, verified first-hand:

```java
// BookingServiceImpl.java:1092-1104
private void recomputeTotals(Booking booking, BigDecimal customerAmount, BigDecimal vendorCost) {
    BigDecimal gst          = customerAmount.multiply(gstRate).setScale(2, RoundingMode.HALF_UP);
    BigDecimal tcs          = customerAmount.multiply(tcsRate).setScale(2, RoundingMode.HALF_UP);
    BigDecimal totalPayable = customerAmount.add(gst).add(tcs);
    BigDecimal netProfit    = customerAmount.subtract(vendorCost);
    booking.setGst(gst); booking.setTcs(tcs);
    booking.setTotalPayable(totalPayable); booking.setNetProfit(netProfit);
    booking.setPaymentStatus(derivePaymentStatus(booking.getPaidAmount(), totalPayable, booking.getPaymentStatus()));
}
```

Notes that matter for the design:
- `netProfit` is **STORED and service-written**, reached from only two call sites: `:1170` (create/convert) and `:452` (update, only when `customerAmount` or `vendorCost` is present in the body).
- **`gst` and `tcs` are explicitly `setScale(2, HALF_UP)`; `totalPayable` and `netProfit` are NOT.** The `numeric(12,2)` column is the only rounding backstop.
- **`BookingMapper.java:41` and `:84` claim `netProfit` is a "GENERATED column, DB owns this". That is false** — `V1__baseline_schema.sql:44` declares a plain `net_profit numeric(12,2) not null`. Stale comment; correct it during this work or it will mislead the implementer.
- Two other places write a *different* profit: `DevDataSeeder.java:522` (`amount × 0.3`) and `CancellationCalculator.java:123` (`chargeBase − sunkVendorCost`, written only to the cancellation record).

### 2.2 Cancellation engine — already complete

`CancellationCalculator` is a pure, side-effect-free function: `(booking, policy, cancelDate, overrideChargeBase, vendorRecoverable) → CancellationQuote` (26 fields).

- **Band selection** is floor-anchored: `effectiveDays = max(0, DAYS.between(cancelDate, travelDate))`, pick the greatest `minDaysBeforeDeparture ≤ effectiveDays`. A validator guarantees every policy has a `minDays == 0` band, so a same-day cancel or a past-travel no-show always lands somewhere. Default seeded policy: 30d→10%, 15d→25%, 7d→50%, 0d→100%.
- **Charge:** `PERCENT` → `base × pct / 100` (scale 2 HALF_UP); `FLAT` → raw value. Both clamped to `[0, customerAmount]`. **`base = booking.customerAmount`** (pre-tax), not `totalPayable`.
- **Retained tax is proportional to STORED amounts, deliberately rate-immune:** `gstOnCharge = booking.gst × chargeBase / base` (gated on `policy.gstOnChargeApplicable`, default **true**); `tcsRetained = booking.tcs × chargeBase / base` (gated on `!policy.tcsRefundable`, and `tcsRefundable` defaults **true** ⇒ **TCS is refunded to the customer today**).
- **Settlement is signed, never clamped:** `refundDue = paidAmount − totalRetained`; negative ⇒ `customerOwes`.
- **Anti-retroactivity:** the policy version is pinned on `Booking` at create/convert; versions are immutable (publish = new version + deactivate prior); legacy null pins resolve the company default *as-of bookingDate* and are written back at cancel time.
- **A booking can be cancelled exactly once** — `UNIQUE(booking_id)` on `booking_cancellations`. `CancelScope.PARTIAL` exists in the enum and DDL check but is **explicitly unimplemented and never set**.
- **On cancel, the ONLY `Booking` mutations are `status = CANCELLED` and `refundedAmount = 0`.** `netProfit` and `customerAmount` are untouched — the booking keeps its full pre-cancel margin forever.

### 2.3 Refund flow — an auditable ledger already exists

`POST /api/bookings/{publicId}/cancellation/refund`, gated `BOOKING_REFUND`, one `@Transactional` unit:

1. Load booking → require an existing `BookingCancellation` → refuse `customerOwes` / `refundDue ≤ 0`.
2. `remaining = refundDue − refundedAmount`; refuse ≤ 0 (409).
3. Amount normalised `setScale(2, HALF_UP)`, must be > 0, hard-capped at `remaining`.
4. **Insert a real ledger row** — `BookingPayment` with `entryType = REFUND`, `paymentType = "Refund"`, method/reference/date/notes/`idempotencyKey`.
5. Accrue `booking.refundedAmount`; mirror `record.refundStatus` to `PAID`/`PARTIALLY_PAID`; on `totalRefunded >= refundDue` flip `BookingStatus.REFUNDED` **and** `PaymentStatus.REFUNDED`.
6. `commissionService.syncForBooking(booking)` — sub-agent commission reversal.
7. Mint a numbered `REFUND_VOUCHER` `BookingDocument` with a frozen JSON model snapshot.

**Spec Req #5 vs reality — matches, with one important nuance:**

| Spec | Reality |
|---|---|
| `totalRetained = chargeBase + gstOnCharge + tcsRetained` | ✅ exact (`CancellationCalculator.java:114`) |
| `refundDue = paidAmount − totalRetained` | ✅ exact — but **frozen at cancel time**, not recomputed per refund. `paidAmount` is snapshotted as `paidAtCancel`. |
| `remainingRefundable = refundDue − refundedAmount` | ✅ exact (write path unclamped; read path additionally `.max(ZERO)` and forced to 0 when `customerOwes`) |
| status → `REFUNDED` when `refundedAmount >= refundDue` | ✅ exact, and **no other endpoint can set REFUNDED** (`PATCH /status` 409s it) |
| `dashboardRefund = SUM(refundedAmount)` | ❌ **bug — see §3.1** |

**Three-layer idempotency:** client `idempotencyKey` lookup → partial unique index `uq_bkpay_idem (tenant_id, idempotency_key)` → the economic cap at `remaining`. Concurrency is the `@Version` optimistic lock on `Booking` (loser gets 409), plus a `SELECT … FOR UPDATE` on the document-sequence row inside the same transaction.

**Refunding touches neither `paidAmount` nor `netProfit`** — deliberate, so `pendingAmount` / `paymentStatus` / receipt aggregates are unaffected.

### 2.4 Every place profit / revenue / refund is read

**Profit — 6 read sites, all read the stored `Booking.netProfit`.** Nothing computes `customerAmount − vendorCost` inline on the read side. `BookingRepository.java:101` (`sumNetProfit`, excludes CANCELLED/REFUNDED), `BookingServiceImpl.java:913, 944-946`, `BookingRevenueService.java:73, 153, 168`, `DashboardAnalyticsService.java:90, 254`.
> ⚠️ `BookingRevenueService` does **not** exclude CANCELLED/REFUNDED, so it sums the **stale pre-cancel margin** of cancelled bookings.

**Revenue — 10 sites, 3 different definitions, inconsistent status filters:**

| # | Site | Base | Excludes CANCELLED/REFUNDED? |
|---|---|---|---|
| 1 | `DashboardAnalyticsService:89` | `customerAmount` | ✅ |
| 2 | `BookingRevenueService:72` | `customerAmount` | ❌ |
| 3 | `ReportService:61-66` | `customerAmount` | ❌ |
| 4 | `BookingRepository:86` (`/stats`) | `customerAmount` | ✅ (all-time, no date filter) |
| 5 | `BookingServiceImpl:935-942` (page summary) | `customerAmount` | ✅ |
| 6 | `BookingRepository:150-153` (customer stats) | `customerAmount` | ❌ |
| 7 | `CalendarBookingQueryRepository:33-43` | **`totalPayable`** ⚠️ | ✅ |
| 8/9 | travel-date trends / intl-domestic | `customer_amount` | ❌ |
| 10 | `AccountingReportService:61-65` | **invoice `taxableValue`** | n/a (ISSUED invoices only) |

**Nothing anywhere adds retained cancellation charges to revenue.** `BookingCancellation.totalRetained` is stored but **never aggregated by any query** — `BookingCancellationRepository` has no SUM. **Req #4 is 0% implemented.**

Also: net margin uses `customerAmount` as denominator on the dashboard and `totalPayable` on the revenue report — same booking, two different percentages.

### 2.5 Frontend consumption

- `netProfit` is read as a server field almost everywhere, **but two hot paths use a falsy `||` fallback**: `BookingDetails.jsx:1142` and `BookingRevenueAnalysis.jsx:22` — `Number(b.netProfit) || (customerAmount - vendorCost)`. **A server `netProfit` of exactly 0 is silently replaced by the client's own subtraction.** Any new profit definition that can legitimately be zero will be overwritten in the UI.
- **Three FE labels assert a formula the server does not implement**: `BookingDetails.jsx:1856` "Customer − Vendor − GST", `BookingServices.jsx:303` "Net Profit Formula: Customer Amount - GST - Vendor Costs", and the AllBookings "Net Revenue" tile (actually Σ `paidAmount`).
- `BookingServices.jsx:401` computes a booking-level profit from **Σ service-line `vendorCost`**, a different base from `booking.vendorCost`.
- **`Booking.refundedAmount` is not on `BookingResponseDTO`** and has **zero occurrences in the entire frontend**. Every FE refund figure is fabricated.
- The expense UI is **write-only**: `BookingExpenseModal` posts via `addExpenses`, but `getExpenses`, `getExpenseSummary`, `updateExpense`, `deleteExpense` have **zero callers**. Nothing ever renders the expense summary.
- `CancelBookingModal` / `RefundBookingModal` are the reference implementations — they render backend DTO fields verbatim and do no arithmetic (one client-side cap check with a float epsilon).

---

## 3. The refund bug — CONFIRMED, and worse than specified

### 3.1 The defect

```java
// report/dashboard/service/DashboardAnalyticsService.java:89-93   ← verified first-hand
BigDecimal revenue = sum(activeBookings, Booking::getCustomerAmount);
BigDecimal profit  = sum(activeBookings, Booking::getNetProfit);
BigDecimal refunds = sum(
        bookings.stream().filter(booking -> booking.getStatus() == BookingStatus.REFUNDED).toList(),
        Booking::getTotalPayable);
```

Emitted at `:103` as `.refunds(refunds)` on `GET /api/dashboard/analytics` (`CRM_FULL`), rendered as the red "Refunds" tile (`Dashboard.jsx:807`).

**Two independent errors, in opposite directions:**

1. **Partial refunds are invisible.** A booking only reaches `REFUNDED` when `refundedAmount >= refundDue` (`BookingRefundServiceImpl.java:117-124`). A partially-refunded booking stays `CANCELLED`, so **real money that left the bank contributes ₹0**.
2. **Full refunds are overstated.** `totalPayable` is the gross invoice (`customerAmount + gst + tcs`); the money actually disbursed is `refundDue = paidAmount − totalRetained`. The tile inflates by the retained charge **plus** any unpaid balance **plus** the tax component.

### 3.2 It is not the only refund figure — there are five, and they disagree

| # | Site | Formula | Correct? |
|---|---|---|---|
| 1 | `DashboardAnalyticsService:91-93` | Σ `totalPayable` where status = REFUNDED | ❌ **the bug** |
| 2 | `BookingRepository:95-99` `sumTotalRefund()` | Σ `refundedAmount`, status-independent | ✅ |
| 3 | `BookingRevenueService:95` | Σ `refundedAmount` | ✅ |
| 4 | FE `Dashboard.jsx:320-321` | Σ `totalPayable` where status = REFUNDED | ❌ mirrors the bug |
| 5 | FE `Allbookings.jsx:554` | Σ `paidAmount` where payStatus = "Refunded" | ❌ third formula |
| 6 | FE `BookingRevenueAnalysis.jsx:429` | hardcoded `0` | ❌ |
| 7 | FE `BookingPayments.jsx:113-116` | Σ ledger rows where `paymentType === 'refund'` | ≈ (ignores `entryType`) |

So `/api/dashboard/analytics.refunds` and `/api/bookings/stats.totalRefundAmount` **already report different numbers** for the same tenant whenever any refund is partial or any cancellation retained a charge. Nothing in `accounting/` computes a refund figure at all.

Two stale comments assert the opposite of the code and should be deleted: `BookingRevenueService.java:32-33` and `RevenueBreakdownDTO.java:10` both claim `refunded` "has no dedicated column and is returned 0" — the code does sum the real column.

---

## 4. GST treatment of `customerAmount` — **PARTIALLY_CORRECT** (the important finding)

**Adversarial verdict: the declared contract is GST-EXCLUSIVE and every backend consumer agrees, but the only live write path seeds it from a tax-INCLUSIVE total.**

**Evidence it is exclusive (4 independent consumers):**
1. `totalPayable = customerAmount + gst + tcs` — exclusive by definition (`BookingServiceImpl.java:1095`).
2. The booking invoice PDF labels it **"Base Amount"**, then adds a GST row and a TCS row, then "Total Payable" (`templates/pdf/invoice.html:198-211`).
3. The statutory GST invoice uses it as the **tax-exclusive taxable value** and multiplies forward by the HSN rate (`InvoiceServiceImpl.java:387-391`). There is **no gross-up / back-calculation path anywhere**, so a gross `customerAmount` can never be decomposed.
4. The cancellation engine calls it **"the pre-tax base"** (`CancellationQuote.java:47`).

**Evidence the stored data may not be:**
- `POST /bookings` has **zero frontend callers**. The **only** way a booking is created from the UI is lead→booking conversion (`ConvertToBookingModal.jsx:243`).
- That path seeds `customerAmount` from the quotation `grandTotal` (`ConvertToBookingModal.jsx:157, 171` → posted verbatim at `:235`).
- `grandTotal` is **tax-inclusive by construction**: `afterDiscount + taxAmount` (`QuotationMapper.java:576-579`), and is shown to the customer as *"Inclusive of all taxes"* (`QuotationWebView.jsx:1045`).

**So the answer hinges on one field — the quotation's Tax %:**

| Quotation Tax % | Stored `customerAmount` | `netProfit = customerAmount − vendorCost` |
|---|---|---|
| **0** (the default — `SummaryPricingTab.jsx:184`, `quotationService.js:72`) | net | ✅ safe |
| **> 0** (`Createquotation.jsx:211` even falls back to 18) | **gross of that tax** | ❌ books the quotation's GST as margin — **and** the backend then adds a further 5% GST + 5% TCS on top |

Mitigation that exists today: the convert modal's live preview visibly re-adds `gst = amount*0.05` and `tcs = amount*0.05` (`ConvertToBookingModal.jsx:200-203`), so an agent pasting a tax-inclusive total *sees* the double tax before submitting. Nothing enforces it, and no DTO message or FE label anywhere states the figure must be pre-tax.

**Recommendation:** do **not** change the `netProfit` formula for this. `customerAmount − vendorCost − totalInternalCosts` is correct **given** a pre-tax `customerAmount`. The fix belongs upstream: make the convert modal subtract the quotation's `taxAmount` before seeding, and label the field "Customer Amount (excl. GST/TCS)". That is a separate, smaller change — but it should land **before or with** this build, otherwise the new engine inherits a base it cannot trust. **OQ-4.**

---

## 5. `vendorRecoverable` — it EXISTS, but only inside the cancellation subsystem

**NOT a new field.** It already exists as:
- a request field — `CancelBookingRequestDTO.java:48-49` (`@DecimalMin("0.0")`),
- an optional query param on the cancellation preview — `BookingCancellationController.java:48`,
- a quote field — `CancellationQuote.java:77`,
- **a frozen column** — `booking_cancellations.vendor_recoverable numeric(12,2)`.

It is clamped to `[0, booking.vendorCost]` before use (`CancellationCalculator.java:64`), and drives `sunkVendorCost = vendorCost − recoverable`.

**NOT FOUND anywhere else:** no `vendorRecoverable` on `Booking`, on `BookingResponseDTO`, on `BookingSummaryDTO`, or on any create/update DTO. A repo-wide case-insensitive grep returns only the cancellation subsystem plus unrelated prose.

**Precision nit worth fixing:** `vendorRecoverable` is stored **unscaled** (clamp only, no `setScale`) while `sunkVendorCost` **is** scaled — so a caller passing a 3-dp value produces a stored `numeric(12,2)` for which `vendorCost = sunkVendorCost + vendorRecoverable` no longer holds to the paisa. Same applies to `baseConsidered` and `paidAmount`, copied raw off the Booking columns.

**Domain expert's verdict on the model itself (see §6.6): a single scalar `vendorRecoverable` per booking is not a realistic model of Indian agency operations.** This is a genuine design fork — **OQ-6**.

---

## 6. Travel-industry / Indian tax research

> Web-researched 31 Jul 2026 with live sources. Items marked ⚠️ **UNVERIFIED** need CA confirmation before they drive code.

### 6.1 TCS on overseas tour packages — **the rate changed on 1 Apr 2026**

| Period | Rate structure |
|---|---|
| **1 Apr 2026 → today** | **flat 2%, NO threshold** (from the first rupee) |
| 1 Apr 2025 – 31 Mar 2026 | 5% up to ₹10L, 20% above |
| 1 Oct 2023 – 31 Mar 2025 | 5% up to ₹7L, 20% above |

- Statutory home moved: **s.206C(1G) of the Income-tax Act 1961 → s.394(1), Table Sl. No. 8 (item D), Income-tax Act 2025**, amended by Finance Act 2026 Clause 73.
- The ₹7L/₹10L figure was **never an exemption** — it was a slab boundary; tax applied from rupee one. The migration is `slab(5,20) → flat(2)`, not "threshold removed".
- **Definition gate unchanged** — CBDT **Circular 10/2023 (30.06.2023)**: a package must bundle **at least two** of {international ticket, hotel, other similar expenditure}. A bare air ticket or hotel-only is **not** an overseas tour programme package and attracts no 1G/394 TCS.
- Trigger point: **debit-or-receipt, whichever is earlier** — so **each instalment triggers TCS on that instalment**, not one lump at final payment.
- Forms renamed 1 Apr 2026: **27EQ → Form 143**, **27D → Form 133**.

**Consequences for this codebase:**
- `application.properties:491-493` carries `threshold=700000 / rate-below=0.05 / rate-above=0.20` — **all three are now wrong**. Externalised, so it is a property change, but the *shape* (slab) is also wrong; `TcsCalculator`'s slab arithmetic is now dead logic.
- `app.booking.tcs-rate=0.05` charges **5% TCS on 100% of bookings including domestic ones**. `Booking` has **no overseas/domestic flag at all**. 206C(1G)/394 does not apply to domestic packages. **This is a live money/compliance issue today, independent of this build — OQ-9.**
- ⚠️ **UNVERIFIED:** whether the TCS base is GST-inclusive for the *tour-package* limb. Circular 17/2020 (GST-inclusive) is written for **206C(1H) sale of goods**, not 1G. Industry practice is inclusive; no direct authority found.
- **Settled the other way:** GST is **not** charged on the TCS component — Corrigendum to CBIC Circular 76/50/2018-GST (07.03.2019), income-tax TCS is *"an interim levy not having the character of tax"*.
- ⚠️ **Primary-source caveat:** `incometaxindia.gov.in` returned HTTP 403 to automated fetch. The flat-2% conclusion rests on unanimous secondary sources quoting the Memorandum (TaxGuru Feb + Apr 2026, ClearTax, TDSMAN Jul 2026, Business Today, Outlook Money). **Confirm once before hardcoding.**

### 6.2 TCS on cancellation — the spec's refund formula does not match compliance practice

**Once TCS is remitted, there is no statutory route for the operator to refund it to the customer.** The credit sits against the buyer's PAN in Form 26AS/AIS; the buyer claims it in their ITR. The trip not happening is irrelevant.

- **The one window:** cancellation **in the same quarter, before the quarterly statement is filed** — the seller reverses in books and refunds the full amount including TCS.
- **Post-filing (the common case):** agencies refund package value minus cancellation charges and **do NOT return the TCS**, telling the customer to claim it in their ITR. Cancellation T&Cs are typically worded to say exactly this.
- CBDT **Circular 2/2011** (refund of excess deducted tax) **covers TDS only and expressly not TCS**.
- ⚠️ **UNVERIFIED and most load-bearing:** whether **Form 26B** / the TRACES cash-refund route is available to a **TCS collector** at all. Sources directly conflict. Circular 2/2011's exclusion cuts against it.
- **Nobody files corrections for cancellations at scale** — the route is manual, DSC-gated and AO-facing.

**⚠️ This directly contradicts the current default.** `CancellationPolicySeeder.java:73-74` seeds `tcsRefundable = true`, and the entity default is `TRUE` — so **the system refunds TCS to the customer by default**, which is the opposite of both the law's mechanics and industry practice. `refundDue = paidAmount − totalRetained` then hands back money the agency has already remitted to the government and cannot recover. **This is the single highest-value finding for the owner — OQ-8.**

The refund model needs to represent *"refunded package value, did NOT refund TCS"* as a **first-class state**. The two amounts do not move together.

### 6.3 TCS is never revenue and never profit

Unambiguous. The agency is a **collection agent**; **Ind AS 115** excludes *"amounts collected on behalf of third parties"* from the transaction price. On collection it is a **liability** (`TCS Payable`), extinguished on challan payment. It never touches the P&L.

Current code is **already correct here** — `netProfit = customerAmount − vendorCost` operates on the pre-tax base and ignores `gst`/`tcs` entirely. Preserve that.

### 6.4 GST for tour operators

- **Notification 11/2017-CT(R), Sl. No. 23, heading 9985**, as amended by 1/2018-CT(R):
  - **9985(i)** tour operator → **5%** (2.5+2.5), **no ITC** except input service in the same line of business; bill must state it is inclusive of accommodation and transportation.
  - **9985(iii)** support services → **18%**, full ITC.
- **There is no formal "opt for 18% with ITC" election inside the tour-operator entry.** The commercial choice is a *structuring* choice — inclusive package (5%, no ITC) vs agency/facilitation fee (18%, full ITC). Advance rulings hold you cannot take entry (i) and claim full ITC.
- **India has NO margin scheme (no EU-style TOMS).** 5% is on the **GROSS** amount charged. 18% applies to the agency's **own commission/fee only**, because third-party cost is not part of that supply. Rule 33 "pure agent" is a fragile valuation *exclusion*, not a margin scheme.
- **Sept 2025 rationalisation (56th Council, effective 22.09.2025):** tour operator **unchanged at 5% without ITC**. But **hotel ≤ ₹7,500/unit/day is now mandatorily 5% WITHOUT ITC** (the 18%-with-ITC option was removed) — a large slice of hotel input cost is now permanently non-creditable. Air: economy 5%, premium/business/first moved 12% → 18%.
- Nothing more recent to Jul 2026; the 57th Council had not convened as of early Jun 2026.
- **Modelling implication:** an Indian agency routinely runs **two tax bases in one booking** — 5% on gross for package legs, 18% on fee/commission for air and ancillary legs. **A single booking-level `gstRate` field is wrong for mixed bookings; tax rate belongs on the service line.** The codebase's flat `app.booking.gst-rate=0.05` cannot express this.

### 6.5 GST on the retained cancellation charge — **yes, payable**

**Circular No. 178/10/2022-GST dated 03.08.2022**, paras 11–11.5:
- Para 11.3 — the cancellation charge is **"assessed as the principal supply"**: taxed at the **same rate as the underlying service**.
- Para 11.4 — expressly names **tour operator** and hotel accommodation: amounts forfeited where the customer fails to avail the service are taxable at the rate applicable to that contract.
- Para 11.5 — the non-taxable carve-out (land/immovable property earnest money) **does not extend to travel**.
- **Rule 35:** a retained amount not expressed as tax-exclusive is **deemed inclusive** — `GST = retained × rate / (100 + rate)`. ₹10,000 retained at 5% → GST ₹476.19, net **₹9,523.81**.

**The codebase's `gstOnChargeApplicable = true` default is correct and well-founded.** But the arithmetic differs: the code computes `gstOnCharge` as a **proportion of the stored `Booking.gst`** (`booking.gst × chargeBase / base`), which is a *tax-exclusive add-on* model. Circular 178 + Rule 35 imply the retained amount is normally **tax-inclusive** and should be *extracted*. With the current flat 5% these give similar-but-not-identical numbers; they diverge as soon as rates are per-line. **OQ-10.**

**Credit note deadline — build for this:** s.34(2) CGST — a credit note reducing output tax must be declared by **30 November following the end of the FY of the ORIGINAL SUPPLY, or the date of filing GSTR-9, whichever is EARLIER**. The clock runs from the FY of the *original supply*, not the cancellation. A tour invoiced 15 Mar 2026 that cancels in Oct 2026 has until **30 Nov 2026** — barely a month.

**Finance Act 2025 s.126, effective 01.10.2025:** no reduction in output tax if (i) a registered recipient has not reversed the ITC, or (ii) **the incidence of tax has been passed on**. For a **B2C leisure customer this means you must actually refund the GST component** — keep the customer's GST money and you get no reduction and eat the GST as a cost.

⚠️ The codebase's cancellation "credit note" is **not** a GST credit note — it carries no GSTIN, HSN, place-of-supply or CGST/SGST/IGST split, and never appears in the GST summary. `TaxInvoice.creditNoteNumber` is declared and read but **never written anywhere**, and `InvoiceServiceImpl.cancel()` is a pure status flip that reverses no tax. **OQ-11.**

### 6.6 Domain-expert opinion — vendor recoverability (no citation; judgement)

Recoverability is **wildly heterogeneous per service line**:

| Vendor line | Typical recoverability |
|---|---|
| Air — published refundable fare | Taxes/UDF/PSF back minus ~₹3–6k/pax fee; refund lands in **30–90 days** |
| Air — non-refundable / series / bulk | **~0%** once ticketed |
| Hotel — inside free-cancel window | ~100% |
| Hotel — outside window | Sliding; peak season / villas / advance-purchase → **0%** |
| DMC advance | 10–25% deposit, usually non-refundable, **often convertible to a 6–12 month credit note** |
| Visa | **Sunk the moment the file is lodged** — embassy + VFS fees never refunded |
| Cruise | Deposit non-refundable; inside 30 days usually 100% loss |

**"A single scalar `vendorRecoverable` per booking is not a realistic model. Do not build it."** Each line has a different vendor, currency, deadline, slab and settlement lag. One number forces ops to guess an average, and the guess is always wrong on exactly the expensive mixed bookings that matter.

Recommended shape — put recoverability on `booking_service_item`: `cancellationDeadline`, `refundBasis` (`FULLY_REFUNDABLE|SLAB|NON_REFUNDABLE`), `estimatedRecoverable`, `actualRecovered`, `recoveryStatus`, and critically **`recoveryMode` (`CASH|VENDOR_CREDIT_NOTE|WAIVED`)** — a DMC credit note is **not cash and must not inflate cancellation profit**. Booking-level `vendorRecoverable` becomes a derived cache. **Separate ESTIMATED from ACTUAL and never collapse them** — the gap is the agency's real cancellation exposure and deserves its own ageing report.

**Note this is a material scope expansion beyond the spec** — flagged, not assumed. **OQ-6.**

### 6.7 Domain-expert opinion — is retained charge revenue, and where does cancelled profit sit?

- **It is revenue, not other income** — same customer contract, ordinary course of business (Ind AS 115 variable/breakage consideration). ✅ Req #4's premise is sound.
- **Book it NET of GST.** Ind AS 115 + ICAI Guidance Note on Schedule III: revenue is presented net of GST *irrespective of whether the price is quoted inclusive or exclusive*. **⚠️ This means Req #4's `totalRetainedCancellationCharges` should use the pre-tax `chargeBase`, NOT `totalRetained`** (which bundles `gstOnCharge` + `tcsRetained`). Using `totalRetained` books government tax as agency revenue.
- **Give it its own GL line — "Cancellation & Retention Income" — never merged into package sales.** It is revenue with **no service delivery behind it**; merging corrupts gross margin %, average realisation per pax and conversion economics.
- **Cancellation profit belongs in the same gross-profit TOTAL but not the same LINE:**
  ```
  Gross Profit
    ├─ GP from delivered travel   ₹ X
    ├─ GP from cancellations      ₹ Y   ← retained (net of GST) − sunk vendor cost
    └─ Total operating GP         ₹ X + Y
  ```
- Two KPIs the owner will actually use: **cancellation margin %** (is the slab priced right?) and **cancellation loss rate** (count/value of cancellations where GP is **negative** — bookings where the retained charge did not cover sunk cost). Almost no agency tracks the second.
- **Governance warning:** never let a cancelled booking's retained charge feed sales incentive at the same rate as a delivered booking, or you have built an incentive to sell bookings that cancel. Relevant here because `SubAgentCommissionService.syncForBooking` already runs on the refund path.

---

## 7. The `totalInternalCosts` problem — Req #7 vs. a documented invariant

### 7.1 What already exists: FOUR unreconciled cost surfaces

| # | Surface | What it is | Feeds `Booking.netProfit`? |
|---|---|---|---|
| 1 | `Booking.vendorCost` | the number the agent **typed** | ✅ (it *is* the formula) |
| 2 | `BookingServiceItem.vendorCost` | per-line operational plan | ❌ documented as deliberate |
| 3 | `BookingExpense.amount` | per-booking **cash book** (11 categories incl. *Commission*, *Office Expense*) | ❌ documented as deliberate |
| 4 | `VendorBill.grossAmount` (accounting/tds) | formal payable w/ TDS + GST, optional `bookingId` | ❌ — and it is the one the **P&L** reads |

`BookingExpense` is invisible to the entire accounting module (0 references), so **surfaces 3 and 4 already double-count conceptually today** if a user records the same cost in both. Nothing warns or reconciles.

### 7.2 Req #7 maps almost exactly onto the existing `BookingExpense`

| Req #7 asks for | `BookingExpense` has |
|---|---|
| `title` (free text) | `description` varchar(300) NOT NULL |
| `amount` | `amount` numeric(12,2) NOT NULL |
| `notes` | `notes` TEXT |
| `costDate` | `expenseDate` date NOT NULL |
| soft delete | ✅ `BaseEntity.softDelete()` |
| **restore** | ❌ **the only genuine gap** (~20 lines: a finder without `AndDeletedAtIsNull`, a service method calling the existing `BaseEntity.restore()`, a `POST /{publicId}/restore` on `BOOKING_UPDATE`) |
| no enum / generic rows | ✅ `category` is deliberately free text, *"so the UI's list can grow without a backend change, a migration and a CHECK-constraint refresh"* |

`BookingExpense` also has a small blast radius — the repository is injected **nowhere else** in `src/main`; the whole slice is 10 files.

### 7.3 The collision

Req #7 says *"Recalculate booking profit on every one of: add, update, delete, restore"* and `netProfit = customerAmount − vendorCost − totalInternalCosts`.

The codebase states the opposite in **three** places:

> `BookingExpense.java:24-29` — *"**It does NOT mutate the booking's totals.** `Booking.vendorCost` and `Booking.netProfit` stay exactly the figures entered on the booking itself … Rolling expenses into vendorCost would **double-count against the cost the user already typed there** and would **silently rewrite netProfit, an audited financial figure, behind their back**."*

> `V2__lead_code.sql:227-229` — *"The parent booking's own figures are untouched: no trigger, no backfill into `bookings.vendor_cost` or `bookings.net_profit`."*

> `BookingExpenseSummaryResponse.java:12-18` — same statement, plus *"Every field is computed from the live rows on each call — nothing is cached or stored."*

**The double-count is concrete.** Agent types `vendorCost = ₹80,000` on a ₹1,00,000 booking (`netProfit = ₹20,000`). Ops later itemises the same ₹80,000 in the expense cash book (hotel ₹50k + flight ₹30k). Under Req #7 as literally written, `netProfit = 1,00,000 − 80,000 − 80,000 = −₹60,000`. A profitable booking reports a loss.

### 7.4 My reading

**Req #7's "internal cost items" as *described* (staff commission, marketing, gateway fee, courier — overhead, not supplier cost) is a genuinely different concept from vendor cost, and is legitimate to net off profit.** The problem is not the concept; it is that `BookingExpense` **already mixes both** — its category list ships `Hotel`/`Flight`/`Transport`/`Visa` (vendor) alongside `Commission`/`Office Expense` (internal). So there is no safe way to sum "the expense table" into profit.

Three viable paths, in my order of preference:

- **(A) Add a `costType` discriminator (`VENDOR | INTERNAL`) to `BookingExpense`, and sum only `INTERNAL` into `totalInternalCosts`.** Reuses the whole slice, keeps one cash book, adds restore, and the double-count becomes structurally impossible. Cost: a backfill decision for existing rows, and — per the recorded gotcha — a **new `@Enumerated` value needs the inline `*_check` constraint refreshed**, so prefer a nullable free-text column or handle the CHECK explicitly in the migration.
- **(B) A separate `booking_internal_costs` table.** Cleanest conceptually; creates a **fifth** cost surface and gives users no rule for which table to use.
- **(C) Don't touch `netProfit`; add a second, clearly-labelled figure** (`netProfitAfterInternalCosts`, or serve it on the expenses-summary endpoint). Zero regression, preserves the audited figure, and matches the existing "rollup served separately" design — but does not satisfy Req #7 as written.

**⚠️ Caveat on confidence:** the independent adversarial verification of this section **did not run** (session limit). This rests on one thorough investigation pass plus my own reading of `BookingExpense.java`, the migration comment and the FE category list. Treat §7 as well-evidenced but not double-checked.

**OQ-2 and OQ-3** below are the decisions this needs.

---

## 8. Trigger points for recalculation

**There is NO recalculation service today — NOT FOUND.** (`grep -i recalc|recompute` returns only the one private method plus an unrelated fleet odometer helper.)

| # | Event | Where it happens now | Recomputes profit today? |
|---|---|---|---|
| 1 | Manual create | `BookingServiceImpl.java:171-172` → `:1170` | ✅ |
| 2 | Lead→Booking convert | `BookingServiceImpl.java:297` → `:1170` (`LeadConversionController:36-42`) | ✅ |
| 3 | Booking update (PUT) | `BookingServiceImpl.java:436, 451-452` | ✅ **only if** `customerAmount` or `vendorCost` is non-null in the body |
| 4 | Vendor assign / service-item CRUD | `BookingServiceItemServiceImpl.java:63, 88, 120-126` | ❌ never touches Booking |
| 5 | Payment add / delete / PATCH | `BookingPaymentServiceImpl:93, 134`; `BookingServiceImpl:807` | ❌ (calls `commissionService.syncForBooking`) |
| 6 | Cancellation | `BookingServiceImpl.java:616-621` | ❌ — freezes `revisedNetProfit` on the cancellation record only |
| 7 | Refund disbursement | `BookingRefundServiceImpl.java:116-130` | ❌ |
| 8 | Expense add / update / delete | `BookingExpenseServiceImpl.java:110, 147, 160-161` | ❌ — no `bookingRepository.save` in the file at all |
| 8b | Expense **restore** | **does not exist** | n/a |
| 9 | Booking soft-delete | `BookingServiceImpl.java:829-843` | ❌ |
| 9b | Trash **restore** of a booking | `TrashServiceImpl.java:107-114` | ❌ — cascade hook exists only for `Lead` |

**There is no separate quotation→booking convert path** — the lead-centric one is the only entry point.

**Mechanism precedent — and a landmine:**
- There is **not a single `@TransactionalEventListener`** in the repository. The established cross-module idiom is a plain synchronous `@EventListener` that **joins** the publisher's transaction (`QuotationEventListener.java:32-34`).
- **But `NotifyEventListener` sets and then CLEARS `TenantContext` in a `finally`, on the publisher's own thread**, and `TenantFilterAspect` **fails OPEN** on a null tenant. `LeadServiceImpl.java:138-145` documents this as load-bearing: *"anything after a publish runs with a null context … Publish last, and keep it last."* **A recalc listener published in the wrong order would run unfiltered across tenants.**
- **The safe precedent is the explicit idempotent call**: `SubAgentCommissionService.syncForBooking(booking)` — *"recomputes the target … and records only the delta … safe to call after every payment/refund without double-counting"* — already wired at **six** sites (`BookingServiceImpl:185, 309, 466, 818`; `BookingPaymentServiceImpl:99, 140`; `BookingRefundServiceImpl:130`). **This is the model to copy. OQ-7.**

**Two guard gaps a recalc would inherit:**
- `assertEditableBooking` (terminal statuses `COMPLETED|CANCELLED|REFUNDED` are locked) is called by `update()` but **not** by `BookingServiceItemServiceImpl` or `BookingExpenseServiceImpl`. An expense added to a COMPLETED booking would today mutate a locked booking's audited profit.
- `BookingRefundServiceImpl:54` and `BookingCancellationServiceImpl:40, 51` call `bookingRepository.findByPublicIdAndDeletedAtIsNull` **directly, without `subAgentScope.assertVisible`**, unlike every other by-id booking path. A `SUB_AGENT` holding `BOOKING_REFUND` could refund another sub-agent's booking in the same tenant. Tenant isolation still holds; this is intra-tenant only. **Pre-existing, worth fixing alongside.**

**Envers:** `Booking` is `@Audited` and `bookings_aud` already carries every money column. A recalc that changes a value **will** mint a revision. Envers only writes when the row is genuinely dirty, so an **idempotent no-op-on-equal** recalc costs nothing — but a recalc firing on every expense row would otherwise fill the audit trail with machine-generated revisions that dilute the human financial history.

---

## 9. Req #6 — WIN Rate

**Out of scope, not implemented, pending a formula from Prasad.** Noting for completeness: a `winRate` field already exists on the dashboard response, computed as `percent(wins, activeBookings.size(), 2)` where `wins` comes from an `isWonBooking` predicate (`DashboardAnalyticsService.java:85-87, 104`). Whatever formula lands should reconcile with that, or explicitly replace it.

---

## 10. Codebase conventions the design must honour

- **Migrations — see §0.** Flyway **disabled and unbootable**; `ddl-auto=validate` everywhere; `db/indexes.sql` (691 lines) still auto-runs at boot via `spring.sql.init.mode=always`. Two migrations exist (`V1`, `V2`); the next is `V3`. `V2` is documented as **applied locally only, never stamped on the deployment DB**. `ProductionConfigValidator:180-192` hard-blocks an inconsistent cutover at boot. **Splitting `@EnableJpaAuditing`/`@EnableTransactionManagement` was already tried as a fix for the cycle and does not work — `JpaConfig.java:14-17` says do not re-attempt.**
- **Soft delete:** `deleted_at` / `deleted_by` on `BaseEntity` + `softDelete(user)` / `restore()`. **No `@SQLRestriction`, no `@Where` anywhere.** Visibility is a `softDeleteFilter` that **only master-data entities opt into**; core CRM entities use explicit `...DeletedAtIsNull` finders. The real restore impl is `TrashServiceImpl.restore()`, which disables the filter first, checks the parent isn't trashed, calls `entity.restore()` on the **managed** entity and flushes. `BookingExpense` is in **neither** camp and is **not** in `TrashableType`.
- **Money:** `numeric(12,2)` / `precision=12, scale=2`, `setScale(2, RoundingMode.HALF_UP)` at every boundary. Intermediates vary by author (GST at scale 4, cancellation at 2; proration names `MONEY_SCALE=2`/`RATE_SCALE=10`). **There is no shared money utility** — 17 classes each declare a private `nz()`/`money()` helper; the convention is copy-the-helper.
- **MapStruct:** `BookingMapper` is `componentModel="spring"`, `nullValuePropertyMappingStrategy=IGNORE`, **`unmappedTargetPolicy=ERROR`**. Rule: *"mapper only maps what the CLIENT sends; everything the SERVER owns is ignored here and set by the service."* `@AfterMapping` is essentially not an idiom (1 occurrence repo-wide). **The accounting module has zero MapStruct mappers.** Two DTO tiers exist on purpose: `toResponse()` carries `netProfit`/`vendorCost`, `toSummary()` deliberately omits them.
- **Envelope:** the **service** builds `PagedApiResponse.of(msg, List<T>, PaginationMeta.from(page, sortBy, sortDir))`; the controller just forwards. Non-paginated uses `ApiResponse.success(msg, data)` / `(msg, data, 201)`. Note booking's `getAll()` **bypasses `PageSupport`** — no size clamp, no stable `id DESC` tiebreaker (customer/vendor do use it).
- **Permissions:** per-booking cost/profit rides on **`BOOKING_READ` / `BOOKING_UPDATE`** — `BookingExpenseController.java:23-38` documents an explicit rejection of `ACCOUNTING_TDS_*` for per-booking cost. Tenant-wide financial aggregates use the legacy coarse **`CRM_FULL`**, which is **not** in the `Permission` enum and cannot be granted per user. There is no `PROFIT_*` / `COST_*` key. `BOOKING_REFUND` and `CANCELLATION_POLICY_MANAGE` are in **no role default** (TENANT_ADMIN only, via resolver bypass).
- **Tests:** 31 classes / 259 `@Test`, plain JUnit 5 + AssertJ + hand-rolled `mock()` in `@BeforeEach` (not `@ExtendWith(MockitoExtension)`), `@Nested` + `@DisplayName`, **no Spring context** for calculators. Zero `@ParameterizedTest`. **No surefire config**, so `mvn test` runs everything — including the PDF render smoke test that writes real files.
- **ArchUnit already guards this area:** `TenantIsolationArchTest` fails the build on any primary-key lookup against a `BaseTenantEntity` repository and on direct `EntityManager`/`Session` lookups. A new un-filtered finder for restore **must** still be `publicId + bookingId` scoped, never `findById`.
- **Tenant-isolation gap to note:** the seven `BookingRepository` money aggregates (`sumTotalRevenue/Collected/Pending/Refund/NetProfit/Gst/Tcs`) carry **no `tenantId` predicate** and rely entirely on the Hibernate `@Filter`, which fails **open** when `TenantContext` is null. Every report-owned query is explicitly scoped; these seven are not.

---

## 11. OPEN QUESTIONS — please answer before Phase 2

Ordered by how much they change the design.

**OQ-1 — `cancelledProfit` formula.** Spec: `retainedCancellationAmount − sunkVendorCost − sunkInternalCosts`. Existing `revisedNetProfit`: `chargeBase − sunkVendorCost`. Two differences: (a) the existing one uses the **pre-tax `chargeBase`**, not `totalRetained` — which §6.7 says is *correct* accounting (GST is never revenue); (b) no internal-cost term. Which wins? My recommendation: **keep `chargeBase`, add the internal-cost term.**

**OQ-2 — Is Req #7 "internal cost items" per-booking or tenant-wide?** If it means office rent / salaries / ad spend **not attached to a booking**, `BookingExpense` is the wrong home (`bookingId` is NOT NULL, every finder is booking-scoped, authz inherits the parent booking's `SubAgentScope`) and it genuinely needs a new entity. If per-booking, see OQ-3.

**OQ-3 — Reuse `BookingExpense`, or a new table?** My recommendation is **(A) add a `VENDOR|INTERNAL` discriminator to `BookingExpense`** and sum only `INTERNAL` — see §7.4 for the three options and the concrete double-count example. Whichever you pick, this **reverses three written design decisions**, so I want it in writing.

**OQ-4 — `customerAmount` GST base.** Confirm it stays **pre-tax**. If yes, the convert modal must subtract the quotation `taxAmount` before seeding (`ConvertToBookingModal.jsx:157, 171`) and the field needs relabelling. If instead it should become all-in, then `recomputeTotals`, `netProfit`, `CancellationCalculator.baseConsidered` and **both** invoice templates all need reworking. See §4.

**OQ-5 — Should `netProfit` deduct GST?** Three FE labels say it should ("Customer − Vendor − GST"); the server does not. §6.3 says GST/TCS must **never** be in profit, and since `customerAmount` is pre-tax the server is already right — meaning **three FE labels are wrong** and should be corrected. Confirm.

**OQ-6 — Is `vendorRecoverable` staying a single per-booking scalar?** §6.6 argues a scalar is unrealistic and recommends per-service-line recoverability with `estimated` vs `actual` and a `CASH|VENDOR_CREDIT_NOTE|WAIVED` mode. That is a **material scope expansion** beyond the spec. Ship the scalar now and revisit, or design for lines from the start?

**OQ-7 — Trigger mechanism: explicit call or event?** Recommendation: **explicit idempotent call**, mirroring `SubAgentCommissionService.syncForBooking`, because the event route has the documented `TenantContext`-clearing landmine (§8). Confirm.

**OQ-8 — TCS on refund. ⚠️ Highest-value compliance item.** `tcsRefundable` currently defaults to **true**, so the system refunds TCS the agency has already remitted and cannot recover. Industry practice and the law's mechanics both say **do not refund post-filing TCS**. Should the default flip to `false`, and should the refund model carry a first-class *"TCS not refunded — claim in your ITR"* state? Needs your CA.

**OQ-9 — TCS on domestic bookings. ⚠️ Live money issue.** `app.booking.tcs-rate=0.05` is applied **unconditionally to every booking**; `Booking` has no overseas/domestic flag. 206C(1G)/394 does not apply to domestic packages. **Is the pilot tenant collecting TCS on domestic bookings today?** Separately, the rate is now **flat 2% with no threshold** (§6.1) — `application.properties:346-347` and `:491-493` are all stale.

**OQ-10 — GST on the retained charge: add-on or extraction?** Code computes `gstOnCharge` as a proportion of stored `Booking.gst` (tax-**exclusive** add-on). Circular 178 + Rule 35 imply the retained amount is normally **tax-inclusive** and GST should be extracted (`retained × rate / (100+rate)`). Similar today at flat 5%; diverges once rates are per-line.

**OQ-11 — Does cancellation need a real GST credit note?** Today's cancellation "credit note" has no GSTIN/HSN/place-of-supply/CGST-SGST-IGST split and never reaches the GST summary; `TaxInvoice.creditNoteNumber` is never written; `InvoiceServiceImpl.cancel()` reverses no tax. Plus the **s.34(2) 30-Nov deadline** and the **Finance Act 2025** requirement to actually refund the GST component to a B2C customer (§6.5). In scope, or a separate accounting workstream?

**OQ-12 — Req #4 revenue base.** `agencyRevenue = activeBookingCustomerAmount + totalRetainedCancellationCharges`. Should the second term be **`final_charge_base`** (pre-tax, what §6.7 recommends) or **`total_retained`** (which bundles GST + TCS, i.e. books government tax as revenue)? Recommendation: **`final_charge_base`.** And should it be a separate reported line ("Cancellation & Retention Income") rather than merged into package sales?

**OQ-13 — Refund period attribution.** `refundedAmount` has no date of its own on `Booking`; the payout date lives on the `BookingPayment` ledger row. Summing `Booking.refundedAmount` inside a `bookingDate` window attributes a refund to the **original booking month**, not the month the money left. Report on booking date (current plumbing) or payout date (aggregate `BookingPayment` where `entryType = REFUND`)?

**OQ-14 — Uniform status filter for revenue?** Four revenue figures include CANCELLED/REFUNDED, five exclude them, one (sub-agent roll-up) documents inclusion as deliberate. Uniform, or per-report? Related: net margin uses `customerAmount` on the dashboard and `totalPayable` on the revenue report — which denominator is canonical?

**OQ-15 — Scope of the FE fix.** `BookingDetails.jsx:1142` and `BookingRevenueAnalysis.jsx:22` use `||` (falsy) fallbacks, so a legitimate **server `netProfit` of exactly 0 is silently replaced** by client arithmetic. Delete the fallbacks outright (server becomes sole source), or convert to `??`? Deleting is the only change that makes the FE incapable of disagreeing. Also: should `refundedAmount` be added to `BookingResponseDTO` so the three fabricated FE refund figures collapse into one field?

---

# PHASE 2/3 — WHAT WAS BUILT

Built on the owner's instruction to proceed without waiting on §11. The recommended answer to each
open question was taken as the working assumption; §11 stays open for review, and each answer is
isolated to one section so a different call means reworking that piece, not the whole engine.

**Assumptions taken** — OQ-1 `chargeBase` + internal-cost term · OQ-2/3 reuse `BookingExpense` with a
`costType` discriminator · OQ-4/5 `customerAmount` stays pre-tax, `netProfit` does not deduct GST ·
OQ-6 `vendorRecoverable` stays a scalar for now · OQ-7 explicit call, not an event · OQ-12
`final_charge_base` · OQ-15 delete the falsy FE fallbacks.
**Explicitly NOT touched: OQ-8/OQ-9.** No tax rate, threshold or TCS-refund behaviour was changed —
those need a CA, and the code carries the same flat 5%/5% it did before.

### Req #5 — the refund bug (and four more like it)

`DashboardAnalyticsService:91-93` now sums `Booking.refundedAmount` over all non-deleted bookings in
the period, status-independent — matching `BookingRepository.sumTotalRefund()` and the spec's
`dashboardRefund = SUM(refundedAmount)`. Status filtering was the second half of the bug: a booking
only reaches `REFUNDED` when fully refunded, so every partial payout reported ₹0.

`refundedAmount` is now on `BookingResponseDTO`, which collapses the three invented frontend refund
figures (`Dashboard.jsx` Σ`totalPayable`, `Allbookings.jsx` Σ`paidAmount`, `BookingRevenueAnalysis`
hardcoded `0`) onto the one server field.

### Req #7 — internal cost items

`ExpenseCostType` (`VENDOR` | `INTERNAL`) on `BookingExpense`. Only `INTERNAL` rows feed
`totalInternalCosts`, so the double-count against the typed `vendorCost` is structurally impossible
rather than merely avoided. `VENDOR` is the default at entity, DTO and column level, so every
pre-existing row is classified the safe way and no stored margin moves on deploy.

Restore added — `POST /api/bookings/{id}/expenses/{expenseId}/restore`, `BOOKING_UPDATE`, 409 on a
row that is not deleted. The un-filtered finder is still `publicId + bookingId` scoped, so it stays
within what `TenantIsolationArchTest` allows.

The three javadoc/migration comments that asserted the old absolute rule were rewritten rather than
left to contradict the code.

### Req #3 — profit

`BookingProfitService` is the only writer of `netProfit`. `netProfit = customerAmount − vendorCost −
totalInternalCosts`, scale 2 HALF_UP, with `totalInternalCosts` denormalised alongside it so the
stored margin is explainable without re-reading the ledger. `apply()` recomputes from source (never
accumulates a delta) and no-ops on an unchanged booking — the equality guard uses `compareTo`, not
`equals`, because `BigDecimal.equals` is scale-sensitive and `0` vs `0.00` would dirty an
`@Audited`, `@Version`-bearing row on every call.

Wired at: create, lead→booking convert, booking update, and expense add/update/delete/restore.
Deliberately NOT wired at payment, refund or service-item CRUD — none of those move any term of the
formula.

### Req #7 — cancellation profit

`revisedNetProfit = chargeBase − sunkVendorCost − sunkInternalCosts`, with `sunkInternalCosts` sunk
in FULL (no recoverable counterpart — the commission was earned and the gateway fee charged whether
or not the customer travelled) and frozen on `booking_cancellations` like every other figure there.

### Req #4 — agency revenue

`agencyRevenue = revenue + retainedCancellationCharges`, where the second term is
`SUM(final_charge_base)` over the period's cancellations keyed on `cancelDate`. Reported as its own
line, not merged into package sales. Uses the pre-tax charge base, so government tax is never booked
as agency revenue.

### Also fixed, same area, no decision needed

- **Refund idempotency ordering** — the "already fully refunded" 409 ran *before* the idempotency
  lookup, so an honest retry of the payout that *completed* a refund was answered with an error.
- **Sub-agent row-scope gap** — the refund and cancellation-read paths bypassed
  `subAgentScope.assertVisible`; a `SUB_AGENT` with `BOOKING_REFUND` could act on a peer's booking.
- Explicit `setScale(2, HALF_UP)` on `totalPayable`.
- Four stale/false comments corrected (`BookingMapper` ×2 "GENERATED column, DB owns this";
  `BookingRevenueService` + `RevenueBreakdownDTO` "refunded is returned 0").
- Three frontend labels that claimed a formula the server never implemented
  ("Customer − Vendor − GST").
- The two falsy `|| (customerAmount - vendorCost)` fallbacks deleted — server is now the sole source.

### Migration

Appended as **PART 4 of `V2__lead_code.sql`**, not a new V3 — V2 has never been stamped on the
deployment database, so editing it in place cannot fail a later validate (its own header states
this). Adds `booking_expenses.cost_type`, `bookings.total_internal_costs`,
`booking_cancellations.sunk_internal_costs`, both Envers `_aud` twins, and two indexes. Every
statement is idempotent.

**It must be applied by hand before the code is deployed** — Flyway is disabled and unbootable and
`ddl-auto=validate`, so the app will refuse to start without these columns:

```
psql -U <user> -d <db> -f src/main/resources/db/migration/V2__lead_code.sql
```

Applied to the local dev database during this work. **Not applied to the pilot/production database.**

### Verification

Backend `306/306` tests green (PDF smoke test excluded), including `contextLoads` booting the real
Spring context against the migrated schema. Frontend `npm run build` green. 26 new tests:
`BookingProfitServiceTest` (11), `BookingRefundServiceImplTest` (7), `CancellationCalculatorTest`
(+3), plus the existing expense suite re-pointed at the real `BookingProfitService` so it would
catch a double-count.

**Nothing is committed** — both repos are left dirty for the owner to review and commit.

---

# PHASE 4 — PER-TENANT TAX (owner request)

> "It's tenant-based — taxes should be generalised. The tenant decides TCS: whether to apply it or
> not." Then: "All types of taxes per-tenant configurable, with standard defaults the tenant can
> override."

Every tax rate in the product was a **platform-wide property**. `app.booking.gst-rate` and
`app.booking.tcs-rate` stamped a flat 5% + 5% on every booking of **every tenant**, including purely
domestic ones — where s.206C(1G)/394 does not apply at all, so those tenants were collecting a tax
from their customers that the statute never imposed. The statutory TCS slab and the TDS section
rates were global too.

All of it now lives on the tenant's own `AccountingSettings` row — the home that already existed for
`GstScheme`, `autoTcsOnOverseas` and `inputTaxCreditEligible`.

| Setting | Default | What it does |
|---|---|---|
| `applyGstOnBookings` | `true` | Add GST to a booking at all |
| `bookingGstRatePct` | `5.00` | Booking GST rate |
| `tcsApplicability` | `ALWAYS` | `NEVER` / `OVERSEAS_ONLY` / `ALWAYS` |
| `bookingTcsRatePct` | `5.00` | Booking TCS rate |
| `tcsThreshold` | `700000.00` | Statutory slab boundary (invoice path) |
| `tcsRateBelowPct` / `tcsRateAbovePct` | `5.00` / `20.00` | Slab rates |
| `tds194cPct` / `194h` / `194j` | `2.00` / `5.00` / `10.00` | TDS section rates |
| `tdsNoPanPct` | `20.00` | s.206AA floor |

New: `BookingTaxCalculator` (pure), `TcsApplicability`, `Percents` (the percent⇄fraction boundary in
one place — getting it wrong is a 100× error), `Booking.overseasTourPackage`, and per-tenant
overloads on `TcsCalculator` / `TdsCalculator`.

**Rates are stored as PERCENTS** (5.00 = 5%) because that is what a tenant and their CA read and
edit; the calculators convert once at the boundary.

**Zero-regression.** Every default equals the value the platform previously hardcoded, and a brand-new
tenant's row is *seeded from the legacy properties*, so an environment that had tuned them keeps its
own values. `tcsApplicability` defaults to `ALWAYS` for that reason alone — it is **not** the right
setting for a domestic operator, and the settings screen shows an amber warning saying so. Silently
changing what a customer is charged during an upgrade would be worse than a visible, documented choice.

**Deliberately NOT encoded:** the 1 Apr 2026 flat-2%-no-threshold regime. The defaults stay at the
old 5%/20%. Changing a tenant's tax rate without their CA's sign-off is not a migration's call — a
tenant adopts it by setting the rate, with no code change.

### Review findings fixed (adversarial pass, 14 refuted / 3 distinct confirmed)

- **`agencyRevenue` counted cancellations of trashed bookings.** A CANCELLED booking is deletable,
  soft-delete does not cascade to `booking_cancellations`, and the purge orphans the row — so the
  same response reported ₹0 revenue and a full retained charge for one booking, permanently. Added a
  booking-liveness `EXISTS` to `sumRetainedChargeBase`.
- **`BookingServices` profit badge always rendered ₹0** — a regression from this work: the page
  builds its state from a whitelist that did not include `netProfit`.
- **`EditBooking` Live Summary contradicted the server** — its `customerAmount − vendorCost` preview
  predates the third term. Now subtracts `totalInternalCosts` too.

### Owner-review findings (three raised, two upheld)

1. **Cancelled profit not on `Booking.netProfit` — upheld, and one part was mine.** The cancel flow
   now writes the revised figure onto the booking, and `BookingProfitService` applies the
   *cancellation* formula (`finalChargeBase − sunkVendorCost − sunkInternalCosts`) to a cancelled
   booking — the policy side stays frozen, only the cost term moves. Without this, an expense edit
   after cancellation reinstated the active formula and reported the margin of a trip that never ran.
2. **Agency revenue only on one endpoint — upheld.** Now on `BookingRevenueService.getSummary` (which
   also stopped counting cancelled bookings as revenue and their profit as trading margin),
   `ReportService.revenueTracked`, `/bookings/stats` and the dashboard. `cancelledProfit` /
   `totalProfit` are reported as separate lines throughout, per §6.7.
3. **Internal-cost field names — partly upheld.** `title`/`costDate` were mapped onto the existing
   `description`/`expenseDate` as a stated design decision (§7.2) to avoid a fifth cost surface, not
   as an oversight. Renaming would break the live expense screen. Added `@JsonAlias("title")` /
   `@JsonAlias("costDate")` and made `category` optional so a client written to the spec's contract
   works unchanged. The `VENDOR` default stands: it is the zero-regression guarantee, and defaulting
   to `INTERNAL` would silently cut the margin of every existing booking.

Its test claim ("context error, `apply_gst_on_bookings` missing") was **stale** — it ran mid-change;
the column exists and `contextLoads` passes.

### Verification

**322/322 backend tests green** (PDF smoke excluded), frontend build green. Migration is **PART 5 of
`V2`**, applied to the local dev DB, **not** to the pilot/production DB.
