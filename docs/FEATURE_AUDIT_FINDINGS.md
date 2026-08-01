# Travel CRM - Feature-by-Feature Audit Findings

This is the canonical file for feature-level code-scan findings. Add every future feature review
to this file instead of creating a separate report.

**Audit baseline:** Current working tree, including uncommitted and untracked files  
**Started:** 2026-07-31  
**Scope:** Backend repository plus explicitly requested frontend feature scans

## Finding conventions

### Severity

| Severity | Meaning |
|---|---|
| Blocker | Prevents the feature from being built, deployed, or used through a supported path. |
| Critical | Can cross a tenant/security boundary or irreversibly corrupt sensitive data. |
| High | Authorization, financial-integrity, or major functional failure. |
| Medium | Bounded correctness, concurrency, auditability, or reliability problem. |
| Low | Limited-impact correctness or maintainability problem. |

### Status

Use `Open`, `In progress`, `Fixed`, `Accepted`, or `Needs decision`. A finding is marked `Fixed`
only after the implementation and relevant verification are complete.

## Feature index

| Feature ID | Module | Feature | Audit date | Open findings | Result |
|---|---|---|---|---:|---|
| BOOKING-EXPENSE | Booking | Expense Ledger / Booking Expenses | 2026-07-31 | 13 | Changes required before production release |
| BOOKING-PAYMENT | Booking | Payment Ledger / Customer Receipts & Refunds | 2026-07-31 | 18 | Changes required before production release |

---

## BOOKING-EXPENSE - Expense Ledger / Booking Expenses

**Audit date:** 2026-07-31  
**Status:** Reviewed; no source fixes applied  
**Overall result:** The implementation is structurally sound, but its authorization and financial
history model need decisions/fixes before it should be treated as an accounting-grade ledger.

### Implemented API

| Method | Endpoint | Permission | Purpose |
|---|---|---|---|
| `GET` | `/api/bookings/{bookingPublicId}/expenses` | `BOOKING_READ` | List live expense rows. |
| `GET` | `/api/bookings/{bookingPublicId}/expenses/summary` | `BOOKING_READ` | Return expense, paid, outstanding, and overdue totals. |
| `POST` | `/api/bookings/{bookingPublicId}/expenses` | `BOOKING_UPDATE` | Create 1-50 expense rows atomically. |
| `PUT` | `/api/bookings/{bookingPublicId}/expenses/{expensePublicId}` | `BOOKING_UPDATE` | Partially update an expense and re-settle its money fields. |
| `DELETE` | `/api/bookings/{bookingPublicId}/expenses/{expensePublicId}` | `BOOKING_UPDATE` | Soft-delete an expense row. |

### What is implemented correctly

- Every operation first resolves the active parent booking and applies `SubAgentScope`.
- Expense lookup is scoped to both the expense public ID and authorized booking ID, preventing a
  foreign expense ID from being used against another booking.
- Tenant filtering is activated by method-level `@Transactional` annotations.
- Bulk creation cascades bean validation, accepts at most 50 rows, and is one transaction.
- `ExpenseSettlementCalculator` derives `CREDIT`, `PARTIAL`, and `PAID` from server-settled money.
- Negative payments, overpayments, non-positive totals, and due dates before expense dates are
  rejected.
- Outstanding amounts are derived rather than stored, preventing drift between three money columns.
- Summary totals and overdue counts are calculated only from live expense rows.
- `/api/bookings/**` is covered by the `BOOKINGS` module-entitlement filter.

### Findings

#### BOOKING-EXPENSE-001 - Supplier financial data uses ordinary booking permissions

**Severity:** High  
**Status:** Needs decision

`BookingExpenseController` protects reads with `BOOKING_READ` and mutations with `BOOKING_UPDATE`.
The default `SUB_AGENT` role has both permissions, so a sub-agent can read and change vendor names,
costs, paid amounts, references, and outstanding balances on bookings it owns.

That conflicts with the permission policy comment stating that supplier cost/commission is
parent-only for sub-agents. The broader booking flow already has a related inconsistency:
`BookingMapper` says its full financial response must never be returned without a permission check,
but `BookingServiceImpl.toResponse()` performs no such role/financial check.

**Evidence:**

- `src/main/java/com/crm/travelcrm/booking/controller/BookingExpenseController.java:41-43,72,85,96`
- `src/main/java/com/crm/travelcrm/permission/enums/Permission.java:214-226`
- `src/main/java/com/crm/travelcrm/booking/mapper/BookingMapper.java:99-116`
- `src/main/java/com/crm/travelcrm/booking/service/BookingServiceImpl.java:998-1009`

**Required decision:** Either introduce/use a financial-cost permission and hide this surface from
sub-agents, or explicitly change the documented role policy and accept that sub-agents can manage
supplier costs for their own bookings.

#### BOOKING-EXPENSE-002 - Mutable cumulative payment is not a complete payment ledger

**Severity:** High  
**Status:** Needs decision

Each expense stores one cumulative `paidAmount`, `paymentMode`, and `referenceNumber`. Recording a
later partial payment updates the same row, so the system cannot show each disbursement, its date,
mode, reference, actor, or reversal independently. This prevents reliable bank/cash reconciliation.

`BookingExpense` also has no `@Version`. Two users can read the same balance and save competing
updates; the last commit wins without reporting a conflict. The existing vendor-payable module
avoids this by using append-only `VendorPayment` rows and a version-guarded parent `VendorBill`.

**Evidence:**

- `src/main/java/com/crm/travelcrm/booking/entity/BookingExpense.java:61-119`
- `src/main/java/com/crm/travelcrm/booking/service/BookingExpenseServiceImpl.java:119-151`
- `src/main/java/com/crm/travelcrm/accounting/tds/entity/VendorBill.java:18-41,113`
- `src/main/java/com/crm/travelcrm/accounting/tds/entity/VendorPayment.java`

**Recommended direction:** Keep `BookingExpense` as the payable/header and add append-only expense
payment rows. Derive the paid balance from those rows or maintain a version-guarded running total.

#### BOOKING-EXPENSE-003 - Update accepts blank required fields

