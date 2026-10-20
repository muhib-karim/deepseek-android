#!/usr/bin/env bash
# CI only (reference.yml, inside android-emulator-runner): install the verified official split APKs unchanged on the
# disposable emulator and run the reference driver. The viewport matches our own capture tests (1080x2200, 440 dpi,
# font scale 1.0) so the official screens and ours can be compared at identical settings.
set -uo pipefail
if [[ "${GITHUB_ACTIONS:-}" != "true" ]]; then
  echo 'This script runs only in GitHub Actions.' >&2
  exit 2
fi
APKS="${REFERENCE_APK_DIR:?verified APKs are staged by fetch_verify_reference.sh}"
OUT=reference-output
mkdir -p "$OUT/raw" "$OUT/device/diagnostics"

adb wait-for-device
adb shell getprop ro.build.version.sdk > "$OUT/device/sdk.txt"
adb shell getprop ro.product.cpu.abilist > "$OUT/device/abilist.txt"
# ARM code runs through the image's native bridge (binary translation); record which one.
{
  echo "native_bridge=$(adb shell getprop ro.dalvik.vm.native.bridge | tr -d '\r')"
  echo "native_bridge_exec=$(adb shell getprop ro.enable.native.bridge.exec | tr -d '\r')"
} > "$OUT/device/native-bridge.txt"
adb shell wm size 1080x2200
adb shell wm density 440
adb shell settings put system font_scale 1.0
adb shell settings put secure show_ime_with_hard_keyboard 1
{
  echo "sdk=$(cat "$OUT/device/sdk.txt")"
  echo "abilist=$(cat "$OUT/device/abilist.txt")"
  echo "reference_abi=${REFERENCE_ABI:-arm64-v8a}"
  echo "reference_build=${REFERENCE_BUILD:-current}"
  echo "play_store=$(adb shell pm list packages com.android.vending | grep -c vending)"
  echo "viewport=1080x2200@440dpi font_scale=1.0"
} > "$OUT/device/settings.txt"

# Every split, unchanged and together (the set was verified before this step). On a Play image the installer is recorded as
# Play, which builds wrapped in Google's PairIP license check look for; nothing in the APK is changed.
installer=()
if adb shell pm list packages com.android.vending | grep -q 'package:com.android.vending'; then installer=(-i com.android.vending); fi
if ! adb install-multiple -r -g "${installer[@]}" "$APKS"/*.apk > "$OUT/device/install.txt" 2>&1; then
  echo 'The official APK could not be installed on this emulator (see device/install.txt and device/abilist.txt).' >&2
  exit 1
fi
adb shell dumpsys package com.deepseek.chat | grep -E 'versionCode|versionName|primaryCpuAbi|secondaryCpuAbi' > "$OUT/device/package.txt" || true
adb shell pm list packages -i com.deepseek.chat > "$OUT/device/installer.txt" 2>&1 || true
# The 1.4.1 build asks a DeepSeek server for its minimum version on start and then covers every screen with an
# "Update Required" modal. Offline (the default), that check cannot run, so the screens underneath can be captured.
# The modal is never dismissed with "Update now", and the APK is never modified.
NETWORK="${REFERENCE_NETWORK:-offline}"
if [[ "$NETWORK" == "offline" ]]; then
  adb shell cmd connectivity airplane-mode enable || adb shell settings put global airplane_mode_on 1 || true
  adb shell svc wifi disable || true
  adb shell svc data disable || true
  sleep 3
fi
{
  echo "network=$NETWORK"
  echo "airplane_mode_on=$(adb shell settings get global airplane_mode_on | tr -d '\r')"
  if adb shell ping -c 1 -W 3 8.8.8.8 > /dev/null 2>&1; then echo "internet_reachable=yes"; else echo "internet_reachable=no"; fi
} > "$OUT/device/network.txt" 2>&1
cat "$OUT/device/network.txt"
if [[ "$NETWORK" == "offline" ]] && grep -q 'internet_reachable=yes' "$OUT/device/network.txt"; then
  echo 'Offline capture requested, but the emulator still reaches the internet (see device/network.txt).' >&2
  exit 1
fi
adb logcat -c || true

version="$(python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1])); print("%s (%s)" % (d["version_name"], d["version_code"]))' "$OUT/source/verification.json")"
python3 -I e2e/reference/driver.py --out "$OUT/raw" --version "$version" --diagnostics "$OUT/device/diagnostics" --network "$NETWORK"
status=$?
# Text only, for the verification artifact: what the app logged during the run (a fresh emulator with no account).
adb logcat -d -t 2000 -b main -b system -b crash > "$OUT/device/diagnostics/logcat-run.txt" 2>&1 || true
adb shell cmd uimode night no || true
exit $status
