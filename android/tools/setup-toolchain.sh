#!/bin/bash
# Isolated, reproducible macOS arm64 tools. Does not change system Java or shell files.
set -euo pipefail

TOOLCHAIN_ROOT="${CLEARLINE_TOOLCHAIN_ROOT:-/private/tmp/clearline-android-toolchain}"
MODE="${1:---bootstrap}"
if [[ "$MODE" != "--bootstrap" && "$MODE" != "--install-sdk" && "$MODE" != "--env" ]]; then
  echo "Usage: $0 [--bootstrap|--install-sdk|--env]" >&2
  exit 2
fi
if [[ "$(uname -s)" != Darwin || "$(uname -m)" != arm64 ]]; then
  echo "This helper pins macOS arm64 archives; install the documented versions manually on other hosts." >&2
  exit 2
fi

JDK_DIR="$TOOLCHAIN_ROOT/jdk-17.0.20.1+1/Contents/Home"
SDK_DIR="$TOOLCHAIN_ROOT/sdk"
GRADLE_DIR="$TOOLCHAIN_ROOT/gradle-8.11.1"

print_env() {
  printf 'export JAVA_HOME=%q\n' "$JDK_DIR"
  printf 'export ANDROID_HOME=%q\n' "$SDK_DIR"
  printf 'export GRADLE_USER_HOME=%q\n' "$TOOLCHAIN_ROOT/gradle-home"
  printf 'export PATH=%q:"$PATH"\n' "$JDK_DIR/bin:$SDK_DIR/platform-tools:$SDK_DIR/cmdline-tools/latest/bin:$GRADLE_DIR/bin"
}
if [[ "$MODE" == "--env" ]]; then
  print_env
  exit 0
fi

mkdir -p "$TOOLCHAIN_ROOT/downloads" "$TOOLCHAIN_ROOT/gradle-home"
download_verified() {
  local url="$1" archive="$2" expected="$3" actual
  if [[ ! -f "$archive" ]]; then
    curl --fail --location --retry 2 --output "$archive.part" "$url"
    mv "$archive.part" "$archive"
  fi
  actual="$(shasum -a 256 "$archive" | awk '{print $1}')"
  if [[ "$actual" != "$expected" ]]; then
    echo "Checksum mismatch: $archive (remove this archive and rerun)." >&2
    exit 1
  fi
}

if [[ ! -x "$JDK_DIR/bin/java" ]]; then
  download_verified \
    'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_aarch64_mac_hotspot_17.0.20.1_1.tar.gz' \
    "$TOOLCHAIN_ROOT/downloads/temurin17.tar.gz" \
    '196d13ba5f10414bef7f6a05a9b3f00edacb18ebacef2b99485db9e2ee18f0e8'
  tar -xzf "$TOOLCHAIN_ROOT/downloads/temurin17.tar.gz" -C "$TOOLCHAIN_ROOT"
fi
if [[ ! -x "$GRADLE_DIR/bin/gradle" ]]; then
  download_verified \
    'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip' \
    "$TOOLCHAIN_ROOT/downloads/gradle-8.11.1-bin.zip" \
    'f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6'
  unzip -q "$TOOLCHAIN_ROOT/downloads/gradle-8.11.1-bin.zip" -d "$TOOLCHAIN_ROOT"
fi

if [[ "$MODE" == "--install-sdk" ]]; then
  echo 'Android SDK terms apply: https://developer.android.com/studio/terms'
  echo 'Proceed only after reviewing and agreeing to those terms; licenses are prompted interactively.'
  if [[ ! -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]]; then
    download_verified \
      'https://dl.google.com/android/repository/commandlinetools-mac_arm64-15859902_latest.zip' \
      "$TOOLCHAIN_ROOT/downloads/commandlinetools.zip" \
      '835b62a26162b229b441d1f6d4680383815a270809eb33522c0d480fa5002c4e'
    unpack_dir="$(mktemp -d "$TOOLCHAIN_ROOT/unpack.XXXXXX")"
    unzip -q "$TOOLCHAIN_ROOT/downloads/commandlinetools.zip" -d "$unpack_dir"
    mkdir -p "$SDK_DIR/cmdline-tools"
    mv "$unpack_dir/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
    rmdir "$unpack_dir"
  fi
  JAVA_HOME="$JDK_DIR" "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK_DIR" --licenses
  JAVA_HOME="$JDK_DIR" "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK_DIR" \
    'platform-tools' 'platforms;android-35' 'build-tools;35.0.0' \
    'ndk;27.2.12479018' 'cmake;3.22.1'
fi

print_env > "$TOOLCHAIN_ROOT/env.sh"
"$JDK_DIR/bin/java" -version
echo "Toolchain environment: source $TOOLCHAIN_ROOT/env.sh"
