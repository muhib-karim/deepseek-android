# v0.6.0 "Agent" (2026-10-18)

DeepSeek can now use tools on the phone, in account-mode web chat and with an API key.
Validation status: implementation and tests complete; **CI and emulator results pending** (see the
feature table in [AGENT_MODE.md](AGENT_MODE.md)).

## New

- **Agent toggle** in the composer next to Think and Search. Tool steps appear as compact rows
  (expand for input and result); reasoning stays collapsible; at most 8 model replies per request.
- **One agent loop, two backends.** API-key mode uses native function calling on
  `/chat/completions`. Account-mode web chat (no native tools for web-session tokens) uses a strict
  fenced ```` ```tool ```` JSON protocol: tool results go back as the next message in the same
  chat; a malformed call gets one automatic repair prompt, then is shown as text.
- **On-device Python** via Chaquopy 17.1.0 with Python 3.13, numpy 1.26.2 and matplotlib 3.8.4
  (arm64-v8a, x86_64): `run_python` in a per-chat folder and namespace, in a separate worker
  process that is killed on timeout or Stop; open figures are saved as PNG cards. **Run** buttons
  on Python code blocks.
- **pip_install** for pure-Python packages from PyPI (SHA-256 verified, native code refused,
  dependencies followed) after per-download approval; **Python toolsets** list with Remove.
- **Files:** `create_pdf {title, markdown}` with a JVM-tested layout engine (headings, lists,
  tables with repeated headers, code, quotes, page breaks, wrapping, images); `write_file` for
  DOCX, CSV, Markdown, HTML, JSON and text; dsh-style `write`, `read` and `present`. Files are
  cards with preview, **Open** (in-app viewer), **Save** (Downloads) and **Share**. **Export PDF**
  on every assistant message.
- **Skills:** install from an https link or a .zip file (`manifest.json`, `SKILL.md`, optional
  `main.py` functions as tools), enable, disable, remove. Built in: `pdf-report`,
  `data-analysis`, `web-summary`. Enabled skills are listed to the model by name and description.
- The API-key client gets the same agent tools and skills.

## Changed

- versionCode 7, versionName 0.6.0.
- ABIs: arm64-v8a and x86_64 only (Chaquopy Python 3.13 has no 32-bit builds); the APK is
  several tens of MB larger.
- CI, emulator and release workflows install Python 3.13 (actions/setup-python) for Chaquopy.
- `create_pdf` takes `{title, markdown, file_path?}`; conversation export input is unchanged.

## Tests added

`ToolCallParserTest`, `WebAgentLoopTest`, `NativeToolCallStreamTest`, `PdfLayoutEngineTest`,
`DocumentWritersTest`, `AgentFileToolsTest`, `PythonToolsTest`, `SkillsTest` (JVM);
`AgentModeTest` (mocked DeepSeek server: create_pdf tool block → PDF → card; approval denial) and
`PythonRuntimeTest` (Chaquopy on the emulator: 2 + 2 = 4, matplotlib, sockets, timeout, skill).

## Not included / known limits

- No native tools in account mode (would need a DeepSeek Platform OAuth grant).
- Images are embedded in PDFs only; DOCX shows a captioned placeholder.
- Python network blocking is best effort (socket-level); use `web_fetch` for pages.
- No live agent run against chat.deepseek.com has been recorded yet.
