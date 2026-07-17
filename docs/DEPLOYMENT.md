# TravelCRM — Production Deployment (KVM2 VPS)

Runbook for deploying the backend to a single KVM2-class VPS (2 vCPU / 8 GB RAM / ~100 GB NVMe)
and handing it to one pilot tenant for testing.

**Scope of this release:** the CRM backend. The Disha AI assistant is **not deployed this sprint**
(`disha.enabled=false`). Everything else ships.

---

## 1. Architecture on the box

```
Internet
   │  443 (TLS, Let's Encrypt)
   ▼
 nginx ──────────► 127.0.0.1:8080  travelcrm.jar   (systemd: travelcrm.service)
                                        │
                                        ▼
                          127.0.0.1:5432  PostgreSQL
```

Only 22, 80 and 443 are open. Tomcat and Postgres bind to loopback only — nothing else can
reach them, which is also what makes it safe for `RateLimitFilter` to trust `X-Forwarded-For`
from `127.0.0.1`.

### Memory budget (8 GB, everything on one box)

| Component | Budget | Set where |
|---|---|---|
| JVM heap | 2 GB | `-Xmx2g` in `travelcrm.service` |
| JVM non-heap (metaspace, threads, direct) | ~1 GB | `-XX:MaxMetaspaceSize=512m` |
| PostgreSQL | ~2.5 GB | `shared_buffers` etc., §7 |
| OS | ~0.5 GB | — |
| **Free for page cache** | **~2 GB** | *this is what keeps Postgres fast* |

The JVM is capped at `MemoryMax=3800M` by systemd so a runaway app gets killed on its own
instead of driving the box into swap and taking Postgres with it. **Don't raise `-Xmx` without
shrinking Postgres** — the two share one 8 GB pool.

This comfortably fits one pilot tenant. It is a single point of failure with no redundancy;
that is an accepted trade for a pilot, not a shape to grow into (§12).

---

## 2. Files in this repo

| File | Goes to |
|---|---|
| `deploy/travelcrm.env.example` | `/etc/travelcrm/travelcrm.env` (**640 root:travelcrm**) |
| `deploy/travelcrm.service` | `/etc/systemd/system/travelcrm.service` |
| `deploy/nginx-travelcrm.conf` | `/etc/nginx/sites-available/travelcrm` |
| `deploy/backup-db.sh` | `/usr/local/bin/travelcrm-backup` |

---

## 3. ⚠ Do this first — rotate the leaked credentials

Every secret that was committed to `application.properties` has been in git history **since the
initial commit**, on a GitHub remote. Treat all of it as public. The dev defaults now left in
that file are throwaways for local dev only, and `ProductionConfigValidator` **refuses to boot
the prod profile** if any of them shows up in the environment.

Rotate before go-live — these are real accounts, not just dev values:

| Credential | Action |
|---|---|
| Gmail app password `hcjfdzsryfhqpjza` | Revoke at Google Account → Security → App passwords, generate a new one |
| Cloudinary api-secret `d033CO…` | Cloudinary console → Settings → Security → regenerate |
| Groq API key `gsk_N4KT…` | Delete at <https://console.groq.com/keys> (AI is off this sprint — just revoke it) |
| Postgres password | Irrelevant now (local dev only) — the prod DB gets a fresh one below |

The old JWT/portal/encryption keys need no "revocation": prod simply uses new ones. But do
**not** reuse them.

> Rotating the credentials does not remove them from git history. Scrubbing history
> (`git filter-repo`) rewrites every commit hash and breaks every clone — worth doing only if
> the repo is or becomes public. Rotation is what actually closes the exposure.

### Generate the new values

```bash
openssl rand -base64 32   # JWT_SECRET
openssl rand -base64 32   # PORTAL_JWT_SECRET   ← must DIFFER from JWT_SECRET
openssl rand -base64 32   # APP_ENCRYPTION_KEY  ← set ONCE, never rotate (see below)
openssl rand -base64 32   # SUPERADMIN_SIGNUP_SECRET
openssl rand -base64 24   # DB_PASSWORD
```

