#!/usr/bin/env python3
"""Deterministic HTTPS stand-in for DeepSeek's web chat, DeepSeek's API and the pages the agent tools read.

Release E2E only (scripts/run_release_e2e.sh). The disposable emulator trusts a throwaway CA and redirects its TCP 443
traffic here, so the unmodified release APK talks to its normal https:// addresses: no endpoint override, no
authentication bypass and no test receiver exists in the app. Replies are chosen by markers in the prompt (see
SCENARIOS below) so the UiAutomator journey is reproducible. The request log keeps hosts, paths, flags and tool names;
it never stores Authorization values, cookies, prompts beyond their markers, or uploaded file contents.

Since v1.1.0 it speaks the live service's shapes, so the bugs a real account found would fail here too: every completion
and every upload must carry a proof of work solved for that path (real DeepSeekHashV1 challenges at the live difficulty,
answers checked), uploads stay PENDING until fetch_files has been polled, Search streams use negative fragment indices,
nested BATCH patches with relative paths and TOOL_SEARCH / TOOL_OPEN fragments with [reference:N] markers, and each
synthetic account has a chat "from another device" in its history (fetch_page, history_messages) that the journey opens
and continues.

Standard library only. Usage: server.py --cert leaf.pem --key leaf.key --port 8443 --log requests.jsonl
"""
import argparse
import base64
import json
import os
import re
import secrets
import ssl
import struct
import threading
import time
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

TOKEN_PREFIX = "e2e-synthetic-session-"
# Synthetic, not a credential: the API-key journey types it and only this fixture accepts it.
FIXTURE_PROFILE_VALUE = "e2e-synthetic-profile-0123456789"  # gitleaks:allow
FENCE = "```"
COMPLETION_PATH = "/api/v0/chat/completion"
UPLOAD_PATH = "/api/v0/file/upload_file"
# The live difficulty (2026-10-20); the app solves it natively.
POW_DIFFICULTY = 144000
EXPECTED_HOSTS = {"chat.deepseek.com", "api.deepseek.com", "html.duckduckgo.com", "example.com", "www.example.com",
                  "cdn.jsdelivr.net", "cdn.deepseek.com"}
SEEDED_TITLE = "Planets from the web"

# Agent scenarios: "E2E-TOOL:<name>" in the user's request makes the first reply call that tool with these arguments.
TOOL_CALLS = {
    "write_file": {"file_path": "cities.csv", "content": "city,population\nTokyo,37400068\nDelhi,28514000\nShanghai,25582000\n"},
    "write_file_docx": {"file_path": "summary.docx", "title": "E2E summary", "content": "# E2E summary\n\n- First point\n- Second point\n\n| Item | Value |\n|---|---|\n| A | 1 |\n"},
    "write_file_md": {"file_path": "notes.md", "content": "# Notes\n\nWritten by the E2E fixture.\n"},
    "write": {"file_path": "hello.py", "content": "print(2 + 2)\n"},
    "read": {"file_path": "hello.py"},
    "present": {"files": [{"path": "hello.py", "description": "A tiny Python script"}]},
    "create_pdf": {"title": "E2E report", "markdown": "# E2E report\n\nRevenue grew **12%**.\n\n| Region | Revenue |\n|---|---:|\n| North | 1200 |\n", "file_path": "report.pdf"},
    "create_image": {"svg": "<svg xmlns='http://www.w3.org/2000/svg' width='120' height='80'><rect width='120' height='80' fill='#426EFE'/><circle cx='60' cy='40' r='24' fill='white'/></svg>", "fileName": "badge.png"},
    "create_chart": {"type": "bar", "title": "Sales", "labels": ["Q1", "Q2", "Q3"], "series": [{"name": "Sales", "values": [3, 5, 4]}], "fileName": "sales.svg"},
    "code_interpreter": {"language": "javascript", "code": "console.log(2 + 2); return 2 + 2;"},
    "code_interpreter_python": {"language": "python", "code": "print(2 + 2)"},
    "run_python": {"code": "print(2 + 2)"},
    "web_fetch": {"url": "https://example.com/report"},
    "web_search": {"query": "deepseek android e2e fixture"},
    "skill": {"name": "data-analysis"},
    "data_analysis__describe_csv": {"path": "cities.csv"},
    "pip_install": {"packages": ["tabulate"]},
    "create_xlsx": {"fileName": "figures.xlsx", "sheets": [{"name": "Q1", "rows": [["Region", "Revenue"], ["North", 1200], ["South", 980]]}]},
    "create_pptx": {"fileName": "deck.pptx", "title": "Quarter", "slides": [{"title": "Highlights", "bullets": ["North led", "South recovered"], "notes": "Keep it short."}]},
    "zip_files": {"sources": ["cities.csv", "hello.py"], "zip_path": "bundle.zip"},
}
TOOL_ALIASES = {"write_file_docx": "write_file", "write_file_md": "write_file", "code_interpreter_python": "code_interpreter"}

# DeepSeekHashV1: SHA3-256 sponge (rate 136, padding 0x06 ... 0x80) whose Keccak-f[1600] skips round 0.
_RC = [0x0000000000000001, 0x0000000000008082, 0x800000000000808A, 0x8000000080008000, 0x000000000000808B, 0x0000000080000001,
       0x8000000080008081, 0x8000000000008009, 0x000000000000008A, 0x0000000000000088, 0x0000000080008009, 0x000000008000000A,
       0x000000008000808B, 0x800000000000008B, 0x8000000000008089, 0x8000000000008003, 0x8000000000008002, 0x8000000000000080,
       0x000000000000800A, 0x800000008000000A, 0x8000000080008081, 0x8000000000008080, 0x0000000080000001, 0x8000000080008008]
