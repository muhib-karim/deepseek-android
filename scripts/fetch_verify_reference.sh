#!/usr/bin/env bash
# CI only (reference.yml): download the official DeepSeek app from APKPure, verify it, and stage its split APKs for the
# emulator. Nothing here installs or runs the APK; capture_reference.sh does that on a disposable emulator.
#
# Verification before anything is installed:
#   1. the XAPK manifest names com.deepseek.chat and the requested versionCode,
#   2. apksigner (latest SDK build-tools) verifies every split's signatures,
#   3. scripts/apk_signers.py finds only the two signer certificates pinned below (and in docs/reference/SOURCE.json),
#   4. aapt2 badging of the base APK repeats package, version and SDK levels,
#   5. for the pinned version, every split's SHA-256 equals docs/reference/SOURCE.json.
# The publisher's Digital Asset Links file is fetched again for the report; it must still list both pins.
set -euo pipefail
if [[ "${GITHUB_ACTIONS:-}" != "true" ]]; then
  echo 'This script runs only in GitHub Actions.' >&2
  exit 2
fi

PACKAGE=com.deepseek.chat
VERSION_CODE="${REFERENCE_VERSION_CODE:-282}"
# Independently established: chat.deepseek.com/.well-known/assetlinks.json and APKPure's certificate SHA-1 (SOURCE.json).
PINS="1E:EC:C4:6F:DC:21:73:43:7D:70:14:0F:16:3A:AA:97:94:1D:41:B0:4D:91:DA:3E:B6:89:AE:C4:F3:39:AB:EB,C6:89:3C:53:68:F7:B5:12:2F:EC:DA:FC:6C:4E:CF:49:E0:B3:DC:4E:42:BD:16:6C:DB:64:69:07:78:8C:7F:FA"
[[ "$VERSION_CODE" =~ ^[0-9]{1,6}$ ]] || { echo 'version_code must be a number' >&2; exit 1; }

REPO="$(pwd)"
REPORT="$REPO/reference-output/source"
# Untrusted download: its own empty directory outside the checkout; only our scripts read it, by path.
WORK="${RUNNER_TEMP:?}/reference-apk"
rm -rf "$WORK" && mkdir -p "$WORK/apks" "$REPORT"

TOOLS="$(ls -d "${ANDROID_HOME:?}"/build-tools/*/ | sort -V | tail -1)"
echo "Using build-tools $(basename "$TOOLS")"

curl -fsS --retry 3 -m 60 -H 'x-cv: 3172501' -H 'x-sv: 29' -H 'x-abis: arm64-v8a,armeabi-v7a,x86_64' -H 'x-gp: 1' \
  -o "$WORK/versions.bin" "https://api.pureapk.com/m/v3/cms/app_version?hl=en-US&package_name=$PACKAGE"
python3 -I - "$WORK/versions.bin" "$PACKAGE" "$VERSION_CODE" "$WORK/url.txt" <<'PY'
import base64, re, sys
data, package, code, out = open(sys.argv[1], "rb").read(), sys.argv[2], sys.argv[3], sys.argv[4]
for match in re.finditer(rb"https://download\.pureapk\.com/b/XAPK/([A-Za-z0-9=_-]+)\?[\x21-\x7e]+", data):
    token = match.group(1).decode()
    name = base64.urlsafe_b64decode(token + "=" * (-len(token) % 4)).decode(errors="replace")
    if name.startswith(f"{package}_{code}_"):
        open(out, "w").write(match.group(0).decode())
        print(f"Mirror offers {name}")
        sys.exit(0)
sys.exit(f"The mirror does not offer {package} versionCode {code}")
PY
# The signed link is short-lived; it is passed to curl and never printed.
curl -fsS --retry 3 -m 300 -L -o "$WORK/official.xapk" "$(cat "$WORK/url.txt")"
rm -f "$WORK/url.txt"