`JWT_SECRET` and `PORTAL_JWT_SECRET` being **different** is the only thing separating the staff
realm from the traveler-portal realm. The validator rejects identical keys at boot.

`APP_ENCRYPTION_KEY` decrypts every tenant's stored SMTP password and WhatsApp API key. Changing
it later makes that ciphertext permanently unreadable. **Back it up separately from the database**
— in a password manager, not on this server.

---

## 4. Provision the box

Ubuntu 22.04/24.04 LTS assumed.

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y openjdk-21-jre-headless postgresql nginx certbot python3-certbot-nginx ufw

sudo useradd --system --no-create-home --shell /usr/sbin/nologin travelcrm
sudo mkdir -p /opt/travelcrm /etc/travelcrm /var/log/travelcrm
sudo chown travelcrm:travelcrm /opt/travelcrm /var/log/travelcrm

# Crons are written in Indian wall-clock terms; a fresh VPS is UTC.
sudo timedatectl set-timezone Asia/Kolkata
```

### Firewall

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow OpenSSH
sudo ufw allow 'Nginx Full'      # 80 + 443
sudo ufw enable
```

Port 8080 and 5432 are **never** opened — both bind to loopback.

### Database

```bash
sudo -u postgres psql <<'SQL'
-- Scoped login role. Explicitly none of the attributes the app has no use for.
CREATE ROLE travelcrm WITH LOGIN PASSWORD 'THE_DB_PASSWORD_YOU_GENERATED'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS INHERIT;

-- Ownership is load-bearing, not cosmetic. On PG15+ schema `public` is owned by
-- pg_database_owner and the database owner is an implicit member of it — that is what
-- grants CREATE on public with no explicit GRANT, and what makes the app the OWNER of
-- every table Hibernate creates. db/indexes.sql then issues ALTER TABLE / CREATE INDEX,
-- which are owner-only operations: no GRANT can substitute for them, so
-- "GRANT ALL ON ALL TABLES" is strictly insufficient here.
CREATE DATABASE travel_crm OWNER travelcrm;

-- Isolation: strip what every role gets on a database for free.
REVOKE ALL ON DATABASE travel_crm FROM PUBLIC;   -- drops the default CONNECT + TEMP
GRANT CONNECT ON DATABASE travel_crm TO travelcrm;
SQL

sudo -u postgres psql -d travel_crm <<'SQL'
-- PG15 already removed CREATE from PUBLIC here; this also takes away the USAGE it still has.
REVOKE ALL ON SCHEMA public FROM PUBLIC;
-- Make ownership explicit rather than resting on the pg_database_owner indirection, so a
-- later ALTER DATABASE ... OWNER TO cannot silently strip the app's CREATE.
ALTER SCHEMA public OWNER TO travelcrm;
GRANT USAGE, CREATE ON SCHEMA public TO travelcrm;
SQL
```

`CREATE` on the schema is required, not optional: `ddl-auto=update` means Hibernate issues the
`CREATE TABLE` statements itself on the first boot. The app is nonetheless **not** a superuser —
verify before starting it (`rolsuper` must be `f`, both owners must be `travelcrm`):

```bash
sudo -u postgres psql <<'SQL'
SELECT rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolbypassrls
  FROM pg_roles WHERE rolname='travelcrm';
SELECT pg_get_userbyid(datdba) AS db_owner FROM pg_database WHERE datname='travel_crm';
SQL
sudo -u postgres psql -d travel_crm -c \
  "SELECT nspowner::regrole AS schema_owner FROM pg_namespace WHERE nspname='public';"
```

After the first boot, confirm the app actually owns the tables — this is the invariant
`db/indexes.sql` depends on, and it must return **zero rows**:

```bash
sudo -u postgres psql -d travel_crm -c \
  "SELECT tablename, tableowner FROM pg_tables WHERE schemaname='public' AND tableowner <> 'travelcrm';"
```

No `CREATE EXTENSION` is needed: pgvector's autoconfiguration is excluded and `disha.rag-enabled`
is false, so nothing in the app requires superuser at any point.

