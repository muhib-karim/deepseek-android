#!/usr/bin/env python3
"""Validate one release E2E run's evidence and write its summary.

Checks: the instrumentation ran tests and none failed; the installed APK equals the artifact; every step screenshot is a
1080x2200 PNG; at least one decodable video segment exists; no credential marker appears in any text evidence; and, for
the mocked run, the fixture saw the required protocol (session validated before chat, proof-of-work header on every
completion and upload, no file referenced before processing, no unexpected request to a fixture host). Results are
labelled "release with mocked backend" or
"live login-page check"; neither claims live model behaviour.

Usage: check_e2e_evidence.py OUT_DIR --mode mocked|live
"""
import argparse
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET

FORBIDDEN = [re.compile(p) for p in (r"Bearer\s+[A-Za-z0-9._~+/-]{8,}", r"Authorization:", r"Cookie:", r"ds_session_id=",
                                      r"DSA_KEYSTORE", r"BEGIN (RSA |EC )?PRIVATE KEY")]
LABELS = {"mocked": "release with mocked backend", "live": "live login-page check"}


def png_size(path):
    with open(path, "rb") as handle:
        head = handle.read(24)
    if head[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    return struct.unpack(">II", head[16:24])


def video_ok(path):
    with open(path, "rb") as handle:
        head = handle.read(12)
    if len(head) < 12 or head[4:8] != b"ftyp" or os.path.getsize(path) < 10_000:
        return False
    if not shutil.which("ffprobe"):
        return True
    result = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
                            capture_output=True, text=True, timeout=60)
    try:
        return result.returncode == 0 and float(result.stdout.strip() or 0) > 0.5
    except ValueError:
        return False


def requests_problems(path):
    problems, notes = [], []
    if not os.path.exists(path):
        return ["the fixture recorded no requests"], notes
    entries = [json.loads(line) for line in open(path) if line.strip()]
    chat = [e for e in entries if e.get("host") == "chat.deepseek.com"]
    unexpected = sorted({f"{e['method']} {e['host']}{e['path']}" for e in entries
                         if not e.get("expected") and e.get("host") in ("chat.deepseek.com", "api.deepseek.com")})
    if unexpected:
        problems.append("unexpected requests to fixture hosts: " + ", ".join(unexpected[:10]))
    other_hosts = sorted({e.get("host", "") for e in entries if not e.get("expected")} - {"chat.deepseek.com", "api.deepseek.com"})
    if other_hosts:
        notes.append("traffic to hosts the fixture does not serve (system services, refused): " + ", ".join(h or "?" for h in other_hosts[:10]))
    validations = [i for i, e in enumerate(chat) if e.get("path") == "/api/v0/users/current" and e.get("auth") == "valid"]
    completions = [i for i, e in enumerate(chat) if e.get("path") == "/api/v0/chat/completion" and e.get("auth") == "valid"]
    if completions and (not validations or validations[0] > completions[0]):
        problems.append("a chat request was sent before the session was validated")
    if not completions:
        problems.append("no chat completion reached the fixture")
    # Since v1.1.0 the fixture checks each proof of work against the challenge it issued for that path (live behaviour).
    if any(chat[i].get("pow") != "ok" for i in completions):
        problems.append("a completion was sent without a valid proof of work for its path")
    uploads = [e for e in chat if e.get("path") == "/api/v0/file/upload_file" and e.get("auth") == "valid"]
    if any(e.get("pow") != "ok" for e in uploads):
        problems.append("a file upload was sent without a valid proof of work for its path")
    if any(e.get("refs_ready") is False for e in chat):
        problems.append("a completion referenced a file before DeepSeek had finished processing it")
    if any(e.get("scenario") == "server_history_wrong_parent" for e in chat):
        problems.append("a chat loaded from DeepSeek was continued from the wrong parent message")
    if any(e.get("auth") == "valid" and e.get("path", "").startswith("/api/v0/") and e.get("rejected") for e in chat):
        problems.append("the fixture rejected a valid session")
    return problems, notes


