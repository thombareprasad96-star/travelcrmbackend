# Minimum MVP — 99.99% Quality Release Plan

**Audit date:** 2026-08-03  
**Product:** TravelCRM Backend  
**Scope:** Minimum production-ready CRM MVP  
**Related evidence:** [`BUG_LIST.md`](./BUG_LIST.md)

## 1. Objective

Release the smallest useful Travel CRM that is secure, tenant-safe, financially correct, recoverable, and operationally supportable.

“99.99% bug-free” cannot be guaranteed or measured literally. For this project, it means:

- zero known Blocker, Critical, or High-severity defects at release;
- all critical business journeys pass automated integration and end-to-end tests;
- no cross-tenant or unauthorized data access in the permission matrix;
- money, cancellation, refund, invoice, and quota operations remain correct under retries and concurrency;
- deployment, database migration, backup restore, monitoring, and rollback are proven before production.

This quality target is different from **99.99% availability**. A 99.99% availability SLO allows approximately 52.6 minutes of downtime per year and normally requires redundant infrastructure, automated failover, and production monitoring. A single VPS deployment should not claim that SLO.

## 2. Current release status

**Decision: NO-GO. The current working tree is not deployable.**

Verified on 2026-08-03:

- `mvnw.cmd test` fails during production compilation. `BookingServiceImpl` uses `BookingCancelledEvent` without importing it.
- The project contains approximately 1,460 production Java sources, 127 controllers, 76 service implementations, and 117 repositories.
- Only 43 test classes are present. There are no MockMvc/security endpoint tests and only one `@SpringBootTest` context test.
- CI performs compilation and schema validation but does not execute the full test suite.
- The Docker image is built with tests skipped.
- The working tree contains a large, mixed change set: 91 tracked files changed, over 5,300 inserted lines, and substantial untracked Fleet, Hotel Marketplace, Booking, and Customer code.
- Portal email/SMS OTP senders still log plaintext OTP values instead of delivering them.
- Portal identity resolution selects the first matching customer by database ID when the same identifier exists in more than one tenant.
- Booking Reminder endpoints rely on general authentication but do not enforce a dedicated permission and row-ownership policy.

The detailed repository findings and repair recommendations are maintained in [`BUG_LIST.md`](./BUG_LIST.md).

## 3. Minimum MVP scope

### 3.1 Included modules

The minimum CRM MVP includes only:

1. Authentication, tenant isolation, user management, permissions, and plan/module entitlements.
2. Company configuration and the minimum master data required to prepare quotations.
3. Lead capture, assignment, follow-up, and lead lifecycle.
4. Customer resolution and customer history.
5. Quotation creation, versioning, PDF generation, and secure public sharing.
6. Lead/quotation conversion to booking.
7. Booking lifecycle, service items, payments, expenses, cancellation, and refunds.
8. Minimum accounting required for GST/TCS, invoice totals, and booking profitability.
9. Vendor master data required by quotations and bookings.
10. In-app notifications and operational reminders.
11. Trash/restore behavior for MVP entities.
12. Database migration, audit logging, health checks, backups, monitoring, and rollback.

### 3.2 Excluded or disabled modules

The following must be hidden behind entitlements/feature flags or removed from navigation and public routes for the first MVP:

- Fleet / Vehicle Diary, including Fleet Standalone;
- Hotel Marketplace;
- Traveler Portal;
- Disha AI;
- marketing automation and drip campaigns;
- online payment gateway flows;
- e-invoice/IRN integration;
- advanced reports and dashboards;
- external lead-source integrations that are not required by the pilot;
- live tracking, trip memories, referral/earn, and other documented future portal features.

A deferred feature must return a clear unavailable/disabled response or have no reachable endpoint. It must not appear operational while using a logging or no-op stub.

## 4. Module work plan

