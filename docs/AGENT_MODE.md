# Agent mode (v0.6.0)

DeepSeek Android v0.6.0 adds an agent: DeepSeek can call tools on the phone (on-device Python,
files, PDF/DOCX/CSV documents, installable skills, web pages) in both the account-mode web chat
and the API-key client. There is one `AgentLoop` and one `ToolRegistry` in `:core`; only the way a
tool call travels between the model and the app differs per backend.

**Since v1.2.0 ("Agent-first")** agent mode is on by default on both routes (off only after the person switches it off),
the API key is offered on the welcome screen ("API mode"), tool calls render as collapsible cards, API mode routes tool
turns to `deepseek-chat`, and each reply may take up to Settings > Agent > Maximum steps (default 8, 1 to 20) with a step
counter. See [RELEASE_NOTES_120.md](RELEASE_NOTES_120.md).

## Facts this design rests on

Verified on 2026-10-18 from DeepSeek's published harness,
[deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) ("dsh", MIT,
default branch `master`):

| Fact | Source path in deepseek-harness |
| --- | --- |
| The DeepSeek adapter speaks the Anthropic-style Messages API; the official root is `https://api.deepseek.com/anthropic` and requests go to `/v1/messages`. Text, thinking, tool calls and tool results are content blocks (`tool_use` / `tool_result`); reasoning effort is sent as `output_config.effort`. API keys are sent as `x-api-key`; account tokens as `x-dsh-auth-token` without a Bearer prefix. | `packages/llm/llm-deepseek/README.md` |
| The account route authenticates with `x-dsh-auth-token`; HTTP 401 maps to `ACCOUNT_TOKEN_INVALID`, and the route never falls back to an API key. | `packages/llm/llm-deepseek-account/README.md` |
| The account token is a DeepSeek **Platform** OAuth grant obtained with PKCE (S256) through a loopback redirect on `127.0.0.1`. | `packages/credentials/deepseek-account-platform/src/index.ts` |
| Tool names and schemas: `run_code {code, description, timeoutMs?}`, `write {file_path, content}`, `read {file_path, offset?, limit?}`, `skill {name}`, `present {files: [{path, description?}]}`, `web_fetch {url}`. | `docs/tool-catalog.md` |

Also verified by the project owner on 2026-10-18: the free chat web-session token that account mode
uses (`localStorage.userToken` from chat.deepseek.com) is refused by dsh's account route with HTTP 401
"invalid dsh token". **Account-mode web chat therefore has no native tool calling.** The
OpenAI-compatible `https://api.deepseek.com/chat/completions` endpoint used by the app's API-key
client does support function calling (`tools`, streamed `tool_calls`, role `tool` results).

## Two backends, one loop

```
                   ┌──────────────── :core AgentLoop ────────────────┐
 user message ───▶ │ stream assistant step ─▶ tool calls? ─▶ guard ─▶ │ ─▶ tools (ToolRegistry)
                   │        ▲                  │ approve if needed     │      run_python, write, read,
                   │        └──── results ◀────┘ per-tool timeout      │      present, create_pdf,
                   └───────────────────────▲─────────────────────────┘      write_file, skill,
                                           │ LlmClient                     web_fetch, pip_install,
               ┌───────────────────────────┴──────────────────────┐       skill functions
   API key:  OpenAiCompatibleClient                    Account mode: WebToolProtocolClient
   native tools / tool_calls / role "tool"             fenced ```tool blocks over the web chat
