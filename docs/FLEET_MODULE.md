# Fleet / Vehicle Diary Module (`fleet/`)

> **Legacy operational overview:** this file documents the pre-ledger Fleet module. The current
> working tree additionally implements trip legs, expenses, driver cash, settlements, accounting
> periods, normalized compliance documents, private attachments, a standalone party directory and
> CRM/standalone ports. For the current architecture, implementation audit, known defects and release
> gates, use `docs/FLEET_MODULE_REDESIGN.md` §0.2. The endpoint and entity lists below must not be
> treated as complete for the redesigned module.

Operational fleet management for a tenant: vehicles, drivers, trips, fuel & maintenance
diaries, and document-expiry alerts. **Fully separate from the quotation master
`master/vehicle/`** (zero changes there) — self-contained package, linked to the rest of
the app only by logical IDs.

## Data model

```
FleetVehicle (fleet_vehicles)          FleetDriver (fleet_drivers)
  vehicleNumber (unique/tenant)          name, phone
  type/make/model/year/seats             licenseNumber, licenseExpiry
  ownerType OWN|VENDOR|RENTED            status ACTIVE|INACTIVE
  vendorId/PublicId/Name (snapshot)      ("on trip" is computed, not stored)
  insurance/rc/permit/puc Expiry
  status AVAILABLE|ON_TRIP|MAINTENANCE|OUT_OF_SERVICE
  lastOdometer (bump-up only)
  nextServiceDueDate/Km (denormalized from latest maintenance log)
        ▲ real FK                ▲ real FK
        │                        │
FleetTrip (fleet_trips) ── vehicle_id, driver_id (@ManyToOne, LAZY, @EntityGraph on lists)
  bookingId/bookingPublicId/bookingCode  ← LOGICAL link to bookings (validated tenant-scoped)
  status PLANNED|ONGOING|COMPLETED|CANCELLED
  start/end datetime + odometers, distanceKm (derived), routes, purpose
  fuelCost/tollCost/driverAllowance, remarks

FleetFuelLog (fleet_fuel_logs)         FleetMaintenanceLog (fleet_maintenance_logs)
  vehicle FK, date, liters, cost,        vehicle FK, serviceDate, serviceType, cost,
  odometer, notes                        vendorName (free text), odometer,
                                         nextServiceDueDate/Km, notes

FleetDocumentAlert (fleet_document_alerts)   — fired alert = idempotency marker + history
  refType VEHICLE|DRIVER, refId/refPublicId/refLabel
  docType INSURANCE|RC|PERMIT|PUC|DRIVER_LICENSE
  expiryDate, daysLeft, thresholdDays
```

All entities extend `BaseTenantEntity` (publicId, audit, soft delete, tenant filter). API
exposes **publicId only**. Soft-delete uses explicit `...DeletedAtIsNull` finders (the
Reminder/Vendor mechanism). All five user-facing entities are registered in
`TrashableType` (children before parents, so the 30-day purge is FK-safe).

## Trip lifecycle & vehicle status sync

- `POST /trips` → **PLANNED**, or **COMPLETED** directly when `endDatetime` is sent
  (post-facto diary entry; no status side effects).
- `PATCH /trips/{id}/start` → PLANNED→**ONGOING**; requires vehicle AVAILABLE + driver
  ACTIVE + no other ONGOING trip for either; vehicle → ON_TRIP.
- `PATCH /trips/{id}/close` → ONGOING→**COMPLETED**; records end odometer/time, computes
  `distanceKm`, vehicle → AVAILABLE, bumps `lastOdometer`.
- `PATCH /trips/{id}/cancel` → PLANNED/ONGOING→**CANCELLED**; frees the vehicle.
- Manual `PATCH /vehicles/{id}/status` rejects ON_TRIP (trip-managed) and any change
  while an ONGOING trip exists.
- Deleting a vehicle/driver with diary history is blocked (retire with
  OUT_OF_SERVICE / INACTIVE instead) — this keeps the trash purge FK-safe.

## Endpoints (all `/api/fleet/**`, ApiResponse/PagedApiResponse envelopes)

| Area | Endpoints |
|---|---|
| Vehicles | `POST/GET /vehicles`, `GET /vehicles/options?status=`, `GET/PUT/DELETE /vehicles/{id}`, `PATCH /vehicles/{id}/status` |
| Drivers | same shape under `/drivers` |
| Trips | `POST/GET /trips` (filters: vehicleId, driverId, status, bookingId, fromDate, toDate, search), `GET/PUT/DELETE /trips/{id}`, `PATCH /trips/{id}/start\|close\|cancel` |
| Fuel | `POST/GET /vehicles/{vid}/fuel-logs`, `PUT/DELETE /fuel-logs/{id}` |
| Maintenance | `POST/GET /vehicles/{vid}/maintenance-logs`, `PUT/DELETE /maintenance-logs/{id}` |
| Dashboard | `GET /dashboard` (counts, ongoing trips, expiring docs, service-due), `GET /alerts` (history) |

Booking integration: create a trip with `bookingPublicId` (validated tenant-scoped;
code snapshotted) and list a booking's trips via `GET /trips?bookingId=`.

## Document-expiry scan

`FleetDocumentExpiryScheduler` (daily, `app.fleet.expiry-scan-cron`) iterates tenants
(sets `TenantContext`, clears in `finally` — portal scheduler pattern) → for each
vehicle document + active driver licence, fires the most urgent crossed-and-unfired
threshold from `app.fleet.expiry-reminder-days` (30,15,7,0; 0 = on/after expiry).
Alert row saved in `fleet_document_alerts` (idempotency + history) and a `NotifyEvent`
(`FLEET_DOCUMENT_EXPIRY`, IN_APP) goes to the tenant's TENANT_ADMIN/MANAGER users.
The dashboard additionally computes upcoming expiries live, so it never depends on the scan.

## Permissions

`FLEET_READ / FLEET_CREATE / FLEET_UPDATE / FLEET_DELETE` in the `Permission` catalog
("Fleet" group; auto-appears in the FE permission UI). Defaults: TENANT_ADMIN all (resolver
bypass), MANAGER all four, TRAVEL_AGENT read/create/update, ACCOUNTANT read-only, STAFF none.
Users with a saved custom permission map need the new keys granted explicitly.

## Configuration (`application.properties`)

```properties
app.fleet.expiry-scan-cron=0 30 7 * * *
app.fleet.expiry-reminder-days=30,15,7,0
app.fleet.dashboard-expiry-window-days=30
app.fleet.service-due-days=15
app.fleet.service-due-km-threshold=500
```

No new external dependencies and no new env vars. Tables are created automatically by
`ddl-auto=update` on next startup.

## Extension notes

- **New expirable document** (e.g. fitness certificate): add the date column on
  `FleetVehicle`, a `FleetDocumentType` constant, one `process(...)` call in
  `FleetExpiryScanService`, one `addIfExpiring(...)` call in `FleetDashboardServiceImpl`,
  and include the column in `findWithDocumentsExpiringBy`.
- **Driver-facing SMS/WhatsApp alerts**: publish the same data through a sender bean, or
  add channels to the `NotifyEvent` in `FleetExpiryScanService`.
- **Master vehicle link**: add a nullable `masterVehicleId` column on `FleetVehicle` +
  resolve in the service — deliberately left out of v1.
- **Odometer corrections**: `lastOdometer` only bumps upward; a true recompute would scan
  trip/fuel/maintenance maxima — add if agencies need downward corrections.
