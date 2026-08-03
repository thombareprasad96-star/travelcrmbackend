# Future Plan — Travel CRM and B2B Marketplace

**Status:** architecture and delivery plan for review

**Audit date:** 3 August 2026

**Scope:** the current Spring Boot backend plus a future Next.js B2B marketplace

**Evidence policy:** this plan was produced from source code, migrations, runtime configuration,
tests, and CI/deployment configuration. Existing files under `docs/` were intentionally not used as
audit evidence.

---

## 1. Executive decision

The existing backend can power a Next.js B2B marketplace for tour packages, hotels,
transportation, activities, visas, insurance, cruises, and later products. It should not be expanded
by placing every new product inside `BookingServiceImpl`, `Quotation`, `FleetTrip`, or the current
hotel-specific marketplace aggregate.

The recommended direction is:

1. Keep one Spring Boot deployable and one PostgreSQL database initially.
2. Turn the codebase into a stricter modular monolith before adding several marketplace verticals.
3. Preserve CRM, Marketplace, Accounting, and Fleet as separate bounded contexts.
4. Add a generic marketplace commercial core for suppliers, listings, contracts, offers, orders,
   payments, credit, commission, and settlement.
5. Keep hotel, tour, and transportation inventory and fulfilment rules in separate vertical modules.
6. Use transactional outbox events and explicit ports between modules; do not share repositories.
7. Use Next.js for both the public SEO storefront and authenticated B2B application, but never put
   negotiated prices or private inventory in public HTML.
8. Launch with an on-request operating model first. Add instant confirmation and real-time supplier
   connectivity only after inventory, holds, payments, and reconciliation are proven.

This keeps deployment simple while preventing the backend from becoming a collection of tightly
coupled product-specific branches.

---

## 2. Recommended initial business assumptions

These assumptions keep the first release buildable. They must be confirmed with business, tax, and
finance owners before payment and invoicing work starts.

| Decision | Recommended starting position |
|---|---|
| Marketplace buyers | Existing CRM tenants, representing travel agencies or B2B buyers |
| Marketplace suppliers | New platform-level supplier organizations, not tenant `Vendor` rows |
| Confirmation | On-request/manual approval first; instant confirmation is a later capability |
| Currency | INR settlement first, while storing ISO currency on every monetary snapshot |
| Order size | One independently confirmable product item per first-release order |
| Buyer payment | Approved credit/offline settlement first; hosted gateway payment next |
| Inventory | Allotment or on-request; no promise of live inventory without a hold |
| Public pricing | Optional “starting from” price only; negotiated net/payable requires login |
| CRM projection | A confirmed marketplace item projects into a CRM Booking service line |
| Transport fulfilment | A confirmed internal-fleet order creates a Fleet fulfilment request, not a Fleet trip directly |
| Architecture | Modular monolith; no microservices until scale or team ownership proves a need |

The biggest unresolved commercial decision is whether the platform is the merchant of record or an
agent earning commission. That decision changes GST invoicing, refunds, supplier payouts, credit
risk, and revenue recognition. It must be recorded as an architecture decision before financial
tables are finalized.

---

## 3. Current backend audit

### 3.1 Repository size and delivery baseline

The working tree inspected for this plan contains:

| Measure | Current value |
|---|---:|
| Main Java files | 1,460 |
| Main Java lines | approximately 92,200 |
| Test Java files | 44 |
| Test lines | approximately 5,959 |
| Exact JPA `@Entity` declarations | 122 |
| Spring MVC controllers | 126 |
| Mapped controller methods | approximately 649 |
| Spring Data repositories | 109 |
| Scheduled methods | 17 |
| Method authorization annotations | approximately 416 |
| SQL table creation statements across active migrations | 152 |

The codebase is already a broad SaaS CRM, not a small booking API. The number of tests is low in
relation to its API and persistence surface, so adding multiple marketplace verticals without first
raising automated coverage would make changes unsafe.

### 3.2 Runtime and infrastructure

The current stack provides a solid foundation:

- Java 21 and Spring Boot 3.5.3;
- Spring MVC, Spring Security, JPA/Hibernate, Bean Validation, and AOP;
- PostgreSQL and Flyway;
- Redis dependency and a `RedisTemplate` configuration;
- JWT authentication for staff, a separate JWT realm for the traveler portal, and a separate
  SuperAdmin realm;
- Hibernate Envers, application audit entities, trace IDs, and Log4j2;
- Cloudinary-backed storage with tenant storage metering;
- SMTP, Twilio, WhatsApp integration, and SSE notifications;
- Razorpay integration for SaaS subscription billing;
- Spring AI/OpenAI support for the assistant module;
- Docker build/runtime stages and GitHub Actions for compile and schema validation.

The backend is deployed as a single executable JAR. That is appropriate for the next phase.

### 3.3 Current module map

| Area | Current modules and purpose | Marketplace relevance |
|---|---|---|
| SaaS platform | `platform`, `tenent`, `auth`, `permission`, `company`, `settings`, `onboarding` | Reuse tenant accounts, staff identity, plans, entitlements, billing shell, and audit |
| Sales CRM | `lead`, `leadsource`, `customer`, `quotation`, `quotationtemplate` | Reuse as the agency sales process; do not make it own marketplace products |
| Booking operations | `booking`, `bookingreminder`, `portal` | Reuse Booking as the agency/customer trip record and project confirmed order items into it |
| Finance | `accounting`, booking payments/expenses, platform billing | Reuse calculators and tax foundations selectively; add a marketplace subledger |
| Supplier records | `vendor` | Keep as a tenant's private CRM vendor master; do not use as the platform supplier registry |
| Product masters | `master` for hotels, vehicles, geography, cruises, sightseeing, airlines, and add-ons | Reuse tenant projections and geography carefully; these are not marketplace inventory |
| Marketplace pilot | `hotelmarketplace` | Evolve/migrate; it proves the workflow but is hotel-specific |
| Fulfilment | `fleet` | Use for internal transportation fulfilment after order confirmation |
| Partner sales | `subagent` | Keep as a subordinate B2B sales seat under one tenant; it is not a marketplace buyer organization |
| Engagement | `marketing`, `notification`, `reminder`, `task`, `calendar` | Reuse notification and operational follow-up capabilities |
| Intelligence | `report`, `activity`, `ai`, `workload` | Add marketplace read models instead of joining transactional tables from every report |
| Retention | `trash` | Define marketplace-specific retention; financial ledgers must never behave like normal trashable data |

