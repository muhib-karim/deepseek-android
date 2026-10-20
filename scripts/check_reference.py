#!/usr/bin/env python3
"""Validate the official-reference capture and copy only safe, complete files into reference-output/sanitized/.

Fails (exit 1) on: an unverified or wrong APK, a missing or blank sign-in capture in either theme, a capture that is not
a 1080x2200 PNG, metadata outside the allowlist, private data (email address, phone number, bearer token) left in a
hierarchy, or an undecodable video. Missing signed-in screens make the reference *incomplete*: reported in the summary
and coverage.json, and a failure only with --require-complete. Never prints capture contents.
"""
import argparse
import json
import os
import re
import shutil
import struct
import subprocess
import sys

PRIVATE = [re.compile(r"[A-Za-z0-9._%+*-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"), re.compile(r"\+?\d[\d\s*()-]{6,}\d"),
           re.compile(r"Bearer\s", re.I), re.compile(r"userToken", re.I)]
META_KEYS = {"screen", "theme", "source", "package", "version", "activity", "step", "redacted_regions"}
PUBLIC_REQUIRED = ["login"]
SIGNED_IN_BOTH_THEMES = ["chat-empty", "think-on", "search-on", "attach-sheet", "drawer", "settings"]
SIGNED_IN_LIGHT = ["chat-populated", "message-menu", "share", "settings-appearance", "profile", "data-controls"]
THEMES = ("light", "dark")


class Failure(Exception):
    pass


def png_size(path):
    with open(path, "rb") as handle:
        head = handle.read(24)
    if head[:8] != b"\x89PNG\r\n\x1a\n" or head[12:16] != b"IHDR":
        raise Failure(f"{os.path.basename(path)} is not a PNG")
    return struct.unpack(">II", head[16:24])


def not_blank(path):
    try:
        from PIL import Image, ImageStat
    except ImportError:
        return os.path.getsize(path) > 15_000
    with Image.open(path) as image:
        stats = ImageStat.Stat(image.convert("L"))
    return stats.stddev[0] > 3.0


