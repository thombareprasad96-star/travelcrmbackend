#!/usr/bin/env bash
# ===================================================================
# TravelCRM — hourly Postgres backup, tiered retention
# -------------------------------------------------------------------
# ddl-auto=update means Hibernate mutates the live schema at boot, and the trash
# purge hard-deletes rows on a timer. Both are irreversible without a backup, so
# this is not optional for a box a real tenant is testing on.
#
# Install:
#   sudo cp deploy/backup-db.sh /usr/local/bin/travelcrm-backup
#   sudo chmod 750 /usr/local/bin/travelcrm-backup
#   sudo chown root:root /usr/local/bin/travelcrm-backup
#
# Schedule (root crontab — `sudo crontab -e`), every hour on the hour:
#   0 * * * * /usr/local/bin/travelcrm-backup >> /var/log/travelcrm/backup.log 2>&1
#
# ── Retention is TIERED, and that is the point ────────────────────────────────
# Hourly dumps at a flat 14-day retention would mean 336 files. Instead:
#   hourly/  every run, kept HOURLY_RETENTION_HOURS (48) — the recent-mistake window
#   daily/   the DAILY_HOUR (02:00) run only, kept DAILY_RETENTION_DAYS (14)
# The 02:00 run lands in both. It is 02:00 specifically because the trash purge runs
# at 02:30 (app.trash.purge-cron) and is the only hard delete in the app — so the
# retained daily copy is always the pre-purge state of that day.
#
# daily/ is a HARDLINK, not a copy: same inode, so the 02:00 dump costs disk once and
# is only actually freed when both tiers have pruned it. That also means `du` on the
# two directories double-counts — use `du -sh --count-links=no` or trust df.
#
# ⚠ DISK MATH — check this as the DB grows. Steady state is (48 + 14) = 62 dumps.
# At a pilot's ~1 MB/dump that is ~62 MB and irrelevant. At 500 MB/dump it is ~31 GB,
# a third of a KVM2's 100 GB, and the box fills silently. Watch `df -h /` and drop
# HOURLY_RETENTION_HOURS first — it is 48 of the 62 files.
#
# ⚠ The cron log grows 24x faster now. /var/log/travelcrm/backup.log is NOT rotated
# by log4j2 (that only manages the app's own files) — add a logrotate rule, see the
# LOGROTATE section at the bottom, or it becomes an unbounded disk leak.
#
# ⚠ A backup that lives only on the box it protects is not a backup — it dies with
# the VPS. Ship BACKUP_DIR off-host (see the RCLONE section at the bottom) and
# restore-test it at least once. An untested backup is a guess.
# ===================================================================

# -e  stop at the first failing command   -u  unset var is an error
# -o pipefail  a failure anywhere in `pg_dump | gzip` fails the whole pipeline —
#              without it, gzip's success would mask a broken dump and this script
#              would cheerfully write a truncated file and report success.
set -euo pipefail

DB_NAME="${DB_NAME:-travel_crm}"
DB_USER="${DB_USER:-travelcrm}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/travelcrm}"
# Recent-mistake window: how far back an hour-by-hour restore is possible.
HOURLY_RETENTION_HOURS="${HOURLY_RETENTION_HOURS:-48}"
# Long window: one dump per day survives this long.
DAILY_RETENTION_DAYS="${DAILY_RETENTION_DAYS:-14}"
# Which hourly run is promoted to daily/. 02 = before the 02:30 trash purge.
DAILY_HOUR="${DAILY_HOUR:-02}"

HOURLY_DIR="${BACKUP_DIR}/hourly"
DAILY_DIR="${BACKUP_DIR}/daily"

STAMP="$(date +%Y%m%d-%H%M%S)"
DEST="${HOURLY_DIR}/${DB_NAME}-${STAMP}.sql.gz"

mkdir -p "${HOURLY_DIR}" "${DAILY_DIR}"
# Backups are a full copy of every tenant's data — same sensitivity as the DB itself.
chmod 700 "${BACKUP_DIR}" "${HOURLY_DIR}" "${DAILY_DIR}"

echo "[$(date -Is)] starting backup of '${DB_NAME}' -> ${DEST}"

# Written to .partial first, renamed only on success: a crash mid-dump then leaves an
# obviously-incomplete file instead of a plausible-looking .sql.gz that restores to a
# half-empty database. Auth is via the postgres peer/ident socket as the postgres user.
#
# --no-owner/--no-privileges are deliberately NOT used. The documented restore pipes this
# dump into psql AS postgres, so stripping ownership means every table comes back owned by
# postgres and the `travelcrm` role owns nothing. The app then cannot read its own database:
# indexes.sql fails with "must be owner of table" (swallowed by continue-on-error), and the
# boot dies in CancellationPolicyBackfillRunner with "permission denied for table tenants".
# The dump would restore cleanly and produce an app that cannot start — the worst kind of
# backup. Keeping ownership means the dump carries `ALTER TABLE ... OWNER TO travelcrm`.
# pg_dump never dumps roles, so recreate the travelcrm role (docs/DEPLOYMENT.md) before
# restoring into a fresh cluster.
sudo -u postgres pg_dump \
    --format=plain \
    "${DB_NAME}" \
  | gzip -9 > "${DEST}.partial"