**Severity:** Medium  
**Status:** Open

Create requires non-blank `category` and `description`, but update applies only `@Size`. Requests
such as `{"category":" "}` or `{"description":""}` therefore pass validation and overwrite valid
required text with blank values.

**Evidence:**

- `src/main/java/com/crm/travelcrm/booking/dto/request/CreateBookingExpenseRequest.java:27-34`
- `src/main/java/com/crm/travelcrm/booking/dto/request/UpdateBookingExpenseRequest.java:38-42`
- `src/main/java/com/crm/travelcrm/booking/service/BookingExpenseServiceImpl.java:124-125`

**Recommended fix:** Add a reusable optional-but-not-blank validation rule, or reject blank supplied
values in the service while continuing to treat `null` as unchanged.

#### BOOKING-EXPENSE-004 - Financial edits and deletions lack durable audit history

**Severity:** Medium  
**Status:** Open

Expense rows are mutable and soft-deletable but are not Envers-audited. A deleted expense disappears
from the list and summary, is not registered as a Trash type, and cannot be restored through the
Trash API. Base audit columns retain only the latest actor/timestamps, not the before/after values.

**Evidence:**

- `src/main/java/com/crm/travelcrm/booking/entity/BookingExpense.java:39-61`
- `src/main/java/com/crm/travelcrm/booking/service/BookingExpenseServiceImpl.java:154-163`
- `src/main/java/com/crm/travelcrm/trash/TrashableType.java:47-79`

**Recommended fix:** Audit expense changes and define an explicit restore/void policy. For financial
records, prefer void/reversal entries over silently removing a row from financial totals.

#### BOOKING-EXPENSE-005 - Database does not enforce the documented money invariants

**Severity:** Medium  
**Status:** Open

The service guarantees `amount > 0` and `0 <= paid_amount <= amount`, but the database has no CHECK
constraints for those rules. A script, import, future service, or manual correction can create an
invalid row. The schema also deliberately has no foreign key to `bookings`, so hard-purging a booking
leaves its expense rows orphaned.

**Evidence:**

- `src/main/resources/db/migration/V2__lead_code.sql:245-268,281-286`
- `src/main/java/com/crm/travelcrm/trash/TrashServiceImpl.java:119-157`

**Recommended fix:** Add database CHECK constraints. Resolve booking-child purge behavior across
expenses, payments, and service items as one aggregate-level design rather than adding only one FK.

#### BOOKING-EXPENSE-006 - Feature and migration files are not tracked for delivery

**Severity:** Blocker  
**Status:** Open

At audit time, all booking-expense Java files, its tests, and `src/main/resources/db/migration/` are
untracked. They will not be included in a commit unless explicitly staged. Flyway is disabled by
default while Hibernate defaults to schema validation, so the database migration must be applied
before starting the application or startup will fail on the missing table.

**Evidence:**

- `git status --short` on 2026-07-31
- `src/main/resources/application.properties:64,93-100`
- `docs/DEPLOYMENT.md:251-277`

**Resolution condition:** Track the intended source/test/migration files and complete the documented
Flyway/manual migration procedure before deployment.

### Deliberate integration boundaries

These are current product decisions, not recorded as defects unless requirements change:

- Expense totals do not update `Booking.vendorCost` or generated `Booking.netProfit`.
- The booking response does not include the expense summary; clients must call `/expenses/summary`.
- The accounting CSV export contains `TaxInvoice` and `VendorBill` rows, not booking expenses.
- The API is per-booking only; there is no tenant-wide expense/payables report.
- The update endpoint has patch semantics even though it uses HTTP `PUT`, and optional fields cannot
  currently be cleared back to `null`.

### Verification performed

- Focused tests: `ExpenseSettlementCalculatorTest` and `BookingExpenseServiceImplTest`.
- Result: **29 tests passed, 0 failures, 0 errors**.
- Compilation: **1,291 production Java source files compiled successfully**.
- Full-suite attempt: **283 tests completed with 0 failures/errors** before the run stalled ahead of
  the final two source test classes; the orphaned Maven/Java processes were stopped.
- Missing coverage: controller authorization/validation tests, repository persistence tests,
  concurrent update tests, and migration/schema validation against PostgreSQL.

### Frontend audit

**Frontend repository:** `D:\CRM PROJECT\travelcrmfe\travelcrmfrontend`  
**Audited files:**

- `src/features/bookings/components/BookingExpenseModal.jsx`
- `src/features/bookings/pages/Allbookings.jsx`
- `src/features/bookings/api/bookingService.js`
- `src/shared/lib/access.js`
- `src/app/router.jsx`

#### Frontend data flow

1. `Allbookings` loads up to 500 booking rows and normalizes each booking's public ID.
2. A `BOOKING_UPDATE`-gated wallet button stores that booking in `expenseBooking`.
3. `BookingExpenseModal` opens with one new, empty expense entry. It does not receive or load saved
   expense rows.
4. The modal performs browser-side validation and derives paid/outstanding preview totals.
5. `Allbookings.saveBookingExpenses()` calls the bulk-create endpoint.
6. On success, the modal closes and the normal booking list is refreshed. Because expenses do not
   update booking totals, that refresh does not display the saved expense or its summary.

#### Backend contract coverage

| Backend capability | Frontend API method | Used by UI | Result |
|---|---|---:|---|
| List expenses | `getExpenses` | No | Saved rows cannot be viewed. |
| Expense summary | `getExpenseSummary` | No | Actual cost, outstanding, and overdue totals are not displayed. |
| Bulk create | `addExpenses` | Yes | New rows can be saved. |
| Update expense | `updateExpense` | No | Corrections and later vendor payments cannot be recorded. |
| Soft-delete expense | `deleteExpense` | No | Incorrect/duplicate rows cannot be removed from the UI. |

#### BOOKING-EXPENSE-007 - The frontend is an add-expense form, not an expense ledger

**Severity:** High  
**Status:** Open

The UI calls only `addExpenses`. Although API methods exist for list, summary, update, and delete,
there are no consumers for them anywhere under `src`. Every modal opening starts with a blank entry.
After saving, users cannot see what was saved, inspect the server-settled amounts, view overdue
payables, correct an entry, record a later partial/full vendor payment, or remove a duplicate.