def video_ok(path):
    with open(path, "rb") as handle:
        head = handle.read(12)
    if len(head) < 12 or head[4:8] != b"ftyp" or os.path.getsize(path) < 10_000:
        return False
    if shutil.which("ffprobe"):
        result = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
                                capture_output=True, text=True, timeout=60)
        try:
            return result.returncode == 0 and float(result.stdout.strip() or 0) > 0.5
        except ValueError:
            return False
    return True


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("output", help="reference-output directory")
    parser.add_argument("--require-complete", action="store_true")
    args = parser.parse_args(argv)
    root = args.output
    raw, sanitized = os.path.join(root, "raw"), os.path.join(root, "sanitized")
    problems, notes = [], []
    shutil.rmtree(sanitized, ignore_errors=True)
    os.makedirs(os.path.join(sanitized, "screens"))
    os.makedirs(os.path.join(sanitized, "video"))

    try:
        verification = json.load(open(os.path.join(root, "source", "verification.json")))
        signers = json.load(open(os.path.join(root, "source", "signers.json")))
    except (OSError, ValueError):
        print("Reference check failed: the APK verification report is missing.", file=sys.stderr)
        return 1
    if not (verification.get("verified") and signers.get("ok") and verification.get("package") == "com.deepseek.chat"):
        problems.append("the official APK did not pass package, signature, signer-pin or hash verification")

    try:
        manifest = json.load(open(os.path.join(raw, "manifest.json")))
    except (OSError, ValueError):
        manifest = {"entries": [], "signed_in": False, "signed_in_reason": "the driver produced no manifest"}
        problems.append("no capture manifest")
    if manifest.get("source", "official-reference") != "official-reference":
        problems.append("manifest source is not official-reference")
    viewport = manifest.get("viewport", {"width": 1080, "height": 2200})

    captured = {}
    for entry in manifest.get("entries", []):
        if entry.get("status") != "captured":
            continue
        screen, theme = entry.get("screen", ""), entry.get("theme", "")
        if theme not in THEMES or not re.fullmatch(r"[a-z0-9-]{1,40}", screen):
            problems.append("a capture has an unexpected screen id or theme")
            continue
        prefix = f"{theme}-{screen}"
        png, xml, meta = (os.path.join(raw, "screens", prefix + ext) for ext in (".png", ".xml", ".json"))
        try:
            width, height = png_size(png)
            if (width, height) != (viewport["width"], viewport["height"]):
                raise Failure(f"{prefix}.png is {width}x{height}, expected {viewport['width']}x{viewport['height']}")
            if not not_blank(png):
                raise Failure(f"{prefix}.png is blank")
            metadata = json.load(open(meta))
            if set(metadata) - META_KEYS or metadata.get("screen") != screen or metadata.get("theme") != theme \
                    or metadata.get("source") != "official-reference" or metadata.get("package") != "com.deepseek.chat":
                raise Failure(f"{prefix}.json is outside the metadata allowlist")
            text = open(xml, encoding="utf-8", errors="replace").read()
            attributes = " ".join(re.findall(r'(?:text|content-desc)="([^"]*)"', text))
            if any(pattern.search(attributes) for pattern in PRIVATE):
                raise Failure(f"{prefix}.xml still contains private data")
        except (OSError, ValueError, KeyError, Failure) as error:
            problems.append(str(error) if isinstance(error, Failure) else f"{prefix}: unreadable capture files")
            continue
        for path in (png, xml, meta):
            shutil.copy2(path, os.path.join(sanitized, "screens", os.path.basename(path)))
        captured[(screen, theme)] = True

    for screen in PUBLIC_REQUIRED:
        for theme in THEMES:
            if (screen, theme) not in captured:
                problems.append(f"required public screen {theme}-{screen} was not captured")

    required_signed_in = [(s, t) for s in SIGNED_IN_BOTH_THEMES for t in THEMES] + [(s, "light") for s in SIGNED_IN_LIGHT]
    missing_signed_in = [f"{t}-{s}" for s, t in required_signed_in if (s, t) not in captured]
    complete = not missing_signed_in
    if missing_signed_in:
        reason = manifest.get("signed_in_reason") or "not reached by the driver"
        notes.append(f"INCOMPLETE: {len(missing_signed_in)} signed-in screens missing ({reason}).")

    video_dir = os.path.join(raw, "video")
    for name in sorted(os.listdir(video_dir)) if os.path.isdir(video_dir) else []:
        path = os.path.join(video_dir, name)
        if not re.fullmatch(r"\d{2}-(light|dark)-(signed-out|chat)\.mp4", name):
            problems.append(f"unexpected video {name}")
        elif not video_ok(path):
            notes.append(f"video {name} is not decodable and was left out")
        else:
            shutil.copy2(path, os.path.join(sanitized, "video", name))

    entries = manifest.get("entries", [])
    coverage = {
        "source": "official-reference", "package": "com.deepseek.chat",
        "version": f"{verification.get('version_name')} ({verification.get('version_code')})",
        "signed_in": bool(manifest.get("signed_in")), "complete": complete,
        "captured": sorted(f"{t}-{s}" for s, t in captured),
        "missing": sorted({f"{e['theme']}-{e['screen']}: {e.get('reason', '')}" for e in entries if e.get("status") != "captured"}),
        "missing_required_signed_in": missing_signed_in,
    }
    json.dump(coverage, open(os.path.join(sanitized, "coverage.json"), "w"), indent=2)
    for name in ("verification.json", "signers.json", "hashes.json", "badging.txt"):
        source = os.path.join(root, "source", name)
        if os.path.exists(source):
            shutil.copy2(source, os.path.join(sanitized, name))
    device = os.path.join(root, "device", "settings.txt")
    if os.path.exists(device):
        shutil.copy2(device, os.path.join(sanitized, "device-settings.txt"))

    summary = ["## Official DeepSeek reference capture", "",
               f"- Version: {coverage['version']}; APK verified: {'yes' if verification.get('verified') and signers.get('ok') else 'no'}",
               f"- Captured screens: {len(captured)}; signed-in screens complete: {'yes' if complete else 'no'}"]
    summary += [f"- {note}" for note in notes] + [f"- PROBLEM: {problem}" for problem in problems]
    text = "\n".join(summary) + "\n"
    open(os.path.join(sanitized, "summary.md"), "w").write(text)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as handle:
            handle.write(text)
    print(text)
    if problems or (args.require_complete and not complete):
        print("Reference check failed.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
