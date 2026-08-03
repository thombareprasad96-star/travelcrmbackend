# Vehicle Diary — standalone deployment runbook

Installing Fleet as its own product on one VPS. Companion to `docs/DEPLOYMENT.md`, which
covers the full CRM suite; read this one instead if the customer bought fleet management
and nothing else.

---

## 0. What "standalone" actually means here

Two independent switches, and confusing them is the commonest mistake:

| | What it is | Where it lives | What it controls |
|---|---|---|---|
| `APP_PRODUCT_MODE=FLEET_STANDALONE` | **packaging** | deployment env | Which fleet integration adapters load. In standalone the ports never read a booking or vendor table. |
| Tenant on the **`FLEET` plan** (`modules = {FLEET}`) | **authorisation** | the tenant's row | `ModuleAccessFilter` answers `403 MODULE_NOT_ENABLED` on every CRM path. |

**The plan is what closes the CRM, not the mode.** A deployment left on `CRM_SUITE` but with
every tenant on the FLEET plan is still a correctly locked-down fleet product; a deployment in
`FLEET_STANDALONE` mode whose tenant sits on `PRO` will happily serve `/api/leads`. Set both.

Neither switch forks the frontend. The same bundle picks its shell at runtime from
`GET /api/me/entitlements` — `productMode` plus the tenant's `modules`. There is one build.

---

## 1. Host prerequisites

Ubuntu 24.04, 2 vCPU / 4 GB is comfortable for a single operator up to ~50 vehicles.

```bash
sudo apt update && sudo apt install -y docker.io docker-compose-v2
sudo mkdir -p /etc/vehicle-diary /opt/vehicle-diary/{backups,tls,dist}
```

Firewall: only 80/443 in. The JVM is never published — `docker-compose.fleet.yml` gives the
`app` service no `ports:` at all, so nginx is the sole way in.

```bash
sudo ufw allow 22 && sudo ufw allow 80 && sudo ufw allow 443 && sudo ufw enable
```

---

## 2. Build the two artefacts

```bash
# Backend image
./mvnw clean package -DskipTests
docker build -t vehicle-diary:1.0.0 .

# Frontend bundle — the SAME build as the CRM; the shell is chosen at runtime
cd ../travelcrmfe/travelcrmfrontend
npm ci && npm run build          # → dist/
rsync -a dist/ root@HOST:/opt/vehicle-diary/dist/
```

`VITE_API_URL` is deliberately **unset**: single-origin means relative `/api/...` calls, which
is also why this product needs no CORS list. Setting it is how you break the deploy.

---

## 3. Schema — apply by hand the first time

