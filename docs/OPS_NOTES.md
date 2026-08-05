# Ops notes — running log and open checklist

One file to look at before touching production. Detail lives in two places:
`docs/DEPLOY_FLYWAY_CUTOVER.md` (how to deploy) and `docs/DEPLOY_AUTOMATION_PLAN.md` (how to make
deploying boring). This file is the state and the TODO.

---

## Current production state — 5 Aug 2026

| | |
|---|---|
| Host | Hostinger KVM2 `187.127.159.81`, Ubuntu 24.04, `srv1631316.hstgr.cloud` |
| Topology | split-origin: `mytripsafar.com` + `www` = SPA · `api.mytripsafar.com` = Spring Boot on `127.0.0.1:8080` |
| Postgres | **inside Docker**, compose service `postgres`, volume `travelcrm_postgres_data`, project `travelcrm`. No host Postgres — every `psql`/`pg_dump` goes through `dc exec -T postgres`. |
| Backend branch | **`master`** (`main` is a stale "1st commit" — pushing there deploys nothing) |
| Frontend branch | **`main`** (work branch `feature`); repo is `D:/CRM PROJECT/travelcrmfe/travelcrmfrontend` |
| Deployed BE commit | `b65f7f0` — merge of `issue-fixes` (`f80e125`) into `master` (`3d986d6`) |
| Schema | **Flyway-managed as of today.** V2 carries PARTs 1–20; checksum `208603621`. |
| Data (pre-deploy) | leads 11 · bookings 1 · customers 1 · quotations 15 · users 5 · tenants 2 · `tenant_modules` distinct tenants **2** |

Production env keys that matter (`/etc/travelcrm/travelcrm.env`, backups at `*.bak`):

```
SPRING_PROFILES_ACTIVE=prod
JPA_DDL_AUTO=validate
SQL_INIT_MODE=never
FLYWAY_ENABLED=true
FLYWAY_BASELINE_ON_MIGRATE=true          ← REMOVE, see checklist
FLYWAY_BASELINE_VERSION=1                ← REMOVE, see checklist
APP_FLYWAY_ALLOW_BASELINE_ON_MIGRATE=true ← REMOVE, see checklist
SPRING_JPA_DEFER_DATASOURCE_INITIALIZATION=false
```

---

## What shipped today

**The Flyway cutover.** Production was previously not Flyway-managed at all: `flyway_schema_history`
did not exist, V1 had never run, and V2 PARTs 1–15 had been applied by hand on 3 Aug. This deploy
turned Flyway on, baselined at version 1 (so V1 is skipped — it has 54 unguarded
`ADD CONSTRAINT FOREIGN KEY` statements that abort on an existing schema) and let Flyway apply V2 in
full, which is what carried PARTs 16–20 to production for the first time.

Also shipped: All Tasks module, lead claim window, lead stats summary, Communication Center schema,
marketplace cancellation consent + vouchers, Basic-plan repair, and the `imagePath` quotation PDF
templates (Modern + Premium).

**Observed:** health 200 → 502 at 12:29:06 → 200 at 12:29:49. **43 s of downtime**, then healthy.
The container booting at all is proof that `ProductionConfigValidator`, Flyway,
`SchemaEnumConstraintValidator` and `ddl-auto=validate` all passed — any of them failing crash-loops
the container and health never returns 200.

### The defect that was caught on the way

`lead_ingest_events_status_check` carried **8** values in production; `LeadIngestStatus` has **10**
(`QUARANTINED_DUPLICATE`, `QUARANTINED_TRASHED` were added in this changeset). The widening lived
**only** in `db/indexes.sql:673` — and turning Flyway on is exactly what stops `db/indexes.sql` from
running, because `ProductionConfigValidator` forces `SQL_INIT_MODE=never`.
`SchemaEnumConstraintValidator` would then have refused the boot, *after* `up -d` had already
destroyed the old container.

Verified against the live database before deploying — it really did have 8. Fixed as **V2 PART 20**
(guarded, idempotent) and proven on a throwaway DB: fresh V1+V2 now yields all ten values.

The same commit fixed the mirror-image bug at `db/indexes.sql:228`: an unguarded `DROP`+`ADD` of a
hand-maintained list that had rotted to 63 of the 83 `PlatformAuditAction` constants. Because
`indexes.sql` runs *after* Flyway, it was **dropping the correct 83-value constraint V2 PART 16.3 had
just written** and putting the stale one back.

---

## OPEN — do these next

### Immediately (production is in a temporary state)

- [ ] **Run the cutover verification** — never executed. Expect exactly two rows:
      `1 | 1 | … | BASELINE | (null) | t` and `2 | 2 | lead code | SQL | 208603621 | t`.
      ```bash
      dc exec -T postgres psql -U travelcrm -d travel_crm -c \
        "SELECT installed_rank,version,description,type,checksum,success FROM flyway_schema_history ORDER BY installed_rank;"
      ```
- [ ] **Remove the three baseline flags** from `/etc/travelcrm/travelcrm.env`
      (`FLYWAY_BASELINE_ON_MIGRATE`, `FLYWAY_BASELINE_VERSION`, `APP_FLYWAY_ALLOW_BASELINE_ON_MIGRATE`),
      then `dc up -d --force-recreate app`. Leaving them on means a future history loss could silently
      re-baseline instead of failing loudly.
- [ ] Re-run the §3 row counts and confirm **equal or higher**, and `tenant_modules` distinct tenants
      still exactly **2**.
- [ ] `SELECT code FROM plans;` → 3 rows. An empty catalogue 403s every CRM page while the sidebar
      still renders, which reads as a broken permission system.
- [ ] **Open a Modern and a Premium quotation PDF.** This deploy replaced both templates.
- [ ] Deploy the frontend (`git push origin feature:main`) — clean fast-forward, `origin/main` is an
      ancestor of `feature`.

