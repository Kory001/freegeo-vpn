# FreeGeo VPN — Architecture

## Overview

FreeGeo VPN is a cardless, general-purpose Android VPN. Traffic exits through a
pool of Xray-core nodes (cardless free hosts + community volunteers), optionally
chained through Cloudflare WARP to mask datacenter IPs. A static JSON registry,
generated hourly by CI, tells clients which nodes are alive.

## Data flow

```
Android app (Kotlin/Compose + libXray/Xray-core)
  │  VpnService → tun2socks → full-tunnel (default)
  ▼
Exit node  ── VLESS+Reality (TCP) or VLESS+WS+TLS (behind PaaS edge TLS)
  ▼
Xray wireguard outbound (userspace TUN, no CAP_NET_ADMIN)
  ▼
Cloudflare WARP  ── per-node toggle; direct-exit fallback
  ▼
Internet
```

## Components

### Exit pool

| Tier | Nodes | Role |
|---|---|---|
| Cardless backbone | Serv00 Warsaw ×2 · CT8 Germany · Koyeb FRA (+ IAD via 2nd account) | Reliability. Serv00 ≈1 Gbps unlimited. Koyeb free = 0.1 vCPU/512 MB, browsing only |
| Community | SE Asia priority (MY/TH/ID/VN/PH), then BR/IN/any volunteer | Low-flagged IPs, regional content |

### Protocol selection

- **VLESS+Reality** where we control a raw TCP port and have no domain
  (Serv00, community VPS): no TLS cert needed, strongest anti-detection.
- **VLESS+WS+TLS** behind PaaS hosts that terminate TLS at their edge (Koyeb):
  WS path `/vless?ed=2560`, SNI = platform domain.
- Hysteria2 is deferred post-P3; the registry `protocol` field already
  accommodates it.

### WARP chaining

`wgcf` registers a WARP account and extracts keys + `reserved` bytes. Each node
runs an Xray native WireGuard outbound in userspace mode (`noKernelTun`),
so no elevated privileges are needed on shared hosts. A per-node `warp`
registry flag allows direct-exit fallback if a service flags the IP.

### Registry

- Source of truth: `registry/seed.json` (backbone entries, maintained by hand)
  + PRs from community bootstrap script.
- `registry/generate.py` runs on GitHub Actions hourly:
  1. TCP+TLS handshake check per node (~latency measurement).
  2. Optional through-proxy egress probe (`--xray`) against `ipinfo.io`.
  3. Node disabled after N=3 consecutive failures (transient failures don't
     immediately kill a healthy node).
  4. Output validated against `registry/schema.json`, then published to
     GitHub Pages as `registry.json`.
- Clients treat only `status == "ok"` nodes as connectable and cache the last
  good copy for offline use.

### Client

- Kotlin + Jetpack Compose, dark theme, single activity.
- Tunnel engine: **XTLS/libXray** AAR (maintained successor of 2dust/libv2ray).
- Foreground service with state machine: disconnected → connecting →
  connected → error.
- Leak hardening: in-tunnel DoH (1.1.1.1, dns.google), IPv4 `0.0.0.0/0` +
  IPv6 `::/0` captured by VpnService, kill switch while connecting/connected.
- Settings in Keystore-encrypted SharedPreferences. No accounts, no telemetry.
- Split-tunnel (optional): app bypass list (VpnService `addDisallowedApplication`)
  and domain→country routes via Xray routing rules.

## Build & release

- `.github/workflows/health-check.yml`: hourly cron + push → generate.py →
  Pages deploy. Failure counters persist via workflow cache (no repo writes,
  so the read-only GITHUB_TOKEN on new repos is sufficient). The hourly runs
  also keep the repo "active" so GH doesn't disable scheduled workflows after
  60 days idle.
- `.github/workflows/keep-alive.yml`: pings Koyeb `/probe` every ~50 min so its
  free instance never scales to zero (1 h idle limit).
- `.github/workflows/build-apk.yml`: debug APK artifact on every push; release
  APK on tags. APKs are built in CI because dev machines may be low-RAM.

## Decisions (locked)

| Decision | Value |
|---|---|
| Client core | XTLS/libXray |
| Protocols | VLESS+Reality / VLESS+WS+TCP-TLS |
| Registry host | GitHub Pages |
| WARP chain | wgcf + userspace WireGuard outbound |
| Default client mode | Full-tunnel |
| Country sort | latency, then alphabetical |
| Community recruitment | MY/TH/ID/VN/PH first, then BR/IN/etc |
| APK builds | CI-only primary, local CLI documented fallback |

## Handoff checklist (requires human accounts)

1. Push this repo to GitHub; enable GitHub Pages (branch: `gh-pages` or
   `/docs`— workflow uses `gh-pages` via actions-deploy-pages).
2. Serv00: enable *Run your own applications*, open TCP ports, run
   `nodes/serv00/deploy.sh`, then replace the matching seed entry's host/uuid.
2. Koyeb: create app → connect GitHub repo `Kory001/freegeo-vpn` →
   builder: Dockerfile, path `nodes/koyeb/Dockerfile` → set `VLESS_UUID`
   env var → note `<app>.koyeb.app`, update seed.
   (Koyeb builds the image itself; no ghcr pipeline needed.)
4. Install the APK artifact on an Android phone and test connect/IP-check.