| Priority | Module group | Required work | MVP acceptance criteria |
|---|---|---|---|
| P0 | Build and repository hygiene | Fix compilation; remove accidental temporary artifacts; split the mixed working tree into reviewable changes; keep generated/local files out of commits. | Clean checkout compiles and packages reproducibly on Windows and Linux. |
| P0 | Database and configuration | Establish Flyway as the reviewed schema owner; validate clean install and existing-schema adoption; disable unsafe SQL init/seeding in production; test rollback and restore. | Clean DB migration, upgrade rehearsal, schema validation, and backup restore all pass. |
| P0 | Auth, tenant, permission, entitlement | Test every role and tenant boundary; prevent inactive/deleted login; enforce module access consistently; rotate committed secrets; verify rate limits and token invalidation. | No cross-tenant access; every protected route has an explicit expected permission result. |
| P0 | Lead and reminders | Make lead creation plus optional follow-up atomic; enforce assignment scope; test retries, deduplication, and reminder failure. | A lead cannot commit with an expected follow-up missing; retries do not duplicate the lead. |
| P0 | Customer | Define deterministic tenant-aware matching; normalize phone/email consistently; test duplicates, merge/resolve rules, and lead conversion. | A contact can never resolve to a customer from another tenant. |
| P0 | Quotation | Test create/update/version/PDF/share flow; protect capability links; make public-view analytics concurrency-safe; make number generation collision-safe. | Parallel quotation creation produces unique numbers and stable financial/PDF output. |
| P0 | Booking | Fix current compilation failure; test direct create and lead conversion; enforce valid status transitions; test snapshots, cancellation, restore, and event behavior. | The complete quotation-to-booking journey passes and rollback leaves no partial state. |
| P0 | Booking finance and accounting | Use decimal money end-to-end; test GST/TCS/profit, payment allocation, expenses, cancellation retention, refund, invoice numbering, and rounding. | Money invariants hold under partial payments, retries, rollback, and concurrent requests. |
| P0 | Notification and provider delivery | Publish only after commit; use a durable outbox/job boundary for external delivery; add idempotency, retry limits, and dead-letter visibility; prevent OTPs/secrets in logs. | Rollback sends nothing; retries never send a business message twice; failures are observable. |
| P0 | CI/CD and operations | Run all tests in CI and before Docker packaging; add PostgreSQL integration tests, coverage reporting, dependency/security scanning, smoke tests, metrics, alerts, and rollback. | A failing test prevents image publication and deployment; production health and rollback are verified. |
| P1 | Master and vendor | Standardize public IDs; validate references; test delete/restore guards and tenant scope; complete or remove stub operations. | Quotation/booking master references remain valid after updates and soft deletion. |
| P1 | Quotas, storage, and identifiers | Replace check-then-create limits with atomic reservation/locking; reclaim replaced/deleted Cloudinary assets; reconcile remote and local storage. | Parallel requests cannot exceed limits or reuse business numbers; deleted assets release quota. |
| P1 | Trash and audit | Verify supported entity registry, restore conflicts, purge retention, and actor attribution. | Delete/restore/purge is tenant-safe, auditable, and preserves required financial records. |

## 5. Critical business journeys

The following journeys are mandatory release tests:

1. Tenant creation → administrator activation → staff login → permission assignment.
2. Lead creation with follow-up → assignment → stage changes → activity history.
3. Lead → customer resolution → quotation → quotation version/PDF/share.
4. Accepted quotation → booking conversion → service item/vendor assignment.
5. Booking payment → expense → GST/TCS/profit calculation → invoice/PDF.
6. Booking cancellation → retention calculation → refund/amount owed → cancellation document.
7. Soft delete → trash listing → restore, including reference conflicts.
8. Two tenants using similar customer data without any data crossover.
9. A sub-agent/user attempting every core operation inside and outside their ownership scope.
10. Transaction rollback during booking, payment, notification, and follow-up creation.
11. Duplicate/retried API requests without duplicate payments, bookings, notifications, or provider calls.
12. Parallel creation at quota and business-number boundaries.

## 6. Test strategy

### 6.1 Unit tests

Required for pure calculations and state rules:

- GST, TCS, profit, cancellation, refund, and rounding;
- booking and lead status-transition rules;
- permission/scope decisions;
- identifier normalization and customer matching;
- quota and number-allocation rules;
- notification retry and idempotency decisions.

