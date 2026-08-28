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

echo "==> tun2socks $TUN2SOCKS_VER (JNI build)"
# Prefer JNI shared library (supports VpnService fd) over standalone executable.
# The executable (hev-socks5-tunnel-android-*) always opens /dev/net/tun itself
# (tun_fd = -1) and fails with exit 254 when used with VpnService.
# We build the shared library via ndk-build which exposes
# hev.htproxy.TProxyService.TProxyStartService(config,f d).
if command -v ndk-build >/dev/null 2>&1 || [ -n "${ANDROID_NDK_HOME:-}" ]; then
  NDK_BUILD="${ANDROID_NDK_HOME:-$(dirname "$(command -v ndk-build)")}/ndk-build"
  if [ ! -x "$NDK_BUILD" ]; then NDK_BUILD="$(command -v ndk-build)"; fi
  echo "Building hev-socks5-tunnel JNI via $NDK_BUILD"
  rm -rf /tmp/hev
  git clone --depth 1 --branch "$TUN2SOCKS_VER" --recursive https://github.com/heiher/hev-socks5-tunnel /tmp/hev 2>&1 | tail -5
  echo "NDK_BUILD=$NDK_BUILD"
  ls -lh "$NDK_BUILD" 2>&1 | head -3
  (cd /tmp/hev && "$NDK_BUILD" -j4 2>&1 | tail -40)
  BUILD_OK=true
  for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    src="/tmp/hev/libs/$abi/libhev-socks5-tunnel.so"
    if [ -f "$src" ]; then
      dir="app/src/main/jniLibs/$abi"
      mkdir -p "$dir"
      cp "$src" "$dir/libtun2socks.so"
      cp "$src" "$dir/libhev-socks5-tunnel.so"
      chmod +x "$dir"/lib*.so
      echo "  $abi -> $(ls -lh "$dir"/lib*.so | awk '{print $9, $5}')"
      # Verify JNI symbols (strings is more reliable than nm -D on runner)
      if ! strings "$src" 2>/dev/null | grep -q "TProxyStartService"; then
        echo "ERROR: $src missing JNI TProxyStartService"
        BUILD_OK=false
      fi
    else
      echo "WARN: $src not found"
      BUILD_OK=false
    fi
  done
  if [ "$BUILD_OK" != "true" ]; then
    echo "WARN: JNI build verification failed — falling back to executable (will give exit 254 at runtime)"
    echo "Contents of /tmp/hev/libs:"
    find /tmp/hev -name "*.so" -ls 2>&1 | head -20 || true
    # Fallback to executable so CI still produces an APK for diagnostics
    BASE="https://github.com/heiher/hev-socks5-tunnel/releases/download/$TUN2SOCKS_VER"
    declare -A ABIS2=([arm64-v8a]=arm64-v8a [armeabi-v7a]=armeabi-v7a [x86]=x86 [x86_64]=x86_64)
    for abi in "${!ABIS2[@]}"; do
      dir="app/src/main/jniLibs/$abi"
      mkdir -p "$dir"
      curl -fsSL -o "$dir/libtun2socks.so" "$BASE/hev-socks5-tunnel-android-${ABIS2[$abi]}" 2>&1 | tail -3
      chmod +x "$dir/libtun2socks.so"
      echo "  fallback $abi -> $(ls -lh "$dir/libtun2socks.so" | awk '{print $5}')"
    done
  fi
else
  echo "NDK not found — using prebuilt executables (will fail with VpnService, exit 254)"
  BASE="https://github.com/heiher/hev-socks5-tunnel/releases/download/$TUN2SOCKS_VER"
  declare -A ABIS=([arm64-v8a]=arm64-v8a [armeabi-v7a]=armeabi-v7a [x86]=x86 [x86_64]=x86_64)
  for abi in "${!ABIS[@]}"; do
    dir="app/src/main/jniLibs/$abi"
    mkdir -p "$dir"
    curl -fsSL -o "$dir/libtun2socks.so" "$BASE/hev-socks5-tunnel-android-${ABIS[$abi]}"
    chmod +x "$dir/libtun2socks.so"
  done
fi

echo "Done. app/libs/libXray.aar + jniLibs populated."
