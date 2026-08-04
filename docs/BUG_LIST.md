# Travel CRM Backend — Repository-Wide Bug List

**Audit date:** 2026-07-31  
**Audited state:** Current working tree, including uncommitted changes  
**Scope:** 1,279 production Java sources, 115 REST controllers, 149 services, configuration, security chains, persistence mappings, two Flyway migrations, deployment material, and 29 test source files

## Validation summary

- `.\mvnw.cmd test` compiled the production sources and executed 259 tests.
- 258 tests passed and `TravelcrmApplicationTests.contextLoads` errored during startup.
- The default context failure reproduces a circular dependency between Flyway and `entityManagerFactory`.
- A focused run with SQL initialization deferred settings disabled passed that point and then correctly reached Flyway's existing-schema adoption guard. This confirms that the first failure is configuration ordering, not a compiler or database-connectivity failure.
- Repository architecture tests for tenant isolation and notification-realm separation passed.

This is a static and automated repository audit, not a penetration test against a deployed environment. Findings below are limited to issues with direct code/configuration evidence; speculative design preferences are excluded.

## Severity definitions

| Severity | Meaning |
|---|---|
| Blocker | Prevents a supported build, boot, or deployment path from working. |
| Critical | Can expose credentials or cross tenant/customer boundaries, or makes a security-critical feature unusable. |
| High | Authorization, billing-entitlement, delivery-integrity, or major data-integrity failure. |
| Medium | Reliability, concurrency, operational, or test-safety defect with a bounded blast radius. |
| Low | Correctness or maintainability defect with limited immediate impact. |

## Prioritized summary

| ID | Severity | Area | Finding |
|---|---|---|---|
| BUG-001 | Blocker | Boot / Flyway | Repository defaults create a Flyway/JPA circular dependency and contradict the production validator. |
| BUG-002 | Critical | Portal authentication | Email/SMS OTP “senders” only log plaintext OTPs; travelers receive nothing. |
| BUG-003 | Critical | Tenant isolation | Portal login silently selects the lowest-ID customer when an identifier exists in multiple tenants. |
| BUG-004 | High | Authorization | Booking-reminder endpoints have no permissions or sub-agent row scope and can send fabricated reminders. |
| BUG-005 | High | Entitlements | Paid `PORTAL`, `DISHA_AI`, and WhatsApp capabilities are not consistently hard-gated. |
| BUG-006 | High | Notifications | Notifications are emitted before commit, can create phantom SSE events, and clear the publisher's tenant context. |
| BUG-007 | High | External messaging | WhatsApp calls occur inside database transactions without an idempotency/outbox boundary. |
| BUG-008 | High | Identifiers | Count/read-then-increment business numbers collide under concurrency; quotation numbers can also be reused after deletion. |
| BUG-009 | Medium | Storage | Cloudinary assets and metering rows are never reclaimed by application flows. |
| BUG-010 | Medium | Plan quotas | User, lead, booking, and storage caps use race-prone check-then-create logic. |
| BUG-011 | Medium | Analytics | Concurrent quotation-link views lose increments or discard one insert. |
| BUG-012 | Medium | Tests | The context test loads developer-local configuration and can migrate/seed a real local database. |
| BUG-013 | Medium | Email notifications | `@Async` is bypassed by self-invocation, so email retries block the publishing thread. |
| BUG-014 | Low | Money | Booking-reminder amounts use binary floating point instead of decimal money. |
| BUG-015 | Medium | Leads / follow-up | Create Lead saves the lead and its requested follow-up reminder in separate, non-atomic requests. |

---

## BUG-001 — Incompatible Flyway, SQL-init, and JPA boot defaults

**Severity:** Blocker  
**Confidence:** Confirmed by test

### Evidence

