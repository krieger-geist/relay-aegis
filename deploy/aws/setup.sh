#!/usr/bin/env bash
# Installs and starts RELAY AEGIS on a fresh Ubuntu 24.04 EC2 server, reachable at https://<domain>.
# Safe to run again: it keeps the existing .env (secrets, database password) and only updates what changed.
#
#   sudo bash setup.sh --admin-email you@example.com              domain from the server's public IP (sslip.io)
#   sudo bash setup.sh --admin-email you@example.com --domain relay.example.com
#   sudo bash setup.sh --version 1.4.0                             move to another release
#
# Copy this folder to the server first (see docs/AWS.md): it brings the Caddyfile and compose file with it.
set -euo pipefail

DIR=/opt/relay-aegis
REPO=https://github.com/krieger-geist/relay-aegis.git
HERE="$(cd "$(dirname "$0")" && pwd)"
VERSION=1.4.0
DOMAIN=""
ADMIN_EMAIL=""

while [ $# -gt 0 ]; do
  case "$1" in
    --domain) DOMAIN=$2; shift 2 ;;
    --admin-email) ADMIN_EMAIL=$2; shift 2 ;;
    --version) VERSION=$2; shift 2 ;;
    -h|--help) sed -n 2,10p "$0"; exit 0 ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

step() { echo; echo "==> $*"; }
[ "$(id -u)" = 0 ] || { echo "Run with sudo."; exit 1; }

step "Swap file (2 GB), so a short memory peak slows the server instead of stopping a container"
if ! swapon --show | grep -q .; then
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile >/dev/null && swapon /swapfile
  grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi
sysctl -q vm.swappiness=10

step "Docker, Compose and git from Ubuntu's own packages"
if ! command -v docker >/dev/null || ! docker compose version >/dev/null 2>&1; then
  apt-get update -q
  DEBIAN_FRONTEND=noninteractive apt-get install -y -q docker.io docker-compose-v2 git openssl curl
fi
systemctl enable --now docker >/dev/null
id -nG ubuntu 2>/dev/null | grep -qw docker || usermod -aG docker ubuntu 2>/dev/null || true

step "RELAY source at v$VERSION (the demo application, runbooks and the main compose file)"
if [ -d "$DIR/.git" ]; then
  git -C "$DIR" fetch -q --tags origin
  # -f: copies of the files below, added by an earlier run, may now be part of the release.
  git -C "$DIR" -c advice.detachedHead=false checkout -q -f "v$VERSION"
else
  git clone -q --branch "v$VERSION" "$REPO" "$DIR"
fi
# A release that predates these files gets the copies that came with this script.
mkdir -p "$DIR/deploy/aws"
for f in docker-compose.aws.yml Caddyfile setup.sh; do
  git -C "$DIR" ls-files --error-unmatch "deploy/aws/$f" >/dev/null 2>&1 && continue
  [ "$HERE/$f" -ef "$DIR/deploy/aws/$f" ] || cp "$HERE/$f" "$DIR/deploy/aws/$f"
done

if [ -z "$DOMAIN" ] && [ -f "$DIR/.env" ]; then DOMAIN=$(sed -n 's/^RELAY_DOMAIN=//p' "$DIR/.env"); fi
if [ -z "$DOMAIN" ]; then
  step "Domain from the public IP"
  token=$(curl -s -m 3 -X PUT http://169.254.169.254/latest/api/token -H "X-aws-ec2-metadata-token-ttl-seconds: 60" || true)
  ip=$(curl -s -m 3 -H "X-aws-ec2-metadata-token: $token" http://169.254.169.254/latest/meta-data/public-ipv4 || true)
  [ -n "$ip" ] || ip=$(curl -s -m 5 https://checkip.amazonaws.com | tr -d '[:space:]')
  [[ "$ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "No public IP found. Pass --domain."; exit 1; }
  DOMAIN="${ip//./-}.sslip.io"
fi

step "Settings in $DIR/.env"
cd "$DIR"
if [ ! -f .env ]; then
  [ -n "$ADMIN_EMAIL" ] || { echo "First run: pass --admin-email (that account becomes the admin when it signs up)."; exit 1; }
  umask 077
  cat > .env <<EOF
COMPOSE_PROJECT_NAME=relay-aegis
RELAY_VERSION=$VERSION
RELAY_DOMAIN=$DOMAIN
DATABASE_PASSWORD=$(openssl rand -hex 24)
RELAY_AUTH_JWT_SECRET=$(openssl rand -hex 32)
RELAY_AUTH_ADMIN_EMAILS=$ADMIN_EMAIL
EOF
else
  sed -i "s/^RELAY_VERSION=.*/RELAY_VERSION=$VERSION/; s/^RELAY_DOMAIN=.*/RELAY_DOMAIN=$DOMAIN/" .env
  [ -z "$ADMIN_EMAIL" ] || sed -i "s/^RELAY_AUTH_ADMIN_EMAILS=.*/RELAY_AUTH_ADMIN_EMAILS=$ADMIN_EMAIL/" .env
fi
chmod 600 .env

compose() { docker compose -f docker-compose.yml -f deploy/aws/docker-compose.aws.yml "$@"; }

step "Images: pull the released ones, build only the small demo application"
compose pull -q db docker-proxy sandbox-proxy backend frontend caddy
compose build -q test-target

step "Start"
compose up -d --no-build --remove-orphans

step "Waiting for https://$DOMAIN (first start and the certificate take 1 to 3 minutes)"
for _ in $(seq 1 60); do
  if curl -fsS -m 5 "https://$DOMAIN/api/health" >/dev/null 2>&1; then
    echo
    echo "RELAY AEGIS is up: https://$DOMAIN"
    echo "Sign up there with $(sed -n 's/^RELAY_AUTH_ADMIN_EMAILS=//p' .env) to get the admin account."
    exit 0
  fi
  sleep 5
done
echo "Not reachable over HTTPS yet. Check that ports 80 and 443 are open in the security group, then:"
echo "  cd $DIR && docker compose -f docker-compose.yml -f deploy/aws/docker-compose.aws.yml logs --tail=50 caddy backend"
exit 1
