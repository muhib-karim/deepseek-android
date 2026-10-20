# Tools and plugins (v1.0.0)

Agent mode lets DeepSeek use tools on the phone: it makes real files (code, Markdown, CSV, Word, PDF, Excel, PowerPoint,
SVG and PNG), runs code in a sandbox, searches and reads the web, and calls remote MCP tools you add. Every tool has a
switch in **Plugins & tools** (drawer, or Settings > Agent). The API-key client's Settings > Tools shows the same
switches. One list, `app/src/main/kotlin/app/dsandroid/tools/ToolCatalog.kt`, feeds both screens and both agent runs.

## Two routes

| Route | Used by | How tools are called |
|---|---|---|
| **Account** | Signed in with a DeepSeek account (the default) | DeepSeek's web chat has no function-calling API, so the app sends the tool list and rules as text with each agent request and reads one fenced `tool` block per reply (`core/.../agent/ToolCallProtocol.kt`, `WebToolProtocolClient.kt`). Results go back as the next message in the same chat. |
| **API** | The separate API-key client (Settings > Advanced) | DeepSeek's API function calling (`tools` in the request, `tool_calls` in the reply). |

Both routes run the same `AgentLoop` with the same guard (`core/.../guard/GuardPolicy.kt`), approval dialogs and
workspace rules. Tools that need the API-key client's own screens are offered only there (last table).

## Rules for every tool

- **A switched-off tool is not offered** (it is left out of the tool list sent to the model) and **is refused if the
  model asks for it anyway**. The switches are read again before a tool runs, so a tool switched off while its approval
  dialog was showing does not run.
- **Files stay in the chat's folder.** Every workspace tool reads and writes only inside the current chat's private
  folder (per account), so files are deleted with their chat or account and never mix between chats.
- **Asks first** means a dialog shows what will happen (the query, the address, the code, the server) and nothing runs
  until you tap Allow once. Deny tells the model it was declined.
- Tool output, files and web pages are treated as untrusted data, not instructions.

## Account route

| Switch | Tool names | What it does | Asks first |
|---|---|---|---|
| Write files | `write` | Creates or replaces text and code files in the chat's folder | No |
| Read files | `read` | Reads a file in the chat's folder | No |
| Show files | `present` | Shows finished files as cards (preview, Open, Edit, Download, Save as, Share) | No |
| Documents | `write_file` | Word (.docx from Markdown), CSV, Markdown, text, HTML and JSON files | No |
| PDF | `create_pdf` | Lays out Markdown (headings, lists, tables, code, images, page breaks) as a PDF | No |
| Excel workbooks | `create_xlsx` (alias `create_spreadsheet`) | Sheets with text and number cells | No |
| PowerPoint decks | `create_pptx` (alias `create_presentation`) | Title and content slides with speaker notes | No |
| Charts | `create_chart` (alias `render_chart`) | Bar, line and pie charts as SVG | No |
| PNG images | `create_image` | Renders an SVG drawing or chart as PNG | No |
| ZIP archives | `zip_files` | Packs files from the chat's folder | No |
| Code sandbox | `code_interpreter` | JavaScript, or Python (Pyodide 0.26.4) in a disposable WebView worker: no network, app files or Android access; 15 s and 16,000 characters of output | Before the one-time Python runtime download (each file checked against a pinned SHA-256) |
| Skills | `skill` | Loads the instructions of an enabled skill | No |
| Web search | `web_search` | Searches (DuckDuckGo, or the SearXNG server set in the API-key client) and numbers the sources | Every search, showing the query and the service |
| Read web pages | `web_fetch` (alias `visit_web`) | Reads a public HTTPS page | Pages that did not come from the current search, long addresses and query strings |
| Python on this phone | `run_python` | Python 3.13 with numpy and matplotlib in the app's Python worker | Every run (trusted execution) |
| Python packages | `pip_install` | Pure-Python wheels from PyPI | Every download (trusted execution) |
| Skill functions | `<skill>__<function>` | A skill's Python functions | Every run (trusted execution) |
| Remote tools (MCP) | `mcp_<server>_<tool>` | Tools of HTTPS MCP servers you add in Plugins & tools | Every call, showing the server and the arguments; the answer goes to DeepSeek |

### Trusted execution

`run_python`, `pip_install` and skill functions run native code with this app's rights. That is not a sandbox, so they
are **off until you switch on Trusted execution** in Plugins & tools (off on a fresh install, never restored from a
backup), and then every call still asks first. The Run button on a Python code block in a reply is an explicit action
and runs without a second prompt. The code sandbox needs no such permission.

### Files

Generated files appear as cards under the reply and in **Files in this chat** (drawer). A card offers Open (in-app
viewer, or the editor and artifacts panel for text, HTML, SVG and Markdown), Edit for text files, Download (to
Downloads/DeepSeek on Android 10 and later, the system file picker before), Save as (the system file picker with the
file's own name and type) and Share (the system share sheet, with a read grant for that one file). Saving and sharing
are always your action, never the model's.

## API-key client only

These tools need the API-key client's screens or its function calling. The account route lists them as "In the API-key
client" and never offers them.

| Switch | Tool names |
|---|---|
| Word documents (structured) | `create_docx`, `create_document` |
| Markdown files, CSV files, SVG images, Text files | `create_markdown`, `create_csv`, `create_svg`, `create_file` |
| Browse the workspace, Look inside ZIP files | `read_file`, `list_dir`, `list_files`, `list_zip` |
| Artifact canvas, Web projects | `create_artifact`, `create_web_project` |
| In-app browser | `open_url`, `read_page`, `click`, `type`, `submit`, `scroll`, `back` |
| Draft a text message, Draft an email, Add a calendar event, Open a map | `draft_sms`, `draft_email`, `add_calendar_event`, `open_map` |
| Memory, Scheduled prompts, Parallel research | `remember`, `schedule_prompt`, `parallel_agents` |

Connectors (read-only search and read tools for services you configure) are also API-key client only; see
[CONNECTORS.md](CONNECTORS.md).

## Tests

- `core`: `AgentLoopTest` (switched-off tools leave the schema, are refused, and do not run after a pending approval),
  `ToolCatalogTest`, `TrustedToolTest`, `ChatFolderToolsTest`, runtime pin tests.
- Emulator: `PluginsToolsTest` (switches survive a restart and leave the prompt, refusals, routes, remote tools,
  trusted execution), `FileJourneyTest` (every format's bytes, Save as, Share, Download, restart), `SandboxExecutionTest`
  and `SandboxPythonTest`, `AgentModeTest`.
- Release E2E: `AgentJourneyTest` with the coverage table [`e2e/coverage.json`](../e2e/coverage.json); see
  [EVIDENCE_v1.md](EVIDENCE_v1.md).

Status: written for v1.0.0; results come from CI and are recorded in [RELEASE_NOTES_100.md](RELEASE_NOTES_100.md).
Nothing was compiled or run locally.
