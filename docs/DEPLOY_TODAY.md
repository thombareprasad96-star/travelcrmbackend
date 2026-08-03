# Deploy runbook — 3 Aug 2026

One-off runbook for **this** deploy. It is not a replacement for `docs/DEPLOYMENT.md` (the standing
reference); it is the ordered command list for today, with the checks that matter for this
particular change set.

**What is going out:** backend (205 changed files) + frontend (80 changed files).
**What makes it different from a normal update:** the production database is stamped at **V1 only**.
`V2__lead_code.sql` has never run there, and it has grown to **15 PARTs**. This deploy applies all
of it to live pilot data in one shot.

---

## 0. Two things that cannot be undone

Read these before starting, not after.

1. **`lead_type` values collapse, and the distinction is lost.** The enum constants were *replaced*,
   not extended. V2 PART 1 rewrites every existing row:

   | Old value | Becomes |
   |---|---|
   | `FRESH_LEAD` | `FRESH` |
   | `REPEAT_CUSTOMER` | `WARM` |
   | `CORPORATE` | `HOT` |
   | `VIP` | `HOT` |

   `CORPORATE` and `VIP` both land on `HOT` — after the migration you cannot tell which was which.
   (`Customer.customerType` still carries `INDIVIDUAL / CORPORATE / VIP`, so the business category
   survives on the customer, not on the lead.) Step 1 captures the pre-migration counts so you at
   least have a record.

2. **After this deploy, V2 has reached production.** The standing "append a new PART to V2 and
   re-stamp" workflow ends today. Every schema change from tomorrow must be a real
   `V3__<description>.sql`. Editing V2 after this point breaks production's checksum.

---

## Data-safety audit — Docker & GitHub Actions

This is the **second** deploy: there is real tenant data in the volume. Both pipelines were audited
specifically for data destruction. Findings:

### What the pipeline cannot do

| Risk | Finding |
|---|---|
| `docker compose down` / `down -v` | **Absent** from both repos' deploy scripts and workflows. The scripts only ever `pull` + `up -d`. |
| `docker volume prune` / `volume rm` / `system prune --volumes` | **Absent** from both repos. |
| `docker image prune -f --filter until=168h` | Present in both — **images only**. `image prune` cannot touch volumes. |
| `--remove-orphans` deleting the database container | No. It removes only containers whose *service is no longer defined*. `postgres` is a defined service in the compose file, so it is never an orphan. |
| Frontend deploy disturbing the backend | No. Separate compose projects: backend `travelcrm`, frontend `travelcrm-frontend`. Each `--remove-orphans` is scoped to its own project. |
| Migrations dropping data | V1 and V2 contain **zero** `DROP TABLE`, `DROP COLUMN`, `TRUNCATE`, or `DELETE FROM`. (Two `DELETE FROM flyway_schema_history` hits are inside comments.) |
| `db/indexes.sql` | Drops exactly one *index* (`uq_users_email_active`, from the username-login change) — an index, not data. And it will not run at all: `SQL_INIT_MODE=never`. |
| `ddl-auto=create` wiping the schema | `ProductionConfigValidator` **refuses to boot** on `create`/`create-drop`, and refuses if Flyway is on while `ddl-auto` is anything but `validate`/`none`. |
| Dev seeder writing demo data | `app.seed.enabled=false` is **pinned** in `application-prod.properties` (not merely defaulted), and `DevDataSeeder` is `@ConditionalOnProperty(havingValue="true")`. The validator also rejects it. |
| A new required env var failing the boot | `application-prod.properties` is **unchanged** in this changeset — the required-var list is identical to the deploy that is already running. |

### What can still go wrong

1. **The volume silently not re-attaching.** This is the real one. If the *first* deploy was run
   under a different compose project name (e.g. manually, from a differently-named directory), then
   `up -d` under project `travelcrm` creates a **new, empty** `travelcrm_postgres_data` and the app
   comes up with an empty CRM. Your data is not deleted — it is orphaned in the other volume — but
   it looks exactly like total data loss. §3 checks this **before** you push.
   *Git history is reassuring:* the compose `name: travelcrm` and the script's
   `COMPOSE_PROJECT_NAME=travelcrm` have never changed since they were introduced.
2. **`lead_type` collapsing `CORPORATE`/`VIP` → `HOT`** (§0). Intended, but lossy.
3. **`POSTGRES_PASSWORD` in `/opt/travelcrm-be/.env` differing from what the volume was initialised
   with.** Postgres ignores `POSTGRES_*` on an existing data directory, so the app would fail
   authentication. An outage, not data loss — but it looks alarming mid-deploy.