Confirm `listen_addresses = 'localhost'` in `/etc/postgresql/*/main/postgresql.conf` (the default).

---

## 5. Build and ship

On your machine:

```bash
mvnw.cmd clean package -DskipTests
scp target/travelcrm-0.0.1-SNAPSHOT.jar root@VPS_IP:/opt/travelcrm/travelcrm.jar
scp deploy/travelcrm.service root@VPS_IP:/etc/systemd/system/travelcrm.service
scp deploy/nginx-travelcrm.conf root@VPS_IP:/etc/nginx/sites-available/travelcrm
scp deploy/backup-db.sh root@VPS_IP:/usr/local/bin/travelcrm-backup
scp deploy/travelcrm.env.example root@VPS_IP:/etc/travelcrm/travelcrm.env
```

The JAR never contains a secret: `application-local.properties` lives at the project root and is
excluded from packaging by the `<resources>` block in `pom.xml`.

On the VPS:

```bash
sudo nano /etc/travelcrm/travelcrm.env      # fill in EVERY value
sudo chown root:travelcrm /etc/travelcrm/travelcrm.env
sudo chmod 640            /etc/travelcrm/travelcrm.env
sudo chown travelcrm:travelcrm /opt/travelcrm/travelcrm.jar
sudo chmod 750 /usr/local/bin/travelcrm-backup

sudo systemctl daemon-reload
sudo systemctl enable --now travelcrm
journalctl -u travelcrm -f
```

**A boot failure here is the system working.** Missing env var → `Could not resolve placeholder
'JWT_SECRET'`. Dev value reused, localhost URL, seeder on → a `REFUSING TO START` block listing
every problem at once. Fix the env file and restart.

### The React SPA

The nginx conf serves **two** vhosts: the API on `api.mytripsafar.com` and the SPA on
`mytripsafar.com` / `www.mytripsafar.com` from `/var/www/travelcrm`. Deploy the SPA before nginx goes
up — one bad server block fails the whole `nginx -t`, which takes the API vhost down with it.

`VITE_API_URL` is compiled **into** the bundle, so it must be right at build time; a wrong value needs
a rebuild, not a config edit. `.env.production` is gitignored — a fresh clone must recreate it from
`.env.example`.

```bash
# in travelcrmfrontend
cat > .env.production <<'EOF'
VITE_API_URL=https://api.mytripsafar.com/api
VITE_CLOUDINARY_CLOUD_NAME=dfjawtgv6
VITE_CLOUDINARY_UPLOAD_PRESET=travelcrm_destinations
EOF
npm ci && npm run build

# Verify the bundle before shipping it — `built in 15s` is not evidence the URL is right.
grep -rl 'api.mytripsafar.com' dist/ | wc -l    # must be > 0
grep -rl 'localhost:8080'      dist/ | wc -l    # must be 0 — non-zero means .env.production wasn't read

sudo mkdir -p /var/www/travelcrm
rsync -a --delete dist/ root@187.127.159.81:/var/www/travelcrm/
```

### TLS

**The install order is authoritative in the header of `deploy/nginx-travelcrm.conf` — follow its
steps 1–8 verbatim.** Do not improvise it, and in particular do not run `certbot --nginx` against the
shipped conf: its `ssl_certificate` lines name cert files that do not exist until certbot has run, so
`nginx -t` fails, the reload fails, and `certbot --nginx` — which needs an nginx that loads — cannot
run either. The conf must go up **HTTP-only first** (both `listen 443` blocks commented out), certs
obtained by `--webroot`, then the TLS blocks uncommented.

Two certificates are needed, not one:

```bash
sudo mkdir -p /var/www/html                    # the acme-challenge root

sudo certbot certonly --webroot -w /var/www/html \
  -d api.mytripsafar.com --deploy-hook "systemctl reload nginx"

sudo certbot certonly --webroot -w /var/www/html \
  -d mytripsafar.com -d www.mytripsafar.com --deploy-hook "systemctl reload nginx"
```