This is especially risky because the modal is titled **Expense Ledger**. A user can reopen it,
mistake the blank form for an empty ledger, and enter the same costs again. Refreshing the bookings
list after create does not help because the backend deliberately does not roll expense totals into
`Booking.vendorCost` or `Booking.netProfit`.

**Evidence:**

- Frontend `src/features/bookings/pages/Allbookings.jsx:592-622,719-730,1112-1122`
- Frontend `src/features/bookings/components/BookingExpenseModal.jsx:1548-1576,1681-1707,1794-1799`
- Frontend `src/features/bookings/api/bookingService.js:133-167`
- Repository-wide usage scan: only `addExpenses` has a live UI caller.
- Backend `src/main/java/com/crm/travelcrm/booking/entity/BookingExpense.java:24-31`

**Recommended fix:** Build a real booking-expense workspace that loads list and summary on open,
renders server response values, supports edit/delete according to permission, and offers an explicit
`Add expense` action inside the ledger. Alternatively, rename the current modal to `Add booking
expenses` until the read/update/delete experience exists.

#### BOOKING-EXPENSE-008 - Frontend validation does not match backend limits

**Severity:** Medium  
**Status:** Open

The modal checks presence and basic numeric relationships but does not enforce the backend's length,
precision, scale, or batch constraints. A request can look valid in the browser and then fail as one
atomic batch at the API.

| Field/rule | Backend | Frontend |
|---|---|---|
| Batch size | Maximum 50 expenses | Unlimited `New Entry` clicks |
| Amount | `0.01`, max 10 integer digits, max 2 decimals | Any JavaScript number greater than zero |
| Paid amount | Max 10 integer digits, max 2 decimals | Relationship check only |
| Description | Maximum 300 characters | No `maxLength` |
| Vendor name | Maximum 200 characters | No `maxLength` |
| Reference number | Maximum 120 characters | No `maxLength` |
| Notes | Maximum 1,000 characters | No `maxLength` |

For example, `0.001` passes `amount > 0` in the modal but fails backend `@Digits(fraction = 2)`.
Likewise, the 51st entry can be added normally but makes the whole create request fail.

**Evidence:**

- Frontend `src/features/bookings/components/BookingExpenseModal.jsx:1631-1679,1924-1936,1957-2045,2060-2068`
- Backend `src/main/java/com/crm/travelcrm/booking/dto/request/BulkCreateBookingExpensesRequest.java:36-38`
- Backend `src/main/java/com/crm/travelcrm/booking/dto/request/CreateBookingExpenseRequest.java:27-75`

**Recommended fix:** Share/document the API limits and enforce them in the inputs and submission
validator. Disable `New Entry` at 50, display a counter, add `maxLength`, and validate monetary scale
and maximum values before sending the batch.

#### BOOKING-EXPENSE-009 - Default expense date uses UTC instead of the user's local date

**Severity:** Medium  
**Status:** Open

`today()` uses `new Date().toISOString().split("T")[0]`. `toISOString()` is UTC. In India, from local
midnight until 05:29, it returns the previous calendar date, so a freshly opened expense form is
pre-filled with yesterday. That incorrect value is then submitted as the business expense date.

**Evidence:**

- Frontend `src/features/bookings/components/BookingExpenseModal.jsx:1516,1548-1556`

**Recommended fix:** Build `YYYY-MM-DD` from local `getFullYear()`, `getMonth() + 1`, and `getDate()`,
or use the application's established local-date utility.

#### BOOKING-EXPENSE-010 - Modal accessibility is incomplete

**Severity:** Medium  
**Status:** Open

The overlay has no `role="dialog"`, `aria-modal`, or accessible title association. Focus is not moved
into the modal, trapped inside it, or restored to the trigger. Escape does not close it, the page
behind it remains keyboard/screen-reader reachable, and `Field` labels are not associated with their
inputs through `htmlFor`/`id`. Keyboard users can therefore lose context or tab outside the modal.

**Evidence:**

- Frontend `src/features/bookings/components/BookingExpenseModal.jsx:1709-1719,1785-1811,1834-1846,2118-2132`

**Recommended fix:** Use the shared accessible dialog primitive if available. Otherwise implement
dialog semantics, labelled title, initial focus, focus trap/restore, Escape handling, scroll lock,
and explicit label/input associations.

#### BOOKING-EXPENSE-011 - Editing one field can hide unrelated validation errors

**Severity:** Low  
**Status:** Open

Every `updateExpense` call clears not only that field's error but also `paymentMode`, `paidAmount`,
and `dueDate` errors. For example, after validation reports a missing payment mode, changing the
description removes the payment-mode message even though the value is still missing. The next save
recreates the error, making validation appear inconsistent.

The API's row-specific business error (for example, `Expense #3`) is shown only as a toast and is not
mapped back to/opened on the corresponding row.

**Evidence:**

- Frontend `src/features/bookings/components/BookingExpenseModal.jsx:1619-1628,1647-1679`
- Frontend `src/features/bookings/pages/Allbookings.jsx:612-618`

**Recommended fix:** Clear only the edited field plus errors whose dependency actually changed.
Parse server row errors and open/highlight the affected expense row while retaining the toast as a
summary.

#### BOOKING-EXPENSE-012 - The expense component contains large obsolete implementations

**Severity:** Low  
**Status:** Open

The active implementation starts around line 1458. Roughly 1,450 preceding lines contain commented
older versions of the same component. This obscures the live code, makes review/search results noisy,
and increases the chance of fixing a dead copy instead of the rendered implementation.

The active component also imports Google Fonts inside a mounted `<style>` block. That mixes a
runtime external request into a financial modal and can be blocked by CSP, ad/privacy tooling, or an
offline deployment. The component's own comment already says to move it to global styles.

**Evidence:**

- Frontend `src/features/bookings/components/BookingExpenseModal.jsx:1-1455,1458-1473,1720-1725`

**Recommended fix:** Delete the commented implementations (Git retains history), keep one live
component, and move reusable styles/fonts to the application's local/global asset pipeline.