### The rule that changed today

**V2 is now applied in production. The next schema change is `V3__<description>.sql`.** Because the
baseline is 1, V2 is recorded as a real `SQL` row *with its checksum*, so editing it fails the next
boot loudly with `Migration checksum mismatch` — that is intended. Do not "fix" that with
`flyway repair`; it rewrites the checksum without running the SQL and `ddl-auto=validate` then dies
on columns that were never created. Write a V3.

Do not name it `V3__hotel_marketplace.sql`, `V3__fleet_expenses_compliance_and_product_family.sql` or
`V3__lead_booking_fe_alignment.sql` — three older design docs already reference those names for work
that shipped as PARTs of V2.

### Automation — Phase 1 (one day; full detail in `docs/DEPLOY_AUTOMATION_PLAN.md`)

- [ ] `logging: {max-size: 20m, max-file: 5}` on both compose services — **5 min.** Docker's
      `json-file` logs are currently unbounded; daily deploys turn this into a disk-full outage, and a
      full `/` takes Postgres with it.
- [ ] `deploy-hostinger.sh:58-71` `env_value` first-match → **last**-match — **10 min.** Compose uses
      last-wins, so a duplicated key can let `reject_placeholder_secret` pass a real value while the
      container receives a placeholder.
- [ ] `--no-deps app` on `deploy-hostinger.sh:136` — **5 min.** Stops a compose-file edit from
      recreating the Postgres container.
- [ ] Drop `-DskipTests` → `./mvnw -B verify` in CI — **30 min.** 496 test methods currently protect
      nothing in the pipeline.
- [ ] **`boot-check` CI job** — **2 h.** Start the packaged jar, prod profile, against a
      Flyway-migrated CI Postgres with `SQL_INIT_MODE=never`; assert `Schema enum-constraint check
      passed`, `Production config validated`, `Started TravelcrmApplication`. **This is the gate that
      would have caught today's defect.** Full YAML in the plan doc.
- [ ] Fix the artifact split — `Dockerfile:15` rebuilds with `-DskipTests` inside the image, so the
      jar that ships is never the jar CI validated. Upload the jar as an artifact and make the
      Dockerfile `FROM eclipse-temurin:21-jre-jammy` + `COPY`.
- [ ] Docker-native backup (`dc exec -T postgres pg_dump`, note the `-T`) + systemd timer + a
      pre-deploy backup step in CI + weekly `restore-test.sh` — **3 h.**
- [ ] UptimeRobot (keyword `"UP"`, plus its free SSL-expiry alert) + healthchecks.io dead-man's switch
      on the backup + a deploy notification — **1 h.**

### Phase 2 / 3

- [ ] Auto-rollback to the previous image digest on health failure (~10 lines) — do this **before**
      blue/green.
- [ ] ShedLock on the 20 `@Scheduled` methods — **hard prerequisite for blue/green.**
- [ ] `mem_limit` on the app services — **hard prerequisite for blue/green** (`-XX:MaxRAMPercentage=75`
      of *host* RAM × 2 containers = 150% committed; the OOM killer takes Postgres too).
- [ ] Healthcheck → `/actuator/health/readiness`, interval 30 s → 5 s.
- [ ] Blue/green: two ports + an nginx `upstream` file + graceful reload.
- [ ] SOPS + age for `/etc/travelcrm/travelcrm.env`.
- [ ] `verify-deploy.sh snapshot|verify` as the last CI step, exit code driving rollback.

---

## Landmines — read before the next deploy

1. **`deploy/backup-db.sh` cannot work on this topology.** `:84` calls `sudo -u postgres pg_dump`,
   but there is no host `postgres` role — Postgres is a compose service. Nothing in version control
   installs it either. **Unverified whether the hourly cron exists and has been silently failing.**
   Check: `sudo crontab -l` · `ls -lt /var/backups/travelcrm/hourly/ | head`
2. **`LeadClaimConcurrencyIT` has never executed — anywhere.** `…IT.java` matches none of surefire's
   default includes and there is no failsafe plugin. The test that proves two agents claiming one
   lead yield one owner has never run.
3. **20 `@Scheduled` methods, no ShedLock.** `CampaignDispatchService.sendBatch` selects `PENDING`
   recipients with no row lock and no `@Version` — two live containers means duplicate WhatsApp/email
   to real customers.
4. **`/actuator/health` can answer 200 UP on a container that is about to die.** Spring starts the web
   server during context refresh but runs `ApplicationRunner`s afterwards, and
   `SchemaEnumConstraintValidator` is one that throws. Use `/actuator/health/readiness` for anything
   that gates traffic.
5. **`git merge issue-fixes` into `master` is not a fast-forward** and produces ~37 files of *fake*
   conflicts (identical add/add, from a rebase). `-X theirs` silently resurrects 23 deleted lines in
   `docs/BUG_LIST.md`. Use the `commit-tree` recipe: build a merge commit whose tree is exactly
   `issue-fixes`, with parents `origin/master` + `issue-fixes`.
6. **A push to backend `master` or frontend `main` IS a production deploy** — GitHub Actions fires on
   push. Pushing a work branch is safe and triggers nothing.

## Verify on the box when there is a spare five minutes

- Host RAM (`free -g`) — the blue/green `mem_limit: 2g` per service assumes ≥8 GB.
- `/var/lib/docker` size and image count — `deploy-hostinger.sh:152` prunes only `until=168h`.
- Which SPA topology is live: host-static (`nginx-travelcrm.conf:270`) or containerised
  (`nginx-frontend-docker.conf:29`). **Both are in version control.**
- Whether `/etc/docker/daemon.json` already caps log size.
