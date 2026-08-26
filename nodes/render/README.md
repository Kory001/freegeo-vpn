# FreeGeo node on Render (free tier, no credit card)

Render's free web services: 512 MB / 0.1 vCPU, 750 h/month, Docker runtime,
managed TLS on `*.onrender.com`. Spins down after 15 min idle — the
`keep-alive` workflow pings it back up.

## Deploy

1. Sign up at https://render.com with GitHub (no card).
2. Dashboard → **New +** → **Web Service** → connect repo `Kory001/freegeo-vpn`.
3. Configure:
   - **Runtime:** Docker
   - **Dockerfile path:** `./nodes/render/Dockerfile`
   - **Docker context:** `./nodes/render`
   - **Region:** Frankfurt
   - **Instance type:** Free
4. Environment variables:

   | Name | Value |
   |---|---|
   | `VLESS_UUID` | any UUID (`uuidgen`) |
   | `WS_PATH` | `/vless` |
   | `PORT` | leave unset — Render injects it automatically |

5. Create Web Service. Note the URL: `https://<app>.onrender.com`

The container binds to `$PORT` automatically; Render terminates TLS at the
edge, so the client speaks VLESS+WS+TLS to `<app>.onrender.com:443`.

## After deploy

Send the URL + UUID to be added into `registry/seed.json`, then verify:

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://<app>.onrender.com/
```

Any HTTP status (even 404) proves the tunnel edge is up.
