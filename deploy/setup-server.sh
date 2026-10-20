#!/usr/bin/env bash
# One-time setup of a fresh Ubuntu 24.04 server for Ludo Lux. Run as root:  bash setup-server.sh <your-ssh-user>
# What it does: security updates, firewall (only 80/443 open; SSH via Tailscale), SSH hardening, fail2ban,
# Docker, the app folder, nightly encrypted backups. Safe to run again.
set -euo pipefail
ADMIN="${1:?usage: setup-server.sh <admin-user>}"

echo "== updates (and automatic security updates from now on)"
apt-get update -y && DEBIAN_FRONTEND=noninteractive apt-get upgrade -y
DEBIAN_FRONTEND=noninteractive apt-get install -y unattended-upgrades ufw fail2ban sqlite3 restic curl ca-certificates
dpkg-reconfigure -f noninteractive unattended-upgrades

echo "== admin user $ADMIN (SSH keys only)"
id "$ADMIN" >/dev/null 2>&1 || adduser --disabled-password --gecos "" "$ADMIN"
usermod -aG sudo "$ADMIN"
mkdir -p /home/$ADMIN/.ssh && chmod 700 /home/$ADMIN/.ssh
[ -f /root/.ssh/authorized_keys ] && cp -n /root/.ssh/authorized_keys /home/$ADMIN/.ssh/authorized_keys || true
chown -R $ADMIN:$ADMIN /home/$ADMIN/.ssh
cat > /etc/ssh/sshd_config.d/90-ludolux.conf <<'SSH'
PermitRootLogin no
PasswordAuthentication no
KbdInteractiveAuthentication no
MaxAuthTries 3
SSH
systemctl reload ssh

echo "== Tailscale (private admin network; SSH is reachable only through it)"
command -v tailscale >/dev/null || curl -fsSL https://tailscale.com/install.sh | sh
echo "   -> run 'tailscale up' once and log in, then confirm you can SSH over Tailscale BEFORE closing port 22."

echo "== firewall: web open to all, SSH only on the Tailscale interface"
ufw default deny incoming
ufw default allow outgoing
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 443/udp
ufw allow in on tailscale0 to any port 22 proto tcp
# Temporary public SSH so you are not locked out; remove with:  ufw delete allow 22/tcp
ufw allow 22/tcp
ufw --force enable

echo "== fail2ban (blocks repeated SSH login attempts)"
systemctl enable --now fail2ban

echo "== Docker"
command -v docker >/dev/null || curl -fsSL https://get.docker.com | sh
usermod -aG docker "$ADMIN"

echo "== app folder /opt/ludolux"
mkdir -p /opt/ludolux/deploy/{data,secrets,caddy-data,caddy-config}
chown -R $ADMIN:$ADMIN /opt/ludolux
chmod 700 /opt/ludolux/deploy/secrets

echo "== nightly encrypted backup at 03:30 (see backup.sh)"
cat > /etc/cron.d/ludolux-backup <<'CRON'
30 3 * * * root /opt/ludolux/deploy/backup.sh >> /var/log/ludolux-backup.log 2>&1
CRON

echo
echo "Done. Next (as $ADMIN): copy the project to /opt/ludolux, create deploy/.env from .env.example,"
echo "build the jar (./gradlew :server:fatJar) and run:  cd /opt/ludolux/deploy && docker compose up -d --build"
