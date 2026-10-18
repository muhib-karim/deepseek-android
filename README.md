# DeepSeek Android

An English, open-source Android agent app for DeepSeek and OpenAI-compatible
models. MIT licensed. **Unofficial. Not affiliated with or endorsed by DeepSeek.**

Current release: **v0.2.0**, a debug testing build (debug-key signed, not audited).
The debug APK and SHA256SUMS are attached to each GitHub release. Builds, unit
tests and lint run on GitHub Actions. Android 8.0 (API 26) or newer is required.

## v0.3.0 Pass F features (working tree, not yet released)

- **Voice conversation:** open Voice from the chat header, grant microphone access
  when requested, then tap Start listening. Speak → send → hear the answer → listen
  again, with the transcript saved through the existing chat flow. Tap to interrupt
  speech or stop a reply. Leaving the activity pauses voice; returning or launching
  from a shortcut never silently starts the microphone. Enable tools in the voice
  screen to use the existing per-action confirmations.
- **Read aloud:** each completed answer has sentence highlighting, pause/resume and
  a 0.5×–2× speed control. Resume repeats the interrupted sentence. English and
  Bangla use installed offline Android TTS voices. System speech recognition prefers
  on-device recognition when available; its fallback service may use the network.
- **Voice images:** take a photo through the system camera or choose Share screen,
  review the image and attach it to the next spoken turn. Existing model capability
  checks send image bytes only to vision models; text-only models receive OCR text.
  The existing Latin OCR model may not recognize Bangla text in images.
- **Phone tools:** `draft_sms`, `draft_email`, `add_calendar_event` and `open_map`
  validate inputs and ask for confirmation before opening the standard phone app.
  Sending/saving is finished there. `set_alarm` is intentionally unavailable because
  Android requires the extra `com.android.alarm.permission.SET_ALARM` permission.
- **One-shot screen capture:** Android 8–9 use fresh MediaProjection consent for
  each screenshot while this app is visible. Android 10+ require foreground-service
  permissions for MediaProjection, so only this app window is captured via PixelCopy.
  Other-app/background capture, continuous recording and protected-content capture
  are unavailable. Every image is reviewed before attaching and sending.
- **Quick launch:** New chat, Voice and Camera static shortcuts, dynamic counterparts
  within the launcher’s combined shortcut limit, a Quick Settings voice tile, and
  an `ACTION_ASSIST` entry where supported. No overlay or assistant-service permission.
- **Home-screen widget:** an Ask DeepSeek field launcher, Voice button and the latest
  active saved chat title. Temporary chats are excluded. The title is visible to
  the home-screen launcher; message bodies are never placed in the widget.
- New controls, errors and notices have English and natural Bangla resources.

No dependencies or install permissions were added. INTERNET plus runtime
RECORD_AUDIO remain the only requested permissions, with minSdk 26/targetSdk 35.
JVM tests are supplied; compilation, test execution, lint and device validation
remain CI/release gates. See [feature status](docs/FEATURE_MATRIX.md) and
[build notes](docs/BUILD_NOTES.md).

## v0.3.0 Pass E features (working tree, not yet released)

- **Saved assistants:** create, edit and delete named personas with a built-in icon,
  instructions, default tools and response style. Start a chat from Assistants in
  the chat-header menu. Existing chats keep their assistant snapshot. Export JSON
  through the existing Save flow.
- **Manual accounts:** keep up to 20 DeepSeek API-key/account profiles in encrypted
  storage. Select one in Settings → Account settings or the chat-header Accounts
  picker. Quota and rate errors are shown without account switching; removing the
  selected profile leaves no other profile selected.
- **Browser agent:** watch public HTTPS pages in the in-app browser and take over
  using native controls. `open_url`, `read_page`, `click`, `type`, `submit`, `scroll`
  and `back` extend the existing registry. Every click, entry and submission shows
  its target and fields before approval. Reading follows the existing fetch policy.
  This is a restricted browser for static pages and simple forms: site scripts,
  login/password fields, cookies, remote resources and downloads are unavailable.
