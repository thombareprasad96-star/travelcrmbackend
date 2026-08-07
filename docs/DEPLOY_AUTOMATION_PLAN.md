# Daily-deploy automation plan — 5 Aug 2026

Written the day the Flyway cutover shipped, from what that deploy actually exposed. Every claim
below cites a file:line or an observation from the deploy itself. This is a plan, not a changelog —
nothing here is implemented yet.

**The goal:** deploying should be a non-event that happens whenever a change is ready, not a
90-minute supervised operation.

---

## 0. What today proved

| Observation | Evidence |
|---|---|
| **A deploy is currently irreversible.** `up -d` destroys the old container before the new one is known good; on failure the script prints logs and `exit 1`, leaving production down. | `deploy/deploy-hostinger.sh:136` then the health poll at `:145-162`. No rollback branch exists. |
| **43 s of hard downtime is the *success* path.** | Observed 12:29:06 → 502, 12:29:49 → 200. The app publishes a host port (`docker-compose.hostinger.yml:40`), so Compose must release it before the new container can bind — the recreate cannot overlap. |
| **CI cannot see a boot-blocking defect.** `lead_ingest_events_status_check` had 8 values, `LeadIngestStatus` had 10, and CI passed. | CI never starts the app; `ValidateHibernateSchema.java:44` uses Hibernate's `SchemaValidator`, which checks tables/columns/types and **never reads `pg_constraint`**. |
| **No test has ever run in the pipeline.** | `deploy-hostinger.yml:50`, `flyway-schema-validation.yml:45`, `Dockerfile:11,15` — every Maven invocation is `-DskipTests`. 56 test files, 496 test methods. |
| **The jar that ships was never the jar that was validated.** `Dockerfile:15` runs a second, independent `mvnw clean package -DskipTests` inside the image build. | `Dockerfile:15` |
| **Production config is hand-edited over SSH.** Today that produced `/etc/travelcrm/travelcrm.en` (typo, silently ignored) and the same key appended three times. | Observed during the cutover. |
| **Every verification was a hand-typed `psql`.** Row counts, volume list, constraint values, enum sanity, `flyway_schema_history` — all relayed one command at a time. | `docs/DEPLOY_FLYWAY_CUTOVER.md` §3, §7. |

### Five findings that were not on the list and matter more than they look

1. **`LeadClaimConcurrencyIT` has never executed — anywhere.** Surefire's default includes are
   `Test*.java` / `*Test.java` / `*Tests.java` / `*TestCase.java`; `…IT.java` matches none, and there
   is no failsafe plugin in `pom.xml`. The one test that proves two agents claiming the same lead
   produce one owner has never run in CI *or* in a local `mvnw test`.
2. **Docker's `json-file` logs are unbounded.** No `logging:` block in either compose file, no
   `daemon.json` in version control. Every deploy's boot log accumulates in
   `/var/lib/docker/containers/` forever. Daily deploys make this a disk-full outage on a timeline
   measured in months — and a full `/` takes Postgres with it.
3. **`deploy/backup-db.sh` cannot work on the current topology.** `:84` calls
   `sudo -u postgres pg_dump`, but Postgres runs *inside* the compose project — there is no host
   `postgres` role. It is a well-written script for the systemd deployment that Docker replaced, and
   nothing in version control installs it. **Whether the "hourly backup" has been silently failing is
   unverified — check `sudo crontab -l` and `ls -lt /var/backups/travelcrm/`.**
4. **`env_value` reads the FIRST match; Docker Compose uses the LAST.**
   `deploy-hostinger.sh:58-71` returns on first match. With today's triple-appended key, a real value
   on line 1 and a `CHANGE_ME` on line 3 would have *passed* `reject_placeholder_secret` while the
   container received the placeholder. `ProductionConfigValidator` covers 12 keys; `MAIL_PASSWORD`,
   `CLOUDINARY_API_SECRET`, `RAZORPAY_KEY_SECRET` have no second line of defence.
5. **20 files carry `@Scheduled` and there is no ShedLock / leader election.** Two app containers
   running at once double-fire every cron. `CampaignDispatchService.sendBatch` selects `PENDING`
   recipients with no row lock and no `@Version` → **duplicate WhatsApp/email to real customers.**
   This is a hard prerequisite for blue/green, not a nice-to-have.

---

## 1. The ordering principle

Zero-downtime is what everyone asks for first and it should be near-last here. With one pilot tenant,
43 s costs almost nothing. What costs a lot is **a bad deploy you cannot undo** and **a defect CI
cannot see**. Fix reversibility and detectability first; make it pretty afterwards.

---

## 2. Phase 1 — one day, and deploys become survivable

