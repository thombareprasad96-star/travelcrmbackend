niw# Deploy #3 — the Flyway cutover — 5 Aug 2026

Runbook for **this** deploy. It supersedes `docs/DEPLOY_TODAY.md` (which was written for the 3 Aug
deploy and is wrong in two places — noted inline below). `docs/DEPLOYMENT.md` remains the standing
reference.

**What is going out:** backend 217 files (+23 118 / −647) + frontend 37 files (+5 698 / −338).
**What makes it different:** production becomes genuinely Flyway-managed. `V2__lead_code.sql` grows
from PARTs 1–15 (hand-applied on 3 Aug) to PARTs 1–**20**, and after this deploy **V2 is frozen** —
every later schema change is a real `V3__*.sql`.

---

## 0. The one thing that will break the boot if you skip it

`SchemaEnumConstraintValidator` is an `ApplicationRunner` that **refuses to start the app** when a
guarded `CHECK` constraint does not name every current Java enum constant.

`lead_ingest_events_status_check` was, until today, refreshed **only** in `db/indexes.sql:673`.
Turning Flyway on is exactly what stops `db/indexes.sql` from running — `ProductionConfigValidator`
forces `SQL_INIT_MODE=never` whenever `spring.flyway.enabled=true`. `LeadIngestStatus` gained
`QUARANTINED_DUPLICATE` and `QUARANTINED_TRASHED` in this changeset, `V1:72` creates the table with
the old **eight**, and production's table (written by `ddl-auto=update`) carries the same eight.

Net effect had this shipped unchanged: **the new container would never start.** Not a 500 on one
endpoint — no boot at all, on a `up -d` that has already replaced the old container.

Fixed by **V2 PART 20** (added 5 Aug). Verified on a throwaway database: `V1` + `V2` from empty now
yields a ten-value constraint containing both new statuses.

The same class of bug was fixed in the other direction in `db/indexes.sql:228`: that block was an
unguarded `DROP` + `ADD` of a hand-maintained list that had rotted to 63 of the 83
`PlatformAuditAction` constants. Because `indexes.sql` runs **after** Flyway, it did not merely fail
to widen the constraint — it **dropped the correct 83-value one V2 PART 16.3 had just written** and
put the stale one back. It is now guarded and complete. That block only runs on environments still
using `SQL_INIT_MODE=always`, i.e. dev boxes, not this deploy.

---

## 0b. Can this deploy lose tenant data? — audited, with the commands

Re-verified against the current V1 and V2 on 5 Aug (comments stripped first, so a mention inside a
comment cannot pass as evidence):

| Destructive statement | V1 | V2 |
|---|---|---|
| `DROP TABLE` | 0 | 0 |
| `DROP COLUMN` | 0 | 0 |
| `TRUNCATE` | 0 (the one grep hit is a *column name* inside `CREATE TABLE lead_ingest_events`) | 0 |
| `DELETE FROM` | 0 | 0 |
| `DROP SCHEMA` / `DROP DATABASE` | 0 | 0 |

The only two statements that alter an existing object at all:
- `V2:157` `DROP INDEX IF EXISTS uq_users_email_active` — an **index**, deliberate (username-login
  migration), already applied.
- `V2:1582` `ALTER COLUMN doc_type TYPE varchar(32)` — a **widening**, already applied.

**All 20 `UPDATE`s are predicate-guarded, and the five that span multiple lines are all
NULL-backfills** — they can only fill a column that is empty, never overwrite a value someone typed:
`lead_code` (`WHERE lead_code IS NULL`), `users.username` (CTE selects `username IS NULL OR ''`),
`activity_logs.username` (`AND a.username IS NULL`), `customers.phone_normalized`
(`WHERE phone_normalized IS NULL`), `bookings.customer_public_id` (`AND … IS NULL`),
`leads.claim_version` (`WHERE claim_version IS NULL`). The `user_permissions` JSON merges each carry
`AND NOT (permissions_json ? '<key>')`, so they only ever ADD a missing key and cannot revert an
admin's later edit. All 6 `INSERT`s are `ON CONFLICT DO NOTHING` or `NOT EXISTS`-guarded.