```

1. **Native function calling (API-key client).** `OpenAiCompatibleClient` sends the registry as the
   `tools` array with `tool_choice: "auto"`; `StreamAccumulator` assembles streamed `tool_calls`
   deltas by index; results go back as `role: "tool"` messages with `tool_call_id`.
2. **Text protocol (account-mode web chat).** `WebToolProtocolClient` implements the same
   `LlmClient` interface over a `WebChatTransport` that sends one message in the current web chat.
   The app prepends instructions and the tool list to the user's message; the model asks for a tool
   by **ending its reply with exactly one fenced block**:

   ````
   ```tool
   {"name": "create_pdf", "arguments": {"title": "Q3 report", "markdown": "# Q3\n..."}}
   ```
   ````

   `ToolCallProtocol.parse` (in `:core`) accepts only that shape: one block, last in the reply,
   whose JSON object has exactly `name` (a listed tool name) and `arguments` (an object). It
   tolerates whitespace around fences and JSON, `\r\n`, and raw newlines or tabs inside JSON
   strings, and scans strings so backticks inside arguments (for example Markdown with code
   fences) never close the block. Everything else is rejected. The app runs the tool and sends the
   result back as the **next message in the same chat** (`parent_message_id` chain):

   ````
   ```tool_result
   {"name": "create_pdf", "ok": true, "content": "Created report.pdf (2 pages)"}
   ```
   ````

   JSON results (for example run_python's stdout/stderr envelope) are embedded as JSON; results
   are capped at 12,000 characters. A malformed block (bad JSON, extra keys, several blocks, text
   after the block, a ```` ```json ```` fence around a known tool) triggers **one automatic repair
   prompt**; if the second reply is still malformed it is shown as plain text. While a reply
   streams, text from an opening ```` ```tool ```` line onwards is hidden, so users never see raw
   calls.

Shared rules: at most **8 assistant replies per request** (then "Stopped after 8 assistant
steps"), a **per-tool timeout of 180 s** measured on a real clock, and the existing `GuardPolicy`
for approvals. Reasoning (DeepThink) streams as collapsible "Thought" rows; the Search toggle
applies to the first message of an agent turn only.

## Tools

v0.6.0 tool set. In v1.0.0 the account route adds more tools and switches, and `run_python`, `pip_install` and skill
functions become trusted execution (offered only with Trusted execution on, asking every call); see
[TOOLS.md](TOOLS.md), which supersedes the approval column below.

| Tool | dsh equivalent | What it does | Approval |
| --- | --- | --- | --- |
| `run_python {code, timeout_s}` | `run_code` (Python instead of TypeScript) | Python 3.13 in the chat's folder; numpy and matplotlib built in; open figures saved as PNG; variables persist per chat | No (runs in the app sandbox; sockets refused) |
| `pip_install {packages}` | — | Pure-Python wheels from PyPI into the app's private site folder | **Every call** |
| `write {file_path, content}` | `write` | Create or replace a UTF-8 file in the chat folder | No |
| `read {file_path, offset, limit}` | `read` | Line-numbered text from the chat folder | No |
| `present {files: [{path, description}]}` | `present` | Show existing files as cards (at most 4) | No |
| `skill {name}` | `skill` | Load an enabled skill's SKILL.md | No |
| `web_fetch {url}` | `web_fetch` | Public HTTPS page as untrusted text | Query strings, URLs over 200 characters and hosts not returned by this turn's search |
| `create_pdf {title, markdown, file_path?}` | — | Markdown laid out as an A4 PDF | No |
| `write_file {file_path, content, title?}` | — | `.docx` (from Markdown), `.csv`, `.md`, `.txt`, `.html`, `.json` as a card | No |
| `<skill>__<tool>` | — | A function from an enabled skill's `main.py`, run like run_python | No |

No tool writes outside the app's private storage. Saving to Downloads (MediaStore on Android 10+,
the system file picker before that), sharing and opening in another app are explicit user actions
on a file card. Agent files live in `files/workspace/account/<chat id>/` (account mode) or
`files/workspace/chats/<chat id>/` (API-key client); account files are deleted with the chat and on
logout.

### Files and PDFs

The PDF layout engine is in `:core` (`PdfLayoutEngine`) and has no Android dependency: it wraps
styled runs with a pluggable text measurer and paginates headings, paragraphs with bold, italic
and code runs, nested bullet and numbered lists, block quotes, shaded code blocks (split across
pages), bordered tables (narrow columns keep their width, a repeated header row on each page,
rows taller than a page split by lines), rules, explicit page breaks (`\pagebreak`, `\newpage`,
`<!-- pagebreak -->`) and images on their own line (`![caption](chart.png)` from the chat
folder). Android replays the resulting drawing operations on `PdfDocument`. "Export PDF" on any
assistant message uses the same engine. DOCX output is a minimal valid OOXML package
(`DocxWriter`); images become a captioned placeholder there.

Charts and images come from **matplotlib through Chaquopy** (feasible: Chaquopy's package index
has matplotlib 3.8.4 and numpy 1.26.2 wheels for CPython 3.13 on arm64-v8a and x86_64), so no
separate Kotlin chart renderer was added for agent mode.

### On-device Python

- [Chaquopy](https://chaquo.com/chaquopy/) 17.1.0 (MIT; released 2026-10-12; supports AGP 7.3–9.4
  and Python 3.10–3.15) with **Python 3.13** — the newest version with matplotlib wheels, and the
  one Chaquopy recommends for 16 KB page devices. `abiFilters` are `arm64-v8a` and `x86_64`.
- The interpreter runs in `PythonService`, a non-exported service in its own `:python` process.
  The app counts the user timeout from interpreter start (startup allowance 60 s) and **kills the
  worker process** on timeout or Stop, so runaway code cannot continue; the next run starts a fresh
  interpreter (that chat's variables are cleared).
- Output is capped at 16,000 characters; a trailing expression is echoed like a REPL; `socket`
  connections and DNS lookups raise `PermissionError` (best effort: native code could bypass it).
- Chaquopy's runtime has no pip, so `pip_install` uses `WheelInstaller` (`:core`): it reads PyPI's
  JSON API, picks the newest `py3-none-any` wheel matching the specifiers and Python 3.13,
  verifies PyPI's SHA-256, refuses native code and unsafe archive paths, follows `Requires-Dist`
  with PEP 508 markers evaluated for Android CPython, and records files so **Settings → Skills →
  Python toolsets** can list and remove packages. Built-in packages are never downloaded.

### Skills

A skill is a folder or `.zip` (one top-level folder allowed) containing:

- `manifest.json`: `name` (lowercase, digits, hyphens), `description`, `version`, optional
  `tools` (each `name`, `description`, optional `function` in main.py, JSON-schema `parameters`)
  and optional `pip` (pure-Python requirements installed with the skill);
- `SKILL.md`: the instructions the `skill` tool returns;
- optional `main.py`: functions exposed as tools named `<skill>__<tool>` and run in the chat folder.

Install from a public HTTPS link or a file on **Skills** (drawer or Settings); the app shows the
skill's tools and packages and installs only after confirmation. Enabled skills are listed in the
agent instructions by name and description (both backends) and load on demand. Built-ins:
`pdf-report`, `data-analysis` (`describe_csv`, `chart_csv`) and `web-summary`. Built-ins can be
disabled but not removed.

## Trade-offs

- **Reliability.** Native function calling is constrained decoding; the text protocol relies on the
  web model following instructions. The strict parser, one repair prompt and the 8-reply limit
  bound the damage, but a model can still answer without calling a tool or mis-shape arguments.
- **Visible history.** Every tool round trip is a real message in the user's DeepSeek web chat
  (the instructions, the tool blocks and the `tool_result` messages appear on chat.deepseek.com).
- **Latency and context.** One tool call per reply and a full instruction block on each user turn
  (web chat has no system role) cost time and context; long tool results are truncated.
- **Native tools in account mode** would need a DeepSeek Platform OAuth grant (dsh's PKCE loopback
  flow) instead of the web session; that adapter is not part of v0.6.0.
- **APK size.** Bundling Python, numpy and matplotlib for two ABIs makes the APK several tens of MB
  larger; 32-bit-only devices (armeabi-v7a) are no longer supported.

## Feature status

Results recorded 2026-10-18 from PR #18 CI on head e60e723 (android job: core + app unit tests, lint, debug + signed release builds; smoke job: 62/62 instrumented tests on an API 34 x86_64 emulator).

| # | Feature | Evidence | Status |
| --- | --- | --- | --- |
| 1 | Fenced tool-call parser (valid, whitespace, malformed, multiple blocks, nested backticks) | `ToolCallParserTest` | PASS (CI unit tests, run 39147503682) |
| 2 | AgentLoop over the web backend: tool result round trip, step limit, per-tool timeout, repair | `WebAgentLoopTest` | PASS (CI unit tests, run 39147503682) |
| 3 | Native tool_calls stream assembly and `tool` results on the OpenAI-compatible client | `NativeToolCallStreamTest` | PASS (CI unit tests, run 39147503682) |
| 4 | PDF layout engine: pagination, wrapping, tables, lists, code, images, page breaks | `PdfLayoutEngineTest` | PASS (CI unit tests, run 39147503682) |
| 5 | DOCX and CSV writers | `DocumentWritersTest` | PASS (CI unit tests, run 39147503682) |
| 6 | write / read / present / write_file in the chat folder; guard decisions | `AgentFileToolsTest` | PASS (CI unit tests, run 39147503682) |
| 7 | run_python contract, PEP 440/508 helpers, wheel installer (checksums, native refusal, dependencies) | `PythonToolsTest` | PASS (CI unit tests, run 39147503682) |
| 8 | Skill manifest loader, zip/folder packs, store, skill tool, main.py harness | `SkillsTest` | PASS (CI unit tests, run 39147503682) |
| 9 | Account mode: mocked model emits create_pdf → PDF with ≥ 1 page → artifact card shown | `AgentModeTest.createPdfToolBlockProducesAPdfAndAnArtifactCard` | PASS (emulator API 34 x86_64, 62/62 androidTests, run 39147503705) |
| 10 | Account mode: web_fetch asks first; a denial is reported to the model | `AgentModeTest.networkToolsAskFirstAndADenialIsReportedToTheModel` | PASS (emulator API 34 x86_64, 62/62 androidTests, run 39147503705) |
| 11 | run_python prints 2 + 2 = 4 on the emulator (skipped with an assumption message on other ABIs) | `PythonRuntimeTest.printsTwoPlusTwo` | PASS (emulator API 34 x86_64, 62/62 androidTests, run 39147503705) |
| 12 | matplotlib figure becomes a PNG card; sockets refused; timeout kills and restarts the worker | `PythonRuntimeTest` | PASS (emulator API 34 x86_64, 62/62 androidTests, run 39147503705) |
| 13 | Built-in data-analysis skill runs its main.py on the device | `PythonRuntimeTest.builtInDataAnalysisSkillRunsItsMainPy` | PASS (emulator API 34 x86_64, 62/62 androidTests, run 39147503705) |
| 14 | Debug and signed release APK build with Chaquopy; merged manifest keeps INTERNET + RECORD_AUDIO only | CI and release workflows | PASS (CI assembleDebug + signed assembleRelease, run 39147503682; release.yml) |
| 15 | Live: agent turn on chat.deepseek.com with a real account | not automated | NOT RUN (manual live check on chat.deepseek.com, not automated) |