_ROT = [1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 2, 14, 27, 41, 56, 8, 25, 43, 62, 18, 39, 61, 20, 44]
_LANES = [10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4, 15, 23, 19, 13, 12, 2, 20, 14, 22, 9, 6, 1]
_MASK = (1 << 64) - 1


def _rotl(value, count):
    return ((value << count) | (value >> (64 - count))) & _MASK


def _permute(a):
    for round_index in range(1, 24):
        c = [a[x] ^ a[x + 5] ^ a[x + 10] ^ a[x + 15] ^ a[x + 20] for x in range(5)]
        for x in range(5):
            d = c[(x + 4) % 5] ^ _rotl(c[(x + 1) % 5], 1)
            for y in range(0, 25, 5):
                a[y + x] ^= d
        carried = a[1]
        for step in range(24):
            lane = _LANES[step]
            a[lane], carried = _rotl(carried, _ROT[step]), a[lane]
        for y in range(0, 25, 5):
            row = a[y:y + 5]
            for x in range(5):
                a[y + x] = row[x] ^ ((~row[(x + 1) % 5] & _MASK) & row[(x + 2) % 5])
        a[0] ^= _RC[round_index]


def deepseek_hash(data):
    padded = bytearray(data) + b"\x06"
    while len(padded) % 136:
        padded.append(0)
    padded[-1] |= 0x80
    state = [0] * 25
    for offset in range(0, len(padded), 136):
        for i in range(17):
            state[i] ^= int.from_bytes(padded[offset + 8 * i:offset + 8 * i + 8], "little")
        _permute(state)
    return b"".join(state[i].to_bytes(8, "little") for i in range(4)).hex()


def tiny_png(rgb):
    """A 16 x 16 single-colour PNG, served as a site icon."""
    rows = b"".join(b"\x00" + bytes(rgb) * 16 for _ in range(16))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")


SITE_ICON = tiny_png((66, 110, 254))


class State:
    def __init__(self, log_path):
        self.lock = threading.Lock()
        self.sessions = 0
        self.revoked = set()
        self.log_path = log_path
        self.challenges = {}
        self.files = {}
        # Synthetic account (token) -> its chats, so each journey sees its own history.
        self.accounts = {}

    def challenge(self, target):
        """A real DeepSeekHashV1 challenge for target, at the live difficulty; the answer is kept to check the header."""
        salt = secrets.token_hex(10)
        expire_at = int(time.time() * 1000) + 300_000
        answer = secrets.randbelow(POW_DIFFICULTY)
        digest = deepseek_hash(f"{salt}_{expire_at}_{answer}".encode())
        entry = {"algorithm": "DeepSeekHashV1", "challenge": digest, "salt": salt, "difficulty": POW_DIFFICULTY,
                 "expire_at": expire_at, "expire_after": 300_000, "signature": secrets.token_hex(16), "target_path": target}
        with self.lock:
            self.challenges[digest] = dict(entry, answer=answer, used=False)
        return entry

    def check_pow(self, header, path):
        """ok, missing, malformed, unknown, wrong-target, wrong-answer, expired or reused."""
        if not header:
            return "missing"
        try:
            data = json.loads(base64.b64decode(header, validate=True))
        except (ValueError, TypeError):
            return "malformed"
        with self.lock:
            entry = self.challenges.get(data.get("challenge"))
            if entry is None:
                return "unknown"
            if data.get("target_path") != path or entry["target_path"] != path:
                return "wrong-target"
            if (data.get("answer") != entry["answer"] or data.get("salt") != entry["salt"] or data.get("signature") != entry["signature"]
                    or data.get("algorithm") != "DeepSeekHashV1"):
                return "wrong-answer"
            if time.time() * 1000 > entry["expire_at"]:
                return "expired"
            if entry["used"]:
                return "reused"
            entry["used"] = True
        return "ok"

    def new_file(self, name, size):
        with self.lock:
            file_id = f"file-e2e-{len(self.files) + 1:04d}"
            self.files[file_id] = {"id": file_id, "status": "PENDING", "file_name": name, "file_size": size, "polls": 0}
            return dict(self.files[file_id])

    def poll_file(self, file_id):
        """DeepSeek processes an upload for a few seconds: the first poll still says PENDING, the next SUCCESS."""
        with self.lock:
            entry = self.files.get(file_id)
            if entry is None:
                return None
            entry["polls"] += 1
            if entry["polls"] >= 2:
                entry["status"] = "SUCCESS"
            return dict(entry)

    def account(self, token):
        with self.lock:
            if token not in self.accounts:
                now = time.time()
                seeded = {"id": f"e2e-web-chat-{len(self.accounts) + 1}", "title": SEEDED_TITLE, "pinned": False, "updated_at": now - 3600,
                          "seeded": True, "messages": [
                              {"message_id": 1, "parent_id": None, "role": "USER", "fragments": [{"id": 1, "type": "REQUEST", "content": "Which planet is largest?"}]},
                              {"message_id": 2, "parent_id": 1, "role": "ASSISTANT", "fragments": [{"id": 2, "type": "RESPONSE", "content": "Jupiter is the largest planet in the Solar System."}]},
                          ]}
                self.accounts[token] = {seeded["id"]: seeded}
            return self.accounts[token]

    def new_session(self, token):
        chats = self.account(token)
        with self.lock:
            self.sessions += 1
            session = {"id": f"e2e-session-{self.sessions}", "title": "", "pinned": False, "updated_at": time.time(), "seeded": False, "messages": []}
            chats[session["id"]] = session
            return session["id"]

    def record(self, entry):
        entry["t"] = round(time.time(), 3)
        with self.lock, open(self.log_path, "a") as handle:
            handle.write(json.dumps(entry, sort_keys=True) + "\n")