#### BOOKING-EXPENSE-013 - No automated frontend coverage exists

**Severity:** Medium  
**Status:** Open

There are no booking-expense tests and no `test` script or frontend test framework in `package.json`.
The most important behaviors are therefore unpinned: status-to-money transitions, batch validation,
date defaults, payload serialization, API failure behavior, permissions, and eventual list/edit/delete
flows.

**Evidence:**

- Frontend `package.json:6-11,32-42`
- No booking/expense `*.test.*`, `*.spec.*`, or `__tests__` files were found.

**Recommended fix:** Add component and API-client tests covering CREDIT/PARTIAL/PAID transitions,
limits, local dates, bulk payloads, server failure retention, permissions, and ledger CRUD.

### Frontend authorization alignment

- The expense button is correctly hidden unless `hasPermission(P.BOOKING_UPDATE)` is true, matching
  the backend mutation gate.
- The frontend permission fallback grants `BOOKING_UPDATE` to `SUB_AGENT`, so the sensitive-cost
  policy conflict recorded in `BOOKING-EXPENSE-001` is visible end to end, not backend-only.
- `/Allbookings` itself is protected only by authentication in the router; permission/module checks
  hide the sidebar entry and the backend remains the real security boundary. Direct navigation by a
  user without `BOOKING_READ` produces an avoidable failed page rather than a route-level denial.

**Evidence:**

- Frontend `src/features/bookings/pages/Allbookings.jsx:1112-1122`
- Frontend `src/shared/lib/access.js:112-119`
- Frontend `src/app/router.jsx:244-265`

### Frontend delivery state and verification

- `src/features/bookings/api/bookingService.js` is modified but not committed at audit time. The
  tracked `Allbookings` page already calls `bookingService.addExpenses`; omitting this API-client
  change from delivery would make the button fail at runtime. This extends the cross-repository
  release blocker in `BOOKING-EXPENSE-006`.
- Focused ESLint command completed with **0 errors**. It reported eight warnings in
  `Allbookings.jsx`; none originated in the expense handler/modal/API additions.
- Production build completed successfully with Vite 8.0.14: **3,109 modules transformed**.
- Build warnings remain for ineffective dynamic imports and the main `index` chunk is approximately
  **1.78 MB minified / 413.60 kB gzip**. These are repository-wide performance findings, not caused
  solely by the expense feature.
- Running the build updated the frontend repository's generated `dist` output; no frontend source
  files were changed by this audit.

---

## BOOKING-PAYMENT - Payment Ledger / Customer Receipts & Refunds

**Audit date:** 2026-07-31  
**Status:** Reviewed in detail across backend and frontend; no source fixes applied  
**Overall result:** The server has a useful transactional receipt/refund foundation, but the active
frontend does not honor its ledger-direction contract. Terminal bookings can still be financially
mutated, ordinary receipt writes are not idempotent, refund-side owner scoping is incomplete, and
several payment experiences are disconnected. These issues can misstate collections, refunds, and
sub-agent commission, so the feature is not yet accounting-grade.

### Implemented API and write paths

| Method | Endpoint/path | Permission | Purpose |
|---|---|---|---|
| `GET` | `/api/bookings/{bookingPublicId}/payments` | `BOOKING_READ` | List live receipt and refund ledger rows. |
| `POST` | `/api/bookings/{bookingPublicId}/payments` | `BOOKING_UPDATE` | Append a customer receipt and increase `paidAmount`. |
| `DELETE` | `/api/bookings/{bookingPublicId}/payments/{paymentPublicId}` | `BOOKING_UPDATE` | Soft-delete a receipt and decrease `paidAmount`; refunds are rejected. |
| `PATCH` | `/api/bookings/{publicId}/payment` | `BOOKING_UPDATE` | Legacy incremental receipt path. |
| `PUT` | `/api/bookings/{publicId}` with `paidAmount` | `BOOKING_UPDATE` | Increase the absolute paid target and append a generic adjustment row. |
| `POST` | `/api/bookings/{publicId}/cancellation/refund` | `BOOKING_REFUND` | Append a refund payout, increase `refundedAmount`, and issue a voucher. |
| `GET` | `/api/bookings/{publicId}/cancellation` | `BOOKING_READ` | Read frozen refund due, paid-out, and remaining totals. |
| `GET` | `/api/bookings/{publicId}/cancellation/refund-voucher` | `BOOKING_READ` | Download the newest refund voucher for the booking. |

### What is implemented correctly

- Receipt add/delete resolves the active booking, applies `SubAgentScope`, and scopes a payment ID
  to its authorized booking, preventing a foreign payment UUID from being used on another booking.
- Add, delete, parent-counter update, payment-status derivation, and commission synchronization run
  in one transaction.
- `Booking.version` provides optimistic locking. Concurrent stale writers fail instead of silently
  overwriting `paidAmount`; the transaction rolls back the child ledger write too.
- A receipt cannot take `paidAmount` above `totalPayable`, and deleting a receipt cannot take it below
  zero.
- Initial payments from direct booking creation and lead conversion create opening-balance rows, so
  new bookings do not begin with a paid counter over an empty receipt table.
- Refunds use explicit `entryType = REFUND`, update a separate `refundedAmount`, are capped to the
  frozen cancellation liability, cannot be deleted through the receipt endpoint, and reverse
  commission through net collection.
- Invoice generation filters out `REFUND` rows from the **Payments Received** table.
- Optional service attribution is server-validated against the same booking before it is stored.
- Parent `@Version`, the refund cap, and the partial unique idempotency index provide useful defenses
  against concurrent over-collection and over-refunding, even though retry semantics remain incomplete.

### End-to-end frontend flow

There are two live receipt experiences:

1. `BookingDetails` loads booking, service lines, and payments separately. Its modal records a basic
   receipt, while the page renders and can delete every returned ledger row.
2. `BookingPayments` is a separate detailed page with receipt type, amount, method, date, a displayed
   per-row status, reference, notes, refund/net summaries, and delete controls. The route exists, but
   no live navigation points to it.