**The one genuinely lossy statement already ran.** `V2:833-835` collapses `lead_type`:
`FRESH_LEAD→FRESH`, `REPEAT_CUSTOMER→WARM`, and **`CORPORATE` *and* `VIP` both → `HOT`**, which
cannot be un-mixed. That is PART 1, hand-applied here on 3 Aug, so this deploy re-runs it as a no-op.
Confirm before starting — if this returns only the new values, it has already happened:

```bash
dc exec -T postgres psql -U travelcrm -d travel_crm -c \
  "SELECT lead_type, count(*) FROM leads GROUP BY 1 ORDER BY 2 DESC;"
```

**The pipeline cannot delete the database.** Neither repo's deploy script or workflow contains
`compose down`, `down -v`, `volume prune`, `volume rm`, or `system prune`. The only prune is
`docker image prune -f --filter until=168h` — images, never volumes. `--remove-orphans` removes only
containers whose *service is undefined*, and `postgres` is a defined service. `app.seed.enabled=false`
is pinned in `application-prod.properties`, and `ProductionConfigValidator` refuses to boot on
`ddl-auto=create/create-drop`.

**So the real risk is not deletion — it is the volume not re-attaching.** If `up -d` ever ran under a
different compose project name, it mints a *fresh empty* `travelcrm_postgres_data` and the CRM comes
up blank while the real data sits orphaned in the other volume. At a glance that is indistinguishable
from total loss. §3 records the row counts and volume list beforehand precisely so §7 can tell the
two apart. Git history says the project name never changed, so this is a check, not an expectation.

**And Flyway itself cannot half-apply.** V2 runs in a single transaction (§4), so a failure rolls the
whole thing back and leaves the schema untouched.

None of this replaces §2. Take the backup.

---

## 1. Establish production's real Flyway state — do this FIRST

Everything below branches on the answer, and `docs/DEPLOY_TODAY.md:186-190` asserts something that
is probably no longer true (it claims one `1 | baseline schema | t` row exists).

```bash
cd /opt/travelcrm-be
dc() { docker compose --project-name travelcrm --env-file .env -f docker-compose.hostinger.yml "$@"; }

# a. Is Flyway even switched on?
sudo grep -E '^(FLYWAY_|APP_FLYWAY_|JPA_DDL_AUTO|SQL_INIT_MODE|SPRING_PROFILES_ACTIVE)=' \
  /etc/travelcrm/travelcrm.env

# b. Does the history table exist at all?
dc exec -T postgres psql -U travelcrm -d travel_crm -c \
  "SELECT to_regclass('public.flyway_schema_history') AS history_table;"

# c. If it exists — what is in it?
dc exec -T postgres psql -U travelcrm -d travel_crm -c \
  "SELECT installed_rank, version, description, type, checksum, success
     FROM flyway_schema_history ORDER BY installed_rank;"
```

| Result | What it means | What to do |
|---|---|---|
| `history_table` is **NULL** | Flyway has never run here. The expected state. | Proceed with §5 as written — `baseline-on-migrate` fires, stamps version 1, then applies V2. |
| Table exists, **0 rows** | ⚠ **The trap.** `baseline-on-migrate` fires only when the table is *absent*. With an empty table present Flyway skips baselining and tries to apply **V1**, which aborts on the 54 unguarded foreign keys at `V1:379`. | `DROP TABLE flyway_schema_history;` first, then §5 unchanged. |
| Table exists with `1 \| baseline schema \| t` | Already baselined at V1 (what `DEPLOY_TODAY.md` assumed). Nothing to baseline. | In §5 set `FLYWAY_BASELINE_ON_MIGRATE=false` and drop `APP_FLYWAY_ALLOW_BASELINE_ON_MIGRATE` entirely. Everything else is identical — Flyway applies V2 the same way. |
| A `2 \| lead code \| … \| t` row exists | V2 has already been applied *and stamped* here. | Stop and re-read — this runbook assumes it has not. The checksum must equal `208603621` or the boot will fail with a mismatch. |
| Any row with `success = f` | A previous migration failed halfway. | Stop. Restore the dump; do **not** `flyway repair`. |

---

## 2. Back up, and get the backup off the box

