#!/usr/bin/env bash
# CI only (release-e2e.yml, inside android-emulator-runner): run the UiAutomator journeys against the release APK.
#   mocked: start the host fixture, route the emulator's HTTPS to it (e2e_device_setup.sh), then run the journeys.
#   live:   ordinary emulator network and trust store; only the real sign-in page and browser hand-off are checked.
# The release APK is installed exactly as supplied and the installed bytes are compared with it. The whole run is
# recorded in bounded screenrecord segments; numbered step screenshots come from the driver.
set -uo pipefail
if [[ "${GITHUB_ACTIONS:-}" != "true" ]]; then
  echo 'This script runs only in GitHub Actions.' >&2
  exit 2
fi
mode="${E2E_MODE:-mocked}"
api="${E2E_API:-unknown}"
port="${FIXTURE_PORT:-8443}"
out="${E2E_OUT:-e2e-output/$mode-api$api}"
release_apk="${RELEASE_APK:?release APK under test}"
mkdir -p "$out/steps" "$out/video" "$out/logs"

adb wait-for-device
until [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == "1" ]]; do sleep 2; done
adb shell wm size 1080x2200
adb shell wm density 440
adb shell settings put system font_scale 1.0
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
[[ "${E2E_THEME:-light}" == "dark" ]] && adb shell cmd uimode night yes

fixture_pid=""
if [[ "$mode" == "mocked" ]]; then
  certs="$(mktemp -d)"
  bash scripts/e2e_certs.sh "$certs" > /dev/null
  pyodide_args=()
  [[ -n "${PYODIDE_DIR:-}" ]] && pyodide_args=(--pyodide-dir "$PYODIDE_DIR")
  python3 -I e2e/fixture/server.py --cert "$certs/leaf.pem" --key "$certs/leaf.key" --port "$port" \
    --log "$out/logs/requests.jsonl" "${pyodide_args[@]}" > "$out/logs/fixture.txt" 2>&1 &
  fixture_pid=$!
  if ! bash scripts/e2e_device_setup.sh "$certs" "$port" > "$out/logs/device-setup.txt" 2>&1; then
    echo 'Emulator routing or CA setup failed; see logs/device-setup.txt' >&2
    cat "$out/logs/device-setup.txt" >&2
    kill "$fixture_pid" 2>/dev/null
    exit 1
  fi
fi

# The release APK exactly as supplied; the installed base.apk must have the same bytes.
if ! adb install -r "$release_apk" > "$out/logs/install.txt" 2>&1; then
  echo 'The release APK did not install' >&2
  cat "$out/logs/install.txt" >&2
  exit 1
fi
installed="$(adb shell pm path app.dsandroid | head -1 | sed 's/^package://' | tr -d '\r')"
adb pull "$installed" "$out/installed.apk" > /dev/null
expected="$(sha256sum "$release_apk" | cut -d' ' -f1)"
actual="$(sha256sum "$out/installed.apk" | cut -d' ' -f1)"
rm -f "$out/installed.apk"
{
  echo "release_apk_sha256=$expected"
  echo "installed_sha256=$actual"
  echo "api=$api"
  echo "mode=$mode"
  adb shell dumpsys package app.dsandroid | grep -E 'versionCode=|versionName=|pkgFlags=' | sed 's/^ *//' | head -4
} > "$out/apk.txt"
if [[ "$expected" != "$actual" ]]; then
  echo 'The installed APK differs from the artifact under test' >&2
  exit 1
fi
adb install -r -t "${E2E_APK:?}" > /dev/null && adb install -r -t "${E2E_TEST_APK:?}" > /dev/null || { echo 'Driver install failed' >&2; exit 1; }

touch "$out/.recording"
(
  segment=0
  while [[ -f "$out/.recording" ]]; do
    segment=$((segment + 1))
    adb shell screenrecord --bit-rate 4000000 --time-limit 170 "/sdcard/e2e-$(printf %02d "$segment").mp4" > /dev/null 2>&1 || sleep 1
  done
) &
recorder=$!

args=(-e mode "$mode")
[[ -n "${E2E_CLASSES:-}" ]] && args+=(-e class "$E2E_CLASSES")
adb shell am instrument -w -r "${args[@]}" app.dsandroid.e2e.test/androidx.test.runner.AndroidJUnitRunner > "$out/instrumentation.txt" 2>&1

rm -f "$out/.recording"
adb shell pkill -INT screenrecord > /dev/null 2>&1 || true
wait "$recorder" 2>/dev/null
for file in $(adb shell 'ls /sdcard/e2e-*.mp4 2>/dev/null' | tr -d '\r'); do
  adb pull "$file" "$out/video/" > /dev/null && adb shell rm -f "$file"
done
adb pull /sdcard/Android/data/app.dsandroid.e2e/files/e2e-steps/. "$out/steps/" > /dev/null 2>&1 || true
adb logcat -d -b crash > "$out/logs/crash.txt" 2>&1 || true
adb logcat -d -t 3000 -b main -b system > "$out/logs/logcat-tail.txt" 2>&1 || true
adb shell dumpsys activity activities | grep -E 'ResumedActivity|mFocusedApp' > "$out/logs/activities.txt" 2>&1 || true
[[ -n "$fixture_pid" ]] && kill "$fixture_pid" 2>/dev/null

python3 -I scripts/instrumentation_to_junit.py "$out/instrumentation.txt" "$out/junit.xml"
