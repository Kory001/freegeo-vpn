#!/usr/bin/env python3
"""FreeGeo VPN registry generator.

Health-checks every node in the seed file, tracks consecutive failures,
and emits the client-facing registry.json consumed by the Android app
(and published to GitHub Pages by build/workflows/health-check.yml).

Usage:
    python3 generate.py [--seed seed.json] [--output ../registry.json]
                        [--state health-state.json] [--max-failures 3]
                        [--xray /path/to/xray]
"""

import argparse
import json
import socket
import ssl
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path

try:
    import requests
    from jsonschema import Draft202012Validator
except ImportError:
    sys.exit("Missing deps. Run: pip install -r requirements.txt")

HERE = Path(__file__).resolve().parent
PLACEHOLDER = "REPLACE-ME"
PROBE_TIMEOUT_S = 10


def check_tcp_tls(host: str, port: int, sni: str, timeout: float):
    """TCP connect + TLS handshake. Returns latency in ms or raises."""
    start = time.monotonic()
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    with socket.create_connection((host, port), timeout=timeout) as sock:
        with ctx.wrap_socket(sock, server_hostname=sni):
            pass
    return int((time.monotonic() - start) * 1000)


def build_probe_config(node: dict, socks_port: int) -> str:
    outbound = {
        "protocol": "vless",
        "settings": {"vnext": [{
            "address": node["host"],
            "port": node["port"],
            "users": [{"id": node["uuid"], "encryption": "none", "flow": ""}],
        }]},
        "streamSettings": {
            "network": node.get("network", "tcp"),
            "security": "tls",
            "tlsSettings": {
                "serverName": node.get("tls", {}).get("sni", node["host"]),
                "allowInsecure": True,
            },
        },
    }
    if node.get("path"):
        outbound["streamSettings"]["wsSettings"] = {"path": node["path"]}
    config = {
        "inbounds": [{
            "listen": "127.0.0.1",
            "port": socks_port,
            "protocol": "socks",
            "settings": {"auth": "noauth", "udp": False},
        }],
        "outbounds": [outbound],
    }
    return json.dumps(config)


def probe_egress(node: dict, xray_path: str, timeout: float):
    """Route a request to ipinfo.io through the node via a throwaway Xray
    instance. Returns (latency_ms, country_code) or raises."""
    import random

    port = random.randint(20000, 40000)
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
        f.write(build_probe_config(node, port))
        cfg_path = f.name
    proc = subprocess.Popen(
        [xray_path, "run", "-config", cfg_path],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    try:
        time.sleep(2)
        start = time.monotonic()
        r = requests.get(
            "https://ipinfo.io/json",
            proxies={"https": f"socks5h://127.0.0.1:{port}"},
            timeout=timeout,
        )
        r.raise_for_status()
        latency = int((time.monotonic() - start) * 1000)
        return latency, r.json().get("country")
    finally:
        proc.kill()
        proc.wait()
        Path(cfg_path).unlink(missing_ok=True)


def check_node(node: dict, args) -> dict:
    result = {"status": node["status"], "latencyMs": node.get("latencyMs")}
    if PLACEHOLDER in node["host"]:
        return result
    try:
        sni = node.get("tls", {}).get("sni", node["host"])
        latency = check_tcp_tls(node["host"], node["port"], sni, args.timeout)
        result.update(status="ok", latencyMs=latency)
        if args.xray:
            try:
                egress_latency, country = probe_egress(node, args.xray, PROBE_TIMEOUT_S)
                result["latencyMs"] = egress_latency
                print(f"  egress OK via {node['id']}: {country} {egress_latency}ms")
            except Exception as exc:
                print(f"  egress probe failed for {node['id']}: {exc}")
                result.update(status="disabled")
    except Exception as exc:
        print(f"  handshake failed for {node['id']}: {exc}")
        result.update(status="disabled", latencyMs=None)
    return result


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--seed", default=str(HERE / "seed.json"))
    ap.add_argument("--output", default=str(HERE.parent / "registry.json"))
    ap.add_argument("--state", default=str(HERE / "health-state.json"))
    ap.add_argument("--schema", default=str(HERE / "schema.json"))
    ap.add_argument("--max-failures", type=int, default=3)
    ap.add_argument("--timeout", type=float, default=8.0,
                    help="per-node TCP/TLS timeout in seconds")
    ap.add_argument("--xray", default=None,
                    help="optional path to xray binary for through-proxy egress probe")
    args = ap.parse_args()

    seed = json.loads(Path(args.seed).read_text(encoding="utf-8"))
    state_path = Path(args.state)
    state = json.loads(state_path.read_text()) if state_path.exists() else {}

    checked_nodes = []
    for node in seed["nodes"]:
        nid = node["id"]
        entry = state.setdefault(nid, {"consecutiveFailures": 0})
        print(f"Checking {nid} ({node['host']})...")
        result = check_node(node, args)

        if result["status"] == "disabled":
            entry["consecutiveFailures"] += 1
            if entry["consecutiveFailures"] >= args.max_failures:
                final_status = "disabled"
            else:
                final_status = "ok"
                print(f"  transient failure {entry['consecutiveFailures']}/{args.max_failures}, keeping ok")
        elif result["status"] == "new":
            final_status = "new"
        else:
            entry["consecutiveFailures"] = 0
            final_status = "ok"

        checked_nodes.append({**node, **result, "status": final_status})

    registry = {
        "version": 1,
        "updatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "defaultCountry": seed["defaultCountry"],
        "domainRoutes": seed["domainRoutes"],
        "nodes": sorted(
            [n for n in checked_nodes if n["status"] == "ok"],
            key=lambda n: (n["latencyMs"] is None, n["latencyMs"] or 0),
        ) + [n for n in checked_nodes if n["status"] != "ok"],
    }

    schema = json.loads(Path(args.schema).read_text(encoding="utf-8"))
    errors = sorted(Draft202012Validator(schema).iter_errors(registry),
                    key=lambda e: list(e.absolute_path))
    if errors:
        for err in errors:
            print(f"SCHEMA ERROR at {'/'.join(map(str, err.absolute_path))}: {err.message}",
                  file=sys.stderr)
        sys.exit(1)

    out = Path(args.output)
    tmp = out.with_suffix(".tmp")
    tmp.write_text(json.dumps(registry, indent=2, ensure_ascii=False), encoding="utf-8")
    tmp.replace(out)

    state_path.write_text(json.dumps(state, indent=2))

    ok = sum(1 for n in checked_nodes if n["status"] == "ok")
    print(f"\nDone: {ok}/{len(checked_nodes)} healthy -> {out}")


if __name__ == "__main__":
    main()
