# Registry JSON API

The client fetches a single static file:

```
https://<org>.github.io/freegeo-vpn/registry.json
```

Regenerated hourly by CI; `updatedAt` is the freshness indicator. Clients must
cache the last good copy and fall back to it when offline or when the fetch
fails.

## Top-level shape

```jsonc
{
  "version": 1,
  "updatedAt": "2026-08-24T09:47:43Z",
  "defaultCountry": "us",
  "domainRoutes": [
    {"country": "us", "domains": ["netflix.com", "nflxvideo.net"]}
  ],
  "nodes": [ /* sorted: ok nodes by latencyMs asc, then the rest */ ]
}
```

## Node object

| Field | Type | Notes |
|---|---|---|
| `id` | string | stable slug, e.g. `koyeb-fra-1` |
| `country` | string | ISO-3166 alpha-2, lowercase |
| `flag` | string | emoji flag |
| `name` | string | human label |
| `platform` | string | `serv00` \| `ct8` \| `koyeb` \| `render` \| `community` \| `vps` |
| `protocol` | string | currently `vless` (Hysteria2 later) |
| `network` | string | `tcp` (Reality) or `ws` |
| `host`, `port` | string/int | endpoint |
| `path` | string? | WS path (`/vless?ed=2560`) — ws only |
| `tls.sni` | string? | TLS server name |
| `tls.alpn` | string[]? | e.g. `["http/1.1"]` |
| `tls.reality.pbk` | string? | Reality public key — presence ⇒ Reality mode |
| `tls.reality.sid` | string? | Reality shortId |
| `tls.reality.fp` | string? | uTLS fingerprint, default `chrome` |
| `uuid` | string | VLESS user id |
| `warp` | bool | node chains through Cloudflare WARP |
| `status` | string | `ok` \| `disabled` \| `new`. **Only connect to `ok`.** |
| `latencyMs` | int/null | measured handshake latency |
| `bandwidth` | string | `low` (chat/browse only) \| `high` (downloads OK) |

## Client rules

1. Ignore every node whose `status != "ok"`.
2. Default pick = nearest healthy `bandwidth: high` node; user override wins.
3. Sort UI list by measured latency, then country name; favorites pinned.
4. Cache last-good copy in app-private storage; use it when fetch fails.
5. Trust `updatedAt` for staleness warnings only — never block connect on a
   stale registry if cached `ok` nodes exist.

Validation schema lives at `registry/schema.json`.
