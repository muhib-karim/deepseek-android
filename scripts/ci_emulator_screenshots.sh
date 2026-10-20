#!/usr/bin/env bash
# CI only: never run Gradle or capture emulator screenshots on a developer machine.
set -uo pipefail
if [[ "${GITHUB_ACTIONS:-}" != "true" ]]; then
  echo 'This script runs only in GitHub Actions.' >&2
  exit 2
fi
adb shell wm size 1080x2200
adb shell wm density 440 # 392.7dp, the 393dp reference viewport
adb shell settings put system font_scale 1.0
adb logcat -c
if [[ "${LIVE_LOGIN:-false}" == "true" ]]; then
  # Opt-in diagnostic only: the real sign-in page, nothing else. Never part of pull request checks.
  gradle --no-daemon :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=app.dsandroid.deepseek.LiveLoginDiagnosticTest \
    -Pandroid.testInstrumentationRunnerArguments.liveLogin=true
  live_status=$?
  mkdir -p live-login
  adb pull /sdcard/Android/data/app.dsandroid/files/live-login/. live-login/ || true
  adb logcat -d -s DeepSeekLogin:D chromium:I > live-login/logcat.txt || true
  exit $live_status
fi
# Collect completed captures even if another instrumentation test fails.
gradle --no-daemon :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
test_status=$?
adb pull /sdcard/Android/data/app.dsandroid/files/official-ui-screenshots official-ui-screenshots
pull_status=$?
adb pull /sdcard/Android/data/app.dsandroid/files/smoke-screenshots smoke-screenshots || true
# The workflow validates captures in an always-run step and gates upload on its success.
if (( test_status != 0 || pull_status != 0 )); then exit 1; fi
