# Community Node Guide

FreeGeo VPN exit nodes are volunteer-run. Anyone with a server (VPS, home
server, cloud instance) can add their node to the public pool in one line.

## What volunteers run

```bash
bash <(curl -fsSL https://raw.githubusercontent.com/<org>/freegeo-vpn/main/nodes/community/bootstrap.sh)
```

The script:

1. Detects the server's country via `ipinfo.io` (asks if detection fails).
2. Installs the official Xray-core binary (amd64/arm64).
3. Generates a VLESS config:
   - **Reality** on TCP 443 when the port is free (recommended — no domain,
     no cert, best anti-detection).
   - **WS+TLS** fallback on an alternate port if 443 is taken and a domain +
     cert are available.
4. Optionally registers Cloudflare WARP via `wgcf` and chains it as the
   outbound (recommended; keeps your server IP hidden).
5. Prints a VLESS share link + QR code so you can test before publishing.
6. Opens a pull request adding your node to `registry/seed.json`.

A maintainer merges the PR; the hourly health-checker picks your node up and
it appears in every user's app within ~an hour.

## Rules & expectations

- **Legal**: you are responsible for what exits through your server. If that
  is unacceptable where you live, do not volunteer a node.
- **Traffic**: nodes are general-purpose browsing/chat. The health-checker
  auto-disables nodes after 3 consecutive failed checks; chronically
  overloaded nodes get removed.
- **Privacy**: don't log user traffic. The project will not accept nodes that
  inject content or MITM.
- **Removal**: stop serving at any time by shutting down Xray — the checker
  disables the entry automatically. Re-run the bootstrap to come back.

## Recruiting priorities

SE Asia first (Malaysia, Thailand, Indonesia, Vietnam, Philippines) for
low-flagged IPs and regional content, then Brazil, India, and any other
country. African nodes are especially welcome — best latency for African
users.

## FAQ

**Do I need a domain?** No — Reality needs none.

**Does my home IP get exposed?** Not if WARP chaining is enabled (default).

**Windows/macOS?** Linux (systemd) only for now.