4. **V2 failing partway.** Postgres runs DDL transactionally, but the file is many statements; a
   failure leaves a `success = f` row. §8 covers this — restore, do not `flyway repair`.

---

## 1. Local pre-flight

Already run on this machine on 3 Aug 2026 — re-run only if you change code before pushing.

```powershell
cd "D:\CRM PROJECT\travelcrmbackend"
.\mvnw.cmd -o clean compile -DskipTests
```

| Check | Result |
|---|---|
| Backend compiles | ✅ pass |
| `V2__lead_code.sql` applies cleanly to a real Postgres | ✅ pass (run against local `travel_crm`, exit 0) |
| Hibernate schema validation vs the V2 schema | ✅ pass — no missing tables/columns |
| Backend test suite | ✅ **411 tests, 0 failures, 0 errors** — BUILD SUCCESS |
| Frontend `npm run build` | ✅ pass |
| Frontend bundle baked the prod API URL | ✅ 3 files contain `api.mytripsafar.com`, **0** contain `localhost:8080` |
| New required env vars introduced | ✅ none — `app.product-mode` and all `app.marketplace.*` have defaults |

> An earlier run of the suite reported 3 failures. All three were artifacts of a stale local
> database — the Flyway checksum mismatch that follows editing V2 after it has been stamped locally.
> Applying V2 by hand and deleting its `flyway_schema_history` row cleared them; the re-run is green.
> If you hit `Migration checksum mismatch for migration version 2` locally in future, that is the
> fix — never `flyway repair`.

> The Hibernate schema check is the same gate CI runs. It is what stops the app booting under
> `JPA_DDL_AUTO=validate`, so a pass here is the strongest local signal that production will boot.

---

## 2. Back up the production database

Postgres runs **inside Docker** on the VPS (compose service `postgres`, volume
`travelcrm_postgres_data`). There is no host `pg_dump` to call — it has to go through the container.

SSH to the VPS, then:

```bash
cd /opt/travelcrm-be
set -a; . ./.env; set +a

docker compose --project-name travelcrm --env-file .env -f docker-compose.hostinger.yml \
  exec -T postgres pg_dump -U "${POSTGRES_USER:-travelcrm}" -d "${POSTGRES_DB:-travel_crm}" \
  | gzip > "$HOME/travel_crm-pre-v2-$(date +%Y%m%d-%H%M%S).sql.gz"

ls -lh "$HOME"/travel_crm-pre-v2-*.sql.gz
```

**Do not continue until the file exists and the size looks sane** (a few hundred KB or more — a
file under 10 KB means the dump failed and `gzip` happily compressed an error).

Then get it **off the box** — a backup on the same disk does not survive the disk:

```bash
# from your laptop
scp root@187.127.159.81:'~/travel_crm-pre-v2-*.sql.gz' "D:/CRM PROJECT/backups/"
```

---

## 3. Baseline the data — run this BEFORE you push

Still on the VPS. This is the check that catches the volume-not-re-attaching failure, and the record
that proves afterwards that nothing was lost.

```bash
cd /opt/travelcrm-be
dc() { docker compose --project-name travelcrm --env-file .env -f docker-compose.hostinger.yml "$@"; }
```

**a. There must be exactly ONE travelcrm postgres volume.**

```bash
docker volume ls | grep -i travelcrm
```

Expect `travelcrm_postgres_data`. If a second, differently-prefixed postgres volume appears
(`travelcrm-be_postgres_data`, `opt_postgres_data`, …), **stop and ask** — you are about to boot
against the wrong one. Confirm which is live:

```bash
docker inspect -f '{{range .Mounts}}{{.Name}} -> {{.Destination}}{{"\n"}}{{end}}' "$(dc ps -q postgres)"
```

**b. Record the row counts.** Save this output off the server — it is what you compare against in §7.

```bash
dc exec -T postgres psql -U travelcrm -d travel_crm -c \
  "SELECT 'leads' t, count(*) FROM leads
   UNION ALL SELECT 'bookings', count(*) FROM bookings
   UNION ALL SELECT 'customers', count(*) FROM customers
   UNION ALL SELECT 'quotations', count(*) FROM quotations
   UNION ALL SELECT 'users', count(*) FROM users
   UNION ALL SELECT 'tenants', count(*) FROM tenants;"
```