### 6.2 PostgreSQL integration tests

Use an isolated PostgreSQL Testcontainer or equivalent disposable database for:

- repositories and tenant filters;
- Flyway clean migrations and existing-schema upgrade rehearsals;
- unique constraints and optimistic/pessimistic locking;
- transactional rollback and after-commit events;
- parallel quota, number generation, analytics, and payment operations.

No test may connect to a developer or production-like persistent database by default.

### 6.3 API security and contract tests

Add MockMvc or full HTTP tests covering:

- anonymous, staff, tenant admin, sub-agent, traveler, and super-admin realms;
- every controller’s expected permission and ownership behavior;
- validation failures and stable error envelopes;
- public UUID versus internal database ID contracts;
- module entitlement disabled/enabled behavior;
- sensitive-field filtering, especially profit, vendor cost, secrets, and traveler documents.

### 6.4 End-to-end and smoke tests

Run the critical business journeys against the production-like staging image. After deployment, verify login, lead creation, quotation PDF, booking conversion, health checks, database connectivity, and one read-only tenant-scoped request.

Coverage percentage is a diagnostic, not proof of correctness. Critical modules should have branch-focused tests for business rules and mutation testing should be considered for financial calculators.

## 7. CI/CD release policy

The required pipeline order is:

1. Compile with warnings reviewed.
2. Unit tests.
3. PostgreSQL integration and security tests.
4. Flyway migrate and validate on a clean database.
5. Existing-schema upgrade rehearsal.
6. Static analysis, dependency vulnerability scan, and secret scan.
7. Package the application without skipping tests.
8. Build the immutable Docker image.
9. Deploy to staging and run smoke/end-to-end tests.
10. Manual production approval.
11. Production deploy, health verification, and short canary observation.
12. Automated rollback when health or smoke checks fail.

No Docker image should be published from a commit whose test suite was skipped or failed.

## 8. Release gates

Production release requires all of the following:

- [ ] Clean checkout builds successfully with JDK 21.
- [ ] Full automated suite passes repeatedly with no flaky tests.
- [ ] Zero open Blocker, Critical, or High bugs.
- [ ] Critical business journeys pass in staging.
- [ ] Tenant and permission matrix passes for all MVP routes.
- [ ] Money, retry, rollback, idempotency, and concurrency tests pass.
- [ ] No real secret or OTP appears in source control, artifacts, or logs.
- [ ] Deferred modules are unreachable or explicitly disabled.
- [ ] Clean migration and existing-schema upgrade both pass.
- [ ] Production backup is restore-tested.
- [ ] Monitoring covers availability, HTTP 5xx, latency, DB pool, failed jobs, notification failures, disk, memory, and backup age.
- [ ] Alert recipients and incident ownership are assigned.
- [ ] Previous image and database rollback procedure are rehearsed.
- [ ] Product owner signs off on the exact included/excluded scope.

## 9. Recommended execution order

1. **Stabilize:** freeze features, fix compilation, clean the working tree, and make tests mandatory in CI.
2. **Secure foundation:** database lifecycle, production configuration, authentication, tenant isolation, permissions, and entitlements.
3. **Prove the funnel:** Lead → Customer → Quotation → Booking, including reminders and PDFs.
4. **Prove the money:** payment, expense, tax, profit, cancellation, refund, and invoice invariants.
5. **Make side effects reliable:** notifications, email/WhatsApp, OTP, storage, outbox, retry, and idempotency.
6. **Operationalize:** staging soak, load/concurrency tests, monitoring, backup restore, deployment, and rollback rehearsal.

Fleet, Hotel Marketplace, Traveler Portal, AI, Marketing, and advanced reporting should begin only after the core MVP satisfies every release gate above.

## 10. Definition of done

The minimum MVP is done when a new tenant can be onboarded and complete the entire lead-to-cash journey without manual database repair, unauthorized access, incorrect money, duplicate side effects, or an unrecoverable deployment failure—and the automated suite continuously proves those guarantees for every change.