Refunds are recorded from `RefundBookingModal`, which first loads the server cancellation summary,
sends a generated idempotency key, then offers the booking-level latest refund-voucher endpoint.

### Findings

#### BOOKING-PAYMENT-001 - The frontend's `Refund` payment type is posted as money received

**Severity:** High  
**Status:** Open

`BookingPayments` offers `Refund` in `PAYMENT_TYPES` and sends it to the ordinary add-payment
endpoint. On the backend, `paymentType` is only a display label. The endpoint always creates the
default `RECEIPT` entry, adds the amount to `Booking.paidAmount`, and synchronizes commission.

The same frontend then classifies the row as a refund by checking the display label, renders it as a
negative amount, adds it to `totalRefunded`, and subtracts it from `netReceived`. A user therefore
sees money going out while the authoritative booking and commission logic record money coming in.
The real refund flow is the separately protected cancellation/refund endpoint.

**Evidence:**

- Frontend `src/features/bookings/pages/BookingPayments.jsx:20,113-123,144-151,464-479`
- Backend `src/main/java/com/crm/travelcrm/booking/entity/BookingPayment.java:51-79`
- Backend `src/main/java/com/crm/travelcrm/booking/service/BookingPaymentServiceImpl.java:63-99`
- Backend `src/main/java/com/crm/travelcrm/booking/cancellation/service/BookingRefundServiceImpl.java:100-130`

**Recommended fix:** Remove `Refund` from the receipt-type list. Use `entryType`, never
`paymentType`, to determine ledger direction, and expose the real refund action only through the
`BOOKING_REFUND` flow.

#### BOOKING-PAYMENT-002 - Pending, failed, and refunded row statuses are silently ignored

**Severity:** High  
**Status:** Open

The dedicated page requires a `Payment Status` and offers `Completed`, `Pending`, `Failed`, and
`Refunded`, but `handleAddPayment()` does not send that field. The request DTO/entity/response do not
contain a per-payment status either. Every submission is immediately counted as received and the UI
later invents `Completed` when no status exists.

Selecting **Pending** or **Failed** therefore still raises `paidAmount`, can mark the booking paid,
and can accrue sub-agent commission. The edit-booking form also submits a booking
`paymentStatus` field that `UpdateBookingRequestDTO` does not accept; the server derives booking
payment status from money instead.

**Evidence:**

- Frontend `src/features/bookings/pages/BookingPayments.jsx:22,64,125-151,382-395,463-491`
- Frontend `src/features/bookings/pages/EditBooking.jsx:219-240,398-416`
- Backend `src/main/java/com/crm/travelcrm/booking/dto/request/CreateBookingPaymentRequest.java:17-51`
- Backend `src/main/java/com/crm/travelcrm/booking/dto/response/BookingPaymentResponse.java:13-37`
- Backend `src/main/java/com/crm/travelcrm/booking/service/BookingPaymentServiceImpl.java:91-99`

**Required decision:** If the ledger records only settled receipts, delete the status control and
reject future/pending semantics. If pending/failed attempts are required, model their lifecycle and
exclude them from `paidAmount` and commission until settlement.

#### BOOKING-PAYMENT-003 - Payment APIs can mutate completed, cancelled, and refunded bookings

**Severity:** High  
**Status:** Open

General booking edits call `assertEditableBooking()` and lock `COMPLETED`, `CANCELLED`, and
`REFUNDED` bookings. Neither payment-ledger add/delete nor the legacy incremental payment method
applies that guard. Both frontends gate controls only by `BOOKING_UPDATE`, not booking status.

After cancellation, the liability is frozen from `paidAtCancel`. A later receipt or deletion changes
the live `paidAmount` without recomputing that cancellation snapshot. On a fully refunded booking,
`derivePaymentStatus()` deliberately preserves `REFUNDED`, so an added receipt changes collection
and commission while the booking still appears terminal/refunded. Deleting an old receipt can also
leave `refundedAmount` greater than the remaining gross collection.

**Evidence:**

- Backend `src/main/java/com/crm/travelcrm/booking/service/BookingServiceImpl.java:93-97,431-435,513-523,793-823`
- Backend `src/main/java/com/crm/travelcrm/booking/service/BookingPaymentServiceImpl.java:60-140,164-172`
- Backend `src/main/java/com/crm/travelcrm/booking/service/BookingServiceImpl.java:589-620`
- Frontend `src/features/bookings/pages/BookingPayments.jsx:49,312-428,497-512`
- Frontend `src/features/bookings/pages/BookingDetails.jsx:1394,1966-2023`

**Recommended fix:** Define the allowed receipt lifecycle explicitly and enforce the same backend
guard on every receipt path. At minimum, reject add/delete after cancellation/refund and decide
whether completed bookings require a privileged reopen/correction workflow.

#### BOOKING-PAYMENT-004 - `BOOKING_UPDATE` lets sub-agents self-accrue commission

**Severity:** High  
**Status:** Needs decision

Both receipt mutation endpoints use the broad `BOOKING_UPDATE` authority. `SUB_AGENT` receives it by
default and owns its bookings, so a sub-agent can record an unsupported receipt up to the full
payable amount, or delete one, without a bank reference, proof, or parent approval. Each action
immediately reconciles the same sub-agent's commission from net collection.

This does not itself pay the commission out, but it lets the beneficiary change the authoritative
accrual basis. Travel agents and accountants also receive the same mutation authority; payment
posting and general booking editing cannot be separated in the current permission model.

**Evidence:**

- `src/main/java/com/crm/travelcrm/booking/controller/BookingPaymentController.java:20,41-58`
- `src/main/java/com/crm/travelcrm/permission/enums/Permission.java:184-226`
- `src/main/java/com/crm/travelcrm/booking/service/BookingPaymentServiceImpl.java:91-99,131-140`
- `src/main/java/com/crm/travelcrm/subagent/service/SubAgentCommissionService.java:17-25,43-70,81-92`

**Required decision:** Introduce a receipt/accounting authority, an approval/reconciliation state,
or both. If sub-agents are meant to self-report collections, retain the action but keep commission
unapproved until parent reconciliation and preserve supporting evidence.