- `src/main/resources/application.properties:61` defaults Hibernate to `validate`.
- `src/main/resources/application.properties:79` defaults Flyway to enabled.
- `src/main/resources/application.properties:112` defaults SQL initialization to `always`.
- `src/main/resources/application.properties:115` sets `spring.jpa.defer-datasource-initialization=true`.
- `src/main/java/com/crm/travelcrm/common/config/ProductionConfigValidator.java:180-188` rejects Flyway unless Hibernate is `validate`/`none` and SQL initialization is `never`.
- `src/main/resources/application-prod.properties` does not override SQL initialization or deferred initialization.
- `deploy/travelcrm.env.example:118-140` leaves the required Flyway/JPA/SQL-init values commented out.
- `docs/DEPLOYMENT.md:260-262` still says Flyway defaults off and Hibernate `update` plus `indexes.sql` remains active, which no longer matches the code.

### Impact

The repository-default Spring context cannot start:

```text
Circular depends-on relationship between 'flyway' and 'entityManagerFactory'
```

A production-profile deployment without an explicit `SQL_INIT_MODE=never` override is separately refused by `ProductionConfigValidator`. Operators are given conflicting defaults and documentation, so a normal deploy path is not reproducible from the repository.

### Recommended fix

Choose one schema owner and move all related settings together:

1. For the completed Flyway cutover, default SQL init to `never` and deferred datasource initialization to `false`.
2. Keep Hibernate at `validate`.
3. Use `baseline-on-migrate` only for the documented one-time existing-schema adoption.
4. Make the deploy environment example executable rather than contradictory comments.
5. Update all legacy `ddl-auto=update`/Flyway-off statements in `docs/DEPLOYMENT.md`.
6. Add clean-database and adopted-database context tests.

---

## BUG-002 — Portal OTP codes are logged instead of delivered

**Severity:** Critical  
**Confidence:** Confirmed by code path

### Evidence

- `TravelerAuthServiceImpl.requestOtp` always requests `OtpChannel.AUTO` at `src/main/java/com/crm/travelcrm/portal/auth/service/TravelerAuthServiceImpl.java:92-93`.
- `AUTO` resolves an email-looking identifier to the email sender and all other identifiers to the SMS sender.
- `src/main/java/com/crm/travelcrm/otp/delivery/EmailOtpSender.java:19-21` logs the destination and plaintext code at INFO level; it sends no email.
- `src/main/java/com/crm/travelcrm/otp/delivery/SmsOtpSender.java:19-21` does the same for SMS.
- The public controller nevertheless returns “a one-time code has been sent” at `PortalAuthController.java:29-34`.

### Impact

Normal travelers cannot receive a portal login code, so the portal login flow is unusable. At the same time, a valid authentication credential is written in plaintext to application logs. Anyone with log access can use the code during its validity window.

### Recommended fix

Replace both stubs with real delivery adapters before exposing portal login. Never log the code or full destination. Fail the request internally when delivery fails while keeping the public response enumeration-safe, and add an integration test using a capturing fake sender that asserts no OTP appears in logs.

---

## BUG-003 — Ambiguous traveler identity can resolve to the wrong tenant

**Severity:** Critical  
**Confidence:** Confirmed by repository contract

### Evidence

- Portal request and verification payloads contain only `identifier` (plus `otp` for verification):  
  `src/main/java/com/crm/travelcrm/portal/auth/dto/OtpRequestDto.java:6-10` and `OtpVerifyDto.java:6-13`.
- `TravelerAuthServiceImpl.resolveCustomer` performs a deliberately cross-tenant lookup at lines `138-143`.
- `CustomerRepository.java:66-72` returns `findFirst...OrderByIdAsc` for matching phone or email.
- Customer phone uniqueness is tenant-scoped, not global (`uk_customer_tenant_phone`), so the same identifier is valid in multiple organizations.
- The OTP store key contains only purpose and normalized destination at `src/main/java/com/crm/travelcrm/otp/OtpKeyBuilder.java:8-10`.

### Impact

When the same email/phone exists in two tenants, the earliest internal customer row always wins. A traveler cannot select the other agency account. With shared or recycled contact details, the current holder can be issued a token for the wrong tenant/customer and see that customer's bookings and documents.