### 3.4 Existing CRM lifecycle

The implemented revenue path is broadly:

```text
Lead ingestion/manual lead
        │
        ├── assignment, follow-up, attribution, reminders
        ▼
Customer resolution/linking
        ▼
Quotation and versions
        ▼
Booking
        ├── service items
        ├── supplier/internal expenses
        ├── payments/refunds
        ├── cancellation snapshot
        ├── tax invoice
        └── traveler portal/vouchers/documents
```

This remains the agency's CRM workflow. A marketplace order is a different business fact: it is a
purchase by a tenant from a platform supplier. The two may be linked, but they must not be the same
aggregate or share a lifecycle enum.

### 3.5 Multi-tenancy and access control

Current strengths include:

- tenant identity is populated from JWT into `TenantContext`;
- `BaseTenantEntity` carries `tenant_id` and a persistence listener blocks cross-tenant writes;
- a Hibernate tenant filter scopes generated queries inside transactional methods;
- an architecture test blocks common primary-key lookup paths that bypass Hibernate filters;
- public UUIDs are used for external APIs rather than exposing internal numeric IDs;
- method permissions and plan/module entitlements are separate controls;
- the traveler portal has a separate filter chain and signing key;
- platform rows and tenant rows are consciously distinguished.

Limitations that matter for marketplace work:

- not every tenant-associated row extends `BaseTenantEntity`; platform-owned booking and commission
  rows require explicit tenant predicates;
- the tenant filter is ambient `ThreadLocal` state and is enabled at transactional boundaries;
- scheduled jobs and cross-tenant platform operations repeatedly set and restore tenant context;
- several safeguards depend on conventions and architecture tests rather than database foreign keys
  or PostgreSQL row-level security.

New marketplace repositories must make ownership explicit in every tenant-facing query. A generic
`findByPublicId` on a platform-owned order is forbidden for buyer APIs.

### 3.6 Existing hotel marketplace pilot

The current `hotelmarketplace` module already implements useful concepts:

- a global SuperAdmin-owned hotel catalog;
- room and meal-plan definitions;
- tenant catalog search and optional import into tenant Hotel Master;
- an idempotent booking-request submission;
- a manual SuperAdmin review/revision/approval lifecycle;
- immutable commercial snapshots on the booking request;
- separate tenant and SuperAdmin DTOs to hide supplier cost and platform margin;
- voucher issue/revoke/download;
- append-only commission entries;
- CRM Booking/service-item/expense projection through a port;
- compensating retries when platform confirmation succeeds but CRM projection fails;
- optimistic locking and database uniqueness for important duplicate-write cases.

It is a strong proof of workflow, but it is not yet a general marketplace engine:

- there are no dated hotel rate plans or availability calendars;
- there are no inventory holds or atomic allocation operations;
- supplier confirmation is manual;
- the catalog's supplier link points logically toward tenant-scoped Vendor identity rather than a
  platform supplier organization;
- there is no generic Offer, Order, Order Item, Payment, Credit, Supplier Settlement, or fulfilment
  contract shared across product types;
- there are no automated tests under the hotel marketplace package in the inspected tree;
- tenant catalog routes are authenticated and there is no public SEO catalog API.

The pilot should be migrated behind the future marketplace core. It must not be copied into
`tourmarketplace` and `transportmarketplace` with renamed entities.

### 3.7 Fleet position

Fleet already covers vehicles, drivers, trips, trip legs, operating expenses, cash entries,
settlements, compliance documents, attachments, allowance policy, and period close. It also has CRM
and standalone integration ports.

That makes Fleet suitable as a transportation fulfilment destination, not as the public marketplace
catalog or order system. A marketplace transport order may be fulfilled by:

- the buyer's/internal fleet;
- a marketplace supplier's fleet;
- a tenant Vendor;
- an external aggregator.

The marketplace order must therefore depend on a `TransportFulfilmentPort`, with Fleet as only one
adapter. Directly creating `FleetTrip` from a public checkout would couple catalog availability,
buyer payment, and dispatch operations into one transaction.

Before marketplace transportation is released, existing Fleet release gates around trip
concurrency, compliance enforcement, financial truth, period reopening, cancellation cleanup, and
trash purge behavior must be closed.

### 3.8 Payments and accounting position

The current Razorpay integration is for SaaS subscription billing from tenant to platform. It is not
a marketplace checkout engine. The traveler portal payment interface is explicitly backed by an
`UNAVAILABLE` stub.

Current Booking finance includes customer amount, vendor cost, service-item cost, expense rows,
payments/refunds, taxes, internal cost, and net profit. Hotel marketplace adds supplier total,
tenant payable, customer selling amount, and a platform commission ledger.

These existing components should inform the future design, but marketplace money needs its own
immutable subledger. Reusing SaaS billing transactions or updating Booking totals as the only source
of truth would mix subscription revenue, marketplace buyer receivables, supplier payables, and the
agency's customer revenue.

### 3.9 Build and release baseline

The audit compile command was:

```text
.\mvnw.cmd -DskipTests compile
```

It currently fails at `BookingServiceImpl.java:1044` because `BookingCancelledEvent` is referenced
without being imported. The event source file exists, so this is a current working-tree integration
failure rather than proof of a missing marketplace concept. No tests can run until compilation is
green.

The compile also reports MapStruct unmapped-property warnings, Lombok builder-default warnings, and
deprecated Spring Data Specification usage.

Both the Docker build and existing GitHub Actions compile with `-DskipTests`. CI validates a fresh
Flyway schema but does not execute the 44 test classes. This is not sufficient for a financial B2B
marketplace.

### 3.10 Database migration baseline

There are only two active versioned migrations. `V2__lead_code.sql` has grown to more than 150 KB and
contains later Booking, Fleet, tenant-plan, and Hotel Marketplace changes. Its own comments describe
deleting the applied V2 history row and rerunning/re-stamping it.