**c. The `lead_type` distribution about to be collapsed** — the only record of the `CORPORATE` vs
`VIP` split once V2 runs.

```bash
dc exec -T postgres psql -U travelcrm -d travel_crm -c \
  "SELECT lead_type, count(*) FROM leads GROUP BY lead_type ORDER BY 2 DESC;"
```

**d. Flyway really is at V1 and nothing else.**

```bash
dc exec -T postgres psql -U travelcrm -d travel_crm -c \
  "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

Expected: exactly one row, `1 | baseline schema | t`. If a version `2` row appears, **stop** — the
situation is different from what this runbook assumes and the checksum path applies instead.

**e. Postgres major version matches the compose pin** (`postgres:16-alpine`) — a mismatch makes the
container refuse to start on the existing data directory:

```bash
dc exec -T postgres postgres --version
```

---

## 4. Verify the server env before pushing

```bash
sudo grep -E '^(FLYWAY_ENABLED|FLYWAY_BASELINE_ON_MIGRATE|APP_FLYWAY_ALLOW_BASELINE_ON_MIGRATE|JPA_DDL_AUTO|SQL_INIT_MODE)=' \
  /etc/travelcrm/travelcrm.env
```

Required values for this deploy:

```
FLYWAY_ENABLED=true
FLYWAY_BASELINE_ON_MIGRATE=false
JPA_DDL_AUTO=validate
SQL_INIT_MODE=never
```

`APP_FLYWAY_ALLOW_BASELINE_ON_MIGRATE` should be absent or `false`. The database is already
baselined at V1, so baseline-on-migrate must stay off — turning it on now would make Flyway stamp
V2 as applied **without running it**, and the app would then fail schema validation on missing
columns.

If `FLYWAY_ENABLED` is missing or `false`, set it to `true` before deploying. Flyway is what applies
V2; without it the new JAR boots against a V1 schema and dies with `Schema-validation: missing
table`.

---

## 5. Push the backend

CI fires on a push to `main` **or** `master`. The live backend branch is **`master`**
(`main` is a stale "1st commit" and is 79 commits behind — do not use it).

`issue-fixes` is `master` + 1 commit, so it fast-forwards cleanly.

```bash
cd "D:/CRM PROJECT/travelcrmbackend"

git status --short          # sanity-check the 205 files
git add -A
git commit -m "Fleet standalone, hotel marketplace, booking trip snapshot, V2 PARTs 9-15"

git checkout master
git merge issue-fixes       # fast-forward
git push origin master
```

> `.gitignore` now excludes `*.java.tmp.*` and `.codex-render/`, so the stray
> `BookingServiceImpl.java.tmp.25176.…` editor artifact and the two render PNGs stay out of the
> commit. The files are left on disk untouched.

### What CI does, in order

1. **`validate-schema`** — compiles, spins up a clean Postgres 16, runs **V1 then V2** on it, runs
   Flyway `validate`, then runs Hibernate schema validation against the migrated schema.
   *This is the real gate.* A fresh DB migrated by V1+V2 is the same shape as prod's V1 DB with V2
   applied on top, so a pass here is direct evidence the production migration will work.
2. **`build-and-push`** — builds the Docker image, tags it `<git-sha>` and `latest`, pushes to
   Docker Hub.
3. **`deploy`** — SSHes to the VPS, uploads the compose files, runs `deploy-hostinger.sh`:
   `docker compose pull` → `up -d` → polls the container health check for up to 5 minutes.

Watch it: `gh run watch` in the repo, or the Actions tab.

**If `validate-schema` fails, nothing reaches the VPS** — that is the design. Fix and re-push.

⚠ **`up -d` replaces the running container.** If the new image fails to boot (migration error, bad
env), the old container is already gone and the API is **down** until you roll back (§8). This is
the window where the backup matters.

---

## 6. Push the frontend

Do this **after** the backend is confirmed healthy — new screens call new endpoints.

The live frontend branch is **`main`**; the work is on **`feature`**.

```bash
cd "D:/CRM PROJECT/travelcrmfe/travelcrmfrontend"

git status --short
git add -A
git commit -m "Booking expense ledger UI, marketplace screens, sidebar and router updates"

git checkout main
git merge feature
git push origin main
```

FE CI builds the bundle with `VITE_API_URL=https://api.mytripsafar.com/api`, **fails the build if
`localhost:8080` survives in `dist/`**, pushes `travelcrm-frontend:<sha>`, and runs the frontend
compose project on `127.0.0.1:5173` under `/opt/travelcrm-frontend`. nginx proxies the SPA vhost
there.

