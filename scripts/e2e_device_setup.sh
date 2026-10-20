#!/usr/bin/env bash
# CI only, disposable emulator only: route the emulator's HTTPS traffic to the host fixture and trust its throwaway CA.
# The release APK stays unmodified: it keeps its normal https:// addresses, public DNS answers and certificate checks;
# only this emulator's system trust store and packet routing change, and both vanish with the emulator.
#   1. adb root (Google APIs images are userdebug),
#   2. the throwaway CA joins a tmpfs copy of the system CA store (also bind-mounted over the Conscrypt APEX store on
#      Android 14+, inside the zygotes' mount namespaces, so apps started afterwards see it),
#   3. iptables sends TCP 443 to 10.0.2.2:8443 (the runner's loopback), blocks QUIC and IPv6 HTTPS,
#   4. Chrome skips its first-run screens.
# Usage: e2e_device_setup.sh CERT_DIR FIXTURE_PORT
set -euo pipefail
if [[ "${GITHUB_ACTIONS:-}" != "true" ]]; then
  echo 'This script runs only in GitHub Actions.' >&2
  exit 2
fi
certs="${1:?cert directory}"
port="${2:-8443}"
ca="$(ls "$certs"/*.0 | head -1)"
name="$(basename "$ca")"

adb root > /dev/null
sleep 3
adb wait-for-device
until [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == "1" ]]; do sleep 2; done
[[ "$(adb shell id -u | tr -d '\r')" == "0" ]] || { echo 'adb root is unavailable on this image' >&2; exit 1; }

adb push "$ca" "/data/local/tmp/$name" > /dev/null
adb shell "set -e
rm -rf /data/local/tmp/e2e-cacerts && mkdir -p -m 700 /data/local/tmp/e2e-cacerts
cp /system/etc/security/cacerts/* /data/local/tmp/e2e-cacerts/
if [ -d /apex/com.android.conscrypt/cacerts ]; then cp /apex/com.android.conscrypt/cacerts/* /data/local/tmp/e2e-cacerts/; fi
cp /data/local/tmp/$name /data/local/tmp/e2e-cacerts/
mount -t tmpfs tmpfs /system/etc/security/cacerts
cp /data/local/tmp/e2e-cacerts/* /system/etc/security/cacerts/
chown root:root /system/etc/security/cacerts /system/etc/security/cacerts/*
chmod 755 /system/etc/security/cacerts
chmod 644 /system/etc/security/cacerts/*
chcon u:object_r:system_file:s0 /system/etc/security/cacerts /system/etc/security/cacerts/*
"
# Android 14+ reads CAs from the Conscrypt APEX; zygote-forked apps inherit the zygotes' mount namespace.
adb shell 'if [ -d /apex/com.android.conscrypt/cacerts ] && command -v nsenter >/dev/null; then
  mount --bind /system/etc/security/cacerts /apex/com.android.conscrypt/cacerts || true
  for z in $(pidof zygote) $(pidof zygote64); do
    nsenter --mount=/proc/$z/ns/mnt -- /system/bin/mount --bind /system/etc/security/cacerts /apex/com.android.conscrypt/cacerts
  done
fi'
adb shell "ls /system/etc/security/cacerts/$name" > /dev/null || { echo 'The throwaway CA is not in the system store' >&2; exit 1; }

adb shell "iptables -t nat -A OUTPUT -p tcp --dport 443 -j DNAT --to-destination 10.0.2.2:$port"
adb shell "iptables -A OUTPUT -p udp --dport 443 -j REJECT" || true
adb shell "ip6tables -A OUTPUT -p tcp --dport 443 -j REJECT" || true
adb shell "iptables -t nat -S OUTPUT" | grep -q "10.0.2.2:$port" || { echo 'HTTPS routing to the fixture is not active' >&2; exit 1; }

# Chrome reads this file on userdebug images; it opens pages without the welcome, sign-in and default-browser screens.
adb shell "echo 'chrome --disable-fre --no-default-browser-check --no-first-run --disable-features=SigninPromo' > /data/local/tmp/chrome-command-line"
adb shell "chmod 644 /data/local/tmp/chrome-command-line"
echo "Emulator routes HTTPS to the fixture on port $port and trusts $name."