An applied Flyway migration must be immutable. Fresh-database CI cannot detect that a deployed
database has a different V2 checksum. Before adding marketplace schema:

1. inventory every deployed database's Flyway history and actual schema;
2. choose one canonical V1/V2 checksum and never edit those files again;
3. reconcile drift once through an explicitly reviewed operational runbook;
4. place every future change in V3, V4, and later forward-only migrations;
5. add CI that rejects changes to already-released migration files;
6. enable Flyway explicitly in controlled environments and remove reliance on `indexes.sql` as a
   parallel schema mutation path.

Current defaults are `ddl-auto=validate`, Flyway disabled unless enabled by environment, and SQL init
set to `always`. Production must not depend on undocumented environment combinations for schema
ownership.

### 3.11 Coupling and maintainability findings

Static imports show important module cycles, including `booking ↔ accounting`, `auth ↔ platform`,
and business modules imported from `common`. The `common` package contains technical primitives but
also knows about Booking, Customer, Lead, Fleet, Master, Platform, Tenant, and Vendor types.

Large classes confirm the pressure:

| Class | Approximate lines | Risk |
|---|---:|---|
| `BookingServiceImpl` | 1,599 | booking creation, updates, money, cancellation, events, and cross-module orchestration converge |
| `LeadServiceImpl` | 808 | lead lifecycle and side effects are concentrated |
| `QuotationServiceImpl` | 757 | versioning, delivery, mapping, and state changes are concentrated |
| `QuotationMapper` | 591 | fixed vertical quotation shape is expensive to extend |
| `CustomerServiceImpl` | 582 | matching, CRUD, linkage, and notification concerns converge |
| `InvoiceServiceImpl` | 562 | tax document generation has a broad responsibility surface |

The current Quotation aggregate contains explicit hotel, flight, sightseeing, cruise, vehicle, and
add-on sections. Booking service types are free text. Adding a new fixed field and mapper branch for
every future marketplace product would make both backward compatibility and testing harder.

### 3.12 Horizontal scaling findings

The current single-instance deployment is reasonable, but it is not horizontally safe without more
work:

- scheduled jobs have no distributed scheduler lock;
- rate-limit windows are stored in process memory;
- OTP and SuperAdmin MFA challenge stores are in memory;
- entitlement, configuration, and timezone caches are local maps;
- staff and platform SSE registries are local to one JVM;
- application events are mainly synchronous and do not form a durable message boundary.

Before running multiple backend replicas, move shared ephemeral state and pub/sub to Redis, add
distributed job coordination, and use a durable outbox for business events.

---

## 4. Architecture principles for the future system

### 4.1 Modular monolith first

One repository, one build, one JAR, and one database remain the default. Module boundaries are
enforced in code and tests. A module can be extracted later only if it has:

- a stable API or event contract;
- no direct access to another module's repositories;
- independent operational scaling needs;
- enough ownership and traffic to justify deployment complexity.

Microservices are not a prerequisite for marketplace scale. Clear data ownership is.

### 4.2 Separate business identities

The following concepts must remain distinct:

| Current/future concept | Owns | Must not be treated as |
|---|---|---|
| CRM Tenant | Buyer organization and SaaS account | Marketplace supplier by default |
| CRM Vendor | One tenant's private supplier/contact master | Global platform supplier registry |
| SubAgent | Sales partner/seat operating under one tenant | Independent buyer tenant |
| Marketplace Supplier | Platform-approved seller/fulfiller | CRM Vendor row |
| Tenant Hotel Master | Agency's private reusable master/projection | Global sellable listing |
| Marketplace Product/Listing | What the platform offers to buyers | CRM quotation section |
| Marketplace Order | Tenant's purchase from the platform/supplier | CRM Booking |
| CRM Booking | Agency's customer trip and P&L | Supplier confirmation record |
| Transport Order | Commercial purchase and fulfilment request | FleetTrip |
| FleetTrip | Dispatch/operations execution | Public listing or checkout order |

Links between these records use public IDs and integration contracts. Their lifecycles remain
independent.

### 4.3 One-way module dependencies

Target direction:

```text
web/API adapters
      ↓
application use cases
      ↓
domain model and module ports
      ↓
repository/provider adapters

Marketplace ──events/ports──> CRM Booking
Marketplace ──events/ports──> Accounting
Marketplace ──fulfilment port──> Fleet
CRM/Fleet/Accounting do not import Marketplace implementation packages
```

Rules to enforce with ArchUnit:

- controllers depend on application services, not repositories;
- one module cannot import another module's entity or repository package;
- cross-module calls go through an `api`, `spi`, or event package;
- `common` may contain technical primitives only and may not import a business module;
- vertical marketplace modules depend on marketplace contracts, not each other's internals;
- platform/SuperAdmin queries and buyer/tenant queries use separate services and DTOs;
- financial, supplier-net, and internal-margin DTOs cannot be returned by buyer/public controllers.

### 4.4 Snapshot commercial facts

Catalog, rates, contract rules, cancellation policies, taxes, supplier details, and customer details
change over time. Every accepted Offer and Order Item must therefore snapshot the facts used to make
the agreement. Historical money must never be recomputed from today's catalog or pricing rules.

### 4.5 Separate state axes

Do not build one giant status enum. At minimum keep separate:

- commercial order status;
- item fulfilment status;
- payment status;
- supplier settlement status;
- cancellation/refund status;
- document/voucher status;
- CRM/Fleet projection status.

This prevents a PDF rendering failure from changing a confirmed room, or a delayed CRM projection
from making a supplier confirmation appear unconfirmed.

---

## 5. Target bounded contexts

### 5.1 Platform and identity

Continue to own tenants, users, roles, MFA, subscriptions, module entitlements, impersonation,
platform audit, usage, and SaaS billing.

Required additions:

- marketplace feature keys separate from CRM module keys;
- supplier-portal realm and users, or a documented organization-aware identity model;
- API clients/service accounts for supplier integrations;
- per-organization status, KYC state, and allowed product capabilities;
- step-up authorization for payouts, credit changes, refunds, and commercial-rule changes.