| # | Change | Effort | What it kills |
|---|---|---|---|
| 1 | `logging: {max-size: 20m, max-file: 5}` on both compose services | 5 min | Unbounded Docker logs filling `/` and taking Postgres down |
| 2 | `env_value` first-match → last-match (`deploy-hostinger.sh:58-71`) | 10 min | A duplicated key masking a placeholder secret |
| 3 | `--no-deps app` on `deploy-hostinger.sh:136` | 5 min | A compose-file edit silently recreating the Postgres container |
| 4 | Drop `-DskipTests`: `./mvnw -B verify` in CI | 30 min | 496 test methods that currently protect nothing |
| 5 | **`boot-check` CI job** — start the packaged app, prod profile, against a Flyway-migrated CI Postgres | 2 h | **Today's exact defect**, plus every missing env var and every `ProductionConfigValidator` violation |
| 6 | Docker-native backup + systemd timer + a pre-deploy backup step in CI | 3 h | An unverified backup story; and it is the precondition for *any* rollback |
| 7 | UptimeRobot + healthchecks.io + a deploy notification | 1 h | Learning about outages from the customer, and about a dead backup never |

### 2.5 — `boot-check`, the one that matters

Two things make it real rather than theatre: the database is migrated **only by Flyway**
(`SQL_INIT_MODE=never`, matching production since the cutover — which is exactly what stopped
`db/indexes.sql` from papering over the stale constraint), and it runs `@Profile("prod")` beans,
which the test suite never does.

```yaml
  boot-check:
    name: Boot the packaged app the way production boots it
    runs-on: ubuntu-latest
    needs: validate-schema
    services:
      postgres:
        image: postgres:16-alpine
        env: { POSTGRES_DB: travelcrm_ci, POSTGRES_USER: postgres, POSTGRES_PASSWORD: ci_postgres_pw }
        ports: ["5432:5432"]
        options: >-
          --health-cmd "pg_isready -U postgres -d travelcrm_ci"
          --health-interval 5s --health-timeout 5s --health-retries 20
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "21", cache: maven }
      - run: chmod +x mvnw && ./mvnw -B package && mkdir -p /tmp/travelcrm-logs
      - name: Boot with a synthetic prod env
        env:
          SPRING_PROFILES_ACTIVE: prod
          DB_URL: jdbc:postgresql://localhost:5432/travelcrm_ci
          DB_USERNAME: postgres
          DB_PASSWORD: ci_postgres_pw
          FLYWAY_ENABLED: "true"
          FLYWAY_BASELINE_ON_MIGRATE: "false"
          JPA_DDL_AUTO: validate
          SQL_INIT_MODE: never          # load-bearing: db/indexes.sql must NOT run
          # ProductionConfigValidator's rules, satisfied with CI-only values.
          # Base64, and APP_ENCRYPTION_KEY must decode to exactly 16/24/32 bytes.
          JWT_SECRET: Q0lfT05MWV9KV1RfU0VDUkVUX05PVF9BX1JFQUxfS0VZ
          PORTAL_JWT_SECRET: Q0lfT05MWV9QT1JUQUxfSldUX1NFQ1JFVF9LRVlfMDAy
          APP_ENCRYPTION_KEY: Q0lfT05MWV9BRVNfRU5DUllQVElPTl9LRVlfMzJCWVQ=
          # These four are compared against exact literals at ProductionConfigValidator:114-123.
          SUPERADMIN_1_EMAIL: rajpoottours2789@gmail.com
          SUPERADMIN_2_EMAIL: thombareprasad96@gmail.com
          SUPERADMIN_1_PASSWORD: Ci-Only-Pass-1!
          SUPERADMIN_2_PASSWORD: Ci-Only-Pass-2!
          MAIL_USERNAME: vetotechit@gmail.com
          APP_SUPER_ADMIN_LOGIN_ALERTS_FROM_EMAIL: vetotechit@gmail.com
          APP_SUPER_ADMIN_LOGIN_ALERTS_ENABLED: "false"
          APP_PUBLIC_BASE_URL: https://api.ci.example.com       # https:// required, no localhost
          APP_CONSOLE_BASE_URL: https://console.ci.example.com
          APP_CORS_ALLOWED_ORIGINS: https://ci.example.com      # no '*', no http://
          SERVER_ADDRESS: 127.0.0.1
          SERVER_PORT: "8080"
          LOG_DIR: /tmp/travelcrm-logs                          # log4j2-prod.xml opens RollingFile here
        run: |
          set -o pipefail
          java -jar target/travelcrm-*.jar > boot.log 2>&1 &
          APP_PID=$!
          for i in $(seq 1 120); do
            kill -0 "$APP_PID" 2>/dev/null || { echo "::error::app exited during startup"; tail -300 boot.log; exit 1; }
            curl -fsS http://127.0.0.1:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"' && break
            sleep 2
          done
          # These three lines ARE the gate. The first is the 5 Aug defect.
          grep -q "Schema enum-constraint check passed" boot.log || { echo "::error::enum CHECK drift"; tail -300 boot.log; exit 1; }
          grep -q "Production config validated"         boot.log || { echo "::error::prod config invalid"; exit 1; }
          grep -q "Started TravelcrmApplication"        boot.log || { echo "::error::app did not start"; exit 1; }
          kill "$APP_PID"
```