⚠ **Apex first** in the second command. certbot names the lineage after the first `-d`, and the SPA
block hardcodes `/etc/letsencrypt/live/mytripsafar.com/`. Leading with `www` puts the cert in
`live/www.mytripsafar.com/` and that path stays broken.

⚠ `--deploy-hook` is **not** optional with `certonly`: it records no installer, so the ~60-day renewal
writes a fresh cert to disk while nginx keeps serving the old one from memory until it expires — a
site that goes down two months after a deploy that looked fine.

Verify:
```bash
curl -s  https://api.mytripsafar.com/actuator/health     # {"status":"UP"}
curl -sI https://mytripsafar.com/Allbookings | head -1    # 200 — SPA deep-link fallback
sudo certbot renew --dry-run
```

---

## 6. The SuperAdmin

**It is created for you on the first boot — you do not register it.**

`DataInitializer` runs on every startup and, when `super_admins` is empty, creates the platform
account from the environment:

| Var | Effect |
|---|---|
| `SUPER_ADMIN_EMAIL` | Optional. Defaults to `superadmin@travelcrm.com`. |
| `SUPER_ADMIN_PASSWORD` | **Required under `prod`** — the boot fails without it. |

This runner is **not** gated by `app.seed.enabled`; it is unrelated to `DevDataSeeder`. Earlier
revisions of this document claimed "the seeder is hard-off in prod, so there is no default account"
— that was wrong, and it mattered: without `SUPER_ADMIN_PASSWORD` the account was created with the
development fallback password, which is public in this repository's git history, sitting on the
internet-facing login form.

**Do not use `/superadmin/signup`.** `AuthServiceImpl` refuses to register a second SuperAdmin once
one exists, so that endpoint returns 403 from the first boot onward. `SUPERADMIN_SIGNUP_SECRET` is
still required by the config validator, but in practice it can never be exercised.

**To change the password afterwards:** `POST /api/super-admin/me/change-password` with
`{"currentPassword": "...", "newPassword": "..."}` and the console bearer token. Editing
`SUPER_ADMIN_PASSWORD` in the env file has no effect after the first boot — the runner is a no-op
once the row exists.

Verify after the first start — this must return exactly the address you configured, and nothing else:

```bash
sudo -u postgres psql -d travel_crm -c "SELECT email FROM super_admins;"
```

Then log in to the console and create the tenant.

---

## 7. PostgreSQL tuning (8 GB shared box)

`/etc/postgresql/*/main/postgresql.conf` — defaults assume a much smaller machine:

```conf
shared_buffers = 2GB                  # ~25% of RAM
effective_cache_size = 4GB            # a hint: what the OS is likely caching
work_mem = 16MB                       # PER sort/hash node — see note
maintenance_work_mem = 256MB
max_connections = 50                  # app pool is 10; leaves room for psql/backup
random_page_cost = 1.1                # NVMe, not a spinning disk
effective_io_concurrency = 200
wal_compression = on
```

`work_mem` is per *operation*, not per connection: a query with several sorts can use a multiple
of it, and concurrent queries multiply again. 16 MB is deliberately conservative on a box where
the JVM already owns 3 GB.

```bash
sudo systemctl restart postgresql
```

---

## 8. Database backups

**The plan:** **hourly** `pg_dump` → gzip → local `/var/backups/travelcrm`, two-tier retention →
**copied off-host**. Plus a VPS-level snapshot as a separate layer.

| Tier | What | Kept |
|---|---|---|
| `/var/backups/travelcrm/hourly/` | every run | 48 hours (`HOURLY_RETENTION_HOURS`) |
| `/var/backups/travelcrm/daily/`  | the 02:00 run only, hardlinked from hourly | 14 days (`DAILY_RETENTION_DAYS`) |

Hourly at a flat 14-day retention would mean 336 dumps; the tiers keep it at ~62. The daily copy is a
**hardlink**, so it costs disk only once. 02:00 is deliberate — see the cron note below.