### 5.2 Marketplace partner management

New module: `marketplace.partner`.

Responsibilities:

- supplier organization profile;
- legal name, GST/PAN, address, contacts, payout identity, and KYC state;
- product capabilities such as HOTEL, TOUR, TRANSPORT, ACTIVITY;
- commercial status and suspension;
- supplier portal membership;
- buyer-supplier visibility and contract relationships;
- mapping to a tenant Vendor projection when a buyer wants that supplier in its CRM.

Sensitive bank/KYC data requires stricter permissions and encryption than public supplier profile
data.

### 5.3 Marketplace catalog

New module: `marketplace.catalog`.

Common product/listing data:

- stable public ID and SEO slug;
- product type;
- supplier ownership;
- title, summary, description, media, destinations, tags, policies, and publish state;
- public/private visibility;
- applicable markets, buyer groups, and effective dates;
- content version and publication timestamps;
- searchable attributes and a read-model projection.

Use relational tables for transactional and constrained data. JSONB may hold validated,
non-financial type-specific search attributes, but it must not become an unvalidated replacement for
rates, availability, passengers, room occupancy, or cancellation terms.

### 5.4 Contracts and pricing

New module: `marketplace.commercial`.

Responsibilities:

- supplier contracts and validity;
- buyer-specific negotiated terms;
- rate plans, seasonal/date rules, occupancy/pax bands, route/zone rules, and supplements;
- platform markup/commission rules;
- buyer credit/payment terms;
- tax inputs;
- price breakdown and immutable Offer snapshots;
- currency and rounding policy.

The pricing pipeline should be explicit:

```text
Supplier net
  + supplier taxes/mandatory charges
  - contracted supplier discount
  + platform markup/service fee
  + platform taxes
  = tenant payable

Tenant payable
  + tenant's own selling markup (CRM-owned, optional)
  = agency customer selling amount
```

Every component must have a named amount, currency, calculation basis, rule/version reference, and
visibility classification. Never derive platform margin in a frontend.

### 5.5 Availability and holds

New module: `marketplace.inventory`.

Responsibilities:

- availability source and freshness;
- dated inventory/allotment;
- request-only, allotment, and live-provider modes;
- atomic holds with expiry;
- release, commit, and reconciliation;
- overbooking protection and idempotency;
- provider-specific availability adapters.

An instant-confirmation listing may be sold only after an atomic hold succeeds. Search results are
informational; a successful search is never an inventory guarantee.

### 5.6 Offers

New module: `marketplace.offer`.

An Offer is the time-limited, buyer-visible snapshot created from product, rate, inventory, and
contract data. It includes:

- buyer tenant and optional CRM lead/booking reference;
- product and selection snapshots;
- travel/service dates;
- passenger/occupancy/route inputs;
- tenant-payable breakdown;
- cancellation terms;
- expiry and availability mode;
- quote version and idempotency key.

The client submits an Offer ID to order. It does not post a trusted payable amount.

### 5.7 Orders

New module: `marketplace.order`.

Responsibilities:

- order number and buyer ownership;
- order items and product-type snapshots;
- state-machine transitions;
- idempotent submission;
- supplier request/confirmation/revision;
- cancellation and amendment requests;
- documents and voucher references;
- CRM and fulfilment projection state;
- immutable order event history.

Recommended first-release commercial statuses:

```text
DRAFT
  → SUBMITTED
  → UNDER_REVIEW
  → BUYER_ACTION_REQUIRED
  → CONFIRMED
  → COMPLETED

SUBMITTED / UNDER_REVIEW / BUYER_ACTION_REQUIRED → REJECTED or EXPIRED
CONFIRMED → CANCEL_REQUESTED → CANCELLED
```

Payment and fulfilment states remain separate. Transitions are implemented as named use cases, not
generic `updateStatus` endpoints.

### 5.8 Payments, credit, commission, and settlement

New module: `marketplace.finance`.

Separate it from SaaS subscription billing. It owns:

- payment intents and hosted gateway references;
- buyer receipts and allocation to orders;
- buyer credit limits, utilization, releases, and adjustments;
- refunds and chargebacks;
- supplier payable entries;
- platform commission/markup entries;
- supplier payouts and payout reconciliation;
- tenant statements and aging;
- financial idempotency/reference keys.

Use append-only signed ledger entries with reversals. Mutable status may describe settlement progress,
but amounts already posted are corrected by new entries. Card details are never stored; use a hosted
gateway/tokenized flow.

### 5.9 CRM integration

New module boundary: `marketplace.integration.crm` consuming stable `booking.api` ports.

Responsibilities:

- optionally create/link a CRM Booking for a buyer;
- project confirmed order items as Booking Service Items;
- project tenant payable as a supplier/marketplace expense without exposing platform margin;
- withdraw or adjust the projection after cancellation/amendment;
- publish CRM booking cancellation back to Marketplace;
- preserve idempotency through marketplace order/item public IDs.

The CRM Booking remains usable even if a marketplace add-on later expires. Historical confirmed
services and documents must not disappear when an entitlement is turned off.

### 5.10 Fleet integration

New port: `TransportFulfilmentPort`.

For internal-fleet fulfilment the adapter should:

1. receive a confirmed transport order-item snapshot;
2. create a Fleet job/dispatch request with route, schedule, passenger, and vehicle-category needs;
3. allow Fleet to assign an actual vehicle and driver;
4. report ACCEPTED, ASSIGNED, STARTED, COMPLETED, or FAILED events back to the order;
5. keep Fleet expenses and settlement private from the marketplace buyer unless explicitly priced.

Vehicle assignment is not inventory allocation. The marketplace sells a service/category; Fleet
later assigns an operational asset unless the commercial product explicitly promises a named
vehicle.

### 5.11 Reporting and search

Do not build marketplace dashboards by joining every transactional module at request time. Maintain
read models for:

- catalog search;
- buyer order history;
- supplier work queue;
- platform gross booking value, commission, refunds, and outstanding balances;
- fulfilment exceptions;
- reconciliation failures.

