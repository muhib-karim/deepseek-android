#!/usr/bin/env python3
"""List the signer certificates of APKs from their APK Signing Block and check them against pinned digests.

apksigner proves that an APK's signatures are intact; it does not say who signed it. This reads the certificate of
every APK Signature Scheme v2, v3 and v3.1 signer, prints its SHA-256 (and SHA-1) digest and, with --pins, fails when
any APK carries a signer outside the pinned set or has no v2+ signature at all. Standard library only; it never runs
or extracts code from the APK.

Usage: apk_signers.py [--pins AA:BB:...,CC:DD:...] [--json report.json] FILE.apk...
"""
import argparse
import hashlib
import json
import struct
import sys

SCHEMES = {0x7109871A: "v2", 0xF05368C0: "v3", 0x1B93AD61: "v3.1"}
MAGIC = b"APK Sig Block 42"


def _prefixed(buffer, offset):
    length = struct.unpack_from("<I", buffer, offset)[0]
    start = offset + 4
    if start + length > len(buffer):
        raise ValueError("truncated length-prefixed field")
    return buffer[start:start + length], start + length


def _colon(hex_digest):
    return ":".join(hex_digest[i:i + 2] for i in range(0, len(hex_digest), 2)).upper()


def signing_block(data):
    eocd = data.rfind(b"PK\x05\x06")
    if eocd < 0:
        raise ValueError("not a ZIP archive")
    central_directory = struct.unpack_from("<I", data, eocd + 16)[0]
    if data[central_directory - 16:central_directory] != MAGIC:
        raise ValueError("no APK Signing Block (v1-only or unsigned APK)")
    size = struct.unpack_from("<Q", data, central_directory - 24)[0]
    start = central_directory - size - 8
    if start < 0 or struct.unpack_from("<Q", data, start)[0] != size:
        raise ValueError("inconsistent APK Signing Block size")
    return data[start + 8:central_directory - 24]


def signers(path):
    """Returns [{scheme, sha256, sha1, min_sdk, max_sdk}] for every signer certificate (first certificate of each signer)."""
    with open(path, "rb") as handle:
        data = handle.read()
    pairs = signing_block(data)
    found = []
    offset = 0
    while offset < len(pairs):
        length, pair_id = struct.unpack_from("<QI", pairs, offset)
        value = pairs[offset + 12:offset + 8 + length]
        offset += 8 + length
        scheme = SCHEMES.get(pair_id)
        if scheme is None:
            continue
        sequence, _ = _prefixed(value, 0)
        position = 0
        while position < len(sequence):
            signer, position = _prefixed(sequence, position)
            signed_data, _ = _prefixed(signer, 0)
            _digests, cursor = _prefixed(signed_data, 0)
            certificates, cursor = _prefixed(signed_data, cursor)
            certificate, _ = _prefixed(certificates, 0)
            entry = {
                "scheme": scheme,
                "sha256": _colon(hashlib.sha256(certificate).hexdigest()),
                "sha1": hashlib.sha1(certificate).hexdigest(),
            }
            if scheme != "v2":
                entry["min_sdk"], entry["max_sdk"] = struct.unpack_from("<II", signed_data, cursor)
            found.append(entry)
    if not found:
        raise ValueError("no APK Signature Scheme v2/v3 signer")
    return found


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--pins", default="", help="comma-separated SHA-256 certificate digests (colon hex)")
    parser.add_argument("--json", help="write the report here")
    parser.add_argument("apks", nargs="+")
    args = parser.parse_args(argv)
    pins = {pin.strip().upper() for pin in args.pins.split(",") if pin.strip()}
    report, failed = {}, False
    for path in args.apks:
        name = path.rsplit("/", 1)[-1]
        try:
            entries = signers(path)
        except (OSError, ValueError, struct.error) as error:
            report[name] = {"error": str(error)}
            failed = True
            continue
        unknown = sorted({e["sha256"] for e in entries} - pins) if pins else []
        report[name] = {"signers": entries, "unpinned": unknown}
        failed = failed or bool(unknown)
        for entry in entries:
            sdk = f" (API {entry['min_sdk']}-{entry['max_sdk']})" if "min_sdk" in entry else ""
            print(f"{name}: {entry['scheme']} {entry['sha256']}{sdk}")
    if args.json:
        with open(args.json, "w") as handle:
            json.dump({"pins": sorted(pins), "apks": report, "ok": not failed}, handle, indent=2)
    if failed:
        print("Signer check failed: an APK is unsigned or carries a certificate outside the pinned set.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