def envelope(data):
    return {"code": 0, "msg": "", "data": {"biz_code": 0, "biz_msg": "", "biz_data": data}}


def sse(event):
    return "data: " + json.dumps(event, separators=(",", ":")) + "\n\n"


def named(name, data):
    return f"event: {name}\ndata: {json.dumps(data, separators=(',', ':'))}\n\n"


def stream(events, request_id, response_id, updated, title=None):
    """A complete reply as the live service frames it: ready, update_session, the patches, then update_session, title, close."""
    head = named("ready", {"request_message_id": request_id, "response_message_id": response_id, "model_type": "default"}) + \
        named("update_session", {"updated_at": updated})
    tail = named("update_session", {"updated_at": updated}) + (named("title", {"content": title}) if title else "") + \
        named("close", {"click_behavior": "none", "auto_resume": False})
    return head + "".join(sse(e) for e in events) + tail


def response_head(request_id, response_id, fragments, thinking=False, search=False):
    return {"v": {"response": {"message_id": response_id, "parent_id": request_id, "model": "", "role": "ASSISTANT",
                               "thinking_enabled": thinking, "status": "WIP", "incomplete_message": None, "accumulated_token_usage": 0,
                               "feedback": None, "search_enabled": search, "fragments": fragments, "conversation_mode": "DEFAULT",
                               "has_pending_fragment": False, "auto_continue": False, "search_triggered": search}}}


SEARCH_RESULTS = [
    {"url": "https://example.com/report", "title": "Example report", "snippet": "A deterministic page served by the E2E fixture.",
     "cite_index": 1, "site_icon": "https://cdn.deepseek.com/site-icons/example.com", "site_name": "Example", "query_indexes": [0]},
    {"url": "https://www.example.com/notes", "title": "Example notes", "snippet": "Notes the fixture serves.",
     "cite_index": 2, "site_icon": "https://cdn.deepseek.com/site-icons/www.example.com", "site_name": "Example notes", "query_indexes": [0]},
]


def web_reply(thinking, search, request_id, response_id):
    """Plain, DeepThink and Search replies in the live service's patch shapes (Search: SEARCH fragment and [citation:N])."""
    events = []
    if search:
        events.append(response_head(request_id, response_id, [{"id": 2, "type": "SEARCH", "status": "WIP", "content": None,
                                                               "queries": [{"query": "e2e search"}], "results": []}], search=True))
        events.append({"p": "response/fragments/-1/results", "v": SEARCH_RESULTS})
        events.append({"p": "response/fragments/-1", "o": "BATCH", "v": [{"p": "status", "v": "FINISHED"}, {"p": "content", "v": "Found 2 web pages"}]})
        events.append({"p": "response/conversation_mode", "o": "SET", "v": "SEARCH"})
        events.append({"p": "response/has_pending_fragment", "v": True})
        events.append({"p": "response", "o": "BATCH", "v": [{"p": "fragments", "o": "APPEND", "v": [{"id": 3, "type": "RESPONSE", "content": "Search result:",
                                                                                                 "references": [], "stage_id": 2}]},
                                                            {"p": "has_pending_fragment", "o": "SET", "v": False}]})
        events.append({"p": "response/fragments/-1/content", "o": "APPEND", "v": " the fixture page says hello"})
        events.append({"v": "[citation:1]."})
        text = "Search result: the fixture page says hello."
    else:
        first = [{"id": 2, "type": "THINK", "content": "Considering", "elapsed_secs": None, "references": [], "stage_id": 1}] if thinking else \
            [{"id": 2, "type": "RESPONSE", "content": "Hello", "references": [], "stage_id": 1}]
        events.append(response_head(request_id, response_id, first, thinking=thinking))
        if thinking:
            events.append({"p": "response/fragments/-1/content", "o": "APPEND", "v": " the question step by step."})
            events.append({"p": "response/fragments/-1/elapsed_secs", "o": "SET", "v": 3.2})
            events.append({"p": "response/fragments", "o": "APPEND", "v": [{"id": 3, "type": "RESPONSE", "content": "Thought it", "references": [], "stage_id": 1}]})
            events.append({"p": "response/fragments/-1/content", "o": "APPEND", "v": " through: 17 × 23 = 391."})
            text = "Thought it through: 17 × 23 = 391."
        else:
            events.append({"p": "response/fragments/-1/content", "o": "APPEND", "v": " from the E2E fixture."})
            text = "Hello from the E2E fixture."
    # The live service ends with a value-only batch that inherits BATCH, then FINISHED.
    events.append({"p": "response", "v": [{"p": "accumulated_token_usage", "v": 42}, {"p": "quasi_status", "v": "FINISHED"}]})
    events.append({"p": "response/status", "o": "SET", "v": "FINISHED"})
    return events, text


