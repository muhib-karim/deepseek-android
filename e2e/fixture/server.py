#!/usr/bin/env python3
"""Deterministic HTTPS stand-in for DeepSeek's web chat, DeepSeek's API and the pages the agent tools read.

Release E2E only (scripts/run_release_e2e.sh). The disposable emulator trusts a throwaway CA and redirects its TCP 443
traffic here, so the unmodified release APK talks to its normal https:// addresses: no endpoint override, no
authentication bypass and no test receiver exists in the app. Replies are chosen by markers in the prompt (see
SCENARIOS below) so the UiAutomator journey is reproducible. The request log keeps hosts, paths, flags and tool names;
it never stores Authorization values, cookies, prompts beyond their markers, or uploaded file contents.

Standard library only. Usage: server.py --cert leaf.pem --key leaf.key --port 8443 --log requests.jsonl
"""
import argparse
import json
import os
import re
import ssl
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

TOKEN_PREFIX = "e2e-synthetic-session-"
# Synthetic, not a credential: the API-key journey types it and only this fixture accepts it.
FIXTURE_PROFILE_VALUE = "e2e-synthetic-profile-0123456789"  # gitleaks:allow
FENCE = "```"
WORKER_PATH = "/chat/static/76608.8f2a9fa413.js"
EXPECTED_HOSTS = {"chat.deepseek.com", "fe-static.deepseek.com", "api.deepseek.com", "html.duckduckgo.com",
                  "example.com", "www.example.com", "cdn.jsdelivr.net"}

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
}
TOOL_ALIASES = {"write_file_docx": "write_file", "write_file_md": "write_file", "code_interpreter_python": "code_interpreter"}


class State:
    def __init__(self, log_path):
        self.lock = threading.Lock()
        self.ids = 0
        self.sessions = 0
        self.revoked = set()
        self.log_path = log_path

    def next_ids(self):
        with self.lock:
            self.ids += 2
            return self.ids - 1, self.ids

    def next_session(self):
        with self.lock:
            self.sessions += 1
            return f"e2e-session-{self.sessions}"

    def record(self, entry):
        entry["t"] = round(time.time(), 3)
        with self.lock, open(self.log_path, "a") as handle:
            handle.write(json.dumps(entry, sort_keys=True) + "\n")


def envelope(data):
    return {"code": 0, "msg": "", "data": {"biz_code": 0, "biz_msg": "", "biz_data": data}}


def sse(event):
    return "data: " + json.dumps(event, separators=(",", ":")) + "\n\n"


def web_reply(prompt, thinking, search, request_id, response_id):
    """The live service's patch stream: SET with ids and fragments, APPENDs, elapsed time, FINISHED, [DONE]."""
    events = []
    if search:
        events.append({"v": {"response": {"message_id": response_id, "parent_id": request_id, "status": "WIP", "search_enabled": True,
                                          "fragments": [{"id": 1, "type": "SEARCH", "status": "WIP", "content": None,
                                                         "queries": [{"query": "e2e search"}], "results": []}]}}})
        events.append({"p": "response/fragments/-1/results", "v": [
            {"url": "https://example.com/report", "title": "Example report", "snippet": "A deterministic page served by the E2E fixture.", "cite_index": 1}]})
        events.append({"p": "response/fragments/-1", "o": "BATCH", "v": [{"p": "status", "v": "FINISHED"}, {"p": "content", "v": "Found 1 web page"}]})
        events.append({"p": "response", "o": "BATCH", "v": [{"p": "fragments", "o": "APPEND", "v": [{"id": 2, "type": "RESPONSE", "content": "Search result:"}]}]})
        events.append({"p": "response/fragments/-1/content", "o": "APPEND", "v": " the fixture page says hello [citation:1]."})
    else:
        first = [{"id": 1, "type": "THINK", "content": "Considering", "elapsed_secs": None}] if thinking else \
            [{"id": 1, "type": "RESPONSE", "content": "Hello"}]
        events.append({"v": {"response": {"message_id": response_id, "parent_id": request_id, "status": "WIP",
                                          "thinking_enabled": thinking, "fragments": first}}})
        if thinking:
            events.append({"p": "response/fragments/-1/content", "o": "APPEND", "v": " the question step by step."})
            events.append({"p": "response/fragments/-1/elapsed_secs", "o": "SET", "v": 3.2})
            events.append({"p": "response/fragments", "o": "APPEND", "v": [{"id": 2, "type": "RESPONSE", "content": "Thought it"}]})
            events.append({"p": "response/fragments/-1/content", "o": "APPEND", "v": " through: 17 × 23 = 391."})
        else:
            events.append({"p": "response/fragments/-1/content", "o": "APPEND", "v": " from the E2E fixture."})
    events.append({"p": "response/status", "o": "SET", "v": "FINISHED"})
    ready = f"event: ready\ndata: {json.dumps({'request_message_id': request_id, 'response_message_id': response_id})}\n\n"
    return ready + "".join(sse(e) for e in events) + "data: [DONE]\n\n"


def text_reply(text, request_id, response_id):
    cut = min(12, len(text))
    events = [{"v": {"response": {"message_id": response_id, "parent_id": request_id, "status": "WIP",
                                  "fragments": [{"id": 1, "type": "RESPONSE", "content": text[:cut]}]}}},
              {"p": "response/fragments/-1/content", "o": "APPEND", "v": text[cut:]},
              {"p": "response/status", "o": "SET", "v": "FINISHED"}]
    ready = f"event: ready\ndata: {json.dumps({'request_message_id': request_id, 'response_message_id': response_id})}\n\n"
    return ready + "".join(sse(e) for e in events) + "data: [DONE]\n\n"