#### BOOKING-PAYMENT-005 - Refund-side services omit sub-agent owner scope

**Severity:** High  
**Status:** Open

The core `/payments` service correctly calls `SubAgentScope.assertVisible()`. The cancellation
summary, cancellation documents, and refund service load a booking by tenant-filtered UUID but do
not apply owner scope.

Consequently, a sub-agent with its default `BOOKING_READ` can read another sub-agent's cancellation
financial summary and download its credit/debit note or latest refund voucher if the booking UUID is
known. If `BOOKING_REFUND` is explicitly granted to a sub-agent, that user can also disburse against
another owner within the tenant. UUID unpredictability reduces discoverability but is not an access
control boundary.

**Evidence:**

- `src/main/java/com/crm/travelcrm/booking/service/BookingPaymentServiceImpl.java:149-154`
- `src/main/java/com/crm/travelcrm/booking/cancellation/controller/BookingCancellationController.java:49-93`
- `src/main/java/com/crm/travelcrm/booking/cancellation/service/BookingCancellationServiceImpl.java:37-60`
- `src/main/java/com/crm/travelcrm/booking/cancellation/service/BookingRefundServiceImpl.java:51-57`
- `src/main/java/com/crm/travelcrm/booking/cancellation/service/CancellationDocumentServiceImpl.java:119-137,270-273`

**Recommended fix:** Centralize booking resolution through the same access guard used by the ledger
and document services, and add authorization tests for a sub-agent accessing another owner's UUID.

#### BOOKING-PAYMENT-006 - Ordinary receipt creation is not idempotent

**Severity:** High  
**Status:** Open

The receipt request has no idempotency key or unique transaction/reference rule. A timeout followed
by retry, browser resubmit, or repeated operator action creates a second receipt whenever the
remaining payable allows it. Optimistic locking protects truly concurrent stale updates, but it
does not recognize a sequential retry after the first transaction committed.

The dedicated page does not await its post-save refresh; `saving` becomes false while `fetchData()`
is still running, so the form can be used again before refreshed totals arrive. Neither frontend
generates a receipt action key.

**Evidence:**

- `src/main/java/com/crm/travelcrm/booking/dto/request/CreateBookingPaymentRequest.java:17-51`
- `src/main/java/com/crm/travelcrm/booking/entity/BookingPayment.java:61-67`
- `src/main/java/com/crm/travelcrm/booking/service/BookingPaymentServiceImpl.java:60-102`
- Frontend `src/features/bookings/pages/BookingPayments.jsx:138-160`
- Frontend `src/features/bookings/pages/BookingDetails.jsx:1206-1233`

**Recommended fix:** Add a client-generated receipt idempotency key, scope its unique index to the
tenant/booking as intended, return the original receipt on replay, and keep the form locked until
the authoritative refresh completes.

#### BOOKING-PAYMENT-007 - Refund idempotency fails for final and concurrent retries

**Severity:** Medium  
**Status:** Open

The refund service checks whether the refundable balance is already zero before looking up the
request's idempotency key. A retry of a one-shot/full or final partial refund therefore gets
`already fully refunded` instead of the original success response. Two concurrent requests with the
same key can both miss the pre-check; the unique index prevents duplicate money, but the losing call
is not caught and replayed as success.

Even when a partial sequential replay reaches `echoExisting()`, that response omits the original
voucher number and document public ID.

**Evidence:**

- `src/main/java/com/crm/travelcrm/booking/cancellation/service/BookingRefundServiceImpl.java:66-86,100-145,157-176`
- `src/main/resources/db/indexes.sql:17-28`
- `src/main/java/com/crm/travelcrm/booking/dto/response/RefundResponseDTO.java:36-39`

**Recommended fix:** Resolve an idempotency hit before balance-state rejection, catch the unique
constraint race and reload the winner, and return the same complete response including voucher
metadata on every replay.

#### BOOKING-PAYMENT-008 - Deleting a receipt is a hidden removal, not a financial reversal

**Severity:** Medium  
**Status:** Open

A `BOOKING_UPDATE` user can soft-delete any receipt without supplying a correction reason. The row
then disappears from the API and invoice and immediately reduces the parent counter and commission.
`BookingPayment` is not Envers-audited, is not a Trash type, and has no restore endpoint. Base
columns preserve the tombstone and last actor in the database, but normal users cannot see the
original transaction or why it was voided.

This is weaker than an accounting ledger, where a posted receipt remains visible and a dated,
actor-attributed reversal/void entry explains the correction.

**Evidence:**

- `src/main/java/com/crm/travelcrm/booking/entity/BookingPayment.java:23-39`
- `src/main/java/com/crm/travelcrm/booking/service/BookingPaymentServiceImpl.java:109-143`
- `src/main/java/com/crm/travelcrm/booking/repository/BookingPaymentRepository.java:14-18`
- `src/main/java/com/crm/travelcrm/trash/TrashableType.java:47-79`

**Recommended fix:** Replace deletion with an explicit void/reversal command requiring a reason and
financial permission. Keep both original and reversal visible in the ledger and invoice audit view.

#### BOOKING-PAYMENT-009 - Both payment views ignore the authoritative `entryType`

**Severity:** Medium  
**Status:** Open

The API explicitly returns `entryType = RECEIPT | REFUND`, but neither live view uses it. The
dedicated page infers refund direction from `paymentType`/nonexistent row status. `BookingDetails`
renders every actual refund as a green, checked, positive payment and exposes the same delete button
for it; clicking delete then fails because the backend correctly rejects refund deletion.

The user therefore sees two different interpretations of the same ledger depending on the page.

**Evidence:**

- `src/main/java/com/crm/travelcrm/booking/dto/response/BookingPaymentResponse.java:15-21`
- Frontend `src/features/bookings/pages/BookingPayments.jsx:113-123,463-512`
- Frontend `src/features/bookings/pages/BookingDetails.jsx:1966-2005`

**Recommended fix:** Create one shared payment-row component driven by `entryType`; render refunds
as money out, never show their receipt-delete action, and show `createdBy` plus service attribution.

#### BOOKING-PAYMENT-010 - Per-service payment history is disconnected end to end