- **Read-only connectors:** configure a public OAuth2 PKCE provider and its search
  and item-read GET endpoints in Settings → Connectors. Sign in yourself in the
  system browser. The first call asks for access for that chat; disconnect deletes
  the encrypted local token. See [connector setup](docs/CONNECTORS.md).
- **Parallel agents:** the model supplies a plan and up to four independent tasks.
  Each gets its own read-only tool loop; a card shows individual progress. They
  share 24 inference requests and 40 tool executions, with capacity reserved for
  the parent to merge results. Stop cancels the entire group. Child agents cannot
  delegate, use the browser or write files.
- **Memory upkeep:** opt in under Settings → Memory to propose additions, edits
  and deletions after answers using recent messages from up to three saved chats.
  Review each suggestion there; nothing is applied without approval. Temporary
  chats are excluded. This uses an additional provider request.
- **Scheduled prompts:** create one-off or repeating prompts in Tasks, or approve
  the model's `schedule_prompt` call. WorkManager runs a bounded, tool-free request
  and stores its answer/error in the Tasks inbox, with an unread badge in the chat
  header. The original account must still be selected. Times are approximate,
  repeats are at least 15 minutes, and no notification or exact-alarm permission
  is used. Reopen the app after reboot or force-stop to reconcile schedules.
- New controls and notices have English and natural Bangla resources. Existing
  registry, confirmations, artifacts, export and attachment paths are retained.

WorkManager 2.9.1 is the only new dependency. No new install permission is granted;
minSdk 26 and targetSdk 35 remain unchanged. JVM coverage is included, but local
verification was static only. CI compilation/tests/lint and device validation are
still required; see [build notes](docs/BUILD_NOTES.md).

## v0.3.0 Pass D features (merged on main, not yet released)

- **Files out:** `create_document`, `create_spreadsheet` and `create_presentation`
  produce DOCX/XLSX/PPTX using the existing lightweight OOXML builders. `create_pdf`
  supports titles, headings, paragraphs and simple tables, sharing the conversation
  export renderer. Generated file cards offer Open and Share; external opens use FileProvider.
- **Files in:** attach up to 50 files per message through the system document picker,
  with a 20 MB per-file and 100 MB batch cap. Text, code, Office files and readable PDFs
  are extracted locally; images use the existing OCR. A compact file inventory and up to
  120,000 extracted characters go to the model. Up to four images also use vision when supported.
  The system camera intent needs no camera permission. Share text, images or PDFs from other
  apps into a new chat, review the attachments, then send.
- **Per-chat styles and Study:** choose Default, Concise, Explanatory or Formal in
  the composer. Study mode guides learning with questions and hints. Choices persist per chat.
- **Multi-file artifacts:** `create_web_project` builds a website or HTML slide deck
  with `index.html` plus CSS, classic JavaScript, SVG and JSON files. Select and edit files
  in the versioned canvas, preview offline in the existing sandbox and share/save the project as ZIP.
- **Chat ergonomics:** optional suggested follow-ups fill the composer when tapped;
  turn them off in Settings → Personalization. Temporary chats disable them. Long-press
  a message or choose Branch from here to copy its conversation prefix to a new chat.
- **Library:** browse generated files, artifacts, images and exports across chats.
  Filter by type, search by file or chat title, open/share/delete a file or jump to its source chat.
  Temporary outputs are excluded. Deletion asks first and removes that file from all chats using it.
- Every new control and notice has English and natural Bangla resources.

The existing agent registry, tool cards, confirmations, OCR, artifact history and
export paths are extended. No new dependency or install permission is added.
See [feature status](docs/FEATURE_MATRIX.md) and [build notes](docs/BUILD_NOTES.md).

## What v0.2.0 adds

- **Agent loop:** real DeepSeek function calling with tool cards in the chat, a
  confirmation sheet for anything that is not safe, Chat/Agent toggle, thinking
  display, stop, retry and edit.
- **Cited web search and fetch:** sources are numbered under the answer; fetches
  with query strings, long URLs or unseen hosts ask first.
- **Code interpreter:** a sandboxed offscreen WebView runtime with charts, an
  artifacts panel and downloadable files.
- **Image input with on-device OCR.**
- **Deep research mode:** several search/fetch rounds into a structured, cited
  report that exports as Markdown or HTML.