def think_search_reply(request_id, response_id):
    """DeepThink + Search exactly as the live stream of 2026-10-20 shapes it: stages, TOOL_SEARCH, TOOL_OPEN pages read,
    statuses patched at -2 and -3, nested batches with relative paths, and a [reference:0] marker resolved through the
    RESPONSE fragment's references (to the second page read)."""
    report, notes = SEARCH_RESULTS
    opened = dict(report, cite_index=None)
    events = [
        response_head(request_id, response_id, [{"id": 2, "type": "THINK", "content": "Plan", "elapsed_secs": None, "references": [], "stage_id": 1}],
                      thinking=True, search=True),
        {"p": "response/conversation_mode", "v": "DEEP_SEARCH"},
        {"p": "response/fragments/-1/content", "o": "APPEND", "v": " a search."},
        {"p": "response/fragments/-1/elapsed_secs", "o": "SET", "v": 1.5},
        {"p": "response/has_pending_fragment", "v": True},
        {"p": "response", "o": "BATCH", "v": [{"p": "fragments", "o": "APPEND", "v": [{"id": 3, "type": "TOOL_SEARCH", "status": "WIP", "content": None,
                                                                                         "queries": [{"query": "e2e think search"}], "results": [], "stage_id": 1}]},
                                              {"p": "has_pending_fragment", "o": "SET", "v": False}]},
        {"p": "response/fragments/-1/results", "o": "SET", "v": [dict(r, cite_index=None) for r in SEARCH_RESULTS]},
        {"p": "response/fragments/-1", "o": "BATCH", "v": [{"p": "status", "v": "FINISHED"}, {"p": "content", "v": "Found 2 web pages"}]},
        {"p": "response/has_pending_fragment", "o": "SET", "v": True},
        {"p": "response", "o": "BATCH", "v": [{"p": "fragments", "o": "APPEND", "v": [{"id": 4, "type": "THINK", "content": "Read", "elapsed_secs": None,
                                                                                         "references": [], "stage_id": 2}]},
                                              {"p": "has_pending_fragment", "o": "SET", "v": False}]},
        {"p": "response/fragments/-1/content", "o": "APPEND", "v": " the pages."},
        {"p": "response/fragments/-1/elapsed_secs", "o": "SET", "v": 2.0},
        {"p": "response/has_pending_fragment", "v": True},
        {"p": "response", "o": "BATCH", "v": [{"p": "fragments", "o": "APPEND", "v": [
            {"id": 5, "type": "TOOL_OPEN", "status": "WIP", "result": dict(notes, cite_index=None), "reference": {"id": 3, "type": "TOOL_SEARCH"}, "stage_id": 2},
            {"id": 6, "type": "TOOL_OPEN", "status": "WIP", "result": opened, "reference": {"id": 3, "type": "TOOL_SEARCH"}, "stage_id": 2}]},
            {"p": "has_pending_fragment", "o": "SET", "v": False}]},
        {"p": "response/fragments/-2/status", "o": "SET", "v": "FINISHED"},
        {"p": "response/fragments/-1/status", "v": "FINISHED"},
        {"p": "response/has_pending_fragment", "v": True},
        {"p": "response", "o": "BATCH", "v": [{"p": "fragments", "o": "APPEND", "v": [{"id": 7, "type": "THINK", "content": "Answer", "elapsed_secs": None,
                                                                                         "references": [], "stage_id": 3}]},
                                              {"p": "has_pending_fragment", "o": "SET", "v": False}]},
        {"p": "response/fragments/-1/content", "o": "APPEND", "v": " briefly."},
        {"p": "response/fragments/-1/elapsed_secs", "o": "SET", "v": 0.5},
        {"p": "response/fragments", "o": "APPEND", "v": [{"id": 8, "type": "RESPONSE", "content": "Think and search:", "references": [], "stage_id": 3}]},
        {"p": "response/fragments/-1/content", "v": " the report says revenue grew 12%"},
        {"p": "response/fragments/-1", "o": "BATCH", "v": [{"p": "content", "o": "APPEND", "v": "[reference:0]"},
                                                         {"p": "references", "o": "SET", "v": [{"id": 6, "type": "TOOL_OPEN"}]}]},
        {"p": "response/fragments/-1/content", "o": "APPEND", "v": "."},
        # Statuses of earlier fragments at deeper negative indices, as the live stream sends them.
        {"p": "response/fragments/-3/status", "v": "FINISHED"},
        {"p": "response", "v": [{"p": "accumulated_token_usage", "v": 78}, {"p": "quasi_status", "v": "FINISHED"}]},
        {"p": "response/status", "o": "SET", "v": "FINISHED"},
    ]
    return events, "Think and search: the report says revenue grew 12%."


def file_reply(request_id, response_id, files):
    names = ", ".join(f"{f['file_name']} ({f['file_size']} bytes)" for f in files)
    return text_events(f"I read {names}.", request_id, response_id), f"I read {names}."


def text_events(text, request_id, response_id):
    cut = min(12, len(text))
    return [response_head(request_id, response_id, [{"id": 2, "type": "RESPONSE", "content": text[:cut], "references": [], "stage_id": 1}]),
            {"p": "response/fragments/-1/content", "o": "APPEND", "v": text[cut:]},
            {"p": "response", "o": "BATCH", "v": [{"p": "accumulated_token_usage", "v": 12}, {"p": "quasi_status", "v": "FINISHED"}]},
            {"p": "response/status", "o": "SET", "v": "FINISHED"}]


def listed_tools(prompt):
    section = prompt.split("\nTools:\n", 1)[-1].split("\n\nUser request:", 1)[0] if "\nTools:\n" in prompt else ""
    return sorted(set(re.findall(r"^- ([A-Za-z0-9_-]+)\(", section, re.M)))


def agent_reply(prompt):
    """First agent message: call the requested tool (even a disabled one, to prove the app rejects it)."""
    request = prompt.rsplit("User request:\n", 1)[-1]
    match = re.search(r"E2E-TOOL:([A-Za-z0-9_]+)", request)
    if not match:
        # Agent mode is on by default since v1.2.0, so a plain message also arrives as an agent turn.
        return "No tool is needed for this request. Hello from the E2E fixture."
    scenario = match.group(1)
    name = TOOL_ALIASES.get(scenario, scenario)
    arguments = TOOL_CALLS.get(scenario, {})
    block = json.dumps({"name": name, "arguments": arguments}, ensure_ascii=False)
    return f"Calling {name}.\n{FENCE}tool\n{block}\n{FENCE}"