**Severity:** Medium  
**Status:** Open

The backend accepts `serviceItemPublicId`, validates it, stores `serviceItemId`, and has a repository
query for service payments. Neither receipt form sends the attribution. `BookingServiceItemResponse`
does not contain payments and its service never invokes the repository query, while
`BookingServices` expects `svc.payments` and tells users to record payments from the booking ledger.

As implemented, normal UI-created receipts are always booking-level and the service payment-history
modal is always empty.

**Evidence:**

- `src/main/java/com/crm/travelcrm/booking/dto/request/CreateBookingPaymentRequest.java:49-51`
- `src/main/java/com/crm/travelcrm/booking/service/BookingPaymentServiceImpl.java:70-90`
- `src/main/java/com/crm/travelcrm/booking/repository/BookingPaymentRepository.java:20-21`
- `src/main/java/com/crm/travelcrm/booking/dto/response/BookingServiceItemResponse.java:13-45`
- Frontend `src/features/bookings/pages/BookingPayments.jsx:144-151`
- Frontend `src/features/bookings/pages/BookingDetails.jsx:1220-1226`
- Frontend `src/features/bookings/pages/BookingServices.jsx:105-132`

**Recommended fix:** Add a service-line selector to receipt entry and either include scoped payment
summaries in service responses or load them from a dedicated per-service endpoint.

#### BOOKING-PAYMENT-011 - The detailed ledger page has no supported navigation path

**Severity:** Medium  
**Status:** Open

`/BookingPayments/:id` is registered and exported, but repository-wide usage contains no button or
link that navigates to it. Users instead get the smaller embedded history on `BookingDetails`.
Maintaining two implementations has already produced contract drift: one invents statuses and
refund totals, while the other treats every row as a positive receipt.

**Evidence:**

- Frontend `src/app/router.jsx:41,374`
- Frontend `src/features/bookings/index.js:9`
- Frontend `src/features/bookings/pages/BookingDetails.jsx:1966-2023`
- Repository-wide frontend search found no `navigate`, `Link`, or `to` consumer for
  `/BookingPayments/:id`.

**Required decision:** Make the detailed ledger the single supported payment workspace and link it
from booking actions/details, or remove it and bring all required ledger behavior into one shared
BookingDetails implementation.

#### BOOKING-PAYMENT-012 - Ledger reads are unbounded and perform per-row service lookups

**Severity:** Medium  
**Status:** Open

`GET /payments` returns every live row with no paging, date range, or server summary. For each
service-attributed row, mapping performs a separate `findById`, producing an N+1 query pattern. Both
frontends also render the full history in one pass.

This remains small for ordinary bookings, but imports, long-running corporate accounts, or repeated
adjustments/refunds can make response time and DOM work grow linearly, with extra database round
trips for attributed rows.

**Evidence:**

- `src/main/java/com/crm/travelcrm/booking/repository/BookingPaymentRepository.java:14-24`
- `src/main/java/com/crm/travelcrm/booking/service/BookingPaymentServiceImpl.java:47-55,156-162`
- Frontend `src/features/bookings/pages/BookingPayments.jsx:452-518`
- Frontend `src/features/bookings/pages/BookingDetails.jsx:1974-2005`

**Recommended fix:** Add cursor/page-based history plus a server-derived summary. Resolve service
public IDs in one query/projection instead of one primary-key load per row.

#### BOOKING-PAYMENT-013 - Date and input validation do not match financial semantics

**Severity:** Medium  
**Status:** Open

All three payment/refund forms default dates with `toISOString()`, which uses UTC. In India, from
local midnight through 05:29 this pre-fills the previous business date. Date-only display is also
parsed through `new Date('YYYY-MM-DD')`, which can show the prior day in negative UTC offsets.

The receipt backend accepts a future `paymentDate` and immediately updates collection/commission.
The frontend sets `min="0"` even though zero is invalid, does not constrain two-decimal scale or
backend maximum lengths, and permits future dates. A locally accepted request can therefore fail at
the API, or a future-dated receipt can count as money received today.

**Evidence:**

- Frontend `src/features/bookings/pages/BookingPayments.jsx:34-36,63,342-350,375-416`
- Frontend `src/features/bookings/pages/BookingDetails.jsx:1206-1210,1253-1281`
- Frontend `src/features/bookings/components/RefundBookingModal.jsx:38`
- Backend `src/main/java/com/crm/travelcrm/booking/dto/request/CreateBookingPaymentRequest.java:19-46`
- Backend `src/main/java/com/crm/travelcrm/booking/dto/request/RefundBookingRequestDTO.java:20-46`

**Recommended fix:** Use a shared local-date utility, parse date-only values without UTC conversion,
decide whether future receipts are forbidden, and align input `min`, scale, maximum, and length
constraints with the API.

#### BOOKING-PAYMENT-014 - Three receipt write contracts can drift and lose receipt detail

**Severity:** Medium  
**Status:** Open

Receipts can enter through ledger `POST`, legacy incremental `PATCH /payment`, or the general booking
`PUT` with an absolute paid target. The live edit-booking page uses the third route and presents
`Amount Paid` as an ordinary editable booking field. An increase creates a generic `Payment
adjustment` dated on the server today with no payment method or reference.

The legacy PATCH uses different field names/limits and also cannot capture a method. This means the
same collection has different metadata depending on where it was entered, and future validation or
lifecycle fixes must be duplicated across three services.

**Evidence:**

- `src/main/java/com/crm/travelcrm/booking/controller/BookingController.java:130-139`
- `src/main/java/com/crm/travelcrm/booking/service/BookingServiceImpl.java:793-823,1106-1145,1173-1189`
- `src/main/java/com/crm/travelcrm/booking/dto/request/PaymentUpdateRequestDTO.java:17-37`
- `src/main/java/com/crm/travelcrm/booking/dto/request/UpdateBookingRequestDTO.java:43-48`
- Frontend `src/features/bookings/pages/EditBooking.jsx:219-240,398-410`
- Frontend `src/features/bookings/api/bookingService.js:43-46,110-120`