### Recommended fix

Require a tenant discriminator such as organization code, branded portal hostname, or tenant-specific invitation token. Resolve `(tenant, identifier)` explicitly, include tenant/account identity in the OTP key, and reject ambiguous legacy requests rather than selecting an arbitrary row.

---

## BUG-004 — Booking reminders lack permission and ownership enforcement

**Severity:** High  
**Confidence:** Confirmed

### Evidence

- `src/main/java/com/crm/travelcrm/bookingreminder/controller/BookingReminderController.java:29-92` exposes list, read, create, update, delete, status, and send-now routes without `@PreAuthorize`.
- The controller comment at lines `16-20` relies only on the catch-all authenticated rule.
- `ModuleAccessFilter` checks the tenant's `BOOKINGS` entitlement, not the user's permission or row scope.
- `BookingReminderService.java:36-75` lists all reminders for the tenant; no sub-agent/owner filter is applied.
- Creation at lines `80-95` copies client-supplied booking code, customer name, phone, destination, and amount without resolving a real tenant booking/customer.
- `sendNow` sends to the stored phone at lines `137-162`.
- Responses and mutation paths expose enumerable internal `Long` IDs (`BookingReminderResponseDto.java:16`, controller lines `52-91`).

### Impact

Any authenticated staff user in a tenant with the booking module—including a low-privilege or sub-agent account—can:

- read every reminder's customer name, phone, destination, and amount;
- fabricate a reminder unrelated to any booking;
- send an outbound WhatsApp message to its supplied phone;
- alter statuses or delete other users' reminders.

### Recommended fix

Add read/create/update/delete/send authorities (or a dedicated reminder permission family), apply the existing sub-agent ownership scope, link reminders to a tenant-validated booking by public ID/FK, derive customer/contact snapshots server-side, and replace public numeric IDs with UUID public IDs.

---

## BUG-005 — Paid module entitlements are not consistently enforced

**Severity:** High  
**Confidence:** Confirmed

### Evidence

- Only Enterprise includes `PORTAL` and `DISHA_AI` in `PlanCatalogueInitializer.java:74-80`.
- `PortalSecurityConfig.java:40-60` creates a separate `/api/portal/**` security chain but never installs `ModuleAccessFilter` or calls `TenantEntitlementService`.
- No portal service references the `PORTAL` module.
- `ChatController` is mapped at `/ai/chat`, outside the `/api/**` paths recognized by `ModuleAccessFilter`; it requires only `isAuthenticated()` at `ChatController.java:42-45`.
- Disha is off in the production profile today, but enabling it immediately exposes it to every authenticated tenant.
- `/api/booking-reminders/**` is gated only as `BOOKINGS` at `ModuleAccessFilter.java:47-48`; its send-now route uses WhatsApp even when the tenant lacks the `WHATSAPP` module.

### Impact

Feature flags are partly UI hints rather than hard billing controls. A Starter/Pro tenant can use portal endpoints despite not owning `PORTAL`; re-enabled AI can consume provider quota for tenants without `DISHA_AI`; and booking reminders can use WhatsApp under the booking entitlement.

### Recommended fix

Enforce entitlements in every security chain and on nonstandard paths. Add explicit mappings/guards for `PORTAL` and `DISHA_AI`, and require both the business-module and delivery-channel entitlement where an endpoint sends WhatsApp.

---

## BUG-006 — Notification delivery occurs before commit and corrupts ambient tenant context

**Severity:** High  
**Confidence:** Confirmed

### Evidence

- `NotifyEventListener.java:21-39` uses synchronous `@EventListener`, sets the event tenant, and unconditionally calls `TenantContext.clear()` instead of restoring the caller's previous value.
- Many transactional services publish `NotifyEvent` inline, including customer creation (`CustomerServiceImpl.java:126-138`) and lead updates (`LeadServiceImpl.java:339-349`).
- `InAppNotificationChannel.java:46-71` joins the caller transaction with `Propagation.REQUIRED`, saves the row, then pushes it over SSE immediately.
- Line `70` claims the row is committed, but it is still inside the surrounding transaction.