Then add `boot-check` to the deploy job's `needs:`.

**Also fix the artifact split** (`Dockerfile:15`): have the build job upload `target/travelcrm-*.jar`
and make the Dockerfile `FROM eclipse-temurin:21-jre-jammy` + `COPY target/travelcrm.jar`. Otherwise
every gate above certifies a jar that is then thrown away and rebuilt untested. Saves ~3 min too.

### 2.6 — backup, corrected for the actual topology

Replace `sudo -u postgres pg_dump` with `dc exec -T postgres pg_dump` (the `-T` matters: without it
the stream gets `\r\n`-mangled and the gzip is corrupt). Keep everything else from the existing
script — the `.partial`+rename, `pipefail`, tiered retention and the <10 KB tripwire are all sound.
Two changes of substance:

- The <10 KB tripwire must **fail**, not warn, when the dump is a pre-deploy backup.
- Off-host via **restic → Backblaze B2** (~$0.02/month at this size). Encryption at rest matters: the
  dump is every tenant's PII. `APP_ENCRYPTION_KEY` must stay *outside* the backup — if it lives in a
  restic repo whose password is on the same box, one compromise is total.

Ship it as `deploy/systemd/travelcrm-backup.{service,timer}` in version control.
`OnCalendar=hourly` + `Persistent=true` (cron does not catch up after a reboot; a timer does), output
to the journal — which also removes the unrotated `/var/log/travelcrm/backup.log` disk leak.

**And the part everyone skips: a weekly `restore-test.sh`** that restores the newest dump into a
throwaway `postgres:16-alpine` container and asserts the row counts, `plans >= 3`, no failed Flyway
rows, and — the real check — **no table owned by anyone but `travelcrm`**. A `\dt` as superuser
passes even when the app cannot read a single row. Note `pg_dump` never dumps roles, so the test must
`CREATE ROLE travelcrm` first; that is exactly the failure a restore would hit at 3am.

---

## 3. Phase 2 — reversibility, then zero downtime

### 3a. Auto-rollback (do this before blue/green)

Capture the currently-running image digest before `up -d`; on health-poll failure, re-`up` that
digest. ~10 lines. Converts "failed boot = outage until a human wakes up" into "failed boot = ~90 s
blip". It does **not** remove the 43 s, and it only works if the old image can boot on the new schema
— which is what §4 is for.

### 3b. Blue/green (zero downtime, and rollback becomes free)

Two app services on two loopback ports, an nginx `upstream` file as the single switch, and a graceful
`nginx -s reload`. The old container is never stopped until the candidate has passed its healthcheck,
answered on its own port, **and** answered through the public edge. A failure before the flip is a
no-op for users.

Four prerequisites, and the first is not optional:

1. **`mem_limit` on both app services.** `Dockerfile:29` sets `-XX:MaxRAMPercentage=75`, which is 75%
   of what the container can *see* — with no limit that is host RAM, so two containers commit 150% of
   the box and the OOM killer takes Postgres too. **Blue/green without `mem_limit` is worse than the
   status quo.**
2. **Per-colour `LOG_DIR`** — `log4j2-prod.xml:47,71,91` use fixed filenames on a shared volume; two
   containers would interleave writes and race on rollover.
3. **Healthcheck on `/actuator/health/readiness`, not `/actuator/health`.** The aggregate answers
   **200 UP on a container that is about to die**: Spring starts the web server during context
   refresh but runs `ApplicationRunner`s afterwards, and `SchemaEnumConstraintValidator` is an
   `ApplicationRunner` that throws. Readiness stays `OUT_OF_SERVICE` until `ApplicationReadyEvent`.
   Probes are already enabled (`application-prod.properties:236`) and the path is already `permitAll`
   (`SecurityConfig.java:117`). Also drop `interval` from 30 s to 5 s — at 30 s the script's verdict
   lags reality by half a minute.