⚠ **Watch the disk as the DB grows.** 62 dumps at a pilot's ~1 MB is nothing; at 500 MB/dump it is
~31 GB, a third of this box. `df -h /`, and drop `HOURLY_RETENTION_HOURS` first — it is 48 of the 62.

This is not optional here, for two reasons specific to this app:
- `ddl-auto=update` lets Hibernate change the live schema at boot.
- `TrashPurgeScheduler` **hard-deletes** rows 30 days after soft-delete. That is the one place in
  the app where data leaves permanently, and it is irreversible.

```bash
sudo cp deploy/backup-db.sh /usr/local/bin/travelcrm-backup
sudo chmod 750 /usr/local/bin/travelcrm-backup
sudo crontab -e
```

```cron
# Every hour. The 02:00 run is the one promoted to daily/ — deliberately BEFORE the 02:30
# trash purge, so the retained daily copy still contains whatever the purge hard-deletes.
0 * * * * /usr/local/bin/travelcrm-backup >> /var/log/travelcrm/backup.log 2>&1
```

⚠ **Add a logrotate rule.** This log now grows 24× faster, and nothing rotates it — log4j2 only
manages the app's own files. `/etc/logrotate.d/travelcrm-backup`:

```
/var/log/travelcrm/backup.log {
    weekly
    rotate 8
    compress
    missingok
    notifempty
    copytruncate
}
```

The script writes to `.partial` and renames only on success (so a crash can't leave a
plausible-looking truncated dump), uses `pipefail` (so a broken `pg_dump` can't be masked by a
happy `gzip`), warns if the dump comes out under 10 KB, and prunes old copies **only after** a
successful run.

### Three things that decide whether this actually saves you

1. **Off-host, or it isn't a backup.** A copy on the same NVMe dies with the VPS. Install
   `rclone`, configure a remote (Backblaze B2 is a few cents/month at this size), and uncomment
   the `rclone copy` line at the bottom of the script.
2. **Back up `APP_ENCRYPTION_KEY` separately.** The DB dump alone does not restore you: without
   that exact key, every tenant's stored SMTP password and WhatsApp API key is unreadable bytes.
   Keep it in a password manager. Keeping it *on the server* turns one disaster into two.
3. **Restore-test it once, now.** An untested backup is a guess:
   ```bash
   sudo -u postgres createdb restore_test
   ls -lt /var/backups/travelcrm/hourly/ | head          # dumps live in hourly/ and daily/
   gunzip -c /var/backups/travelcrm/hourly/travel_crm-YYYYMMDD-HHMMSS.sql.gz \
     | sudo -u postgres psql -d restore_test

   # Verify OWNERSHIP, not just existence. `\dt` as postgres passes even when the app cannot
   # read a single row — it is not a real check. This is (expect 0):
   sudo -u postgres psql -d restore_test -c \
     "SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tableowner <> 'travelcrm';"

   sudo -u postgres dropdb restore_test
   ```
   `pg_dump` never dumps roles — restoring into a fresh cluster means recreating the `travelcrm`
   role (§4) **first**, or every `OWNER TO travelcrm` in the dump fails.

**Also enable your provider's snapshot backups** (Hostinger et al. sell weekly/daily snapshots).
The dump protects the *data*; the snapshot protects the *box* — a bad `apt upgrade` or a lost
disk is a restore-the-whole-VM problem that `pg_dump` cannot answer.

**Recovery point:** hourly dumps ⇒ up to 1 h of loss in the worst case, for anything inside the 48 h
hourly window; up to 24 h beyond it, where only the daily tier remains. Fine for a pilot. For real
customers, move to WAL archiving / PITR (§12).

---

## 9. Logs

| What | Where |
|---|---|
| App (INFO+, daily rotate, 50 MB/file, 2 GB cap, 30 d) | `/var/log/travelcrm/travelcrm.log` |
| Errors only (WARN+, 90 d) — **read this first after an incident** | `/var/log/travelcrm/travelcrm-error.log` |
| OTP delivery trail | `/var/log/travelcrm/otp.log` |
| systemd journal | `journalctl -u travelcrm -f` |
| nginx | `/var/log/nginx/travelcrm-*.log` |