### Impact

- A client can receive an SSE notification for a transaction that later rolls back; refreshing then shows no notification or referenced entity.
- A notification persistence failure can mark the business transaction rollback-only even though the listener catches the exception.
- Code continuing after publication sees `TenantContext` as null, weakening later tenant checks and causing failures in operations that require it.

### Recommended fix

Publish after commit with `@TransactionalEventListener(phase = AFTER_COMMIT)` or a durable outbox. Run notification persistence in a clearly isolated transaction, push SSE only after that commit, and always save/restore—not clear—the previous tenant context around scoped work.

---

## BUG-007 — WhatsApp provider calls are inside non-idempotent database transactions

**Severity:** High  
**Confidence:** Confirmed

### Evidence

- `WhatsAppMessagingService.sendTemplate` and `sendPurpose` are transactional at lines `72-82` and `89-101`.
- The irreversible provider call occurs before the message audit row is recorded at lines `123-143`.
- `BookingReminderService.sendNow` opens another transaction, calls the provider, and only then changes the reminder to `Sent` at lines `137-162`.
- No idempotency key, outbox record, or provider-message identifier protects the operation.

### Impact

If the provider accepts a message but the message-log insert, reminder update, or outer transaction commit fails, the database says the message was not sent. Retrying sends a duplicate. Slow provider calls also hold database transactions/connections open.

### Recommended fix

Commit a uniquely keyed outbound-message/outbox row first, deliver outside the business transaction, persist the provider message ID/result in a new transaction, and make retries idempotent. Do not perform network I/O while holding a database transaction.

---

## BUG-008 — Business-number generation is race-prone and can reuse numbers

**Severity:** High  
**Confidence:** Confirmed

### Evidence

- Root quotation number is `count(active root quotations) + 1` at `QuotationServiceImpl.java:91-106`.
- Quotation version number is also derived from a live-row count at lines `91-99`.
- The baseline has no uniqueness constraint for `(tenant_id, quote_no)` or quotation-family version.
- Customer code reads the current highest row then adds one at `CustomerCodeGenerator.java:29-38`.
- Vendor code reads the current highest row then adds one at `VendorCodeGenerator.java:20-35`.
- SaaS billing invoice number is global `billingRepository.count() + 1` at `BillingServiceImpl.java:421-425`.
- Booking, accounting invoice, cancellation-document, and lead generators already demonstrate the correct locked sequence-row pattern elsewhere in the repository.

### Impact

Concurrent creates can assign the same customer/vendor/billing code; a unique constraint then turns one valid request into a server error. Quotations have no equivalent constraint, so duplicate customer-facing quote numbers/versions can persist silently. Deleting a non-last quotation reduces the active count and can deterministically reuse an existing quote number.

### Recommended fix

Use the existing per-tenant sequence-row design with a pessimistic lock or a database sequence/atomic upsert. Add database uniqueness constraints for quotation family number/version. Convert constraint conflicts into a bounded retry, not a raw 500.

---

## BUG-009 — Cloudinary storage cannot be reclaimed through application flows

**Severity:** Medium  
**Confidence:** Confirmed

### Evidence

- Every upload is metered in a separate committed transaction at `CloudinaryService.java:103-116` and `StorageMeterImpl.java:27-45`.
- `CloudinaryService.deleteImage` removes the remote object and metering row at lines `119-129`.
- `deleteImage` has no production call sites.
- Upload-first endpoints return a URL that the caller may never attach to an entity.
- Replacement flows such as company logo/favicon (`CompanyService.java:67-78`) upload a new asset without deleting the prior one.
- `StorageQuotaGuard.java:49-56` sums all metering rows to block future uploads.

### Impact

