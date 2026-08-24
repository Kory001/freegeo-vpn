#!/usr/bin/env sh
# FreeGeo VPN — Serv00 node deploy (FreeBSD)
# Prereqs (do these in the Serv00 panel first):
#   1. Enable "Run your own applications"
#   2. Open one TCP port (default 443 or any free TCP port) in Ports
#   3. SSH into the account, then run this script
set -eu

XRAY_VER="v25.8.31"
INSTALL_DIR="$HOME/freegeo"
BIN_DIR="$INSTALL_DIR/bin"
CFG="$INSTALL_DIR/config.json"
PORT="${FREEGEO_PORT:-443}"

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$1"; }

command -v curl >/dev/null 2>&1 || { echo "curl is required"; exit 1; }
command -v unzip >/dev/null 2>&1 || pkg install -y unzip >/dev/null 2>&1 || \
  { echo "install unzip: pkg install unzip (or use the panel)"; exit 1; }

ARCH="$(uname -m)"
case "$ARCH" in
  amd64|x86_64) XRAY_ARCH="freebsd-64" ;;
  aarch64|arm64) XRAY_ARCH="freebsd-arm64-v8a" ;;
  *) echo "Unsupported arch: $ARCH"; exit 1 ;;
esac

mkdir -p "$BIN_DIR" "$INSTALL_DIR"

say "Downloading Xray $XRAY_VER ($XRAY_ARCH)"
URL="https://github.com/XTLS/Xray-core/releases/download/$XRAY_VER/Xray-$XRAY_ARCH.zip"
TMPZIP="$INSTALL_DIR/xray.zip"
curl -fsSL -o "$TMPZIP" "$URL"
unzip -oq "$TMPZIP" -d "$BIN_DIR"
rm -f "$TMPZIP" "$BIN_DIR"/geoip.dat "$BIN_DIR"/geosite.dat
chmod +x "$BIN_DIR/xray"

say "Generating UUID + Reality keys"
UUID=$("$BIN_DIR/xray" uuid)
KEYS=$("$BIN_DIR/xray" x25519)
PRIVATE_KEY=$(printf '%s\n' "$KEYS" | sed -n 's/^Private key: //p')
PUBLIC_KEY=$(printf '%s\n' "$KEYS" | sed -n 's/^Public key: //p')
SHORT_ID=$(openssl rand -hex 4 2>/dev/null || head -c 4 /dev/urandom | od -An -tx1 | tr -d ' \n')

HOST=$(hostname | sed 's/\.$//')
SNI_HOST="${FREEGEO_SNI:-www.microsoft.com}"
FLOW="xtls-rprx-vision"

say "Writing config ($HOST:$PORT, Reality -> $SNI_HOST)"
cat > "$CFG" <<EOF
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
    },
    "sniffing": {"enabled": true, "destOverride": ["http", "tls"]}
  }],
  "outbounds": [
    {"protocol": "freedom", "tag": "direct"},
    {"protocol": "blackhole", "tag": "block"}
  ]
}
EOF

say "Starting Xray + keep-alive cron"
pkill -f "$BIN_DIR/xray" 2>/dev/null || true
nohup "$BIN_DIR/xray" run -config "$CFG" >/dev/null 2>&1 &

CRON_LINE="@reboot $BIN_DIR/xray -run -config $CFG >/dev/null 2>&1"
(crontab -l 2>/dev/null | grep -vF "$BIN_DIR/xray" ; echo "$CRON_LINE") | crontab -

cat <<EOF

============================================================
  Serv00 node deployed.

  Add to registry seed.json:
    id:       serv00-waw-<n>
    host:     $HOST
    port:     $PORT
    protocol: vless / network: tcp
    uuid:     $UUID
    flow:     $FLOW
    tls.reality.pbk: $PUBLIC_KEY
    tls.reality.sid: $SHORT_ID
    tls.reality.fp:  chrome
    warp:     false (add WARP chain later via wgcf if desired)

  Test from your phone with v2rayNG before publishing.
============================================================
EOF
