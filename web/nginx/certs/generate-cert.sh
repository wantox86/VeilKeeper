#!/usr/bin/env sh
# Generates a self-signed TLS certificate for the `web` service's LAN-only
# HTTPS deployment (Web Sprint 8 follow-up -- see CLAUDE.md "Web Sprint 8").
#
# Why this exists: crypto.subtle (Web Crypto API) only works in a browser
# "secure context" (HTTPS, or localhost). Plain http://192.168.50.131:18092
# is NOT a secure context, so register/login/vault crypto silently fail for
# any device other than the host itself. There is no real DNS name for a
# private LAN IP, so a public CA can't issue a cert for it -- a self-signed
# cert (manually trusted once per device) is the accepted trade-off here.
#
# Run this BEFORE `docker compose up -d --build web` (or any time the cert
# needs regenerating/rotating). Output is gitignored -- never commit the
# private key (see repo root .gitignore).
#
# Usage: ./generate-cert.sh [LAN_IP]
#   LAN_IP defaults to 192.168.50.131 (this MACMINI host's current LAN IP).

set -eu

LAN_IP="${1:-192.168.50.131}"
DIR="$(cd "$(dirname "$0")" && pwd)"
DAYS=3650  # ~10 years -- manual-trust-once-per-device makes short-lived
           # certs and a renewal story not worth the complexity here.

openssl req -x509 -nodes \
  -newkey rsa:2048 \
  -keyout "$DIR/key.pem" \
  -out "$DIR/cert.pem" \
  -days "$DAYS" \
  -subj "/CN=${LAN_IP}" \
  -addext "subjectAltName=IP:${LAN_IP}"

chmod 644 "$DIR/cert.pem" "$DIR/key.pem"

echo "Generated $DIR/cert.pem and $DIR/key.pem for IP ${LAN_IP} (valid ${DAYS} days)."
echo "Next: docker compose up -d --build web"