- **Memory and custom instructions:** saved facts you can view, edit, delete or
  switch off; global and per-chat instructions.
- **Remote tool servers:** add a public HTTPS tool server; each of its tools needs
  confirmation and can be switched off (see [SECURITY.md](SECURITY.md)).
- **Projects, temporary chat, pins, and export** to Markdown, HTML or PDF.
- **Full Bangla UI:** in-app language option plus Android per-app language.

The full feature list and what comes next are in
[docs/FEATURE_MATRIX.md](docs/FEATURE_MATRIX.md).

## v0.1 foundation

- **DeepSeek-style interface:** original chat-and-spark mark, white/near-black
  Material 3 themes with a calm blue accent, conversation drawer with search and
  Today / 30 Days / month groups, rename/delete, centered title/model picker, and a rounded
  composer with **Think**, **Search**, attach, send and stop controls. Dynamic colour
  is off. Grouped Settings, language/appearance dialogs, personalization and local
  account/data controls use original code and the supplied screenshots as references.
- **File upload:** Camera, Photo Picker and multi-document picker; up to 50
  files per message, 20 MB each (100 MB combined), copied to private storage without storage permissions. PDF,
  DOCX, XLSX and PPTX text extraction plus TXT/Markdown/CSV/JSON, capped at 200,000 characters
  per file with a truncation marker. Images become JPEG data-URI parts, at most
  1568 px, only for vision-capable models. Text-only models show an explicit
  unreadable-image chip and receive no image bytes, including from earlier turns.
- **Automatic latest models:** GET `/models` on startup, after sign-in and after
  saving settings/keys; cached by provider and endpoint. Automatic mode prefers
  creation dates, then numeric versions, with aliases last and alphabetical ties.
  A manual model choice is remembered. The picker marks **newest**; `deepseek-chat`
  is only an explicitly labelled offline fallback. Settings override vision and
  reasoning heuristics for each model.
- Streaming Markdown: headings, paragraphs, lists, quotes, horizontal rules,
  bold/italic/strike/link/code spans, bordered scrollable GFM tables and fenced
  code with Copy. Unfinished fences remain code while streaming. Inline `$math$`
  uses monospace in v0.1; full LaTeX rendering is on the roadmap.
- An ordered reasoning/tool timeline with collapsed previews, elapsed thinking
  time, human-readable tool labels, live status, and a Details sheet with
  arguments, copyable bounded results and duration. **Think** selects a reasoning
  model when available, or sends the provider's `thinking: {type: enabled}` flag.
  **Search** enables `web_search`, `visit_web` and the `web_fetch` alias for that turn.
  DuckDuckGo HTML is the default; Settings accepts a public HTTPS SearXNG URL.
- Tool calling with Markdown, RFC 4180 CSV, sanitized static SVG, PNG images and multipage A4
  text PDF artifacts, real PPTX/DOCX/XLSX makers, bar/line/pie charts, and sandbox text/ZIP tools.
- **In-app artifact previews:** PDF pages, static SVG/HTML, Markdown, CSV tables
  and images, plus Office text/table previews. Every viewer has **Share**, **Save** and
  **Open in app** through system actions. SVGs use AndroidSVG and offer PNG export.
  Table previews display up to 500 rows; exported files retain all rows.
- Plain-English authentication, quota, refusal, rate-limit and network error cards
  with **Open Settings**. One active provider/account, without automatic switching.
- A confirmation dialog with **Allow once** / **Deny**, an eight-step agent limit,
  and Stop. Keystore-backed encrypted settings; no analytics or account rotation.
- **Resilient runs:** bounded exponential retry with jitter and Retry-After, a calm
  reconnecting countdown, Retry now, durable JSON checkpoints and explicit Resume
  after restart. Completed tools are retained. Network return wakes waiting runs;
  401/403 prompts for deliberate reauthentication instead of silently retrying.
- **Dictation and language:** hold the mic for Android SpeechRecognizer; microphone
  permission is requested at first use. System/English app locales and voice language
  are selectable. Recognition availability and processing depend on the system service.
- Pure JVM `:core` for protocol, attachments, Markdown, model ranking, errors,
  agent timeline, authentication primitives, guards and tools; `:app` for Android
  UI, sign-in, encrypted storage, PDF extraction/creation and viewers.

