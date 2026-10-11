#!/usr/bin/env bash
# Installs (or updates) RELAY on every Android phone connected over USB.
# Usage (from the relay folder):  ./scripts/install-all.sh [path/to/app-debug.apk]
set -euo pipefail
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"

ADB="$(command -v adb || true)"
if [ -z "$ADB" ]; then
  for c in "$HOME/Library/Android/sdk/platform-tools/adb" "$HOME/Android/Sdk/platform-tools/adb"; do
    [ -x "$c" ] && ADB="$c" && break
  done
fi
[ -z "$ADB" ] && { echo "adb not found. Install Android Platform-Tools or Android Studio."; exit 1; }
[ -f "$APK" ] || { echo "APK not found at $APK. Build it first: ./gradlew assembleDebug"; exit 1; }

DEVICES=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
if [ -z "$DEVICES" ]; then
  echo "No phone found. Connect a phone with USB, enable USB debugging, and accept the prompt on the phone."
  "$ADB" devices -l
  exit 1
fi

for SERIAL in $DEVICES; do
  MODEL=$("$ADB" -s "$SERIAL" shell getprop ro.product.model | tr -d '\r')
  echo "Installing on $MODEL ($SERIAL)..."
  if "$ADB" -s "$SERIAL" install -r "$APK"; then
    echo "  OK"
  else
    echo "  FAILED. If it says INSTALL_FAILED_UPDATE_INCOMPATIBLE, uninstall RELAY from that phone first."
  fi
done