mv "${DEST}.partial" "${DEST}"
chmod 600 "${DEST}"

SIZE="$(du -h "${DEST}" | cut -f1)"
echo "[$(date -Is)] backup OK (${SIZE})"

# Promote the DAILY_HOUR run into the long-retention tier. `ln -f` (hardlink, not cp):
# the two tiers share one inode, so this costs no extra disk and cannot drift from the
# hourly copy. Only when BOTH tiers have pruned their name is the data actually freed.
# `date +%H` is zero-padded, matching the DAILY_HOUR default of "02".
if [ "$(date +%H)" = "${DAILY_HOUR}" ]; then
    ln -f "${DEST}" "${DAILY_DIR}/$(basename "${DEST}")"
    echo "[$(date -Is)] promoted to daily/ (hardlink)"
fi

# A dump far smaller than yesterday's usually means the dump broke, not that the data
# vanished. Cheap tripwire — a silently-empty backup is the classic way to discover
# your backups never worked on the day you need them.
if [ "$(stat -c%s "${DEST}")" -lt 10240 ]; then
    echo "[$(date -Is)] WARNING: backup is under 10 KB — verify this is a real dump!" >&2
fi

# Prune both tiers. Runs AFTER a successful dump on purpose: if the dump fails, set -e
# aborts above and the existing backups survive rather than being pruned away on a run
# that produced no replacement.
#
# -mmin for the hourly tier, not -mtime: -mtime counts in whole 24h units, so `-mtime +1`
# is "older than 48h" and there is no way to express 48 hours precisely with it.
find "${HOURLY_DIR}" -name "${DB_NAME}-*.sql.gz" -type f \
     -mmin "+$((HOURLY_RETENTION_HOURS * 60))" -print -delete
find "${DAILY_DIR}"  -name "${DB_NAME}-*.sql.gz" -type f \
     -mtime "+${DAILY_RETENTION_DAYS}" -print -delete
find "${BACKUP_DIR}" -name "*.partial" -type f -mmin +120 -print -delete

HOURLY_N="$(find "${HOURLY_DIR}" -name "${DB_NAME}-*.sql.gz" | wc -l)"
DAILY_N="$(find "${DAILY_DIR}"  -name "${DB_NAME}-*.sql.gz" | wc -l)"
DISK="$(df -h "${BACKUP_DIR}" | awk 'NR==2 {print $4" free ("$5" used)"}')"
echo "[$(date -Is)] done. hourly: ${HOURLY_N}  daily: ${DAILY_N}  disk: ${DISK}"

# Steady state is 48 + 14. Materially more means pruning is not keeping up — most likely
# the clock, the retention vars, or a stuck run leaving .partial files behind.
if [ "${HOURLY_N}" -gt $((HOURLY_RETENTION_HOURS + 6)) ]; then
    echo "[$(date -Is)] WARNING: ${HOURLY_N} hourly backups, expected ~${HOURLY_RETENTION_HOURS} — pruning is not keeping up." >&2
fi

# ── OFF-HOST COPY (do this — see the warning at the top) ──────────────────────
# Install rclone, configure a remote (`rclone config`) — Backblaze B2, S3, Google
# Drive, anything — then uncomment:
#
#   rclone copy "${DEST}" remote:travelcrm-backups/ --config /root/.config/rclone/rclone.conf
#
# At hourly cadence, ship the DAILY tier off-host, not every hourly dump — 24 uploads a
# day of the full DB is bandwidth you do not need to spend for a pilot. Put this inside
# the DAILY_HOUR branch above instead:
#
#   rclone copy "${DAILY_DIR}/$(basename "${DEST}")" remote:travelcrm-backups/
#
# ── LOGROTATE (the cron log now grows 24x faster) ────────────────────────────
# /var/log/travelcrm/backup.log is written by cron's append, NOT by log4j2 — nothing
# rotates it. Create /etc/logrotate.d/travelcrm-backup:
#
#   /var/log/travelcrm/backup.log {
#       weekly
#       rotate 8
#       compress
#       missingok
#       notifempty
#       copytruncate
#   }
#
# ── RESTORE (practise this BEFORE you need it) ───────────────────────────────
# Backups now live in two tiers — hourly/ for the last 48h, daily/ for 14 days:
#   ls -lt /var/backups/travelcrm/hourly/ | head     # most recent first
#   ls -lt /var/backups/travelcrm/daily/
#
#   sudo systemctl stop travelcrm
#   sudo -u postgres createdb travel_crm_restore_test
#   gunzip -c /var/backups/travelcrm/hourly/travel_crm-YYYYMMDD-HHMMSS.sql.gz \
#     | sudo -u postgres psql -d travel_crm_restore_test
#   # Verify ownership survived — `\dt` as postgres passes even when the app cannot read
#   # a single row, so it is not a real check. This is (expect 0):
#   sudo -u postgres psql -d travel_crm_restore_test -c \
#     "SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tableowner <> 'travelcrm';"
#   # then repoint DB_URL or rename the databases
#   sudo systemctl start travelcrm
#
# NOTE: the database is only half of a restore. APP_ENCRYPTION_KEY decrypts every
# tenant's stored SMTP/WhatsApp credentials — restoring the DB without that exact key
# leaves those columns as permanently unreadable bytes. Back the key up SEPARATELY
# (a password manager, not this server).