```bash
dc exec -T postgres pg_dump -U travelcrm -d travel_crm \
  | gzip > "$HOME/travel_crm-pre-cutover-$(date +%Y%m%d-%H%M%S).sql.gz"
ls -lh "$HOME"/travel_crm-pre-cutover-*.sql.gz     # under 10 KB means the dump FAILED
```
```bash
# from the laptop
scp root@187.127.159.81:'~/travel_crm-pre-cutover-*.sql.gz' "D:/CRM PROJECT/backups/"
```

---

## 3. Record the pre-state — this is what proves nothing was lost

```bash
docker volume ls | grep -i travelcrm          # expect exactly ONE: travelcrm_postgres_data

dc exec -T postgres psql -U travelcrm -d travel_crm -c \
 "SELECT 'leads' t,count(*) FROM leads
  UNION ALL SELECT 'bookings',count(*)   FROM bookings
  UNION ALL SELECT 'customers',count(*)  FROM customers
  UNION ALL SELECT 'quotations',count(*) FROM quotations
  UNION ALL SELECT 'users',count(*)      FROM users
  UNION ALL SELECT 'tenants',count(*)    FROM tenants;"

# PART 19 guard — this number must be UNCHANGED afterwards
dc exec -T postgres psql -U travelcrm -d travel_crm -c \
 "SELECT count(DISTINCT tenant_id) AS tenants_with_snapshot FROM tenant_modules;"
```

### Pre-conditions that make V2 abort if violated

Every one of these must come back clean, or the migration stops partway.

```bash
dc exec -T postgres psql -U travelcrm -d travel_crm -c "
-- tables PART 18 alters without a to_regclass guard
SELECT to_regclass('public.tasks')            AS tasks,
       to_regclass('public.tenant_settings')  AS tenant_settings,
       to_regclass('public.notifications')    AS notifications,
       to_regclass('public.lead_ingest_events') AS lead_ingest_events;   -- all four NON-NULL

-- PART 18 rebuilds notifications_reference_type_check with a bare ADD
SELECT DISTINCT reference_type FROM notifications;
--   allowed: NULL, LEAD, BOOKING, REMINDER, CUSTOMER, VENDOR, TASK

-- PART 16 rebuilds the marketplace status check
SELECT DISTINCT status FROM platform_hotel_bookings;
--   allowed: REQUESTED, UNDER_REVIEW, TENANT_APPROVAL_REQUIRED, TENANT_ACCEPTED, CONFIRMED,
--            REJECTED, CANCEL_REQUESTED, CANCELLATION_QUOTED, CANCELLED, EXPIRED

-- the plan-enum loop re-adds CHECK (col IN ('STARTER','PRO','ENTERPRISE','FLEET'))
SELECT DISTINCT plan FROM tenants UNION SELECT DISTINCT code FROM plans;
"
```

---

## 4. Flyway applies V2 — this is the last deploy that needs any of this

**Flyway runs the migration during container start. No `psql` step.** That is the point of the
cutover: from V3 onwards a schema change ships by committing a file, and nothing is done by hand
again.

The obvious objection is the one `deploy-hostinger.sh` creates — it runs `up -d` **before** health is
known, so a migration that fails or drags leaves the API down with no automatic rollback. Measured,
that objection does not survive:

| Measured on a real Postgres | Duration |
|---|---|
| `V1` on an empty database | **2 s** |
| `V2`, all 20 PARTs, first run | **2 s** |
| `V2` re-run (what prod does over the already-applied PARTs 1–15) | **< 1 s** |

