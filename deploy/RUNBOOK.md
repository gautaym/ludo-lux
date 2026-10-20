# Ludo Lux server runbook

The whole server is one container (`ludolux`) behind Caddy (HTTPS). All data is one SQLite file:
`/opt/ludolux/deploy/data/ludolux.db`. Live games are saved after every move, so restarts and updates do not lose them
(players' apps reconnect by themselves within seconds).

## First deploy
1. Rent an Ubuntu 24.04 server (2-4 vCPU, 4-8 GB is plenty for thousands of players). Add your SSH key.
2. Point your domain's DNS `A` record (e.g. `play.example.in`) at the server's IP.
3. As root: `bash deploy/setup-server.sh <you>`; then `tailscale up`, check SSH over Tailscale works, then close public
   SSH: `ufw delete allow 22/tcp`.
4. Copy the project to `/opt/ludolux`, then `cp deploy/.env.example deploy/.env` and fill it in.
   Put the Google Play service-account key in `deploy/secrets/play-service-account.json`, owned by the game user:
   `sudo install -o 10001 -g 10001 -m 400 ~/play-service-account.json /opt/ludolux/deploy/secrets/`
5. `./gradlew :server:fatJar` (or build on your PC and copy the jar), then
   `cd /opt/ludolux/deploy && docker compose up -d --build`.
6. Check: `curl https://play.example.in/health` prints `ok`.

## Monthly (10 minutes)
- `docker compose ps` - both containers "healthy"/"running".
- `tail /var/log/ludolux-backup.log` - recent "backup done", and on the 1st "restore test OK".
- `sudo needrestart -r l` or reboot if updates ask for it (games survive the reboot).

## Update the server
`git pull && ./gradlew :server:fatJar && cd deploy && docker compose up -d --build ludolux`

## Server down?
1. `docker compose ps` and `docker compose logs --tail 200 ludolux`.
2. `docker compose restart ludolux` (games are restored from the database).
3. Disk full? `df -h`; old Docker images: `docker image prune -f`.
4. Certificates: `docker compose logs caddy` (DNS must point at the server; ports 80/443 open).

## Restore from backup
```
docker compose stop ludolux
restic restore latest --tag ludolux --target /tmp/r
cp /tmp/r/tmp/ludolux-backup.db data/ludolux.db
docker compose start ludolux
```

## Suspected break-in
1. Rotate secrets: new `RESTIC_PASSWORD` (re-init a new repo), new Play service-account key, revoke the old one.
2. Sign everyone out: `sqlite3 data/ludolux.db "DELETE FROM sessions;"` (players sign in again with Google).
3. Check `last`, `/var/log/auth.log`, `docker compose logs`. Rebuild the server from scratch if in doubt and restore the
   latest backup made before the incident.

## Reviewing ads (every day or two)
Every ad is checked by you before it can run. The review page is only reachable on the server itself:
1. From your PC: `ssh -L 8080:127.0.0.1:8080 <you>@<server's Tailscale name>` and keep it open.
2. Open `http://localhost:8080/admin`, paste `ADMIN_TOKEN` from `deploy/.env`.
3. For each new ad: look at the picture and the business. **Approve** if it is honest and suitable for a family game;
   otherwise **Reject** with a short reason (the advertiser sees it).
4. After approval the advertiser pays you (UPI / bank transfer, with a GST invoice). Then **Mark paid** with the payment
   reference. Only paid ads run. Bookings not reviewed, or not paid, by their start day are cancelled automatically.
5. Never approve: anything misleading, other people's trademarks without permission, tobacco/alcohol/betting/adult,
   or political ads (switched off for now).

## Monitoring
`docker compose exec ludolux wget -qO- http://127.0.0.1:8080/metrics` - live rooms by status and players waiting for
ranked. (Kept off the public internet by Caddy.) Point Prometheus/Grafana or any uptime checker at `/health`.
