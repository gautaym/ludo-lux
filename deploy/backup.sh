#!/usr/bin/env bash
# Nightly backup: a consistent copy of the SQLite database (safe while the server runs), encrypted and sent off the
# machine with restic. Keeps 14 daily, 8 weekly, 12 monthly. On the 1st of each month it also test-restores.
set -euo pipefail
cd /opt/ludolux/deploy
set -a; . ./.env; set +a
SNAP=/tmp/ludolux-backup.db
sqlite3 data/ludolux.db ".backup '$SNAP'"
restic snapshots >/dev/null 2>&1 || restic init
restic backup "$SNAP" --tag ludolux --host ludolux
restic forget --tag ludolux --keep-daily 14 --keep-weekly 8 --keep-monthly 12 --prune
rm -f "$SNAP"
if [ "$(date +%d)" = "01" ]; then
  # Monthly restore test: the latest backup must open and contain the users table.
  rm -rf /tmp/ludolux-restore && restic restore latest --tag ludolux --target /tmp/ludolux-restore
  sqlite3 /tmp/ludolux-restore/tmp/ludolux-backup.db "SELECT COUNT(*) FROM users;" && echo "restore test OK"
  rm -rf /tmp/ludolux-restore
fi
echo "backup done $(date -Is)"
