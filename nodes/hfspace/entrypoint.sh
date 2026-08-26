#!/bin/sh
# FreeGeo VPN — Koyeb entrypoint
# Koyeb terminates TLS at its edge and forwards plain HTTP to the container,
# so this runs VLESS+WS *without* local TLS on $PORT (default 8080).
set -eu

UUID="${VLESS_UUID:-$(xray uuid)}"
WS_PATH="${WS_PATH:-/vless}"
PORT="${PORT:-8080}"

CFG=/tmp/config.json

cat > "$CFG" <<EOF
{
  "log": {"loglevel": "warning"},
  "inbounds": [{
    "listen": "0.0.0.0",
    "port": $PORT,
    "protocol": "vless",
    "settings": {
      "clients": [{"id": "$UUID", "flow": ""}],
      "decryption": "none"
    },
    "streamSettings": {
      "network": "ws",
      "wsSettings": {"path": "$WS_PATH"}
    },
    "sniffing": {"enabled": true, "destOverride": ["http", "tls"]}
  }],
  "outbounds": [
    {"protocol": "freedom", "tag": "direct"},
    {"protocol": "blackhole", "tag": "block"}
  ]
}
EOF

echo "FreeGeo node up: vless+ws path=$WS_PATH port=$PORT uuid=$UUID"
exec xray run -config "$CFG"
