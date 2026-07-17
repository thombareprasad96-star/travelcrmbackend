#!/usr/bin/env bash
# ===================================================================
# TravelCRM — nightly Postgres backup
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
# Schedule (root crontab — `sudo crontab -e`), 02:00 IST daily, before the 02:30
# trash purge so the day's backup still contains whatever the purge removes:
#   0 2 * * * /usr/local/bin/travelcrm-backup >> /var/log/travelcrm/backup.log 2>&1
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
RETENTION_DAYS="${RETENTION_DAYS:-14}"

STAMP="$(date +%Y%m%d-%H%M%S)"
DEST="${BACKUP_DIR}/${DB_NAME}-${STAMP}.sql.gz"

mkdir -p "${BACKUP_DIR}"
# Backups are a full copy of every tenant's data — same sensitivity as the DB itself.
chmod 700 "${BACKUP_DIR}"

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

# A dump far smaller than yesterday's usually means the dump broke, not that the data
# vanished. Cheap tripwire — a silently-empty backup is the classic way to discover
# your backups never worked on the day you need them.
if [ "$(stat -c%s "${DEST}")" -lt 10240 ]; then
    echo "[$(date -Is)] WARNING: backup is under 10 KB — verify this is a real dump!" >&2
fi

# Prune old local copies. Runs AFTER a successful dump on purpose: if the dump fails,
# set -e aborts here and yesterday's backups survive rather than being pruned away
# on a day when no new one was produced.
find "${BACKUP_DIR}" -name "${DB_NAME}-*.sql.gz" -type f -mtime "+${RETENTION_DAYS}" -print -delete
find "${BACKUP_DIR}" -name "*.partial" -type f -mtime +1 -print -delete

echo "[$(date -Is)] done. local copies: $(find "${BACKUP_DIR}" -name "${DB_NAME}-*.sql.gz" | wc -l)"

# ── OFF-HOST COPY (do this — see the warning at the top) ──────────────────────
# Install rclone, configure a remote (`rclone config`) — Backblaze B2, S3, Google
# Drive, anything — then uncomment:
#
#   rclone copy "${DEST}" remote:travelcrm-backups/ --config /root/.config/rclone/rclone.conf
#
# ── RESTORE (practise this BEFORE you need it) ───────────────────────────────
#   sudo systemctl stop travelcrm
#   gunzip -c /var/backups/travelcrm/travel_crm-YYYYMMDD-HHMMSS.sql.gz \
#     | sudo -u postgres psql -d travel_crm_restore_test
#   # verify, then repoint DB_URL or rename the databases
#   sudo systemctl start travelcrm
#
# NOTE: the database is only half of a restore. APP_ENCRYPTION_KEY decrypts every
# tenant's stored SMTP/WhatsApp credentials — restoring the DB without that exact key
# leaves those columns as permanently unreadable bytes. Back the key up SEPARATELY
# (a password manager, not this server).