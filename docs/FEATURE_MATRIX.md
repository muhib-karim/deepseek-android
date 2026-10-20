# Feature matrix

What the big assistant apps (ChatGPT, Claude, Kimi) do on Android, and when DeepSeek Android (Unofficial) gets each one.
The goal: the official DeepSeek app only chats; this app lets DeepSeek *do* things — call tools mid-conversation and show the results inside the chat.

Legend: ✓ = the app has it · — = it doesn't · **Release** = the version of this app that ships it.
v0.1.0, v0.2.0 and v0.3.0 are released: every row below has shipped. The v0.4.0 section
near the end plans the next release, which hardens and packages what is already here.

## Core: the agent loop

| # | Feature | ChatGPT | Claude | Kimi | Release |
|---|---|---|---|---|---|
| 1 | Real function calling: the model calls tools, reads results, keeps going (multi-step) | ✓ | ✓ | ✓ | v0.2.0 |
| 2 | Tool-call cards inline in the chat (what ran, inputs, status, output; collapsible) | ✓ | ✓ | ✓ | v0.2.0 |
| 3 | Chat / Agent mode toggle (plain chat vs tool-using agent) | ✓ | ✓ | ✓ | v0.2.0 |
| 4 | Per-action confirm for anything that leaves the app or changes data | ✓ | ✓ | ✓ | v0.2.0 |
| 5 | Stop, retry, edit-and-resend, regenerate | ✓ | ✓ | ✓ | v0.2.0 |
| 6 | Thinking display (live reasoning, collapsible, time taken) | ✓ | ✓ | ✓ | v0.2.0 |

## Research and the web

| # | Feature | ChatGPT | Claude | Kimi | Release |
|---|---|---|---|---|---|
| 7 | Web search with inline numbered citations and a sources sheet | ✓ | ✓ | ✓ | v0.2.0 |
| 8 | Read a URL (fetch a page) with citation | ✓ | ✓ | ✓ | v0.2.0 |
| 9 | Deep research mode: plan → many searches → cited report | ✓ | ✓ | ✓ | v0.2.0 |
| 10 | Research report export (Markdown / HTML / PDF) | ✓ | ✓ | ✓ | v0.2.0 |

## Code, artifacts and outputs

| # | Feature | ChatGPT | Claude | Kimi | Release |
|---|---|---|---|---|---|
| 11 | Code interpreter in a sandbox, output shown in chat (stdout, tables) | ✓ | ✓ | ✓ | v0.2.0 |
| 12 | Charts from data, rendered in the chat | ✓ | ✓ | ✓ | v0.2.0 |
| 13 | Artifacts / canvas panel: live HTML, SVG, Mermaid, Markdown preview | ✓ | ✓ | ✓ | v0.2.0 |
| 14 | Edit an artifact and re-run it; version history | ✓ | ✓ | — | v0.2.0 |
| 15 | Downloadable generated files (.md .html .csv .txt .svg .json) via save/share | ✓ | ✓ | ✓ | v0.2.0 |
| 16 | Syntax-highlighted code blocks with copy | ✓ | ✓ | ✓ | v0.1.0 |
| 17 | Office files out: DOCX, XLSX, PPTX (slides) generation | ✓ | ✓ | ✓ | v0.3.0 — Pass D |
| 18 | PDF generation | ✓ | ✓ | ✓ | v0.3.0 — Pass D |

## Inputs

| # | Feature | ChatGPT | Claude | Kimi | Release |
|---|---|---|---|---|---|
| 19 | File input: PDF, Word, Excel, PowerPoint, text | ✓ | ✓ | ✓ | v0.1.0 |
| 20 | Image input (text extraction on-device; vision when the model supports it) | ✓ | ✓ | ✓ | v0.2.0 |
| 21 | Many files at once (batch analysis, ~50 files) | ✓ | ✓ | ✓ | v0.3.0 — Pass D |
| 22 | Camera input (snap a photo or document) | ✓ | ✓ | ✓ | v0.3.0 — Pass D |
| 23 | Share into the app from any other app (share target) | ✓ | ✓ | ✓ | v0.3.0 — Pass D |
| 24 | Voice typing (press and hold) | ✓ | ✓ | ✓ | v0.1.0 |