**Recommended fix:** Make ledger POST the only receipt command. Remove paid-amount editing from the
general form and deprecate PATCH after migrating callers; every receipt should carry the same
idempotency, date, method, reference, evidence, permission, and lifecycle rules.

#### BOOKING-PAYMENT-015 - Historical partial-refund vouchers cannot be retrieved individually

**Severity:** Medium  
**Status:** Open

Every partial refund issues a distinct numbered voucher and the response exposes its document
public ID. The only download route is booking-level and always queries the newest refund voucher.
After a second payout, there is no endpoint or ledger link for downloading the first voucher by its
document/payment ID.

**Evidence:**

- `src/main/java/com/crm/travelcrm/booking/cancellation/service/BookingRefundServiceImpl.java:132-145`
- `src/main/java/com/crm/travelcrm/booking/cancellation/service/CancellationDocumentServiceImpl.java:93-112,128-137`
- `src/main/java/com/crm/travelcrm/booking/cancellation/repository/BookingDocumentRepository.java:16-20`
- Frontend `src/features/bookings/components/RefundBookingModal.jsx:91-104,156-164`

**Recommended fix:** Add a document-public-ID download route scoped through the booking access guard
and link each `REFUND` ledger row to its exact voucher.

#### BOOKING-PAYMENT-016 - Database constraints and aggregate purge do not protect ledger integrity

**Severity:** Medium  
**Status:** Open

The database column is numeric but has no CHECK enforcing a positive payment amount, and
`entry_type` remains nullable. `booking_id` and `service_item_id` are logical references with no
foreign keys. Application validation covers supported requests, but imports, scripts, or a future
writer can persist invalid rows.

Hard-deleting/purging a booking does not cascade its payment rows because `Booking` has no child
relationship. The rows survive as inaccessible orphans containing financial references and notes.
Deleting a service line likewise leaves attribution behind; the current primary-key lookup can
still resolve a soft-deleted service because it deliberately bypasses the soft-delete filter.

**Evidence:**

- `src/main/java/com/crm/travelcrm/booking/entity/BookingPayment.java:23-88`
- `src/main/resources/db/migration/V1__baseline_schema.sql:39`
- `src/main/java/com/crm/travelcrm/trash/TrashServiceImpl.java:119-157`
- `src/main/java/com/crm/travelcrm/booking/service/BookingServiceItemServiceImpl.java:97-104`
- `src/main/java/com/crm/travelcrm/booking/service/BookingPaymentServiceImpl.java:156-162`

**Recommended fix:** Add money/direction CHECK constraints and define booking-child archival/purge as
one aggregate policy. Prefer retaining financial ledgers under an anonymized archived booking over
uncontrolled orphaning.

#### BOOKING-PAYMENT-017 - Payment/refund behavior has no automated feature coverage

**Severity:** Medium  
**Status:** Open

There is no `BookingPaymentServiceImpl` or `BookingRefundServiceImpl` test. The frontend has no test
script/framework and no booking-payment tests. The highest-risk cases are unpinned: receipt vs
refund direction, ignored statuses, terminal-state mutation, owner scope, sequential/concurrent
idempotency, commission effects, optimistic-lock rollback, delete reversal, per-service attribution,
and partial-voucher retrieval.

The architecture test exempts `BookingPaymentServiceImpl.findById()` based on an internal-ID safety
argument, but no feature test verifies that assumption or the soft-deleted service behavior.

**Evidence:**

- Backend `src/test/java/com/crm/travelcrm/arch/TenantIsolationArchTest.java:65-78`
- No payment/refund service tests found under backend `src/test/java`.
- Frontend `package.json:6-10,30-39`
- No frontend `*.test.*`, `*.spec.*`, or `__tests__` payment files found.

**Recommended fix:** Add backend unit/integration tests around all money and access-control branches,
plus frontend contract tests that assert payloads and render behavior from `entryType`.

#### BOOKING-PAYMENT-018 - Payment and refund interactions are not keyboard/dialog accessible

**Severity:** Medium  
**Status:** Open

The add-payment and refund overlays lack dialog semantics, labelled title association, initial
focus, focus trap/restore, and Escape handling. Several icon-only actions have no accessible name,
and receipt delete controls are hidden with hover-only opacity, making them difficult or invisible
on touch and keyboard-only navigation.

**Evidence:**

- Frontend `src/features/bookings/pages/BookingDetails.jsx:1236-1294,1991-2003`
- Frontend `src/features/bookings/components/RefundBookingModal.jsx:114-221`
- Frontend `src/features/bookings/pages/BookingPayments.jsx:231-251,497-512`

**Recommended fix:** Use the shared accessible dialog primitive, label icon buttons, expose actions
on focus/touch, and implement focus management plus Escape/scroll-lock behavior.

### Verification performed

- Backend focused architecture verification: `TenantIsolationArchTest` completed with **2 tests
  passed, 0 failures, 0 errors**. The test confirms the repository-wide primary-key rule but carries
  an explicit exemption for the payment service's internal service-item lookup.
- Previous compile in this audit session compiled **1,291 production Java sources successfully**.
- Frontend focused ESLint completed with **0 errors and 10 warnings** across `BookingPayments.jsx`,
  `BookingDetails.jsx`, `RefundBookingModal.jsx`, and `bookingService.js`. Eight warnings are the
  repeated render-local `Row` component in the refund modal; one warning each flags effect-triggered
  synchronous state updates in the two pages.
- The production frontend build had already completed successfully in this audit session with Vite
  8.0.14 and **3,109 modules transformed**.
- Missing verification: payment/refund service tests, controller authorization tests, database
  persistence/concurrency tests, UI component tests, and PostgreSQL migration/constraint validation.

### Delivery state observed

- The core backend payment/refund Java files are tracked.
- Frontend `src/features/bookings/api/bookingService.js` is modified but not committed; the payment
  pages/components themselves are tracked.
- Backend `src/main/resources/db/indexes.sql` is modified for unrelated active work, and the entire
  `src/main/resources/db/migration/` directory is currently untracked. Preserve and review those
  user changes before release; this audit did not alter them.
- This audit changed only this findings document. No backend or frontend product source was fixed.