4. **ShedLock on the 20 `@Scheduled` methods** (finding #5 above). Until it lands, each deploy carries
   a ~5 s window in which a per-minute cron can fire twice — with duplicate customer messages as the
   failure mode. Once it exists, the standby can be left *running*, which turns rollback from ~45 s
   into a ~2 s nginx reload.

---

## 4. The policy change daily deploys require

Automation is the easy half. This is the half that bites.

> **A migration that ships in release *N* must leave release *N-1*'s code working** — because *N-1*
> runs against it during every overlap, and indefinitely after a rollback.
>
> - **Expand in N, contract in N+2.** Add a nullable column → deploy → backfill → `NOT NULL` in a
>   *later* release.
> - **Never in the same release:** `SET NOT NULL` on a column the old code does not write;
>   `DROP COLUMN` / `DROP TABLE`; a rename; narrowing a type; tightening a CHECK.
> - **Always safe in the same release:** a new table, a new nullable column, a new index, and
>   **widening** a CHECK constraint.
> - A release that must break the rule sets `ALLOW_DOWNTIME=true` on the deploy, which stops the old
>   container before starting the candidate — a deliberate ~45 s window instead of a silent
>   corruption.

The counter-example is already in the tree: `V2__lead_code.sql:150`
`ALTER TABLE users ALTER COLUMN username SET NOT NULL`. Old code inserting a user without a username
fails the instant that commits. It was fine because it shipped with a full outage; under daily
deploys it would not be.

**When is an image rollback enough?**

| Situation | Fix |
|---|---|
| New code is wrong, migration was additive | **Image rollback only.** Old code ignores what it does not know. Leave the migration applied. |
| The expand/contract rule was broken | Image rollback is **not** enough. Roll forward with a fix, or write a compensating migration. Never `flyway repair`. |
| The migration itself failed | Restore the pre-deploy dump. (V1/V2 have no non-transactional statements, so a Flyway failure rolls back whole and writes no history row — but restore is still the safe move.) |

---

## 5. Phase 3 — config, verification, observability

- **Config: SOPS + age, encrypted file committed.** Chosen over GitHub secrets (36 opaque values, no
  diff, no history) and git-crypt (bad failure mode). With SOPS the *keys* stay plaintext and only
  *values* are encrypted, so `git diff` shows which key changed without leaking it — and config ships
  in the same commit as the code that needs it. Pair with an `install-env.sh` that writes atomically,
  reports key-level drift, and **refuses to finish if a stray file sits next to `travelcrm.env`** —
  today's exact failure.
  New required variable? Add it to the encrypted file in the same commit as the `${NEW_VAR}`, and let
  a CI `required-keys.sh --check` fail the *build* instead of the *boot*.
- **Verification: `verify-deploy.sh snapshot|verify`,** wired as the last CI step, exit code driving
  rollback. It turns "deploy succeeded" from *the container is running* into *a user can log in and
  read a lead*. Include a real authenticated call and a CORS check with an `Origin` header — CORS is
  invisible to plain `curl`, which is the classic "works in curl, dead in the browser".
- **Observability, minimum viable and $0:** UptimeRobot with keyword `"UP"` (a 200 carrying
  `"status":"DOWN"` still alerts) + its free SSL-expiry alert; healthchecks.io as a dead-man's switch
  pinged by the backup and restore-test — the one failure class an uptime monitor structurally cannot
  see is *everything is green because nothing ran*; and a 15-minute ops-watch timer for error bursts,
  disk, container restarts and config drift.
  Metrics (micrometer + Grafana Cloud free) only once daily deploys are boring. Do not self-host
  Prometheus on this box — the memory belongs to the JVM and Postgres.

---

## 6. Verify on the box before acting

- Is the hourly backup cron installed at all, and has it been failing since the Docker migration?
  `sudo crontab -l` · `ls -lt /var/backups/travelcrm/hourly/ | head`
- Host RAM — `mem_limit: 2g` per app service assumes ≥8 GB. `free -g`. At 4 GB use `1500m`.
- `/var/lib/docker` size and image count — `deploy-hostinger.sh:152` prunes only `until=168h`.
- Which SPA topology is live: host-static (`nginx-travelcrm.conf:270`) or containerised
  (`nginx-frontend-docker.conf:29`). **Both are in version control.**
- Whether `/etc/docker/daemon.json` already caps log size.

## 7. Two things asserted from documentation, not executed

Both are worth a 10-minute confirmation on the CI database before anyone relies on them at 2am:

1. Flyway's PostgreSQL implementation serialises concurrent `migrate()` with a session advisory lock.
2. `ignoreMigrationPatterns=*:future` lets an older jar boot against a schema carrying a newer
   migration — i.e. whether an image rollback works without a schema rollback.