PostgreSQL full-text/trigram search is sufficient initially. Introduce a separate search engine only
when measured catalog size, facets, ranking, or latency justify it.

---

## 6. Vertical product design

### 6.1 Hotel

Hotel-specific model:

- property and location;
- room category and occupancy limits;
- meal plans;
- rate plan and per-date/season rates;
- room-night inventory/allotment;
- minimum stay, stop-sell, blackout, and release period;
- child/extra-bed policies;
- cancellation bands;
- supplier confirmation and voucher.

The existing platform hotel catalog can seed this model, but its room/meal definitions need dated
commercial and availability aggregates before instant confirmation is allowed.

### 6.2 Tour packages

Tour-specific model:

- package identity, destinations, duration, themes, inclusions, exclusions, and media;
- itinerary days and components;
- fixed departures or private/on-demand availability;
- adult, child, infant, single-supplement, and occupancy pricing;
- minimum/maximum group size;
- pickup/departure points;
- visa/insurance/flight inclusion flags;
- cutoff dates and cancellation policy;
- passenger/traveler requirements;
- supplier confirmation and tour voucher.

The CRM Quotation itinerary may import a confirmed package snapshot, but it must not own the sellable
package or departure inventory.

### 6.3 Transportation

Transport-specific model:

- service type: transfer, point-to-point, local package, outstation, multi-day, disposal;
- origin/destination, service zones, route, distance, and duration assumptions;
- vehicle category, capacity, luggage capacity, and amenities;
- rate cards: fixed, per-km, per-hour, per-day, airport, toll/parking, night allowance;
- minimum charge, included distance/hours, overtime, and cancellation/no-show terms;
- service windows and availability mode;
- internal Fleet, marketplace supplier, tenant Vendor, or aggregator fulfilment source.

Do not expose the Fleet expense ledger as marketplace pricing. Commercial rates are agreed before
the trip; operating costs are recorded during fulfilment.

### 6.4 Future products

Activities, sightseeing, visa, insurance, cruise, and flight integrations implement the same
catalog/offer/order contracts while owning their domain-specific fields and provider adapters.

Adding a product type should require:

1. a vertical product model and validator;
2. a pricing input adapter;
3. availability/confirmation capabilities;
4. an order-item fulfilment handler;
5. cancellation rules;
6. voucher/document rendering if applicable;
7. search projection mapping;
8. contract, integration, and concurrency tests.

It should not require editing one giant switch in Booking or copying the Hotel Marketplace module.

---

## 7. Proposed persistence model

Names below are conceptual. Final DDL must be delivered through forward-only Flyway migrations.

### 7.1 Core tables

| Table | Purpose |
|---|---|
| `mp_suppliers` | Platform supplier organization, KYC/status, capabilities |
| `mp_supplier_users` | Supplier portal membership and role |
| `mp_supplier_external_refs` | Provider/aggregator identifiers |
| `mp_products` | Common listing identity, type, supplier, content state, slug/version |
| `mp_product_media` | Ordered media and accessibility metadata |
| `mp_listing_visibility` | Public, all buyers, selected buyers/groups, contract-only |
| `mp_contracts` | Supplier/platform and buyer-specific commercial terms |
| `mp_rate_plans` | Versioned rate-plan identity and validity |
| `mp_markup_rules` | Platform/buyer pricing rules with priority and validity |
| `mp_inventory_pools` | Product-specific capacity source |
| `mp_inventory_calendar` | Dated capacity/sold/held quantities where applicable |
| `mp_inventory_holds` | Expiring idempotent holds |
| `mp_offers` | Buyer-visible quote snapshot and expiry |
| `mp_offer_items` | Product selection and price/cancellation snapshot |
| `mp_orders` | Buyer-owned commercial order |
| `mp_order_items` | Independently confirmable fulfilment units |
| `mp_order_events` | Immutable business transition history |
| `mp_order_documents` | Voucher/invoice/document metadata |
| `mp_payment_intents` | Hosted payment request and gateway reference |
| `mp_financial_entries` | Immutable signed buyer/supplier/platform ledger entries |
| `mp_payout_batches` | Supplier payout grouping and reconciliation |
| `mp_projection_state` | CRM/Fleet/accounting projection status and retry metadata |
| `outbox_events` | Durable cross-module event delivery |
| `inbox_messages` | Consumer deduplication for replayed external/internal messages |

### 7.2 Vertical tables

Hotel examples:

- `mp_hotel_properties`;
- `mp_hotel_room_types`;
- `mp_hotel_meal_plans`;
- `mp_hotel_rate_rules`;
- `mp_hotel_inventory`.

Tour examples:

- `mp_tours`;
- `mp_tour_itinerary_days`;
- `mp_tour_departures`;
- `mp_tour_pax_rates`;
- `mp_tour_inventory`.

Transport examples:

- `mp_transport_services`;
- `mp_transport_vehicle_categories`;
- `mp_transport_rate_cards`;
- `mp_transport_service_windows`;
- `mp_transport_fulfilment_refs`.

### 7.3 Required database invariants

- unique public IDs and normalized slugs;
- unique order number and idempotency key per buyer;
- offer ownership and expiry enforced during order creation;
- optimistic version on mutable workflow aggregates;
- atomic inventory/hold updates with no negative availability;
- one live hold per logical idempotency reference;
- one projection per target type and marketplace item;
- append-only financial entries with unique business reference keys;
- currency equality within a ledger/accounting operation unless an explicit FX snapshot exists;
- tenant/buyer and supplier ownership predicates in indexes used by every external query;
- check constraints for statuses, non-negative quantities, valid date ranges, and monetary scale;
- no cascade delete from product/catalog data into historical order/financial snapshots.

---

## 8. End-to-end order workflow

### 8.1 Search and quote

1. Next.js calls a public content API or authenticated B2B search API.
2. Search returns product summaries, capabilities, and optional indicative pricing.
3. The buyer selects dates, occupancy/passengers/route, and options.
4. Backend resolves buyer contract, supplier rates, markup, taxes, and availability.
5. Backend writes an expiring Offer snapshot and returns only buyer-visible money.

### 8.2 Submit and confirm

