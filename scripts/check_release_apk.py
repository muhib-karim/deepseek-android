#!/usr/bin/env python3
"""Check a built release APK before it is tested or published.

Reads `aapt2 dump badging` and `apksigner verify --print-certs` from the newest SDK build-tools and requires:
package app.dsandroid, the expected versionName/versionCode (when given), minSdk 26 and targetSdk 35, exactly the
INTERNET and RECORD_AUDIO permissions, arm64-v8a and x86_64 native code, no debuggable flag, an intact v2+ signature
and, when --cert is given, that signer certificate SHA-256. Writes a JSON report; exits 1 on any mismatch.

Usage: check_release_apk.py APK [--version-name 1.0.0 --version-code 10] [--cert HEX] [--json report.json]
"""
import argparse
import glob
import json
import os
import re
import subprocess
import sys

ALLOWED_PERMISSIONS = {"android.permission.INTERNET", "android.permission.RECORD_AUDIO"}
REQUIRED_ABIS = {"arm64-v8a", "x86_64"}


def build_tool(name):
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
    candidates = sorted(glob.glob(os.path.join(home, "build-tools", "*", name)),
                        key=lambda p: [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", p.split(os.sep)[-2])])
    if not candidates:
        sys.exit(f"{name} not found under ANDROID_HOME/build-tools")
    return candidates[-1]


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("apk")
    parser.add_argument("--version-name")
    parser.add_argument("--version-code")
    parser.add_argument("--cert", help="expected signer certificate SHA-256 (hex, colons optional)")
    parser.add_argument("--json")
    args = parser.parse_args(argv)
    badging = subprocess.run([build_tool("aapt2"), "dump", "badging", args.apk], capture_output=True, text=True)
    signing = subprocess.run([build_tool("apksigner"), "verify", "--verbose", "--print-certs", args.apk], capture_output=True, text=True)
    text, problems = badging.stdout, []
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'", text, re.M)
    min_sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", text, re.M)
    target_sdk = re.search(r"^targetSdkVersion:'(\d+)'", text, re.M)
    permissions = set(re.findall(r"^uses-permission: name='([^']+)'", text, re.M))
    native = set(re.findall(r"'([^']+)'", (re.search(r"^native-code: (.*)$", text, re.M) or [None, ""])[1]))
    debuggable = bool(re.search(r"^application-debuggable", text, re.M))
    certs = sorted(set(m.lower() for m in re.findall(r"certificate SHA-256 digest: ([0-9a-fA-F]{64})", signing.stdout)))
    schemes = sorted(set(re.findall(r"Verified using (v[0-9.]+) scheme \(APK Signature Scheme [^)]*\): true", signing.stdout)))

    if badging.returncode != 0 or not package:
        problems.append("aapt2 could not read the APK")
    else:
        name, code, version = package.groups()
        if name != "app.dsandroid":
            problems.append(f"package is {name}")
        if args.version_code and code != args.version_code:
            problems.append(f"versionCode is {code}, expected {args.version_code}")
        if args.version_name and version != args.version_name:
            problems.append(f"versionName is {version}, expected {args.version_name}")
    if not min_sdk or min_sdk.group(1) != "26":
        problems.append("minSdk is not 26")
    if not target_sdk or target_sdk.group(1) != "35":
        problems.append("targetSdk is not 35")
    if permissions != ALLOWED_PERMISSIONS:
        problems.append(f"permissions differ from INTERNET + RECORD_AUDIO: {sorted(permissions ^ ALLOWED_PERMISSIONS)}")
    if not REQUIRED_ABIS <= native:
        problems.append(f"native code lacks {sorted(REQUIRED_ABIS - native)}")
    if debuggable:
        problems.append("the APK is debuggable")
    if signing.returncode != 0 or "Verifies" not in signing.stdout:
        problems.append("apksigner did not verify the signature")
    if not any(s in ("v2", "v3", "v3.1") for s in schemes):
        problems.append("no APK Signature Scheme v2 or v3 signature")
    expected = (args.cert or "").replace(":", "").lower()
    if expected and expected not in certs:
        problems.append("the signer certificate is not the expected release certificate")

    report = {"apk": os.path.basename(args.apk), "package": package.groups() if package else None,
              "min_sdk": min_sdk.group(1) if min_sdk else None, "target_sdk": target_sdk.group(1) if target_sdk else None,
              "permissions": sorted(permissions), "native_code": sorted(native), "debuggable": debuggable,
              "signature_schemes": schemes, "signer_sha256": certs, "expected_signer_checked": bool(expected),
              "problems": problems}
    if args.json:
        with open(args.json, "w") as handle:
            json.dump(report, handle, indent=2)
    for problem in problems:
        print(f"Release APK check: {problem}", file=sys.stderr)
    print(f"Release APK check: {'passed' if not problems else 'FAILED'} ({report['apk']})")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