def coverage_problems(log_path, coverage_path):
    """Each required tool case must appear in the fixture's log: an agent turn requesting the tool, then its result."""
    spec = json.load(open(coverage_path))
    if not os.path.exists(log_path):
        return ["coverage: the fixture recorded no requests"], 0
    entries = [json.loads(line) for line in open(log_path) if line.strip()]
    observed, pending = [], {}
    for entry in entries:
        if entry.get("kind") == "agent" and entry.get("requested"):
            pending[entry["requested"]] = entry["requested"] in (entry.get("tools") or [])
        elif entry.get("kind") == "tool_result" and entry.get("tool") in pending:
            observed.append((entry["tool"], pending.pop(entry["tool"]), bool(entry.get("ok"))))
    problems = [f"coverage: no '{journey}' journey in the fixture's log" for journey in spec.get("journeys", [])
                if not any(entry.get("scenario") == journey for entry in entries)]
    for case in spec["cases"]:
        wanted = (case["tool"], case["offered"], case["ok"])
        if wanted not in observed:
            problems.append(f"coverage: no {'successful' if case['ok'] else 'refused or declined'} {case['tool']} call "
                            f"({'offered' if case['offered'] else 'not offered'})")
    return problems, len(spec["cases"]) + len(spec.get("journeys", []))


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("out")
    parser.add_argument("--mode", choices=sorted(LABELS), required=True)
    parser.add_argument("--coverage", help="e2e/coverage.json: required tool cases (full mocked suite only)")
    args = parser.parse_args(argv)
    out, problems, notes = args.out, [], []

    junit = os.path.join(out, "junit.xml")
    tests = failures = skipped = 0
    try:
        suite = ET.parse(junit).getroot()
        tests, failures, skipped = (int(suite.get(k, 0)) for k in ("tests", "failures", "skipped"))
        failed_names = [c.get("name") for c in suite.iter("testcase") if c.find("failure") is not None]
    except (OSError, ET.ParseError):
        failed_names = []
        problems.append("no JUnit result (the instrumentation did not run or its output was unreadable)")
    if tests - skipped <= 0:
        problems.append("no test ran")
    if failures:
        problems.append(f"{failures} test(s) failed: " + ", ".join(failed_names[:10]))

    apk = os.path.join(out, "apk.txt")
    if os.path.exists(apk):
        values = dict(line.strip().split("=", 1) for line in open(apk) if "=" in line and not line.startswith(" "))
        if values.get("release_apk_sha256") != values.get("installed_sha256"):
            problems.append("the installed APK differs from the artifact")
    else:
        problems.append("no installed-APK record")

    shots = []
    for root, _dirs, files in os.walk(os.path.join(out, "steps")):
        for name in sorted(files):
            if name.endswith(".png"):
                size = png_size(os.path.join(root, name))
                if size != (1080, 2200):
                    problems.append(f"step screenshot {name} is not a 1080x2200 PNG")
                shots.append(name)
    if not shots:
        problems.append("no step screenshots")
    videos = [n for n in sorted(os.listdir(os.path.join(out, "video"))) if n.endswith(".mp4")] if os.path.isdir(os.path.join(out, "video")) else []
    good_videos = [n for n in videos if video_ok(os.path.join(out, "video", n))]
    if not good_videos:
        problems.append("no decodable screen recording")
    elif len(good_videos) < len(videos):
        notes.append(f"{len(videos) - len(good_videos)} video segment(s) were not decodable")

    for root, _dirs, files in os.walk(out):
        for name in files:
            if name.endswith((".txt", ".xml", ".jsonl", ".json", ".md")):
                text = open(os.path.join(root, name), encoding="utf-8", errors="replace").read()
                if any(p.search(text) for p in FORBIDDEN):
                    problems.append(f"credential marker in {os.path.relpath(os.path.join(root, name), out)}")

    if args.mode == "mocked":
        found, extra = requests_problems(os.path.join(out, "logs", "requests.jsonl"))
        problems += found
        notes += extra
    covered = None
    if args.coverage:
        found, cases = coverage_problems(os.path.join(out, "logs", "requests.jsonl"), args.coverage)
        problems += found
        covered = (cases - len(found), cases)

    lines = [f"## Release E2E ({LABELS[args.mode]}): {os.path.basename(os.path.normpath(out))}", "",
             f"- Tests: {tests - skipped - failures} passed, {failures} failed, {skipped} skipped",
             f"- Step screenshots: {len(shots)}; video segments: {len(good_videos)}"]
    if covered:
        lines.append(f"- Tool coverage (e2e/coverage.json, from the fixture's log): {covered[0]} of {covered[1]} cases")
    lines += [f"- Note: {n}" for n in notes] + [f"- PROBLEM: {p}" for p in problems]
    if args.mode == "mocked":
        lines.append("- Mocked backend: proves the release build's UI, storage, tools and protocol handling, not live model behaviour.")
    text = "\n".join(lines) + "\n"
    open(os.path.join(out, "summary.md"), "w").write(text)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as handle:
            handle.write(text)
    print(text)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