1. Buyer submits Offer ID plus an idempotency key.
2. Backend rechecks ownership, expiry, and any required hold.
3. Backend creates Order and Order Item transactionally.
4. A durable outbox event starts supplier confirmation.
5. On-request items enter review; live/allotment items attempt a hold/commit.
6. A changed price creates a new buyer-action revision; it never silently overwrites payable.
7. Confirmation snapshots supplier reference and terms.
8. Separate events project the item into CRM and, for internal transport, Fleet fulfilment.

### 8.3 Payment or credit

1. Commercial policy decides PREPAID, CREDIT, DEPOSIT, or OFFLINE.
2. Credit reservation or payment authorization occurs before irreversible confirmation when required.
3. Gateway callbacks are verified, idempotent, and stored as raw provider events plus normalized
   payment state.
4. Order confirmation and payment capture follow the selected provider's safe sequence.
5. Failed compensation is visible in an exception queue and retried from durable state.

### 8.4 Cancellation and refund

1. Buyer requests cancellation; confirmed supply is not immediately assumed released.
2. Supplier/provider determines accepted cancellation and charge.
3. Backend writes a cancellation settlement snapshot.
4. Financial reversals/refunds are new ledger entries.
5. CRM service/expense and Fleet fulfilment are adjusted through idempotent events.
6. Voucher/document state changes separately.

### 8.5 Completion and supplier settlement

1. Fulfilment reports service completion or no-show.
2. Platform commission becomes earned according to the commercial policy.
3. Supplier payable becomes eligible for a payout batch.
4. Payout reconciliation records gateway/bank references.
5. Buyer and supplier statements are derived from ledgers, not mutable order totals alone.

---

## 9. API surface for Next.js

Introduce a versioned API for all new marketplace contracts and generate OpenAPI from it.

### 9.1 Public SEO/content API

```text
GET /api/v1/public/marketplace/destinations
GET /api/v1/public/marketplace/products
GET /api/v1/public/marketplace/products/{slug}
GET /api/v1/public/marketplace/products/{slug}/related
GET /api/v1/public/marketplace/sitemaps/changes
```

Public responses contain publishable content only. They never include supplier net, private buyer
rates, live negotiated inventory, internal notes, contact secrets, or platform margin.

### 9.2 Authenticated buyer API

```text
POST /api/v1/b2b/marketplace/search
POST /api/v1/b2b/marketplace/offers
GET  /api/v1/b2b/marketplace/offers/{publicId}
POST /api/v1/b2b/marketplace/orders
GET  /api/v1/b2b/marketplace/orders
GET  /api/v1/b2b/marketplace/orders/{publicId}
POST /api/v1/b2b/marketplace/orders/{publicId}/accept-revision
POST /api/v1/b2b/marketplace/orders/{publicId}/cancel
POST /api/v1/b2b/marketplace/orders/{publicId}/payment-intents
GET  /api/v1/b2b/marketplace/statements
```

### 9.3 Supplier API

```text
/api/v1/supplier/catalog/**
/api/v1/supplier/rates/**
/api/v1/supplier/inventory/**
/api/v1/supplier/orders/**
/api/v1/supplier/settlements/**
```

### 9.4 Platform administration API

```text
/api/v1/super-admin/marketplace/suppliers/**
/api/v1/super-admin/marketplace/catalog/**
/api/v1/super-admin/marketplace/orders/**
/api/v1/super-admin/marketplace/finance/**
/api/v1/super-admin/marketplace/exceptions/**
```

### 9.5 Contract rules

- consistent response and error envelope;
- stable machine-readable error codes;
- cursor pagination for large event/order feeds and page pagination for admin grids;
- idempotency header or field on every retryable write;
- explicit API versioning and deprecation window;
- ETag/version handling for concurrent admin edits;
- no JPA entities in API responses;
- separate public, buyer, supplier, and platform DTOs;
- generated TypeScript client/types for Next.js from OpenAPI;
- contract tests for backend and frontend fixtures.

---

## 10. Next.js and SEO plan

The backend language and framework do not create an SEO problem. SEO depends on what the Next.js
public storefront renders and exposes to crawlers.

### 10.1 Surface separation

Recommended deployment:

```text
www.example.com       public marketplace, crawlable
portal.example.com    authenticated B2B buyer application, noindex
supplier.example.com  authenticated supplier portal, noindex
admin.example.com     platform operations, noindex
api.example.com       Spring Boot APIs
```

These may live in one Next.js codebase with route groups, but security and indexing policy must be
explicit.

### 10.2 Public page requirements

- server-rendered or statically generated product and destination pages;
- stable readable slugs and canonical URLs;
- unique title, description, heading, and useful destination/product copy;
- correct hotel/product/breadcrumb structured data where the schema actually applies;
- XML sitemaps split by product type/destination when volume requires it;
- optimized responsive images, dimensions, alt text, and controlled third-party scripts;
- localized/currency-aware pages only when canonical/hreflang behavior is defined;
- 404/410 behavior for withdrawn products and redirects for changed slugs;
- cache invalidation triggered by catalog publication events;
- Core Web Vitals monitoring.

### 10.3 Crawl controls

- authenticated portal, cart, checkout, orders, and account pages are `noindex`;
- search/filter combinations do not create unlimited crawlable URLs;
- canonicalize or block sorting, dates, occupancy, and tracking parameters;
- do not render negotiated B2B prices into public HTML, JSON-LD, page source, or hydration data;
- avoid duplicating supplier-provided descriptions across many URLs;
- public “starting from” prices must carry freshness and qualification rules.

### 10.4 Backend support for SEO

The public API should return a publication projection designed for rendering, not the transactional
aggregate. It needs content version, canonical slug, last-modified timestamp, publishability,
destination links, media metadata, and optional safe indicative price. Next.js should not assemble a
page through many per-component backend calls.

---

## 11. Reliability, security, and operations

### 11.1 Durable events

Add a PostgreSQL transactional outbox:

1. aggregate write and outbox row commit in one transaction;
2. dispatcher claims rows with safe multi-worker locking;
3. consumers record inbox/idempotency keys;
4. retry with bounded backoff;
5. dead-letter/abandoned state is visible to operators;
6. replay is an admin action with audit.