Conversations persist as private JSON in `filesDir/conversations`; run checkpoints
live in `filesDir/runs` and artifacts in `filesDir/workspace`. Deleting a conversation
also deletes its checkpoints. Artifacts remain until deleted from Library, app data is cleared or the app is
uninstalled; Share and Save provide external copies. PDF output supports structured
text and tables; conversation exports keep Markdown punctuation as plain text.

## Connect a provider

Open **Settings → Account settings**, choose a provider, save, and return to chat.

| Provider | Configuration | Authentication |
| --- | --- | --- |
| API key | Enter your own Platform API key | `Authorization: Bearer …` |
| DeepSeek account | Configure supported authorize/exchange endpoints, then sign in | `x-dsh-auth-token`, without Bearer |
| Custom OpenAI-compatible | Base URL, model and optional separate key | Bearer key when supplied |

The default base URL is `https://api.deepseek.com`. Leave the model override blank
for automatic selection, or choose any fetched model from the title dropdown.
Custom endpoints may specify an ID in Settings even if `/models` is unavailable.
Model capabilities are heuristic and can be overridden; provider support for
vision, tool calling and the thinking flag varies. The client appends `/models`
and `/chat/completions` to the base URL; include `/v1` if the server needs it.
All configured provider endpoints require HTTPS. Calls go directly to the chosen
provider; there is no relay or automatic account switching.

### Account sign-in caveats

Authorize URL, token exchange URL and OAuth client ID **default to empty**. Until
configured the app says: **"Account sign-in endpoint not configured; use an API key"**.
No DeepSeek authorize or exchange endpoint is invented or bundled.

The configurable adapter sends a standard authorization-code request with S256
PKCE, random state, `login_source=web`, and a loopback redirect bound to
`127.0.0.1` on a random port. It opens a Custom Tab; passwords stay on the
provider's page. The token exchange is a form-encoded OAuth request and accepts
an `access_token` or `auth_token` JSON field. Configure it only for a provider
that explicitly supports this flow and its loopback redirect. The account grant
is bound to the selected inference origin; changing that origin requires sign-in
again. Removing the local grant does not revoke it on the provider's servers.

DeepSeek's harness-specific `auth_init` / `auth_exchange` / `auth_cancel` protocol
may need an additional adapter once a supported public contract is available.
This scaffold does **not** claim that third-party DeepSeek account sign-in works.
If refused or unavailable, use the first-class API-key option. It does not bypass
provider restrictions, spoof another app, rotate accounts or pool credentials.
Account inference may draw on Platform balance or bonuses; it is **not unlimited
free chat**. Tokens may expire; automatic refresh and remote revocation are not
implemented in v0.1.

Client identity headers explicitly identify **DeepSeek-Android-Unofficial**, its version, Android platform,
language and UTC offset. Their provider-specific acceptance remains to be
verified against a supported integration. Inference and token-exchange redirects
are refused to avoid forwarding credentials to a different origin.

## Safety and permissions

**INTERNET is the only install-time permission.** RECORD_AUDIO
is declared and requested at first dictation use. ACCESS_NETWORK_STATE is explicitly
removed during manifest merging. There are no storage, notification, install,
location or broad package-query permissions.
Artifacts live under the app's private `filesDir/workspace` directory. The
non-exported FileProvider exposes only artifact directories and a narrow camera
cache directory. Camera uses the system intent without CAMERA permission. Uploads
live under private `files/uploads`; they are copied immediately, so persistable
URI permission is not taken. Imported copies are deleted after message preparation or when discarded; only
the prepared text/image context remains in the private persisted conversation. Exports use the Storage Access
Framework. Images go through on-device OCR; PDF text extraction does not OCR
scanned pages, so scanned PDFs may contain no readable
text, and encrypted/damaged files can be rejected.

Known, safe workspace tools are allowed. Unknown tools are denied. Reads stay
inside the workspace; path traversal, encoded path tricks and symlinks are
rejected. Built-in writers create new files and never silently overwrite existing
ones. Guard policy requires confirmation for writes outside the workspace, but
v0.1 built-ins cannot perform those writes; the user exports through Save as….
Shell/install policy toggles default off and require confirmation every time when
enabled. No shell/install tools are actually registered in this scaffold.