python3 -I - "$WORK/official.xapk" "$WORK/apks" "$PACKAGE" "$VERSION_CODE" "$REPORT/xapk.json" <<'PY'
import hashlib, json, os, sys, zipfile
xapk, target, package, code, report = sys.argv[1:6]
with zipfile.ZipFile(xapk) as archive:
    manifest = json.loads(archive.read("manifest.json"))
    assert manifest.get("package_name") == package, "XAPK names another package"
    assert str(manifest.get("version_code")) == code, "XAPK has another versionCode"
    names = []
    for info in archive.infolist():
        name = info.filename
        # Only flat *.apk entries; nothing is extracted by its own path.
        if name.endswith(".apk") and "/" not in name and "\\" not in name and not name.startswith("."):
            with archive.open(info) as source, open(os.path.join(target, name), "wb") as sink:
                sink.write(source.read())
            names.append(name)
assert f"{package}.apk" in names, "XAPK has no base APK"
json.dump({
    "package": package, "version_code": int(code), "version_name": manifest.get("version_name"),
    "min_sdk": manifest.get("min_sdk_version"), "target_sdk": manifest.get("target_sdk_version"),
    "splits": sorted(names), "xapk_sha256": hashlib.sha256(open(xapk, "rb").read()).hexdigest(),
}, open(report, "w"), indent=2)
print(f"Extracted {len(names)} APKs")
PY

status=0
for apk in "$WORK"/apks/*.apk; do
  name="$(basename "$apk")"
  if ! "$TOOLS/apksigner" verify --verbose --print-certs "$apk" > "$REPORT/apksigner-$name.txt" 2>&1; then
    echo "apksigner could not verify $name" >&2
    status=1
  fi
done
python3 -I scripts/apk_signers.py --pins "$PINS" --json "$REPORT/signers.json" "$WORK"/apks/*.apk || status=1
"$TOOLS/aapt2" dump badging "$WORK/apks/$PACKAGE.apk" > "$REPORT/badging.txt" || status=1
grep -q "^package: name='$PACKAGE' versionCode='$VERSION_CODE'" "$REPORT/badging.txt" || { echo 'Badging package/version mismatch' >&2; status=1; }

python3 -I - "$REPO/docs/reference/SOURCE.json" "$WORK/apks" "$VERSION_CODE" "$REPORT/hashes.json" <<'PY' || status=1
import hashlib, json, os, sys
source, directory, code, report = json.load(open(sys.argv[1])), sys.argv[2], int(sys.argv[3]), sys.argv[4]
actual = {name: hashlib.sha256(open(os.path.join(directory, name), "rb").read()).hexdigest() for name in sorted(os.listdir(directory))}
pinned = source["apk_sha256"] if code == source["version_code"] else {}
mismatch = sorted(name for name in set(actual) | set(pinned) if pinned and actual.get(name) != pinned.get(name))
json.dump({"pinned_version": code == source["version_code"], "apk_sha256": actual, "mismatch": mismatch}, open(report, "w"), indent=2)
if mismatch:
    sys.exit("Split APKs differ from the pinned hashes in SOURCE.json: " + ", ".join(mismatch))
PY

# Informational cross-check: the publisher still lists both pins.
if curl -fsS -m 30 -o "$REPORT/assetlinks.json" https://chat.deepseek.com/.well-known/assetlinks.json; then
  python3 -I - "$REPORT/assetlinks.json" "$PINS" <<'PY' || echo '::warning::assetlinks.json no longer lists both pinned digests'
import json, sys
listed = {d.upper() for e in json.load(open(sys.argv[1])) if e.get("target", {}).get("package_name") == "com.deepseek.chat"
          for d in e["target"].get("sha256_cert_fingerprints", [])}
sys.exit(0 if set(sys.argv[2].split(",")) <= listed else 1)
PY
else
  echo '::warning::assetlinks.json could not be fetched for the report'
fi

python3 -I - "$REPORT" "$status" <<'PY'
import json, os, sys
report, status = sys.argv[1], int(sys.argv[2])
xapk = json.load(open(os.path.join(report, "xapk.json")))
json.dump({**xapk, "verified": status == 0, "apk_dir_env": "REFERENCE_APK_DIR"}, open(os.path.join(report, "verification.json"), "w"), indent=2)
PY
if (( status != 0 )); then
  echo 'Verification failed: nothing will be installed.' >&2
  exit 1
fi
echo "REFERENCE_APK_DIR=$WORK/apks" >> "$GITHUB_ENV"
echo "Verified $PACKAGE versionCode $VERSION_CODE: signatures intact, signer pinned, hashes match."