Abandoned uploads, replaced logos/images, deleted master records, and failed outer transactions leave billable remote objects and storage rows indefinitely. Tenants eventually hit a storage cap they cannot reclaim through normal deletion/update operations.

### Recommended fix

Track asset ownership/reference state, delete or detach the old asset on replacement/deletion, add cleanup for unattached uploads, and reconcile Cloudinary against `tenant_storage_assets`. Compensate when a database operation fails after upload.

---

## BUG-010 — Plan quota checks are vulnerable to concurrent bypass

**Severity:** Medium  
**Confidence:** Confirmed

### Evidence

The code reads current usage and then inserts without a shared lock/reservation:

- staff seats: `UserServiceImpl.java:58-68`;
- leads: `LeadServiceImpl.java:93-97` and `593-603`;
- monthly bookings: `BookingServiceImpl.java:201-217`;
- storage bytes: `StorageQuotaGuard.java:36-57`, followed later by the upload.

There is no tenant quota row lock, atomic counter, reservation, or serializable boundary across these checks.

### Impact

Two requests just below a limit can both pass and commit, exceeding a paid plan cap. Parallel file uploads can exceed the byte allowance by the combined file sizes.

### Recommended fix

Serialize quota-changing operations per tenant or use atomic database counters/reservations with a conditional update. For storage, reserve bytes before upload and release the reservation on failure/deletion.

---

## BUG-011 — Quotation weblink analytics loses concurrent views

**Severity:** Medium  
**Confidence:** Confirmed

### Evidence

- `WeblinkAnalyticsService.java:49-71` performs a read-then-update/insert for `(tenant, quotation, IP)`.
- `QuotationWeblinkView.java` has no `@Version`.
- The database has a unique constraint on `(tenant_id, quotation_public_id, ip_address)`.
- All exceptions, including a concurrent insert conflict, are swallowed at `WeblinkAnalyticsService.java:73-75`.

### Impact

Two simultaneous views from the same IP can both read the same count and write the same increment, losing one view. On the first concurrent view, both can attempt insert; one unique-constraint failure is swallowed, again under-counting.

### Recommended fix

Use one atomic PostgreSQL upsert:

```sql
INSERT ... VALUES (..., 1)
ON CONFLICT (tenant_id, quotation_public_id, ip_address)
DO UPDATE SET view_count = quotation_weblink_view.view_count + 1,
              last_viewed_at = EXCLUDED.last_viewed_at;
```

---

## BUG-012 — Spring context tests are not isolated from developer databases

**Severity:** Medium  
**Confidence:** Confirmed by test

### Evidence

- `TravelcrmApplicationTests.java:6-11` is a bare `@SpringBootTest` with no test profile or test datasource.
- `application.properties:406` imports root `./application-local.properties`.
- Flyway and the development seeder default on at `application.properties:79` and `95`.
- After bypassing the SQL-init cycle during the audit, the test connected to the configured local PostgreSQL database and failed because its non-empty schema had no Flyway history table.

### Impact

Test results depend on a developer's machine and schema history. On a clean configured database, a context test may run migrations and seed demo data. On an existing database it fails for environmental reasons, keeping the default suite red and making CI behavior non-reproducible.

### Recommended fix

Create `application-test.properties`, activate it on the context test, disable the dev seeder, and use a disposable PostgreSQL Testcontainer (or an explicitly isolated test schema). Add separate clean-Flyway and baseline-adoption tests.

---

## BUG-013 — Email notification `@Async` is bypassed

**Severity:** Medium  
**Confidence:** Confirmed; currently latent because production publishers request only in-app delivery

### Evidence

- `EmailNotificationChannel.send` calls `sendAsync` on the same bean at `EmailNotificationChannel.java:41-47`.
- Spring proxy-based `@Async` does not apply to self-invocation.
- The method performs SMTP I/O and up to three attempts with one- and two-second sleeps at lines `61-103`.
- `NotifyEventListener` itself is synchronous.