## Memory, projects, personalisation

| # | Feature | ChatGPT | Claude | Kimi | Release |
|---|---|---|---|---|---|
| 25 | Memory: saved facts the user can see, edit and switch off | ✓ | ✓ | ✓ | v0.2.0 |
| 26 | Custom instructions (global) and per-chat system prompt | ✓ | ✓ | ✓ | v0.2.0 |
| 27 | Projects: folders with shared files and instructions | ✓ | ✓ | — | v0.2.0 |
| 28 | Temporary (no-history) chat | ✓ | ✓ | — | v0.2.0 |
| 29 | Saved assistants / personas | ✓ | ✓ | ✓ | v0.3.0 — Pass E |
| 30 | Response styles (concise, explanatory, formal) | ✓ | ✓ | — | v0.3.0 — Pass D |

## Tools and integrations

| # | Feature | ChatGPT | Claude | Kimi | Release |
|---|---|---|---|---|---|
| 31 | Custom tools (MCP-style): add remote HTTPS tool servers, each call confirmed | ✓ | ✓ | — | v0.2.0 |
| 32 | Built-in tool settings: turn each tool on/off | ✓ | ✓ | ✓ | v0.2.0 |
| 33 | Scheduled tasks / reminders run by the assistant | ✓ | — | — | v0.3.0 — Pass E |

## Voice

| # | Feature | ChatGPT | Claude | Kimi | Release |
|---|---|---|---|---|---|
| 34 | Voice mode: hands-free spoken conversation | ✓ | ✓ | ✓ | v0.3.0 — Pass F |
| 35 | Read aloud with sentence highlighting | ✓ | ✓ | ✓ | v0.3.0 — Pass F |

## Phone actions (every action behind a confirm)

| # | Feature | ChatGPT | Claude | Kimi | Release |
|---|---|---|---|---|---|
| 36 | Draft SMS / email, add calendar events, set alarms, open maps | ✓ | — | — | v0.3.0 — Pass F |
| 37 | Screen awareness (opt-in screenshot of the current screen) | ✓ | — | — | v0.3.0 — Pass F |
| 38 | Floating assistant bubble / quick launch | ✓ | — | — | v0.3.0 — Pass F |
| 39 | Home-screen widget | ✓ | ✓ | — | v0.3.0 — Pass F |

## Conversations and sharing

| # | Feature | ChatGPT | Claude | Kimi | Release |
|---|---|---|---|---|---|
| 40 | Saved history, search, rename, delete | ✓ | ✓ | ✓ | v0.1.0 |
| 41 | Pin chats | ✓ | ✓ | ✓ | v0.2.0 |
| 42 | Share / export a conversation (Markdown, HTML, PDF, share sheet) | ✓ | ✓ | ✓ | v0.2.0 |

## Account and app

| # | Feature | ChatGPT | Claude | Kimi | Release |
|---|---|---|---|---|---|
| 43 | Sign in with your own account (system browser) or API key | ✓ | ✓ | ✓ | v0.1.0 |
| 44 | Model / mode picker (fast vs reasoning) | ✓ | ✓ | ✓ | v0.1.0 |
| 45 | Several accounts listed, switched manually only (never automatic) | ✓ | ✓ | — | v0.3.0 — Pass E |
| 46 | Language toggle; Bangla interface | ✓ | ✓ | ✓ | v0.2.0 |
| 47 | Text size and display controls; dark/light | ✓ | ✓ | ✓ | v0.2.0 |

## Agent extras

