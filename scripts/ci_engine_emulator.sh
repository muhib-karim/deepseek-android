#!/usr/bin/env bash
# CI only: the official engine (full flavor, x86_64 payload) on the emulator: payload node -e, harness full UI with a
# fake key, first screen, Google route hand-off, chat, overflow menu, keyboard and palette screenshots.
set -uo pipefail
if [[ "${GITHUB_ACTIONS:-}" != "true" ]]; then
  echo 'This script runs only in GitHub Actions.' >&2
  exit 2
fi
adb shell wm size 1080x2200
adb shell wm density 440
adb shell settings put system font_scale 1.0
adb logcat -c
# Notifications for the engine's foreground service (targetSdk 28 apps get the prompt on first channel creation).
adb shell pm grant app.dsandroid.full android.permission.POST_NOTIFICATIONS 2>/dev/null || true
gradle --no-daemon :app:connectedFullDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
test_status=$?
mkdir -p v130-screenshots engine-diagnostics
adb pull /sdcard/Android/data/app.dsandroid.full/files/v130-screenshots/. v130-screenshots/ || true
adb shell run-as app.dsandroid.full cat files/engine.log > engine-diagnostics/engine.log 2>/dev/null || true
adb logcat -d -s DshEngine:V chromium:I AndroidRuntime:E > engine-diagnostics/logcat.txt || true
ls -la v130-screenshots
exit $test_status