Web fetch accepts public HTTPS only, checks IP literals and resolved addresses,
disables proxies and redirects, and limits responses to 2 MB with a 20-second
call timeout. HTML extraction is intentionally simple. Fetched text remains
untrusted and may contain prompt injection; the guard checks subsequent tool
calls regardless of model instructions. SVG output uses a conservative element
and attribute allowlist; scripts, event handlers, styles, external references,
DTDs and processing instructions are removed or rejected. AndroidSVG renders sanitized
SVG without external resources. HTML canvas previews permit sandboxed JavaScript,
with no native bridge, file/content access, device permissions or navigation.
Networking starts blocked and requires a separate confirmation for single-file HTML.
Multi-file projects stay offline; their exact local resources are intercepted without
network access. See SECURITY.md for the full preview policy. CSV cells follow RFC 4180; spreadsheet applications
may interpret formula-like cells when opened, so review untrusted CSVs there.

Do not send secrets in chat or model-generated tool arguments. Prompts, reasoning,
tool results and fetched text can be transmitted to the provider you configure.
See [SECURITY.md](SECURITY.md) for limitations and reporting guidance.

## Build and download

No Gradle wrapper JAR is committed. Dependencies use a version catalog with
AGP 8.7.2, Kotlin/Compose plugin 2.0.21 and Gradle 8.10.2 in CI, using JDK 17 and
Android compile/target SDK 35.

1. Push to `main` or open a pull request targeting `main`.
2. In **Actions → CI**, inspect tests and lint, then download
   **deepseek-android-debug-apk**. Failure reports are attached when available.
3. Version tags matching `v*` trigger the debug release workflow. Download
   `deepseek-android-<tag>-debug.apk` from **Releases**.

CI runs:

```sh
gradle --no-daemon :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The separate secret scan runs Gitleaks 8.21.2 in Docker; it does not use the
license-gated Gitleaks action. APKs use Android's automatically generated debug
key and are intended for testing, not Play distribution. Different CI runs can
use different debug keys; an update may require uninstalling the previous build,
which clears private app data. Export important artifacts first.

## Tests and remaining validation

Tests cover SSE framing, streamed tool calls, OpenAI string/multipart wire models,
model discovery/ranking/capability overrides, auth headers, PKCE, callbacks, guards,
workspace paths, CSV/SVG, web URL policy and agent continuation/denial/cancellation.
Pass 2 adds in-memory DOCX fixtures, bounded text/charset handling, attachment
context, Markdown blocks/inlines/streaming, quota/status errors, CSV preview parsing,
and reasoning → tool → reasoning → tool → answer ordering and status updates.
MockWebServer fixtures cover `/models`, thinking flags, errors and streaming.
Pass 3 adds Office package integrity and extraction fixtures, traversal-safe ZIP tests,
chart/search fixtures, retry/checkpoint/cancellation/session tests, conversation recovery
and store tests, and image-tool sanitization tests.

Tests were authored first but have not been executed locally. See
[docs/PASS3.md](docs/PASS3.md) for source checks and remaining CI/device validation.
Device validation is still needed for pickers, camera handoff, PDFBox extraction,
PDF/AndroidSVG/Office previews, dictation/locales, reconnecting, sharing/export, configuration changes, large fonts and
light/dark layouts. No first-build success or screenshot parity is claimed.

## Roadmap

- Remaining v0.3.0 passes: voice, device actions and the other undelivered
  rows in [FEATURE_MATRIX.md](docs/FEATURE_MATRIX.md).
- Full LaTeX rendering and OCR for scanned PDF pages.
- Supported account-provider adapter, token lifecycle and integration validation.
- Device tests for PDFs, encrypted preferences and system document actions.

## Credits and license

The protocol and safety design follows
[deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness)
(MIT; Copyright DeepSeek). This implementation is original. No code from other
unlicensed projects is included. See [NOTICE](NOTICE), [LICENSE](LICENSE),
[docs/PLAN.md](docs/PLAN.md), and [SKILLS_USED.md](SKILLS_USED.md).
