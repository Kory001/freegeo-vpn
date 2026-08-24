# FreeGeo VPN — Android Client

Kotlin + Jetpack Compose app using **XTLS/libXray** (Xray-core) + hev-socks5-tunnel.

## Build (recommended: CI)

Push to GitHub — `build-apk.yml` builds a debug APK automatically.
Download it from **Actions → build-apk → Artifacts → freegeo-debug-apk**
and sideload onto your phone.

## Build locally (low-RAM machines)

Requirements: JDK 17, Android SDK (`cmdline-tools` + platform 34 + build-tools).
No Android Studio needed.

```bash
./fetch-deps.sh        # downloads libXray.aar + tun2socks binaries (~40 MB)
./gradlew assembleDebug --no-daemon
```

The APK lands in `app/build/outputs/apk/debug/`.

`gradle.properties` is pre-tuned for 4 GB RAM machines (`-Xmx1536m`, no
parallel). Close other apps while building; an emulator will not fit in RAM —
use a physical device via USB debugging instead.

Install to a connected phone:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## First run

1. The app needs a registry URL — tap **Registry URL…** and point it at your
   published `registry.json` (default placeholder must be replaced after you
   push the repo and enable GitHub Pages).
2. Tap **CONNECT** → accept the VPN permission dialog.
3. Verify with **Check my IP** — it should show the exit node's IP/country.

## Architecture

```
ui/MainViewModel  ── UiState + connection StateFlow
service/FreeGeoVpnService
  ├─ VpnService TUN (0.0.0.0/0 + ::/0, kill switch via monitor loop)
  ├─ TunnelEngine → libxray.LibXray.invoke(runXray|stopXray)
  │    └─ Xray config built by engine/XrayConfigBuilder
  │       (socks inbound :10808, DoH dns outbound, port-53 hijack,
  │        VLESS+Reality / VLESS+WS+TLS outbound per node)
  └─ tun2socks (hev-socks5-tunnel) bridges TUN ⇄ socks :10808
data/
  ├─ RegistryRepository  fetch + offline cache of registry.json
  └─ SecurePrefs         Keystore(AES/GCM)-encrypted SharedPreferences
```

## Notes

- `app/libs/libXray.aar` and `app/src/main/jniLibs/**` are downloaded at build
  time by `fetch-deps.sh` — never commit them.
- Split-tunnel bypass list hooks exist (`FreeGeoVpnService.bypassApps`);
  full UI for it ships with P5.
- Release APKs are built unsigned on tags; sign them before distribution.
