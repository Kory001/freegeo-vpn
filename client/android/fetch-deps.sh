#!/usr/bin/env bash
# Fetches prebuilt tunnel dependencies for local and CI builds.
set -eu
cd "$(dirname "$0")"

LIBXRAY_VER="${LIBXRAY_VER:-v26.7.28}"
TUN2SOCKS_VER="${TUN2SOCKS_VER:-2.17.1}"

download_executables() {
  echo "Downloading prebuilt hev-socks5-tunnel executables..."
  BASE="https://github.com/heiher/hev-socks5-tunnel/releases/download/$TUN2SOCKS_VER"
  for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    dir="app/src/main/jniLibs/$abi"
    mkdir -p "$dir"
    curl -fsSL -o "$dir/libtun2socks.so" "$BASE/hev-socks5-tunnel-android-$abi"
    chmod 755 "$dir/libtun2socks.so"
    echo "  $abi executable ($(stat -c%s "$dir/libtun2socks.so") bytes)"
  done
}

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

# Find NDK: check ANDROID_NDK_HOME, ANDROID_HOME/ndk/*, PATH
NDK_BUILD=""
if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -x "${ANDROID_NDK_HOME}/ndk-build" ]; then
  NDK_BUILD="$ANDROID_NDK_HOME/ndk-build"
elif [ -n "${NDK_HOME:-}" ] && [ -x "${NDK_HOME}/ndk-build" ]; then
  NDK_BUILD="$NDK_HOME/ndk-build"
elif [ -n "${ANDROID_HOME:-}" ]; then
  for d in "$ANDROID_HOME/ndk"/*/; do
    if [ -x "${d}ndk-build" ]; then
      NDK_BUILD="${d}ndk-build"
      break
    fi
  done
fi
if [ -z "$NDK_BUILD" ] && command -v ndk-build >/dev/null 2>&1; then
  NDK_BUILD="$(command -v ndk-build)"
fi

if [ -n "$NDK_BUILD" ]; then
  echo "Building hev-socks5-tunnel JNI via $NDK_BUILD"
  rm -rf /tmp/hev
  git clone --depth 1 --branch "$TUN2SOCKS_VER" --recursive https://github.com/heiher/hev-socks5-tunnel /tmp/hev 2>&1 | tail -3
  (cd /tmp/hev && "$NDK_BUILD" -j$(nproc) 2>&1 | tail -20)
  JNI_OK=false
  for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    src="/tmp/hev/libs/$abi/libhev-socks5-tunnel.so"
    if [ -f "$src" ]; then
      dir="app/src/main/jniLibs/$abi"
      mkdir -p "$dir"
      cp "$src" "$dir/libtun2socks.so"
      cp "$src" "$dir/libhev-socks5-tunnel.so"
      chmod 644 "$dir"/lib*.so
      echo "  $abi OK ($(stat -c%s "$src") bytes)"
      JNI_OK=true
    fi
  done
  if [ "$JNI_OK" = true ]; then
    echo "JNI build succeeded"
  else
    echo "JNI build failed — no .so produced"
    download_executables
  fi
else
  echo "NDK not found"
  download_executables
fi

echo "Done."
