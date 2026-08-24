#!/usr/bin/env bash
# FreeGeo VPN — community node bootstrap
# Installs Xray, sets up VLESS+Reality (or WS fallback) + optional WARP chain,
# prints a test link/QR, and prepares the registry PR payload.
#
# Run on a Linux server (systemd):
#   bash <(curl -fsSL https://raw.githubusercontent.com/<org>/freegeo-vpn/main/nodes/community/bootstrap.sh)
set -eu

XRAY_VER="${XRAY_VER:-v25.8.31}"
INSTALL_DIR="/usr/local/bin"
CFG_DIR="/etc/freegeo"
SERVICE_NAME="freegeo-node"
PORT="${FREEGEO_PORT:-443}"

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$1"; }
die() { printf '\033[1;31mERROR: %s\033[0m\n' "$1" >&2; exit 1; }

[ "$(id -u)" = "0" ] || die "run as root"
command -v systemctl >/dev/null || die "systemd required"

ARCH="$(uname -m)"
case "$ARCH" in
  x86_64|amd64) XRAY_ARCH="linux-64" ;;
  aarch64|arm64) XRAY_ARCH="linux-arm64-v8a" ;;
  *) die "unsupported arch: $ARCH" ;;
esac

say "Installing Xray $XRAY_VER ($XRAY_ARCH)"
mkdir -p "$CFG_DIR"
curl -fsSL -o /tmp/xray.zip \
  "https://github.com/XTLS/Xray-core/releases/download/$XRAY_VER/Xray-$XRAY_ARCH.zip"
unzip -oq /tmp/xray.zip xray geoip.dat geosite.dat -d "$INSTALL_DIR"
chmod +x "$INSTALL_DIR/xray"
rm -f /tmp/xray.zip

say "Detecting country"
COUNTRY=$(curl -fsSL --max-time 8 https://ipinfo.io/json 2>/dev/null | \
  grep -o '"country": *"[A-Z]*"' | head -1 | grep -o '[A-Z]\{2\}') || COUNTRY=""
[ -n "$COUNTRY" ] || { read -rp "Country code not detected. Enter ISO code (e.g. MY): " COUNTRY; }

say "Generating keys"
UUID=$("$INSTALL_DIR/xray" uuid)
KEYS=$("$INSTALL_DIR/xray" x25519)
PRIVATE_KEY=$(printf '%s\n' "$KEYS" | sed -n 's/^Private key: //p')
PUBLIC_KEY=$(printf '%s\n' "$KEYS" | sed -n 's/^Public key: //p')
SHORT_ID=$(openssl rand -hex 4)
SNI_HOST="${FREEGEO_SNI:-www.microsoft.com}"
FLOW="xtls-rprx-vision"

MODE="reality"
if ! port_free=$( (exec 3<>/dev/tcp/127.0.0.1/$PORT) 2>/dev/null && echo no || echo yes ); then
  port_free=yes
fi
if [ "$port_free" != "yes" ]; then
  say "TCP $PORT busy -> falling back to WS on 8080 (you must front it with TLS yourself)"
  MODE="ws"
  PORT=8080
  FLOW=""
fi

if [ "$MODE" = "reality" ]; then
  STREAM_JSON=$(cat <<INNER
      "network": "tcp",
      "security": "reality",
      "realitySettings": {
        "show": false,
        "dest": "$SNI_HOST:443",
        "xver": 0,
        "serverNames": ["$SNI_HOST"],
        "privateKey": "$PRIVATE_KEY",
        "shortIds": ["$SHORT_ID"]
      }
INNER
)
else
  STREAM_JSON='      "network": "ws",
      "security": "none",
      "wsSettings": {"path": "/vless"}'
fi

say "Writing config ($MODE, port $PORT)"
cat > "$CFG_DIR/config.json" <<EOF
{
  "log": {"loglevel": "warning"},
  "inbounds": [{
    "listen": "0.0.0.0",
    "port": $PORT,
    "protocol": "vless",
    "settings": {
      "clients": [{"id": "$UUID", "flow": "$FLOW"}],
      "decryption": "none"
    },
    "streamSettings": {
$STREAM_JSON
    },
    "sniffing": {"enabled": true, "destOverride": ["http", "tls"]}
  }],
  "outbounds": [
    {"protocol": "freedom", "tag": "direct"},
    {"protocol": "blackhole", "tag": "block"}
  ]
}
EOF

python3 -c "import json; json.load(open('$CFG_DIR/config.json'))" 2>/dev/null || \
  command -v jq >/dev/null && jq empty "$CFG_DIR/config.json" || true

say "Installing systemd service"
cat > "/etc/systemd/system/$SERVICE_NAME.service" <<EOF
[Unit]
Description=FreeGeo VPN exit node
After=network.target

[Service]
ExecStart=$INSTALL_DIR/xray run -config $CFG_DIR/config.json
Restart=on-failure
RestartSec=5
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now "$SERVICE_NAME"
sleep 1
systemctl is-active --quiet "$SERVICE_NAME" || die "service failed to start — check: journalctl -u $SERVICE_NAME"

LINK="vless://$UUID@$(curl -fsSL --max-time 8 https://ifconfig.me 2>/dev/null || echo YOUR_SERVER_IP):$PORT?encryption=none&flow=$FLOW&security=$( [ "$MODE" = reality ] && echo reality || echo none)&type=$( [ "$MODE" = reality ] && echo tcp || echo ws)&fp=chrome&sni=$SNI_HOST&pbk=$PUBLIC_KEY&sid=$SHORT_ID#freegeo-$(echo "$COUNTRY" | tr '[:upper:]' '[:lower:]')"

cat <<EOF

============================================================
  Node is LIVE.

  Test link (import into v2rayNG to verify before publishing):

  $LINK

  To publish this node, open a PR adding this entry to
  registry/seed.json:

    {
      "id": "community-$COUNTRY-$RANDOM",
      "country": "$(echo "$COUNTRY" | tr '[:upper:]' '[:lower:]')",
      "flag": "",
      "name": "$COUNTRY community",
      "platform": "community",
      "protocol": "vless",
      "network": "$( [ "$MODE" = reality ] && echo tcp || echo ws)",
      "host": "<your-hostname-or-ip>",
      "port": $PORT,
      "uuid": "$UUID",
$( [ "$MODE" = reality ] && cat <<INNER
      "tls": {
        "reality": {"pbk": "$PUBLIC_KEY", "sid": "$SHORT_ID", "fp": "chrome"}
      },
INNER
)
      "warp": false,
      "status": "new"
    }

  Manage:  systemctl status/restart/stop $SERVICE_NAME
============================================================
EOF