`FLYWAY_ENABLED=false` on first boot. Apply the two migrations directly, then stamp them, then
turn Flyway on. (The alternative — letting Flyway run against an empty database — works too,
but a hand-applied first install is what the CRM's own runbook does and keeps the two identical.)

```bash
docker compose -f docker-compose.fleet.yml up -d postgres
docker compose -f docker-compose.fleet.yml exec -T postgres \
  psql -U fleet -d vehicle_diary < src/main/resources/db/migration/V1__baseline_schema.sql
docker compose -f docker-compose.fleet.yml exec -T postgres \
  psql -U fleet -d vehicle_diary < src/main/resources/db/migration/V2__lead_code.sql
```

**V2 is where the fleet product lives.** Parts 6–12 are all fleet:

| Part | Ships |
|---|---|
| 6 | tenant timezone + the `FLEET_MONEY_*` permission backfill |
| 7 | trip legs, expenses, cash entries, settlements, period closes |
| 8 | allowance (bata) policies |
| 9 | compliance documents + backfill of the legacy expiry columns |
| 10 | one document vocabulary across the alert history |
| 11 | attachments — receipts, scans, signed sheets (`bytea`, quota-metered) |
| 12 | `fleet_parties` + the `FLEET` plan code across seven CHECK constraints |

Then set `FLYWAY_ENABLED=true` in `fleet.env` and let the app stamp the history on boot.
Verify before going further:

```sql
SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;   -- expect 1, 2
SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
 WHERE contype='c' AND pg_get_constraintdef(oid) LIKE '%STARTER%';            -- expect 7, all with FLEET
```

If the second query returns a row **without** `FLEET`, stop — provisioning will fail at the
step that first writes the new plan value, which is not always the first step.

---

## 4. Configure and start

```bash
cp deploy/fleet.env.example /etc/vehicle-diary/fleet.env
sudo chmod 600 /etc/vehicle-diary/fleet.env
$EDITOR /etc/vehicle-diary/fleet.env     # replace EVERY CHANGE_ME
```

Generate the secrets properly — `ProductionConfigValidator` refuses to boot on a placeholder,
and `PORTAL_JWT_SECRET` must differ from `JWT_SECRET` (sharing one is what lets a token cross
auth realms):

```bash
openssl rand -base64 48     # JWT_SECRET
openssl rand -base64 48     # PORTAL_JWT_SECRET
openssl rand -base64 32     # APP_ENCRYPTION_KEY
```

```bash
cd deploy
FLEET_IMAGE=vehicle-diary:1.0.0 \
SPA_DIST_DIR=/opt/vehicle-diary/dist \
TLS_DIR=/opt/vehicle-diary/tls \
BACKUP_DIR=/opt/vehicle-diary/backups \
docker compose -f docker-compose.fleet.yml up -d
```

TLS: follow the ordered steps in the header of `deploy/nginx-fleet.conf`. Do not skip the
deploy hook — without it the ~60-day renewal writes a fresh certificate to disk while nginx
keeps serving the expired one from memory.

---

## 5. Provision the operator

On boot, `PlanCatalogueInitializer.ensureFleetPlan()` creates the **Vehicle Diary** plan
(`FLEET`, ₹1999, 10 users, 2 GB, `modules = {FLEET}`) if it is absent. Confirm it:

```sql
SELECT p.code, p.display_name,
       (SELECT array_agg(m.module) FROM plan_modules m WHERE m.plan_id = p.id) AS modules
FROM plans p WHERE p.code = 'FLEET';
-- FLEET | Vehicle Diary | {FLEET}
```

Then sign in to the console at `/superadmin/login` with `SUPERADMIN_1_*` and create the
tenant **on the FLEET plan**. That single choice is the product boundary.

### Verify the boundary before handing over

Log in as a tenant user and check all four. Any one failing means the customer can see a
product they did not buy:

```bash
TOKEN=...   # a tenant user's JWT

# 1. Entitlements report the fleet product
curl -s -H "Authorization: Bearer $TOKEN" https://HOST/api/me/entitlements
#    → "modules":["FLEET"], "productMode":"FLEET_STANDALONE"

# 2. Fleet works
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
     https://HOST/api/fleet/vehicles                        # 200

# 3. CRM is closed — not hidden, closed
for p in leads bookings quotations customers accounting marketing reports; do
  printf '%-12s %s\n' "$p" "$(curl -s -o /dev/null -w '%{http_code}' \
       -H "Authorization: Bearer $TOKEN" https://HOST/api/$p)"
done                                                        # all 403

# 4. The UI agrees: the sidebar shows Vehicle Diary, Users and Organization only,
#    and "/" lands on /fleet rather than the CRM dashboard.
```

---

## 6. What a standalone deployment does and does not have

**Has** — vehicles, drivers, trips with legs and mid-duty handover, the expense ledger,
driver cash and settlements, bata policy, month close, the compliance register with renewal
history and expiry alerts, receipt/scan/signed-sheet attachments, the printable duty slip and
settlement sheet, and its own party directory (`fleet_parties`) for the owners it hires
vehicles from.

**Does not have** — leads, bookings, quotations, customers, the vendor master, accounting,
marketing, the traveler portal. The fleet code cannot even import them: `FleetBoundaryArchTest`
fails the build if anything under `fleet.*` names a CRM package, which is what keeps this claim
true after month three rather than merely true today.

**Shared platform capabilities**, which a fleet operator needs and which are not CRM business
logic: auth, users, roles and permissions, company profile and branding, notifications, trash,
storage quota, audit.

---

## 7. Backups

The settled cash ledger and its evidence carry an **8-year** retention requirement, and fleet
money rows are deliberately excluded from the 30-day trash purge for that reason. Attachments
are `bytea` inside Postgres, so a database dump is a complete backup — but it is also therefore
a large one.

```bash
docker compose -f docker-compose.fleet.yml exec -T postgres \
  pg_dump -U fleet -Fc vehicle_diary > /opt/vehicle-diary/backups/$(date +%F).dump
```

Ship that directory off-box nightly. A backup that lives only on the VPS is not a backup.

---

## 8. Upgrading a standalone customer to the full CRM

No migration, no reinstall: change the tenant's plan and restart with the mode flipped.

1. Move the tenant to `PRO` or `ENTERPRISE` in the console.
2. Set `APP_PRODUCT_MODE=CRM_SUITE` (or drop the line — that is the default) and restart.

The CRM adapters then win the port bindings, so a trip can be linked to a booking and a
vehicle to a vendor. **Existing fleet data is untouched** — the ports change what a job
reference *resolves to*, never whether fleet works. Parties already recorded in
`fleet_parties` stay on their vehicles; new vehicles pick owners from the Vendor master
instead. The two directories never merge, and nothing has to be reconciled.