def tool_result_reply(prompt):
    body = prompt.split("\n", 1)[-1].rsplit(FENCE, 1)[0]
    try:
        result = json.loads(body)
    except ValueError:
        return "The tool result could not be read.", None, None
    name, ok = result.get("name"), bool(result.get("ok"))
    return (f"Done: {name} {'succeeded' if ok else 'was not run'}." if ok else f"Noted: {name} was not run."), name, ok


SIGN_IN_PAGE = """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"><title>DeepSeek sign-in (E2E fixture)</title>
<style>body{font:16px sans-serif;margin:24px}input,button{display:block;width:100%;margin:8px 0;padding:12px;font-size:16px}</style></head>
<body><h1>Log in</h1><p>Phone number / email address</p>
<input id="account" placeholder="Phone number / email address" aria-label="Phone number / email address">
<input id="password" type="password" placeholder="Password" aria-label="Password">
<button id="login" onclick="localStorage.setItem('userToken', JSON.stringify({value: 'e2e-synthetic-session-webview-0001', __version: '0'})); document.getElementById('state').textContent = 'Signed in';">Log in</button>
<button id="google">Log in with Google</button>
<p id="state">Fixture sign-in page</p></body></html>"""
ROOT_PAGE = "<!doctype html><html><head><title>DeepSeek (E2E fixture)</title></head><body><p>E2E fixture root</p></body></html>"
REPORT_PAGE = ("<!doctype html><html><head><title>Example report</title></head><body><h1>Example report</h1>"
               "<p>The E2E fixture served this page for web_fetch.</p><p>Revenue grew 12% in the north.</p></body></html>")
SEARCH_PAGE = ("<!doctype html><html><body>"
               "<a class=\"result__a\" href=\"https://example.com/report\">Example report</a>"
               "<a class=\"result__snippet\" href=\"https://example.com/report\">A deterministic page served by the E2E fixture.</a>"
               "</body></html>")