Start with database polling. Kafka/RabbitMQ is optional later and does not replace outbox correctness.

### 11.2 Multi-instance readiness

Before adding replicas:

- Redis-backed rate limiting;
- Redis/database-backed OTP and MFA challenges;
- shared entitlement/config cache invalidation;
- Redis pub/sub or another broker for SSE fan-out;
- distributed scheduler locks or queue-based job ownership;
- idempotent scheduled jobs and provider webhooks;
- no business reliance on JVM-local state.

### 11.3 Security controls

- buyer tenant ownership checked inside repository query predicates;
- supplier organization ownership checked separately from buyer tenancy;
- deny-by-default marketplace route classification in module entitlements;
- separate permissions for catalog, rates, inventory, orders, payable, refunds, credit, settlement,
  and supplier bank/KYC data;
- step-up MFA for high-value/refund/payout actions;
- encryption for provider credentials and sensitive financial identity;
- signed/expiring private document downloads;
- webhook signature verification plus replay/idempotency protection;
- audit every state transition and financial adjustment;
- never log secrets, traveler identity documents, full bank data, or raw payment credentials.

### 11.4 Observability

Add metrics and operational dashboards for:

- search/offer/order conversion;
- offer pricing failures;
- inventory hold conflicts and expiries;
- supplier response time;
- order state age;
- payment, refund, and payout reconciliation;
- outbox backlog and retry age;
- CRM/Fleet projection failures;
- per-provider latency/error rate;
- scheduled job last-success timestamp;
- tenant and supplier isolation violations blocked.

Trace ID, order ID, buyer tenant ID, supplier ID, and provider reference should be available as
structured log fields without exposing sensitive data.

---

## 12. Testing and CI quality gates

### 12.1 Immediate CI changes

Every pull request must run:

```text
compile
unit tests
architecture tests
PostgreSQL migration from empty database
Hibernate schema validation
marketplace integration tests with PostgreSQL
```

The Docker build may still avoid rerunning tests if CI has already produced an immutable artifact,
but deploy must depend on a green test job. A compile-only deployment gate is not acceptable.

### 12.2 Required marketplace tests

- price calculation and rounding golden tests;
- rule precedence and date-boundary tests;
- offer expiry/ownership tests;
- order state-machine transition tests;
- tenant and supplier cross-organization isolation tests;
- DTO visibility tests proving supplier net/platform margin never reaches buyer/public responses;
- idempotent submit, webhook, outbox, projection, refund, and payout tests;
- inventory hold/commit/release concurrency tests;
- optimistic-lock race tests for admin decisions;
- payment and refund reconciliation tests;
- CRM projection and compensation tests;
- Fleet fulfilment adapter contract tests;
- cancellation charge and ledger reversal tests;
- public catalog publication/withdrawal tests;
- OpenAPI compatibility tests;
- Next.js/backend contract fixtures.

Use Testcontainers PostgreSQL for query, constraint, locking, migration, and tenant-isolation tests.
H2 or mocks cannot prove those behaviors.

### 12.3 Architecture tests

Add rules for:

- no cross-module repository/entity imports;
- `common` cannot depend on business packages;
- buyer controllers cannot return platform/admin DTOs;
- public controllers cannot depend on rate, finance, or supplier-secret packages;
- financial entities cannot expose normal soft-delete operations;
- every new controller prefix is entitlement-gated or explicitly public/platform-neutral;
- every product vertical implements the required fulfilment contract.

---

## 13. Migration from the current hotel marketplace

Use an incremental migration, not a big-bang rewrite.

### Step 1 — stabilize

- fix the current compile failure;
- freeze V1/V2 and establish forward-only migration discipline;
- add hotel marketplace integration and isolation tests;
- document current API responses through generated OpenAPI;
- preserve existing routes for current clients.

### Step 2 — introduce marketplace core

- add Supplier, Product, Offer, Order, Order Item, financial entry, outbox, and projection contracts;
- add adapters around the current hotel catalog and approval services;
- add generic IDs/back-references without changing existing behavior;
- keep current tenant and admin DTO visibility rules.

### Step 3 — migrate new hotel writes

- new requests enter generic Order/Order Item;
- hotel fulfilment handler uses the existing manual approval capability;
- CRM projection consumes the generic confirmation event;
- backfill existing `platform_hotel_bookings` to generic read/history references;
- do not maintain indefinite dual sources of truth.

### Step 4 — add rates and inventory

- introduce dated hotel rates, policies, and availability;
- generate Offers server-side;
- support allotment holds;
- enable instant confirmation only per listing/provider capability.

### Step 5 — retire compatibility surface

- migrate Next.js/API clients to versioned generic endpoints;
- measure old-route usage;
- announce deprecation;
- remove old write paths only after data reconciliation and acceptance tests pass.

---

## 14. Delivery roadmap and release gates

### Phase 0 — make the current CRM a safe foundation

Deliverables:

- green compile and full test execution in CI;
- immutable migration policy and reconciled deployed schema;
- OpenAPI generation for current/new APIs;
- package dependency rules and initial service extraction from large classes;
- durable outbox foundation;
- Redis/shared-state plan for horizontal scale;
- close Fleet blockers required by transport fulfilment.

Exit gate: main branch builds, all tests run, fresh and upgraded database paths validate, and no
released migration is edited.

### Phase 1 — marketplace foundation

Deliverables:

- supplier organization/KYC model;
- common catalog/listing and visibility;
- contract/rate/markup foundation;
- Offer and generic Order/Order Item;
- immutable commercial snapshots;
- outbox/inbox delivery;
- buyer/supplier/admin permissions and route gates;
- buyer order history and platform exception queue.

Exit gate: a test product can be searched, offered, ordered, revised, confirmed, cancelled, audited,
and replayed without importing a vertical implementation into core.

### Phase 2 — hotel marketplace v1

Deliverables:

- migrate current hotel workflow behind generic core;
- dated rates and on-request/allotment availability;
- supplier portal/manual confirmation queue;
- buyer-visible payable and safe voucher;
- CRM projection and reconciliation;
- public hotel/destination pages and SEO publication projection.