| # | Feature | ChatGPT | Claude | Kimi | Release |
|---|---|---|---|---|---|
| 48 | Browser agent: the model opens and reads pages in an in-app browser for you, every click/submit confirmed | ✓ | ✓ | ✓ | v0.3.0 — Pass E |
| 49 | Connectors to your own accounts (e.g. cloud drive, mail), read-only first, signed in by you | ✓ | ✓ | — | v0.3.0 — Pass E |
| 50 | Multi-file website / slide-deck builder in the artifacts panel, export as a zip | ✓ | ✓ | ✓ | v0.3.0 — Pass D |
| 51 | Study mode: step-by-step tutoring instead of straight answers | ✓ | ✓ | — | v0.3.0 — Pass D |
| 52 | Suggested follow-up questions under an answer | ✓ | — | ✓ | v0.3.0 — Pass D |
| 53 | Branch a conversation from any message into a new chat | ✓ | ✓ | — | v0.3.0 — Pass D |
| 54 | Library: every generated file, artifact and image from all chats in one place | ✓ | ✓ | ✓ | v0.3.0 — Pass D |
| 55 | Parallel sub-agents for big tasks: one plan, several agents at once, progress shown per agent | ✓ | — | ✓ | v0.3.0 — Pass E |
| 56 | Memory upkeep: suggested memory updates drawn from past chats, each approved by the user | ✓ | ✓ | ✓ | v0.3.0 — Pass E |
| 57 | Share the camera or screen into voice mode (vision when the model supports it) | ✓ | — | — | v0.3.0 — Pass F |

## Pass D delivery status

Rows **17, 18, 21, 22, 23, 30, 50, 51, 52, 53 and 54** — Status note:
**shipped in v0.3.0 pass D**. Released in v0.3.0 with CI green (core tests, app unit tests, lint, debug build, secret scan); on-device checks are part of the v0.4.0 emulator smoke tests (row 60).
Other v0.3.0 rows are tracked below.

## Pass E delivery status

Rows **29, 33, 45, 48, 49, 55 and 56** — Status note:
**shipped in v0.3.0 pass E**. Released in v0.3.0 with CI green (core tests, app unit tests, lint, debug build, secret scan); on-device checks are part of the v0.4.0 emulator smoke tests (row 60).

The browser supports static public pages and simple confirmed forms, with site
scripts and login disabled. The generic connector supports configurable public-client
OAuth2 PKCE and read-only GET search/read endpoints, requiring a compatible provider
registration. Scheduled prompts run without tools, notifications or exact timing;
the original account must remain manually selected.

## Pass F delivery status

Rows **34, 35, 36, 37, 38, 39 and 57** — Status note:
**shipped in v0.3.0 pass F**. Released in v0.3.0 with CI green (core tests, app unit tests, lint, debug build, secret scan); on-device checks are part of the v0.4.0 emulator smoke tests (row 60).

Voice uses foreground system speech recognition and installed offline English/Bangla
TTS, with sentence playback controls and chat transcripts. Camera and screen images
are reviewed before joining the existing attachment path. Image bytes require a
vision-capable model; the existing Latin OCR model is the text-only fallback.

Row 36 delivers SMS/email drafts, calendar insertion and maps through confirmed
standard intents. Alarms are skipped because `SET_ALARM` needs an extra install
permission. Row 37 uses fresh, single-frame MediaProjection consent on API 26–28
only while visible; API 29+ support **this app window only** via PixelCopy because
MediaProjection requires forbidden foreground-service permissions. No continuous
or background capture is provided. Row 38 delivers static/dynamic shortcuts (subject
to launcher capacity), the voice tile and ACTION_ASSIST; no floating overlay or
full VoiceInteractionService is installed. The RemoteViews widget exposes the
latest active saved title to the launcher, excluding temporary chats.

## v0.4.0 plan: hardening and packaging

Every feature above has shipped, so v0.4.0 adds no new assistant features. It makes the
existing ones easier to trust and install. Install permissions stay INTERNET only.