class Handler(BaseHTTPRequestHandler):
    server_version = "e2e-fixture"
    protocol_version = "HTTP/1.1"
    timeout = 60  # a stalled client (TLS handshake included) never blocks the server

    def log_message(self, *args):  # noqa: D401 - silence the default stderr access log
        pass

    @property
    def state(self):
        return self.server.state

    def host(self):
        return (self.headers.get("Host") or "").split(":")[0].lower()

    def token(self):
        value = self.headers.get("Authorization", "")
        return value[7:] if value.startswith("Bearer ") else None

    def auth_status(self):
        token = self.token()
        if token is None:
            return "none"
        if token.startswith(TOKEN_PREFIX) and len(token) >= 24 and token not in self.state.revoked:
            return "valid"
        if token == FIXTURE_PROFILE_VALUE:
            return "api-key"
        return "invalid"

    def body(self):
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if 0 < length <= 25 * 1024 * 1024 else b""

    def send(self, status, payload, content_type="application/json", headers=None):
        data = payload if isinstance(payload, bytes) else (payload if isinstance(payload, str) else json.dumps(payload)).encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(data)

    def note(self, expected=True, **summary):
        path = urlsplit(self.path).path
        self.state.record({"host": self.host(), "method": self.command, "path": path, "auth": self.auth_status(),
                           "expected": expected and self.host() in EXPECTED_HOSTS, **summary})

    def do_GET(self):  # noqa: N802 - http.server naming
        self.route("GET", b"")

    def do_POST(self):  # noqa: N802
        self.route("POST", self.body())

    def route(self, method, raw):
        host, path = self.host(), urlsplit(self.path).path
        try:
            if host == "chat.deepseek.com":
                return self.chat(method, path, raw)
            if host == "cdn.deepseek.com" and path.startswith("/site-icons/"):
                self.note(icon=True)
                return self.send(200, SITE_ICON, "image/png")
            if host == "api.deepseek.com":
                return self.api(method, path, raw)
            if host == "html.duckduckgo.com" and path.startswith("/html"):
                self.note(query_present=bool(parse_qs(urlsplit(self.path).query).get("q")))
                return self.send(200, SEARCH_PAGE, "text/html; charset=utf-8")
            if host in ("example.com", "www.example.com") and path == "/report":
                self.note()
                return self.send(200, REPORT_PAGE, "text/html; charset=utf-8")
            if host in ("example.com", "www.example.com") and path == "/favicon.ico":
                self.note(icon=True)
                return self.send(200, SITE_ICON, "image/png")
            if host == "cdn.jsdelivr.net" and path.startswith("/pyodide/v0.26.4/full/"):
                return self.pyodide(path.rsplit("/", 1)[-1])
        except (ValueError, KeyError):
            self.note(expected=False, error="bad request")
            return self.send(400, {"error": "bad request"})
        self.note(expected=False)
        self.send(404, {"error": "not served by the E2E fixture"})

    def chat(self, method, path, raw):
        if method == "GET" and path in ("/", "/sign_in"):
            self.note(page=path)
            return self.send(200, SIGN_IN_PAGE if path == "/sign_in" else ROOT_PAGE, "text/html; charset=utf-8")
        if method == "GET" and path == "/favicon.ico":
            self.note()
            return self.send(404, b"", "image/x-icon")
        if not path.startswith("/api/v0/"):
            self.note(expected=False)
            return self.send(404, {"error": "unknown page"})
        status = self.auth_status()
        if status != "valid":
            self.note(rejected=True)
            return self.send(401, {"code": 40003, "msg": "invalid token", "data": None})
        if method == "GET" and path == "/api/v0/users/current":
            self.note()
            return self.send(200, envelope({"id": "e2e-user", "email": "e2e***@example.com", "mobile_number": "",
                                            "need_birthday": False, "chat": {"is_muted": 0}}))
        query = parse_qs(urlsplit(self.path).query)
        if method == "GET" and path == "/api/v0/chat_session/fetch_page":
            return self.fetch_page(query)
        if method == "GET" and path == "/api/v0/chat/history_messages":
            return self.history_messages(query.get("chat_session_id", [""])[0])
        if method == "GET" and path == "/api/v0/file/fetch_files":
            ids = [i for value in query.get("file_ids", []) for i in value.split(",") if i]
            files = [f for f in (self.state.poll_file(i) for i in ids) if f is not None]
            self.note(fetch_files=len(ids), statuses=[f["status"] for f in files])
            return self.send(200, envelope({"files": [{k: v for k, v in f.items() if k != "polls"} for f in files]}))
        if path == UPLOAD_PATH:
            return self.upload(raw)
        body = json.loads(raw or b"{}")
        chats = self.state.account(self.token())
        if path == "/api/v0/chat_session/create":
            session = self.state.new_session(self.token())
            self.note(session=session)
            return self.send(200, envelope({"chat_session": {"id": session}}))
        if path == "/api/v0/chat/create_pow_challenge":
            target = body.get("target_path")
            self.note(target=target)
            if target not in (COMPLETION_PATH, UPLOAD_PATH):
                return self.send(200, {"code": 0, "data": {"biz_code": 1, "biz_msg": "unknown target", "biz_data": None}})
            return self.send(200, envelope({"challenge": self.state.challenge(target)}))
        session = chats.get(str(body.get("chat_session_id", "")))
        if path == "/api/v0/chat_session/update_title":
            if session is not None:
                session["title"] = str(body.get("title", ""))[:100]
            self.note(known=session is not None)
            return self.send(200, envelope(None))
        if path == "/api/v0/chat_session/delete":
            chats.pop(str(body.get("chat_session_id", "")), None)
            self.note(known=session is not None)
            return self.send(200, envelope(None))
        if path == "/api/v0/chat_session/update_pinned":
            if session is not None:
                session["pinned"] = bool(body.get("pinned"))
                session["updated_at"] = time.time()
            self.note(known=session is not None, pinned=bool(body.get("pinned")))
            return self.send(200, envelope({"chat_session_updated_at": time.time()}))
        if path == "/api/v0/chat/message_feedback":
            feedback = body.get("feedback_type")
            if feedback not in (None, "GOOD", "BAD"):
                self.note(expected=False, feedback="invalid")
                return self.send(422, {"detail": [{"loc": "body.feedback_type"}]})
            message = next((m for m in (session or {}).get("messages", []) if m["message_id"] == body.get("message_id")), None)
            if message is not None:
                message["feedback"] = {"feedback_type": feedback} if feedback else None
            self.note(known=message is not None, feedback=feedback)
            return self.send(200, envelope(None))
        if path == COMPLETION_PATH:
            return self.completion(body, session)
        self.note(expected=False)
        return self.send(404, {"error": "unknown API"})

    def pow_rejected(self, state):
        """The live answers for a missing or wrong proof of work (HTTP 200 with codes 40300 / 40301)."""
        if state == "missing":
            return self.send(200, {"code": 40300, "msg": "MISSING_HEADER", "data": None})
        return self.send(200, {"code": 40301, "msg": "INVALID_POW_RESPONSE", "data": None})

    def upload(self, raw):
        pow_state = self.state.check_pow(self.headers.get("X-DS-PoW-Response"), UPLOAD_PATH)
        if pow_state != "ok":
            self.note(upload_bytes=len(raw), pow=pow_state)
            return self.pow_rejected(pow_state)
        head = raw[:4096].decode("latin-1")
        name = (re.search(r'filename="([^"]{1,200})"', head) or [None, "upload.bin"])[1]
        start = raw.find(b"\r\n\r\n")
        end = raw.rfind(b"\r\n--")
        size = max(0, end - start - 4) if start >= 0 and end > start else len(raw)
        entry = self.state.new_file(name, size)
        self.note(upload_bytes=len(raw), pow="ok", file=entry["id"])
        return self.send(200, envelope({"id": entry["id"], "status": "PENDING", "file_name": name, "from_share": False, "file_size": size,
                                        "model_kind": "VISION", "token_usage": None, "error_code": None, "is_image": False}))

    def fetch_page(self, query):
        try:
            count = int(query.get("count", ["0"])[0])
        except ValueError:
            count = 0
        if not 2 <= count <= 100:
            self.note(count=count)
            return self.send(200, {"code": 0, "msg": "", "data": {"biz_code": 1, "biz_msg": "illegal count", "biz_data": None}})
        chats = sorted(self.state.account(self.token()).values(), key=lambda c: (not c["pinned"], -c["updated_at"]))
        if "lte_cursor.updated_at" in query:
            cursor = float(query["lte_cursor.updated_at"][0])
            pinned = query.get("lte_cursor.pinned", ["false"])[0] == "true"
            chats = [c for c in chats if (c["pinned"] and pinned and c["updated_at"] <= cursor) or (not c["pinned"] and (pinned or c["updated_at"] <= cursor))]
        page = chats[:count]
        self.note(count=count, listed=len(page), cursor="lte_cursor.updated_at" in query)
        return self.send(200, envelope({"chat_sessions": [{"id": c["id"], "title": c["title"] or "New Chat", "title_type": "SYSTEM", "model_type": "default",
                                                           "pinned": c["pinned"], "updated_at": c["updated_at"]} for c in page],
                                        "has_more": len(chats) > count}))

    def history_messages(self, session_id):
        session = self.state.account(self.token()).get(session_id)
        self.note(known=session is not None, seeded=bool(session and session["seeded"]))
        if session is None:
            return self.send(200, {"code": 0, "msg": "", "data": {"biz_code": 1, "biz_msg": "session not found", "biz_data": None}})
        messages = session["messages"]
        current = messages[-1]["message_id"] if messages else None
        return self.send(200, envelope({
            "chat_session": {"id": session["id"], "title": session["title"] or "New Chat", "title_type": "SYSTEM", "model_type": "default",
                             "pinned": session["pinned"], "updated_at": session["updated_at"], "agent": "chat", "version": 2,
                             "is_empty": not messages, "current_message_id": current},
            "chat_messages": [dict(m, model="", status="FINISHED", feedback=m.get("feedback"), thinking_enabled=False, search_enabled=False,
                                   incomplete_message=None, accumulated_token_usage=0) for m in messages],
            "cache_control": "REPLACE"}))

    def completion(self, body, session):
        prompt = str(body.get("prompt", ""))
        thinking, search = bool(body.get("thinking_enabled")), bool(body.get("search_enabled"))
        refs = [str(r) for r in body.get("ref_file_ids") or []]
        pow_state = self.state.check_pow(self.headers.get("X-DS-PoW-Response"), COMPLETION_PATH)
        if pow_state != "ok":
            self.note(kind="chat", pow=pow_state)
            return self.pow_rejected(pow_state)
        if session is None:
            self.note(expected=False, kind="chat", pow="ok", error="unknown session")
            return self.send(200, {"code": 0, "msg": "", "data": {"biz_code": 1, "biz_msg": "session not found", "biz_data": None}})
        files = [self.state.files.get(r) for r in refs]
        if not all(f is not None and f["status"] == "SUCCESS" for f in files):
            # A completion may only reference files DeepSeek has finished processing (fetch_files said SUCCESS).
            self.note(kind="chat", pow="ok", refs=len(refs), refs_ready=False)
            return self.send(200, {"code": 0, "msg": "", "data": {"biz_code": 1, "biz_msg": "FILE_NOT_READY", "biz_data": None}})
        messages = session["messages"]
        parent = body.get("parent_message_id")
        current = messages[-1]["message_id"] if messages else None
        request_id = max((m["message_id"] for m in messages), default=0) + 1
        response_id = request_id + 1
        updated = time.time()
        title = None
        if not messages:
            words = re.sub(r"[^\w\s-]", " ", prompt.rsplit("User request:\n", 1)[-1]).split()
            title = " ".join(words[:4]) or "New Chat"
        history = {}
        if session["seeded"] and parent is not None:
            # Continuing the chat "from another device": the parent must be its current message, as history_messages said.
            history = {"scenario": "server_history" if parent == current else "server_history_wrong_parent", "parent_ok": parent == current}
        if "E2E-EXPIRE" in prompt:
            self.state.revoked.add(self.token())
            self.note(scenario="expire", thinking=thinking, search=search, pow="ok")
            return self.send(401, {"code": 40003, "msg": "expired", "data": None})
        prefix = ""
        if prompt.startswith(FENCE + "tool_result"):
            text, name, ok = tool_result_reply(prompt)
            events = text_events(text, request_id, response_id)
            self.note(kind="tool_result", tool=name, ok=ok, pow="ok")
        elif prompt.startswith("[Agent mode"):
            tools = listed_tools(prompt)
            text = agent_reply(prompt)
            requested = re.search(r'"name": "([A-Za-z0-9_-]+)"', text)
            events = text_events(text, request_id, response_id)
            self.note(kind="agent", tools=tools, requested=requested.group(1) if requested else None, pow="ok", refs=len(refs))
        elif refs:
            events, text = file_reply(request_id, response_id, files)
            prefix = "".join(named("update_file", {k: v for k, v in f.items() if k != "polls"}) for f in files)
            self.note(kind="chat", pow="ok", refs=len(refs), refs_ready=True, scenario="upload")
        elif thinking and search:
            events, text = think_search_reply(request_id, response_id)
            self.note(kind="chat", thinking=True, search=True, pow="ok", refs=0, scenario="think_search")
        else:
            events, text = web_reply(thinking, search, request_id, response_id)
            self.note(kind="chat", thinking=thinking, search=search, refs=0, pow="ok", slow="E2E-SLOW" in prompt, **history)
        user = ([{"id": 1, "type": "FILE", "files": [{k: v for k, v in f.items() if k != "polls"} for f in files]}] if files else []) + \
            [{"id": 2 if files else 1, "type": "REQUEST", "content": prompt}]
        messages.append({"message_id": request_id, "parent_id": parent, "role": "USER", "fragments": user})
        messages.append({"message_id": response_id, "parent_id": request_id, "role": "ASSISTANT",
                         "fragments": [{"id": len(user) + 1, "type": "RESPONSE", "content": text, "references": [], "stage_id": 1}]})
        session["updated_at"] = updated
        if title:
            session["title"] = title
        reply = stream(events, request_id, response_id, updated, title)
        if prefix:
            ready, rest = reply.split("\n\n", 1)
            reply = ready + "\n\n" + prefix + rest
        return self.stream(reply, slow="E2E-SLOW" in prompt)

    def stream(self, text, slow=False):
        data = text.encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        if not slow:
            self.wfile.write(data)
            return
        # Slow reply for the Stop test: the first event, then one chunk every 2 seconds.
        parts = text.split("\n\n")
        for index, part in enumerate(parts):
            if not part:
                continue
            try:
                self.wfile.write((part + "\n\n").encode())
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                return
            if index >= 1:
                time.sleep(2.0)

    def api(self, method, path, raw):
        key_ok = self.auth_status() == "api-key"
        if method == "GET" and path == "/models":
            self.note(key_ok=key_ok)
            if not key_ok:
                return self.send(401, {"error": {"message": "Authentication Fails", "type": "authentication_error"}})
            return self.send(200, {"object": "list", "data": [{"id": "deepseek-chat", "object": "model", "owned_by": "deepseek"},
                                                              {"id": "deepseek-reasoner", "object": "model", "owned_by": "deepseek"}]})
        if method == "GET" and path == "/user/balance":
            self.note(key_ok=key_ok)
            if not key_ok:
                return self.send(401, {"error": {"message": "Authentication Fails"}})
            return self.send(200, {"is_available": True, "balance_infos": [{"currency": "USD", "total_balance": "5.00",
                                                                            "granted_balance": "0.00", "topped_up_balance": "5.00"}]})
        if method == "POST" and path == "/chat/completions":
            if not key_ok:
                self.note(key_ok=False)
                return self.send(401, {"error": {"message": "Authentication Fails"}})
            return self.api_completion(json.loads(raw or b"{}"))
        self.note(expected=False)
        return self.send(404, {"error": "unknown API"})

    def api_completion(self, body):
        messages = body.get("messages") or []
        tools = sorted(t.get("function", {}).get("name", "") for t in body.get("tools") or [])
        last = messages[-1] if messages else {}
        created = int(time.time())

        def chunk(delta, finish=None):
            return "data: " + json.dumps({"id": "e2e", "object": "chat.completion.chunk", "created": created, "model": body.get("model", "deepseek-chat"),
                                          "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}) + "\n\n"
        model = body.get("model", "")
        if last.get("role") == "tool":
            try:
                result = json.loads(last.get("content") or "{}")
            except ValueError:
                result = {}
            # v1.2.0: a tool turn must use the chat model with the tools still offered (ModelSelector.forAgentTurn).
            scenario = "api_agent" if model == "deepseek-chat" and tools else "api_agent_wrong_model"
            self.note(kind="api_tool_result", tools=tools, model=model, ok=not result.get("isError", False), scenario=scenario)
            text = (chunk({"role": "assistant", "content": "Here is your file. "}) + chunk({"content": "Native tool call "})
                    + chunk({"content": "finished."}) + chunk({}, "stop") + "data: [DONE]\n\n")
            return self.stream(text)
        content = last.get("content") if isinstance(last.get("content"), str) else json.dumps(last.get("content"))
        match = re.search(r"E2E-TOOL:([A-Za-z0-9_]+)", content or "")
        if match and tools:
            scenario = match.group(1)
            name = TOOL_ALIASES.get(scenario, scenario)
            arguments = json.dumps(TOOL_CALLS.get(scenario, {}))
            self.note(kind="api_agent", tools=tools, requested=name, model=model)
            # The arguments arrive split across deltas, as the live API streams them.
            third = max(1, len(arguments) // 3)
            parts = [arguments[:third], arguments[third:2 * third], arguments[2 * third:]]
            text = (chunk({"role": "assistant", "content": None, "tool_calls": [{"index": 0, "id": "call_e2e_1", "type": "function",
                                                                                 "function": {"name": name, "arguments": ""}}]})
                    + "".join(chunk({"tool_calls": [{"index": 0, "function": {"arguments": part}}]}) for part in parts)
                    + chunk({}, "tool_calls") + "data: [DONE]\n\n")
            return self.stream(text)
        self.note(kind="api_chat", tools=tools, model=model)
        text = chunk({"role": "assistant", "content": "Hello from the API fixture."}) + chunk({}, "stop") + "data: [DONE]\n\n"
        return self.stream(text)

    def pyodide(self, name):
        directory = self.server.pyodide_dir
        allowed = {"pyodide.js", "pyodide.asm.js", "pyodide.asm.wasm", "python_stdlib.zip", "pyodide-lock.json"}
        if not directory or name not in allowed or not os.path.isfile(os.path.join(directory, name)):
            self.note(expected=False, file=name)
            return self.send(404, {"error": "runtime file not staged"})
        self.note(file=name)
        with open(os.path.join(directory, name), "rb") as handle:
            data = handle.read()
        mime = "application/wasm" if name.endswith(".wasm") else ("text/javascript" if name.endswith(".js") else "application/octet-stream")
        return self.send(200, data, mime, {"Access-Control-Allow-Origin": "*"})


class FixtureServer(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        """System traffic the emulator sends to hosts the fixture does not serve fails its TLS handshake: one line, no traceback."""
        import sys
        error = sys.exc_info()[1]
        print(f"connection error: {type(error).__name__}: {str(error)[:120]}", flush=True)


def main():
    parser = argparse.ArgumentParser(description="E2E fixture server")
    parser.add_argument("--cert", required=True)
    parser.add_argument("--key", required=True)
    parser.add_argument("--port", type=int, default=8443)
    parser.add_argument("--log", required=True)
    parser.add_argument("--pyodide-dir", default="")
    args = parser.parse_args()
    server = FixtureServer(("127.0.0.1", args.port), Handler)
    server.state = State(args.log)
    server.pyodide_dir = args.pyodide_dir
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(args.cert, args.key)
    context.set_alpn_protocols(["http/1.1"])
    # The handshake runs in the handler thread, so system traffic redirected here cannot stall accept().
    server.socket = context.wrap_socket(server.socket, server_side=True, do_handshake_on_connect=False)
    print(f"E2E fixture listening on 127.0.0.1:{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