Against a 90 s `start_period` plus a 300 s health poll. Production carries pilot-sized data, so the
two row-touching statements (`UPDATE leads SET claim_version = 0`, PART 1's `lead_type` rewrite) add
milliseconds, not minutes.

**And a failure cannot leave a mess.** Postgres does DDL transactionally, and V2 contains no
`CONCURRENTLY`, `VACUUM`, `ALTER TYPE` or explicit `BEGIN/COMMIT` — the only things that would make
Flyway demote it out of a transaction. So the whole file runs as **one transaction**: it either
applies completely or rolls back completely, writing **no history row at all**. The failure mode is
a crash-looping container on an untouched schema, not a half-migrated database.

So the residual risk is downtime-until-you-redeploy-the-previous-tag, and §3's pre-flight checks are
what shrink it. Run them. They cover every statement in V2 that can be rejected by existing rows.

### Optional but cheap: rehearse against the real production schema

The only thing §3 cannot prove is "V2 on top of *prod's actual* schema shape". One command does,
using a schema-only dump (no tenant data leaves the server):

```bash
dc exec -T postgres pg_dump -U travelcrm -d travel_crm --schema-only --no-owner --no-privileges \
  > "$HOME/prod-schema.sql"
```
```powershell
scp root@187.127.159.81:'~/prod-schema.sql' "D:\CRM PROJECT\travelcrmbackend\prod-schema.sql"
cd "D:\CRM PROJECT\travelcrmbackend"
& "C:\Program Files\PostgreSQL\18\bin\createdb.exe" -U postgres travel_crm_prodclone
$env:PGPASSWORD="Admin123"
& "C:\Program Files\PostgreSQL\18\bin\psql.exe" -U postgres -d travel_crm_prodclone -v ON_ERROR_STOP=1 -f .\prod-schema.sql

.\scripts\predeploy-flyway-shadow-check.ps1 `
  -CloneTargetSchema `
  -TargetJdbcUrl "jdbc:postgresql://localhost:5432/travel_crm_prodclone" `
  -TargetUsername postgres -TargetPassword Admin123 `
  -BaselineExistingSchema -BaselineVersion 1
```

It spins up a throwaway Postgres container, clones the schema, baselines at 1, runs V2, then runs the
same Hibernate `validate` the app performs at boot. Must end with
`Shadow Flyway/JPA schema check passed.` Blind spot to keep in mind: the clone has **zero rows**, so
it cannot exercise "an existing row violates the new constraint" — that is what §3 is for.

### After the deploy: verify PARTs 16–20 actually landed

```bash
dc exec -T postgres psql -U travelcrm -d travel_crm -c "
SELECT count(*) AS p16_cols FROM information_schema.columns
 WHERE table_name='platform_hotel_bookings' AND column_name IN
 ('quoted_cancellation_charge','quoted_retained_earning','cancellation_quote_note',
  'cancellation_quoted_at','cancellation_quote_expires_at');                        -- 5
SELECT to_regclass('public.platform_hotel_voucher_files') AS p16_voucher_table;     -- not null
SELECT count(*) AS p17_comm_tables FROM information_schema.tables
 WHERE table_schema='public' AND table_name LIKE 'comm\\_%';                        -- 12
SELECT count(*) AS p18_lead_cols FROM information_schema.columns
 WHERE table_name='leads' AND column_name IN
 ('claim_version','first_contacted_at','first_contacted_by_user_id',
  'first_response_seconds','sla_target_seconds');                                   -- 5
SELECT to_regclass('public.lead_assignment_events') AS p18_events_table;            -- not null
SELECT count(*) AS p18_task_cols FROM information_schema.columns
 WHERE table_name='tasks' AND column_name IN
 ('booking_id_ref','booking_public_id','booking_code','customer_name_snapshot',
  'trip_source','owner_name','overdue_notified_at');                                -- 7
SELECT count(*) AS p18_tenant_settings_cols FROM information_schema.columns
 WHERE table_name='tenant_settings' AND column_name IN
 ('task_overdue_alert_whatsapp','task_overdue_alert_email');                        -- 2
SELECT count(*) AS p19_starter_still_at_500 FROM tenants
 WHERE deleted_at IS NULL AND plan='STARTER' AND quota_override=false AND max_leads=500;  -- 0
SELECT count(DISTINCT tenant_id) AS tenants_with_snapshot FROM tenant_modules;      -- SAME as §3
"
```

### The two constraints that decide whether the app boots

```bash
dc exec -T postgres psql -U travelcrm -d travel_crm -c "
SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
WHERE conname IN ('lead_ingest_events_status_check','platform_audit_logs_action_check');"
```
`lead_ingest_events_status_check` **must** contain `QUARANTINED_DUPLICATE` and `QUARANTINED_TRASHED`
(PART 20). `platform_audit_logs_action_check` **must** contain `MARKETPLACE_CANCELLATION_QUOTED`
(PART 16.3). Either one missing ⇒ `SchemaEnumConstraintValidator` refuses the boot.

---

## 5. Set the FIRST-BOOT environment

`/etc/travelcrm/travelcrm.env`:

```
FLYWAY_ENABLED=true
FLYWAY_BASELINE_ON_MIGRATE=true
FLYWAY_BASELINE_VERSION=1
APP_FLYWAY_ALLOW_BASELINE_ON_MIGRATE=true
JPA_DDL_AUTO=validate
SQL_INIT_MODE=never
SPRING_JPA_DEFER_DATASOURCE_INITIALIZATION=false
```

**Baseline at 1.** Flyway stamps a `BASELINE` row at version 1, resolves V1 as `BELOW_BASELINE` and
skips it, then **applies V2 in full** — which is what you want, because V2 is the only thing carrying
PARTs 16–20 and production has never seen them.

Skipping V1 is not a convenience, it is required: `V1__baseline_schema.sql:379-432` holds 54
`ALTER TABLE … ADD CONSTRAINT <name> FOREIGN KEY` statements with **no** `IF NOT EXISTS` (the
`IF EXISTS` there guards the *table*, not the constraint). 24 of them carry Hibernate hash names that
already exist on this `ddl-auto`-built schema, so V1 would abort at line 379 with SQLSTATE 42710.
It destroys nothing — V1 has zero `DROP`/`DELETE`/`UPDATE` — but it kills the boot.

**Never `FLYWAY_BASELINE_VERSION=2` here.** That stamps V2 as applied *without running it*, and
`ddl-auto=validate` then kills the boot on 14 missing tables (12 `comm_*`,
`platform_hotel_voucher_files`, `lead_assignment_events`). Baseline 2 is only correct if V2 was
applied by hand first — that was the earlier plan and it is no longer the one.

Baseline 1 also buys the thing that makes this the *last* manual cutover: Flyway records V2 as a real
`SQL` row **with its checksum**, so `validate-on-migrate=true` mechanically refuses any future boot
where V2 has been edited. Under baseline 2 that check is skipped for every version ≤ 2
(`MigrationInfoImpl.validate()` requires `version > appliedBaseline`), and an edited V2 would boot
*silently without the new SQL*.

`SPRING_JPA_DEFER_DATASOURCE_INITIALIZATION=false` is not optional folklore: it is hard-coded `true`
at `application.properties:129` with no prod override, and the only configuration ever observed to
boot with Flyway on (`application-local.properties:52`) sets it to `false`. With
`SQL_INIT_MODE=never` there is nothing to defer, so the setting has no downside and removes the last
unrehearsed variable.

`APP_FLYWAY_ALLOW_BASELINE_ON_MIGRATE` is a deliberate second switch — `ProductionConfigValidator`
refuses the boot if `baseline-on-migrate` is on without it, so the flag cannot linger unnoticed.

Also confirm these are **not** in the file (both are vetoed by the validator, but check anyway):
`APP_SEED_ENABLED`, `APP_SUPER_ADMIN_DEV_LOGIN_ENABLED`.

---

## 6. Ship the backend

The live branch is **`master`**. `main` is a stale "1st commit" — pushing there deploys nothing.

`origin/master` (`3d986d6`) and local `issue-fixes` (`8e80308`) have diverged because `issue-fixes`
was rebased **after** master was fast-forwarded from it. `git diff --diff-filter=D origin/master
issue-fixes` is empty: **nothing on master is absent from issue-fixes**, so no content decision is
needed. But a plain `git merge` hits ~37 files / ~87 hunks of *fake* conflicts (identical add/add
from the rebase), and `git merge -X theirs` silently resurrects 23 lines in `docs/BUG_LIST.md` that
`issue-fixes` deliberately replaced.

The merge that carries `issue-fixes`' tree verbatim while staying a fast-forward push:

```bash
cd "D:/CRM PROJECT/travelcrmbackend"
git fetch origin
git status --porcelain                            # must be EMPTY

git checkout master
git merge --ff-only origin/master

git merge --no-commit --no-ff -s ours issue-fixes
git read-tree -u --reset issue-fixes
git commit -m "Merge issue-fixes into master: All Tasks, lead claim window, Communication Center, V2 PARTs 16-20, Flyway cutover"

git diff --stat HEAD issue-fixes                  # must print NOTHING
git merge-base --is-ancestor origin/master HEAD && echo FF-OK

git push origin master                            # <-- this IS the deploy
```

### What CI does

1. **`validate-schema`** — clean Postgres 16, applies V1 then V2, `flyway validate`, then Hibernate
   schema validation. **If this fails nothing reaches the VPS.**
2. **`build-and-push`** — Docker image, tags `<git-sha>` + `latest`.
3. **`deploy`** — SSH, `docker compose pull` → `up -d` → poll health for ≤ 300 s.

Two gaps worth knowing:
- CI's Hibernate check validates **tables, columns and types only — never CHECK constraints.** The
  PART 20 class of failure is invisible to it. §4's constraint query is the real gate.
- **No job runs `mvn test`.** Run `./mvnw.cmd test` locally before pushing. (Done for this
  changeset: 534 tests, 0 failures.)

---

## 7. Verify

```bash
curl -s https://api.mytripsafar.com/actuator/health          # {"status":"UP"}

dc exec -T postgres psql -U travelcrm -d travel_crm -c \
 "SELECT installed_rank, version, description, type, checksum, success
    FROM flyway_schema_history ORDER BY installed_rank;"
```
**Expect exactly two rows:**

```
 installed_rank | version |       description        |   type   | checksum  | success
----------------+---------+--------------------------+----------+-----------+---------
              1 | 1       | Existing schema before…  | BASELINE |    (null) | t
              2 | 2       | lead code                | SQL      | 208603621 | t
```

`208603621` is the checksum of the V2 in this changeset (it moved from `-287096334` when PART 20 was
added). A different number means a different file was deployed. Only **one** row, at version 2 with
type `BASELINE`, means baseline-version was set to 2 and **V2 never ran** — stop, the app is about to
fail Hibernate validate on the `comm_*` tables.

```bash
dc logs --tail=300 app | grep -iE \
  "Production config validated|Successfully validated|Schema enum-constraint check passed|REFUSING|ERROR"
```
Want to see: `Production config validated`, `Schema enum-constraint check passed for N guarded
column(s)`, `Started TravelcrmApplication`.

Then re-run the §3 row counts — **equal or higher**, never lower — and `SELECT code FROM plans;`
(3 rows; an empty `plans` table 403s every CRM page while the sidebar still renders, which reads as
a broken permission system).

Click-through: login → leads list → create lead → quotation → booking → download a booking PDF →
notification bell connects. **Also open a Modern and a Premium quotation PDF** — this deploy replaces
both templates (vehicle `imagePath` rendering).

---

## 8. Steady state — same session, don't defer it

Remove both baseline flags:

```
FLYWAY_ENABLED=true
JPA_DDL_AUTO=validate
SQL_INIT_MODE=never
SPRING_JPA_DEFER_DATASOURCE_INITIALIZATION=false
# FLYWAY_BASELINE_ON_MIGRATE            -> REMOVED
# APP_FLYWAY_ALLOW_BASELINE_ON_MIGRATE  -> REMOVED
# FLYWAY_BASELINE_VERSION               -> REMOVED (inert once the row exists; the default is 1 anyway)
```
`dc up -d --force-recreate app`, confirm green. With both removed the validator is armed again and
will refuse any future boot that re-enables baseline-on-migrate.

---

## 9. What starts firing the moment this is live

| Thing | Cadence | Kill switch |
|---|---|---|
| `TaskOverdueScanner` — new, **every 60 s from boot**; the first tick alerts *every* already-overdue task (100/tick cap) | 60 s | `APP_TASK_OVERDUE_SCAN_ENABLED=false` |
| `WhatsAppNotificationChannel` — new delivery channel; **skipped unless the tenant has WhatsApp configured**, and the template needs exactly 2 body variables | per alert | tenant setting `task_overdue_alert_whatsapp` (defaults **false**) |
| `notificationExecutor` — the EMAIL channel had `@Async("notificationExecutor")` with no such bean and had therefore **never run**; it now does | per alert | tenant setting `task_overdue_alert_email` (defaults **false**) |
| Marketplace cancellation-quote expiry, folded into the existing 5-min sweep | 5 min | none (24 h validity is a literal) |

Both `tenant_settings` toggles default to `false`, so the WhatsApp/email blast is opt-in. In-app
notifications are not.

---

## 10. Rollback

- **App bad, schema fine:**
  `TRAVELCRM_IMAGE=docker.io/<user>/travelcrm-backend:<previous-sha> ./deploy-hostinger.sh`
- **V2 wrong:** `dc stop app` → `gunzip -c ~/travel_crm-pre-cutover-*.sql.gz | dc exec -T postgres psql -U travelcrm -d travel_crm` → `dc start app`
- **Never `flyway repair`.** It rewrites the recorded checksum without executing the SQL, and
  `ddl-auto=validate` then dies on columns that were never created. If V2 failed, fix the SQL and
  redeploy — there is no partial state to repair (see the box below).
- **"The app came up empty"** — §3 counts are all zero. The data is almost certainly intact in a
  *different* volume, not gone. `dc stop app` immediately; do not let users in and do not create
  records, that writes into the wrong volume. `docker volume ls | grep -i postgres`.

> **A failed migration leaves nothing behind — verified on the current files.** Counted with comments
> stripped, V1 and V2 contain **0** `CONCURRENTLY`, **0** `VACUUM`, **0** `ALTER TYPE … ADD VALUE`,
> **0** `REINDEX`, **0** explicit `BEGIN`/`COMMIT`, **0** `flyway:executeInTransaction` directives —
> i.e. none of the statements that make Flyway demote a migration out of its transaction. (`V2:1582`
> is `ALTER TABLE … ALTER COLUMN … TYPE`, a different statement, and it is transactional.) So Flyway
> runs the whole file in one transaction: it either applies completely or rolls back completely and
> writes **no history row at all**. The container crash-loops on an untouched schema.
>
> This corrects `DEPLOY_TODAY.md:73,376`, which says a failure leaves a `success = f` row. That
> becomes true only the day someone adds a non-transactional statement.

---

## 11. Frontend — after the backend is confirmed healthy

`origin/main` is an ancestor of `feature`, so this is a clean fast-forward:

```bash
cd "D:/CRM PROJECT/travelcrmfe/travelcrmfrontend"
git fetch origin
git status --porcelain                              # must be EMPTY
git merge-base --is-ancestor origin/main feature && echo FF-OK
git push origin feature:main
```

---

## 12. After the deploy — the rule that changes today

**V2 is now applied in production. The next schema change is `V3__<description>.sql`.**

Because the baseline is 1, V2 is recorded as a real `SQL` row with checksum `208603621`, and
`validate-on-migrate=true` compares it on **every** boot. Editing V2 from now on does not fail
quietly — the next deploy dies with `Migration checksum mismatch for migration version 2` before the
EntityManagerFactory is built. That is the intended behaviour and the reason baseline 1 was chosen
over baseline 2, where the same check is skipped for every version ≤ the baseline.

Which also means: **do not "fix" a checksum mismatch with `flyway repair`.** It rewrites the recorded
checksum without executing the SQL, and `ddl-auto=validate` then dies on the columns that were never
created. Write a V3.

And from here on, deploying a schema change is: commit the `V3__*.sql`, push `master`. Flyway applies
it during container start. Nothing is run by hand — that was the entire point of this cutover.

Do not name the new file `V3__hotel_marketplace.sql`,
`V3__fleet_expenses_compliance_and_product_family.sql` or `V3__lead_booking_fe_alignment.sql` —
three older design docs already reference those names for work that shipped as PARTs of V2.

Documentation that still says "append a PART to V2" and must be corrected:
`V2__lead_code.sql:86,203,294,299,399,497,556,866` (kept as history — see the END OF V2 block),
`application.properties:71-92`, `application-prod.properties:36-45`,
`deploy/travelcrm.env.example:19-41`, `docs/DEPLOYMENT.md:258,273,281-301`, `docs/BUG_LIST.md:860`,
`docs/booking-financial-engine-plan.md:21,459,586`, `docs/FLEET_MODULE_REDESIGN.md:687`,
`docs/BACKEND-CONTRACT-LEAD-BOOKING.md:24,112`, `docs/ALL_TASKS_MODULE_DESIGN.md:5,340,373`,
`docs/COMMUNICATION_CENTER_DESIGN.md:37,204`, `CLAUDE.md:292,787`, and — highest leverage, because
it is an instruction to an agent — `.claude/agents/travelcrm-architect.md:32`, which currently says
"**No Flyway.** … Never add Flyway or migration files."