| # | Item | Release |
|---|---|---|
| 58 | Signed, minified release APK (R8) next to the debug APK, built in CI from a secret keystore | v0.4.0 |
| 59 | SHA-256 checksums file attached to every GitHub release | v0.4.0 |
| 60 | Emulator smoke tests in CI: launch, send a message to a fake backend, open an artifact, open settings | v0.4.0 |
| 61 | Accessibility pass: content descriptions, touch targets, font scaling up to 200%, contrast in dark mode | v0.4.0 |
| 62 | Full backup and restore of chats, projects, memory and settings to one file the user picks (no cloud) | v0.4.0 |
| 63 | Local diagnostics screen: recent errors and tool-call failures, copyable, never sent anywhere | v0.4.0 |
| 64 | README refresh: screenshots of each major screen, install steps, checksum check | v0.4.0 |

## Pass G delivery status

Rows **58–63**: implemented in this working tree for v0.4.0; CI and device validation
are pending, so this is not a release claim. Optional environment-signed R8 builds,
checksums, unsigned release CI assembly, fake-backend emulator smoke tests,
accessibility updates, confirmed SAF backup merging and redacted local diagnostics
are delivered. The emulator workflow action is pinned to a verified full commit SHA.
Backup restores task definitions
paused/unbound and excludes credentials and workspace binaries. Large-font smoke
and contrast checks are authored; full TalkBack/light/dark device checks remain.

Row **64** is **partial**: install and checksum verification documentation is done;
real screenshots of each major screen remain pending the emulator run. Version
metadata remains unchanged. See BUILD_NOTES for validation and packaging follow-up.

## v0.7.0 plan: Workbench

The three Phase 2 roadmap items that were still missing (issues #20, #21, #22). They extend rows 13, 14
and 45 for the account-mode app; the API-key client keeps its v0.2 canvas.

| # | Item | Release |
|---|---|---|
| 65 | Artifacts panel for HTML, SVG and Markdown from answers or agent files: full screen on phones, side by side at 840 dp and wider; sandboxed HTML preview with opt-in network; edit and Run; "Ask DeepSeek to change this" makes v2, v3, ... with a version picker and "+N -M lines" | v0.7.0 |
| 66 | In-app code editor for kt, py, js, ts, html, css, json, md, csv, sh, sql, xml, yaml and txt: original tokenizer highlighting, line numbers, undo/redo, find, Save, Save as (Storage Access Framework), Share, Run for Python | v0.7.0 |
| 67 | Account manager: DeepSeek accounts and API-key profiles with sign-in type, balance (GET /user/balance for API keys) and when it was read; one-tap manual switching, separate credentials and chats per account; never automatic, "Switch account" on quota and rate errors only opens the list ([ACCOUNTS.md](ACCOUNTS.md)) | v0.7.0 |

## v0.7.0 delivery status

Rows **65-67**: released in v0.7.0 (2026-10-20, PR #23). CI green (JVM and app unit tests, lint, debug and
release builds) and 68/68 emulator tests; see [RELEASE_NOTES_070.md](RELEASE_NOTES_070.md).

## Not planned

- Image and video generation: DeepSeek has no image model. Drawings come out as SVG/HTML artifacts instead (row 13).
- Automatic account rotation: never (terms-of-service and ban risk).
- Group chats with other people: needs a shared server, and this app is client-only.

## Totals

| Release | Features |
|---|---|
| v0.1.0 (released) | 6 |
| v0.2.0 (released) | 26 |
| v0.3.0 (released) | 25 |
| v0.4.0 (released, hardening) | 7 |
| v0.5.0 / v0.5.1 (released) | account mode, see [ACCOUNT_CLIENT_050.md](ACCOUNT_CLIENT_050.md) |
| v0.6.0 (released) | Agent, see [AGENT_MODE.md](AGENT_MODE.md) |
| v0.7.0 (released) | 3 (rows 65-67), see [RELEASE_NOTES_070.md](RELEASE_NOTES_070.md) |
| v0.7.1 (released) | sign-in fix, see [RELEASE_NOTES_071.md](RELEASE_NOTES_071.md) |
| **v0.1.0-v0.4.0** | **64 rows shipped** (row 64 partial, above) |