Exit gate: complete hotel order, cancellation, refund/adjustment, CRM projection, isolation, and
visibility tests pass.

### Phase 3 — B2B tour packages

Deliverables:

- package and itinerary catalog;
- fixed-departure/on-request inventory;
- pax/occupancy pricing;
- traveler data requirements;
- tour confirmation and voucher;
- quotation/booking projection.

Exit gate: the tour module implements marketplace contracts without changes to hotel internals or
fixed new fields on the CRM Quotation aggregate.

### Phase 4 — B2B transportation

Deliverables:

- transport products and rate cards;
- route/zone/service-window pricing;
- supplier and internal-fleet fulfilment adapters;
- dispatch handoff and status synchronization;
- cancellation/no-show handling;
- transport voucher/duty details where applicable.

Exit gate: commercial order, Fleet operations, and Fleet expenses remain separate and reconcile by
stable references.

### Phase 5 — marketplace payments, credit, and settlement

Deliverables:

- hosted payment gateway integration for marketplace orders;
- buyer credit ledger and reservation;
- receipts, refunds, and chargebacks;
- supplier payable and payout batches;
- commission recognition and statements;
- accounting export/reconciliation.

Exit gate: every money movement is idempotent, ledger-backed, permissioned, auditable, and covered by
reconciliation tests. Merchant-of-record/tax decisions are approved.

### Phase 6 — additional products and providers

Deliverables:

- activities/sightseeing, visa, insurance, cruise, or flight adapters in business-priority order;
- provider certification/health dashboards;
- stronger search ranking and merchandising;
- measured horizontal scaling improvements.

Exit gate: each new vertical follows the extension checklist and does not increase core module
coupling.

---

## 15. Priority risk register

| ID | Severity | Risk | Required response |
|---|---|---|---|
| FP-01 | Release blocker | Current source does not compile due to missing `BookingCancelledEvent` import | Restore green compile before marketplace work |
| FP-02 | Release blocker | Applied V2 migration is being expanded/re-stamped | Freeze and reconcile migration lineage; use V3+ only |
| FP-03 | Critical | CI and Docker paths skip tests | Make deploy depend on full green tests |
| FP-04 | Critical | No automated hotel marketplace tests in inspected tree | Add state, isolation, money, idempotency, and projection tests |
| FP-05 | Critical | No generic supplier, Offer, Order, inventory, or settlement core | Build foundation before adding tour/transport copies |
| FP-06 | Critical | Marketplace money would span several mutable totals and ledgers | Define immutable marketplace finance source of truth |
| FP-07 | High | Tenant filtering mixes automatic and explicit ownership | Standardize buyer/supplier query guards and database indexes |
| FP-08 | High | Module cycles and very large services increase change radius | Enforce dependency direction and extract application use cases |
| FP-09 | High | In-memory auth/rate/SSE state and unlocked schedulers block safe replicas | Move shared state/coordination to Redis/database before scaling |
| FP-10 | High | Current Vendor/SubAgent identities do not model global suppliers/buyers | Introduce explicit marketplace organizations and mappings |
| FP-11 | High | Transport marketplace could couple checkout directly to Fleet | Use fulfilment port and asynchronous handoff |
| FP-12 | High | No public catalog API/read model exists for SEO | Add publication projection and public versioned API |
| FP-13 | Medium | API paths and response styles are inconsistent; no OpenAPI tooling found | Version new APIs and generate clients/contracts |
| FP-14 | Medium | Search is database-centric and page assembly could create N+1 calls | Start with a search/read projection and measure before adding a search engine |

---

## 16. Explicit non-goals

The first marketplace release should not attempt:

- microservice decomposition;
- automatic integration with every hotel/flight/transport supplier;
- multi-item atomic package confirmation across unrelated suppliers;
- a full general ledger replacement for accounting software;
- real-time instant confirmation where no authoritative inventory/hold exists;
- storing card data;
- exposing negotiated B2B rates to anonymous SEO pages;
- using Fleet operational costs as public sell rates;
- one universal JSON product table with no vertical constraints;
- rewriting all existing CRM modules before delivering value.

---

## 17. Definition of done for the marketplace foundation

The foundation is ready for multiple product verticals only when all of the following are true:

- buyer, supplier, platform, and public identities/DTOs are structurally separated;
- Product, Offer, Order, Order Item, and financial snapshots have stable ownership;
- rates and payable amounts are computed server-side;
- retryable writes and provider callbacks are idempotent;
- cross-module events are durable and replayable;
- CRM and Fleet projections can fail and recover without changing the commercial order incorrectly;
- tenant/supplier isolation and money visibility have integration tests;
- inventory cannot go negative and holds expire safely under concurrency;
- all schema changes are forward-only Flyway migrations;
- CI runs tests and schema validation before deployment;
- public SEO pages cannot leak private rates or internal fields;
- a new vertical can be added through contracts without importing or modifying another vertical's
  repositories/entities;
- finance can reconcile buyer receivable, supplier payable, platform earning, refunds, and payouts
  from immutable entries.

---

## 18. Decisions required before implementation

Record these as short architecture/business decisions before final DDL and payment code:

1. Is the platform merchant of record, reseller, or commission agent for each product type?
2. Who issues the tax invoice to the B2B buyer and who bears refund liability?
3. Are suppliers only platform-managed, or can a tenant onboard and sell inventory?
4. Are buyer prices public, login-only, or contract-specific?
5. Which first product and confirmation mode is the commercial MVP?
6. Is buyer credit platform-funded, supplier-funded, or only an accounting limit?
7. Which currencies and FX locking rules are required at launch?
8. Does one marketplace order represent one item, or must a cart contain independently confirmable
   mixed products at launch?
9. Which fulfillment party owns customer support and cancellation negotiations?
10. What are the legal retention periods for traveler data, invoices, KYC, orders, and financial
    ledgers?

Until these are decided, the safe engineering default is: existing tenant buyers, platform-managed
suppliers, INR, one item per order, on-request confirmation, no public negotiated price, hosted
payments/approved credit, and explicit supplier/platform/tenant money snapshots.
