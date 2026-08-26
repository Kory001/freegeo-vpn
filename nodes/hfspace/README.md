# FreeGeo node on Hugging Face Spaces (free, no credit card)

HF Spaces gives every account free Docker containers (2 vCPU, 16 GB RAM,
no credit card) exposed at `https://<user>-<space>.hf.space` with TLS.
The container must listen on port **7860** — handled via the Space README
(`app_port: 7860`) and `PORT=7860`.

⚠️ **ToS reality check**: running a proxy on HF violates the spirit of
their ToS. Spaces get suspended if reported/abused. Treat this as a
disposable personal node, keep traffic modest, don't share it publicly.

## Deploy (web UI only, no git needed)

1. Sign up at https://huggingface.co (free).
2. Click **New → Space**:
   - Space SDK: **Docker → Blank**
   - Name: anything innocuous, e.g. `api-tools`
   - Visibility: **Private** won't work for client connections — use Public.
3. In the Space → **Files** tab → **Add file** → upload these 3 files from
   this directory: `README.md`, `Dockerfile`, `entrypoint.sh`
4. Go to **Settings → Variables and secrets** → add variable:

   | Name | Value |
   |---|---|
   | `VLESS_UUID` | your UUID |
   | `WS_PATH` | `/vless` |
   | `PORT` | `7860` |

5. The Space rebuilds automatically (~2 min). Status dot turns green.

## Verify

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://<user>-<space>.hf.space/
```

Any HTTP status = tunnel edge is up. Then send URL + UUID to be added to
the registry seed.
