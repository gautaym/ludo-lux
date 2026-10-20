#!/usr/bin/env bash
# Downloads this month's free IP-location file (DB-IP "IP to City Lite", CC BY 4.0) used to pick state-level ads.
# It stays on this server: no player's address is ever sent anywhere. Runs monthly from cron; games survive the restart.
set -euo pipefail
cd /opt/ludolux/deploy
mkdir -p data/geo
MONTH=$(date +%Y-%m)
URL="https://download.db-ip.com/free/dbip-city-lite-$MONTH.mmdb.gz"
curl -fsSL "$URL" -o /tmp/dbip.mmdb.gz || { MONTH=$(date -d "$(date +%Y-%m-01) -1 month" +%Y-%m); curl -fsSL "https://download.db-ip.com/free/dbip-city-lite-$MONTH.mmdb.gz" -o /tmp/dbip.mmdb.gz; }
gunzip -c /tmp/dbip.mmdb.gz > data/geo/dbip-city-lite.mmdb.new
mv data/geo/dbip-city-lite.mmdb.new data/geo/dbip-city-lite.mmdb
chown -R 10001:10001 data/geo
rm -f /tmp/dbip.mmdb.gz
docker compose restart ludolux >/dev/null 2>&1 || true
echo "geo updated to $MONTH $(date -Is)"