Every appender is size- **and** age-capped, so logs cannot fill the disk. Cap the journal too:

```bash
sudo sed -i 's/^#SystemMaxUse=.*/SystemMaxUse=500M/' /etc/systemd/journald.conf
sudo systemctl restart systemd-journald
```

Every request carries a trace id — returned to the client as `X-Trace-Id` and in the error body,
and printed in every log line. A tenant quoting an id gets you straight to the stack trace:

```bash
grep '<trace-id>' /var/log/travelcrm/travelcrm.log
```

### Monitoring

`GET /actuator/health` is public and returns only `{"status":"UP"}` (no internals). Point any
uptime monitor at it. It reports `DOWN` when Postgres is unreachable, which is the failure that
actually matters.

---

## 10. Deploying a new version

```bash
sudo systemctl stop travelcrm          # graceful: in-flight requests get 30s
sudo cp travelcrm.jar /opt/travelcrm/travelcrm.jar.bak    # rollback copy
scp target/travelcrm-0.0.1-SNAPSHOT.jar root@VPS:/opt/travelcrm/travelcrm.jar
sudo systemctl start travelcrm
journalctl -u travelcrm -f
```

Rollback = restore the `.bak` and restart. **Caveat:** `ddl-auto=update` may have already added
columns for the new version. Additive DDL is harmless to the old JAR, but if a release ever needs
a destructive change, take a backup first and don't rely on JAR rollback alone.

---

## 11. Go-live checklist

- [ ] All credentials in §3 rotated
- [ ] `travelcrm.env` filled; `640 root:travelcrm`
- [ ] `JWT_SECRET != PORTAL_JWT_SECRET`
- [ ] `APP_ENCRYPTION_KEY` backed up **off the server**
- [ ] `APP_CORS_ALLOWED_ORIGINS` = real frontend origin (no localhost, no `*`)
- [ ] `APP_PUBLIC_BASE_URL` = real `https://` origin
- [ ] App boots clean; `journalctl` shows `Production config validated`
- [ ] `curl https://.../actuator/health` → `{"status":"UP"}`
- [ ] TLS valid; HTTP redirects to HTTPS; certbot timer active
- [ ] `ufw status` → only 22/80/443
- [ ] Seeder off — confirm no `Demo Travels` tenant and no `superadmin@demo.crm`
- [ ] `SELECT email FROM super_admins;` returns exactly the configured address, and nothing else
      (the account is auto-created on the first boot from SUPER_ADMIN_EMAIL/SUPER_ADMIN_PASSWORD —
      do NOT try to register it, see §6)
- [ ] `SELECT code FROM plans;` returns 3 rows — an empty catalogue 403s every CRM page while the
      sidebar still renders, which reads as a broken permission system, not a missing catalogue
- [ ] SuperAdmin password rotated via the console (🔑 in the header) after the first login
- [ ] `grep -rl 'localhost:8080' /var/www/travelcrm` is empty — the SPA was built with VITE_API_URL set
- [ ] Tenant created
- [ ] Backup cron installed **and restore-tested once**; off-host copy working
- [ ] Provider snapshots enabled
- [ ] `timedatectl` → `Asia/Kolkata`
- [ ] Login, create a lead → quotation → booking, download a PDF, check the notification bell (SSE)
- [ ] `/ai/chat` returns 404 and the FE chat widget is hidden (AI is off this sprint)

### Lead-source webhooks (Integrations → Lead Sources)

Only needed if a lead source is being connected during the pilot. The endpoint is `permitAll` and
creates rows, so these are not optional once it is live.

- [ ] `nginx -t` passes **after** the `map`/`log_format travelcrm` block was added — a typo there takes
      the whole site down, not just the log
- [ ] **Token is not in the access log.** Send one delivery, then:
      `grep -c 'lsk_' /var/log/nginx/travelcrm-access.log` → **0**, and the line reads
      `/api/webhooks/leads/<channel>/[REDACTED]`. If a raw `lsk_…` appears, the `access_log` directive
      is still on the default `combined` format and every ingest credential is being written to disk.
