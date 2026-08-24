#!/usr/bin/env bash
# Fetches prebuilt tunnel dependencies for local builds.
# CI (build-apk.yml) runs the same logic inline.
set -eu
cd "$(dirname "$0")"

LIBXRAY_VER="${LIBXRAY_VER:-v26.7.28}"
TUN2SOCKS_VER="${TUN2SOCKS_VER:-2.17.1}"

echo "==> libXray $LIBXRAY_VER"
mkdir -p app/libs
curl -fsSL -o /tmp/libxray.zip \
  "https://github.com/XTLS/libXray/releases/download/$LIBXRAY_VER/libxray-android.zip"
rm -rf /tmp/libxray-extract && mkdir /tmp/libxray-extract
unzip -oq /tmp/libxray.zip -d /tmp/libxray-extract
AAR=$(find /tmp/libxray-extract -name 'libXray.aar' | head -1)
[ -n "$AAR" ] || { echo "libXray.aar not found in zip"; exit 1; }
cp "$AAR" app/libs/libXray.aar

echo "==> tun2socks $TUN2SOCKS_VER"
BASE="https://github.com/heiher/hev-socks5-tunnel/releases/download/$TUN2SOCKS_VER"
declare -A ABIS=(
  [arm64-v8a]=arm64-v8a
  [armeabi-v7a]=armeabi-v7a
  [x86]=x86
  [x86_64]=x86_64
)
for abi in "${!ABIS[@]}"; do
  dir="app/src/main/jniLibs/$abi"
  mkdir -p "$dir"
  curl -fsSL -o "$dir/libtun2socks.so" "$BASE/hev-socks5-tunnel-android-${ABIS[$abi]}"
  chmod +x "$dir/libtun2socks.so"
done

echo "Done. app/libs/libXray.aar + jniLibs populated."