def listed_tools(prompt):
    section = prompt.split("\nTools:\n", 1)[-1].split("\n\nUser request:", 1)[0] if "\nTools:\n" in prompt else ""
    return sorted(set(re.findall(r"^- ([A-Za-z0-9_-]+)\(", section, re.M)))


def agent_reply(prompt):
    """First agent message: call the requested tool (even a disabled one, to prove the app rejects it)."""
    request = prompt.rsplit("User request:\n", 1)[-1]
    match = re.search(r"E2E-TOOL:([A-Za-z0-9_]+)", request)
    if not match:
        return "No tool is needed for this request."
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
WORKER = ("self.onmessage = function (event) {\n"
          "  var data = event.data || {};\n"
          "  if (data.type === 'pow-challenge') self.postMessage({type: 'pow-answer', answer: {answer: 0}});\n"
          "};\n")
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
            if host == "fe-static.deepseek.com" and path == WORKER_PATH:
                self.note()
                return self.send(200, WORKER, "application/javascript")
            if host == "api.deepseek.com":
                return self.api(method, path, raw)
            if host == "html.duckduckgo.com" and path.startswith("/html"):
                self.note(query_present=bool(parse_qs(urlsplit(self.path).query).get("q")))
                return self.send(200, SEARCH_PAGE, "text/html; charset=utf-8")
            if host in ("example.com", "www.example.com") and path == "/report":
                self.note()
                return self.send(200, REPORT_PAGE, "text/html; charset=utf-8")
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
        body = json.loads(raw or b"{}")
        if path == "/api/v0/chat_session/create":
            session = self.state.next_session()
            self.note(session=session)
            return self.send(200, envelope({"chat_session": {"id": session}}))
        if path == "/api/v0/chat/create_pow_challenge":
            self.note(target=body.get("target_path"))
            return self.send(200, envelope({"challenge": {"algorithm": "DeepSeekHashV1", "challenge": "e2e", "salt": "e2e-salt",
                                                          "difficulty": 64, "expire_at": int(time.time() * 1000) + 600_000,
                                                          "signature": "e2e-signature"}}))
        if path in ("/api/v0/chat_session/update_title", "/api/v0/chat_session/delete"):
            self.note()
            return self.send(200, envelope(None))
        if path == "/api/v0/file/upload_file":
            self.note(upload_bytes=len(raw))
            return self.send(200, envelope({"file": {"id": f"e2e-file-{len(raw)}"}}))
        if path == "/api/v0/chat/completion":
            return self.completion(body)
        self.note(expected=False)
        return self.send(404, {"error": "unknown API"})

    def completion(self, body):
        prompt = str(body.get("prompt", ""))
        thinking, search = bool(body.get("thinking_enabled")), bool(body.get("search_enabled"))
        request_id, response_id = self.state.next_ids()
        if "E2E-EXPIRE" in prompt:
            self.state.revoked.add(self.token())
            self.note(scenario="expire", thinking=thinking, search=search, has_pow=bool(self.headers.get("X-DS-PoW-Response")))
            return self.send(401, {"code": 40003, "msg": "expired", "data": None})
        if prompt.startswith(FENCE + "tool_result"):
            text, name, ok = tool_result_reply(prompt)
            self.note(kind="tool_result", tool=name, ok=ok, has_pow=bool(self.headers.get("X-DS-PoW-Response")))
            return self.stream(text_reply(text, request_id, response_id))
        if prompt.startswith("[Agent mode"):
            tools = listed_tools(prompt)
            reply = agent_reply(prompt)
            requested = re.search(r'"name": "([A-Za-z0-9_-]+)"', reply)
            self.note(kind="agent", tools=tools, requested=requested.group(1) if requested else None,
                      has_pow=bool(self.headers.get("X-DS-PoW-Response")), refs=len(body.get("ref_file_ids") or []))
            return self.stream(text_reply(reply, request_id, response_id))
        self.note(kind="chat", thinking=thinking, search=search, refs=len(body.get("ref_file_ids") or []),
                  has_pow=bool(self.headers.get("X-DS-PoW-Response")), slow="E2E-SLOW" in prompt)
        stream = web_reply(prompt, thinking, search, request_id, response_id)
        return self.stream(stream, slow="E2E-SLOW" in prompt)

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
        if last.get("role") == "tool":
            self.note(kind="api_tool_result", tools=tools)
            text = chunk({"role": "assistant", "content": "Native tool call finished."}) + chunk({}, "stop") + "data: [DONE]\n\n"
            return self.stream(text)
        content = last.get("content") if isinstance(last.get("content"), str) else json.dumps(last.get("content"))
        match = re.search(r"E2E-TOOL:([A-Za-z0-9_]+)", content or "")
        if match and tools:
            scenario = match.group(1)
            name = TOOL_ALIASES.get(scenario, scenario)
            arguments = json.dumps(TOOL_CALLS.get(scenario, {}))
            self.note(kind="api_agent", tools=tools, requested=name)
            text = (chunk({"role": "assistant", "content": None, "tool_calls": [{"index": 0, "id": "call_e2e_1", "type": "function",
                                                                                 "function": {"name": name, "arguments": ""}}]})
                    + chunk({"tool_calls": [{"index": 0, "function": {"arguments": arguments}}]})
                    + chunk({}, "tool_calls") + "data: [DONE]\n\n")
            return self.stream(text)
        self.note(kind="api_chat", tools=tools)
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