- [ ] `APP_PUBLIC_BASE_URL` is `https://api.mytripsafar.com` — this is what the ingest URL handed to
      JustDial/Google Ads is built from. **Treat it as permanent**: those URLs get pasted into a
      provider's console (and for JustDial, emailed to an account manager who configures it by hand).
      Changing this origin later silently kills every live integration, because no provider will
      re-paste. If the tenant app ever moves to per-tenant subdomains, `api.` stays the webhook origin.
- [ ] Connect one source, then confirm the round trip end-to-end: **Deliveries** panel shows the
      delivery with its raw payload. For Google Ads, "Send test data" should appear as `test_lead` —
      that proves the wiring without creating a real lead or burning plan quota.
- [ ] `SELECT conname FROM pg_constraint WHERE conrelid='leads'::regclass;` includes
      `leads_lead_source_check` **and** it lists `JUSTDIAL`/`GOOGLE_ADS`/`META_ADS`. `ddl-auto=update`
      never alters an existing constraint, so a DB whose `leads` table predates these constants rejects
      every inbound lead at the DB layer. `SchemaEnumConstraintValidator` refuses to boot if so — but
      confirm rather than assume, since `db/indexes.sql` runs with `continue-on-error=true`.

---

## 12. Known gaps — accepted for a pilot, fix before real customers

Ranked by what will bite first.

1. **`ddl-auto=update` is not schema management.** It cannot migrate data, cannot be reviewed
   before it runs, and two instances starting together can race on DDL. → Add Flyway, baseline
   the current schema, set `JPA_DDL_AUTO=validate` (the env var exists so it needs no code change).
2. **Single node, single point of failure.** No redundancy: the VPS is the app, the DB, and the
   backup target. A restore is manual and measured in hours.
3. **In-memory OTP store.** `InMemoryOtpStore` is correct for exactly one node. The moment a
   second instance runs behind a load balancer, an OTP issued by node A can't be verified on
   node B. → Redis-backed `OtpStore` marked `@Primary`.
4. **Nightly-dump RPO (up to 24 h).** → WAL archiving / PITR.
5. **Flat booking tax rates.** `app.booking.gst-rate` / `tcs-rate` are flat per booking; real
   Indian TCS is slabbed and GST varies by service (already noted in `BookingServiceImpl`).
6. **No metrics/APM.** Only health + logs. → Expose `prometheus` via actuator (keep it behind
   auth or bind it to loopback) once there's somewhere to send it.
7. **Secrets live in a file on the box.** Fine at this size; revisit if the team grows.

---

## 13. Environment variables

Full annotated list: `deploy/travelcrm.env.example`.

Required in prod (no defaults — a missing one fails the boot):
`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `PORTAL_JWT_SECRET`,
`SUPERADMIN_SIGNUP_SECRET`, `APP_ENCRYPTION_KEY`, `APP_PUBLIC_BASE_URL`,
`APP_CORS_ALLOWED_ORIGINS`, `SUPER_ADMIN_PASSWORD`.

`SUPER_ADMIN_PASSWORD` is enforced twice and is easy to miss: `ProductionConfigValidator` rejects it
blank or as `CHANGE_ME`, and `DataInitializer` throws under `prod` rather than fall back to the
development password that is public in this repository's git history.

Optional: `SUPER_ADMIN_EMAIL` (defaults to `superadmin@travelcrm.com`), `MAIL_*`, `CLOUDINARY_*`,
`WHATSAPP_TEMPLATE_*` (read by the live Interakt sender — unset means each flow falls back to the
tenant's own default template), `RAZORPAY_*`, `TOMCAT_MAX_THREADS`, `DB_POOL_MAX_SIZE`,
`TRASH_RETENTION_DAYS`, `LOG_DIR`, `JPA_DDL_AUTO`, `HOURLY_RETENTION_HOURS`, `DAILY_RETENTION_DAYS`.

Not used this sprint: `GROQ_API_KEY` (AI is off).