### Impact

When any event enables `DeliveryChannel.EMAIL`, the publisher/request thread performs all SMTP sends and retry sleeps. A multi-recipient event can block for many seconds and, when published inside a business transaction, hold a database connection for that duration.

### Recommended fix

Move asynchronous delivery to a separate proxied worker bean or queue/outbox consumer. Pass explicit tenant and recipient context to the worker and remove blocking sleep retries from request threads.

---

## BUG-014 — Booking-reminder money uses `Double`

**Severity:** Low  
**Confidence:** Confirmed

### Evidence

- `BookingReminder.amount` is `Double` at `src/main/java/com/crm/travelcrm/bookingreminder/entity/BookingReminder.java:69`.
- Request and response DTOs also use `Double` at `BookingReminderRequestDto.java:44` and `BookingReminderResponseDto.java:26`.

### Impact

Binary floating-point values cannot exactly represent common decimal currency values. Formatting, equality, and message-template output can expose rounding artifacts.

### Recommended fix

Use `BigDecimal` end-to-end and a fixed database precision/scale consistent with the booking/accounting modules.

---

## BUG-015 — Create Lead follow-up scheduling is non-atomic

**Severity:** Medium
**Confidence:** Confirmed by code path

### Evidence

- The Create Lead payload includes `followUpDate`, and the lead is created first at `../travelcrmfe/travelcrmfrontend/src/features/leads/pages/CreateLead.jsx:2088-2115`.
- Only after that request succeeds, the page makes a second `addLog` request with `createReminder: true` at `CreateLead.jsx:2119-2126`.
- Failure of the second request is reduced to a warning and the already-created lead remains saved at `CreateLead.jsx:2127-2129`.
- The first request persists the date on the lead at `src/main/java/com/crm/travelcrm/lead/service/LeadServiceImpl.java:406-410`.
- The actual reminder is created only through `LeadLogServiceImpl.addLog` at `src/main/java/com/crm/travelcrm/lead/service/LeadLogServiceImpl.java:63-88` and `198-206`.

### Impact

A network interruption, expired session, permission change, or server error between the two requests leaves a lead whose follow-up date appears saved but has no corresponding log/reminder. The user can reasonably believe the follow-up was scheduled, so a sales callback can be missed. Retrying the whole form risks creating a duplicate lead instead of repairing the reminder.

### Recommended fix

Move lead creation and optional follow-up log/reminder creation behind one backend command and transaction. If reminder delivery requires asynchronous processing, commit a durable outbox/job in the same transaction. Return the complete outcome from the create endpoint and add rollback and retry/idempotency tests.

---

## Recommended repair order

1. Fix BUG-001 so the application and context suite can boot predictably.
2. Disable portal authentication externally until BUG-002 and BUG-003 are fixed.
3. Close BUG-004 and BUG-005 before enabling portal/AI or broader tenant access.
4. Introduce after-commit/outbox delivery for BUG-006 and BUG-007.
5. Replace count-based identifiers and quota checks (BUG-008 and BUG-010).
6. Repair storage lifecycle, analytics, and test isolation.
7. Address the latent email worker and decimal type cleanup.
8. Make Create Lead follow-up scheduling atomic (BUG-015).

## Regression tests to add

- Default, production, clean-database, and existing-schema context startup.
- Portal login with the same identifier in two tenants.
- An assertion that OTP codes never appear in logs.
- Permission matrix tests for every booking-reminder method, including sub-agent ownership.
- Entitlement tests for `PORTAL`, `DISHA_AI`, and WhatsApp send routes.
- Transaction rollback tests proving no SSE/provider delivery occurs before commit.
- Parallel create tests for quote/customer/vendor/billing numbers and plan caps.
- Parallel same-IP quotation-view increments.
- Upload replacement/deletion tests proving both Cloudinary and metering cleanup.
- Create Lead follow-up creation where reminder/log persistence fails, proving no partial lead state is committed.
