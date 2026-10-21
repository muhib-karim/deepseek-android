"""CI gate for the full (official engine) APKs: one per ABI, each with exactly its own engine payload, stored.

usage: python3 -I scripts/check_full_apk.py <dir with app-full-<abi>-<type>.apk files>
"""
import pathlib
import sys
import zipfile

ABIS = ("arm64-v8a", "x86_64")
MIN_PAYLOAD = 20 * 1024 * 1024
apks = sorted(pathlib.Path(sys.argv[1]).glob("app-full-*.apk"))
problems = []
if {abi for abi in ABIS for apk in apks if f"-{abi}-" in apk.name} != set(ABIS):
    problems.append(f"expected one full APK per ABI {ABIS}, found {[a.name for a in apks]}")
for apk in apks:
    abi = next((a for a in ABIS if f"-{a}-" in apk.name), None)
    with zipfile.ZipFile(apk) as z:
        libs = {name.split("/")[1] for name in z.namelist() if name.startswith("lib/") and name.count("/") >= 2}
        if libs != {abi}:
            problems.append(f"{apk.name}: native code for {sorted(libs)}, expected only {abi}")
        try:
            entry = z.getinfo(f"lib/{abi}/libdshpayload.so")
        except KeyError:
            problems.append(f"{apk.name}: no engine payload lib/{abi}/libdshpayload.so")
            continue
        if entry.compress_type != zipfile.ZIP_STORED:
            problems.append(f"{apk.name}: payload is compressed in the APK (must be stored)")
        if entry.file_size < MIN_PAYLOAD:
            problems.append(f"{apk.name}: payload is only {entry.file_size} bytes")
        with z.open(entry) as fh:
            if fh.read(2) != b"\x1f\x8b":
                problems.append(f"{apk.name}: payload is not gzip")
        print(f"{apk.name}: {abi} payload {entry.file_size / 1e6:.1f} MB stored; APK {apk.stat().st_size / 1e6:.1f} MB")
if problems:
    print("\n".join(problems), file=sys.stderr)
    sys.exit(1)