---

## 7. Verify

```bash
# API is up
curl -s https://api.mytripsafar.com/actuator/health          # {"status":"UP"}

# SPA deep link falls back correctly
curl -sI https://mytripsafar.com/Allbookings | head -1        # 200
```

On the VPS:

```bash
cd /opt/travelcrm-be
dc() { docker compose --project-name travelcrm --env-file .env -f docker-compose.hostinger.yml "$@"; }

# *** DATA STILL THERE *** — compare against the §3b baseline. These numbers must be
# EQUAL OR HIGHER. Anything lower, or all zeros, means you are on a different volume:
# stop, do not let users in, and go to §8.
dc exec -T postgres psql -U travelcrm -d travel_crm -c \
  "SELECT 'leads' t, count(*) FROM leads
   UNION ALL SELECT 'bookings', count(*) FROM bookings
   UNION ALL SELECT 'customers', count(*) FROM customers
   UNION ALL SELECT 'quotations', count(*) FROM quotations
   UNION ALL SELECT 'users', count(*) FROM users
   UNION ALL SELECT 'tenants', count(*) FROM tenants;"

# V2 applied and succeeded
dc exec -T postgres psql -U travelcrm -d travel_crm -c \
  "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
#   expect: 1 | baseline schema | t
#           2 | lead code       | t

# lead_type migrated — only the four new values may appear
dc exec -T postgres psql -U travelcrm -d travel_crm -c \
  "SELECT lead_type, count(*) FROM leads GROUP BY lead_type ORDER BY 2 DESC;"

# The plan catalogue is populated — an empty plans table 403s every CRM page
# while the sidebar still renders, which reads as a broken permission system
dc exec -T postgres psql -U travelcrm -d travel_crm -c "SELECT code FROM plans;"   # 3 rows

# Boot was clean
dc logs --tail=200 app | grep -iE "Production config validated|Successfully validated|ERROR|REFUSING"
```

Then click through, logged in as the pilot tenant:

- [ ] Login works
- [ ] Leads list renders and the lead-type pills show Fresh/Hot/Warm/Cold
- [ ] Create a lead → quotation → booking
- [ ] Download a booking PDF
- [ ] Notification bell (SSE) connects
- [ ] `/ai/chat` returns 404 and the chat widget is hidden (AI is off this sprint)

---

## 8. Rollback

**App only** (new code is bad, schema is fine — V2 is additive apart from the `lead_type` rewrite,
so the previous JAR generally still runs):

```bash
cd /opt/travelcrm-be
TRAVELCRM_IMAGE=docker.io/<dockerhub-user>/travelcrm-backend:<previous-sha> ./deploy-hostinger.sh
```

Get `<previous-sha>` from the last successful Actions run, or `docker images | grep travelcrm-backend`.

**Database** (V2 itself failed or did the wrong thing) — restore the §2 dump:

```bash
cd /opt/travelcrm-be
dc() { docker compose --project-name travelcrm --env-file .env -f docker-compose.hostinger.yml "$@"; }

dc stop app
gunzip -c ~/travel_crm-pre-v2-YYYYMMDD-HHMMSS.sql.gz \
  | dc exec -T postgres psql -U travelcrm -d travel_crm
dc start app
```

A partially-applied V2 leaves a `success = f` row in `flyway_schema_history`. Do **not** run
`flyway repair` to clear it — repair rewrites the checksum without running the SQL, and
`ddl-auto=validate` then fails on the columns that were never created. Restore the dump instead.

**"The app came up empty"** — the §7 row counts are all zero. Your data is almost certainly intact
in a different volume, not gone. Do **not** let users in and do **not** start creating records (that
writes into the wrong volume and makes reconciliation genuinely hard).

```bash
dc stop app
docker volume ls | grep -i postgres          # find the other volume
```

Then either point the compose project back at the original volume by declaring it `external`, or —
simpler and safer — restore the §2 dump into the current volume. Either way, **stop first**.

---

## 9. After the deploy

- [ ] Copy the pre-V2 dump to a second location and keep it — it is the only record of the
      `CORPORATE`/`VIP` lead split
- [ ] Confirm the hourly backup cron is still running: `tail /var/log/travelcrm/backup.log`
- [ ] **From now on: new schema change ⇒ `V3__<description>.sql`.** V2 is stamped in production;
      editing it breaks the next boot.
