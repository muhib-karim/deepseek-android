# DeepSeek Android — plan (v0.1)

An English, open-source Android agent app for DeepSeek models: chat that can actually *do* things
(make PDFs, SVGs, CSVs, Markdown, images; fetch the web; work with files) with a confirm step
before anything risky. MIT. Built in Kotlin + Jetpack Compose. Protocol and safety design follow
deepseek-ai/deepseek-harness (MIT, attributed); no code from ylh440104/dsh-android (unlicensed).

## What we learned
- deepseek-harness (MIT, TypeScript, Cordis plugins). Account sign-in = system-browser OAuth with PKCE:
  `auth_init` (with `login_source`), a loopback `http://localhost:<port>/oauth/callback`, `auth_exchange`,
  `auth_cancel` (authorize_id + verifier), logout via `POST /auth-api/v0/users/logout`. Grants authenticate
  Platform, inference and Files with header `x-dsh-auth-token` (no Bearer). Inference origin defaults to
  https://api.deepseek.com, redirects refused. Every call carries honest client-identity headers
  (client version, UI language, UTC offset, platform). The harness gates tools through `guard`.
- dsh-android: Kotlin/Compose, ~37 Kotlin files, tools/ui/api/auth packages. Ideas only. Its manifest asks for
  MANAGE_EXTERNAL_STORAGE, QUERY_ALL_PACKAGES, INTERACT_ACROSS_USERS_FULL, WRITE_SETTINGS, REQUEST_INSTALL_PACKAGES,
  fine location: far broader than needed. No confirm gate. Spoofs the desktop client; rotates accounts.

## Sign-in, done legitimately
1. "Sign in with DeepSeek": PKCE (S256) in-app, open the authorize page in a Custom Tab (system browser,
   the user types their own password on DeepSeek's page, never in our app), redirect to an in-app loopback
   listener on 127.0.0.1/localhost (RFC 8252 §7.3), exchange the code, store the grant in Android Keystore-backed
   EncryptedSharedPreferences. `login_source` = "web"; client headers name **DeepSeek-Android-Unofficial/<version> (Android)**,
   never "desktop" and never another app's identity. One account per device user; no rotation, no pooling.
2. API key (platform.deepseek.com key) as a first-class option.
3. Any OpenAI-compatible endpoint (base URL + key + model), e.g. a local server.
If DeepSeek's Platform does not accept a third-party client on the account flow, the app says so plainly and
falls back to API key. We do not work around it.

## v0.1 features
- English UI, Material 3, light/dark; chat with streaming (SSE), Markdown + code rendering, reasoning toggle,
  model picker (deepseek chat/reasoner ids from /models), durable JSON conversations and resumable runs in v0.1.
- Tool calling via the OpenAI-compatible `tools` API. v0.1 tools:
  create_pdf (Markdown/HTML -> PDF via Android PdfDocument), create_svg (validated, preview), create_markdown,
  create_csv, create_image (render SVG/chart to PNG), save/share (Android share sheet + SAF "Save as"),
  web_fetch (https only, size/time caps, text extraction), list/read files inside the app workspace.
- Every artifact appears as a card in chat: preview, Open, Share, Save to Downloads.
- Confirm dialog (shows the exact action + arguments) for: any write outside the app folder, any shell command,
  any package install/intent launch. Shell and install are OFF by default (Settings > Advanced).
- Minimal permissions: INTERNET plus ACCESS_NETWORK_STATE for run recovery; RECORD_AUDIO requested at first dictation use. Files outside the app go through the Storage Access Framework picker
  (no MANAGE_EXTERNAL_STORAGE). No notification permission in v0.1.

## Architecture
Single-activity Compose app, modules: `:app` (UI), `:core` (agent loop, tool registry, guard policy),
`:llm` (OpenAI-compatible client, SSE parser, auth providers: DeepSeekAccount, ApiKey, Custom),
`:tools` (artifact makers, web fetch), `:data` (Room, encrypted settings). Agent loop: stream -> collect
tool_calls -> guard.check(tool, args) -> (confirm UI if needed) -> run -> append tool result -> continue,
max N steps, cancel anywhere. Pure-Kotlin core so most logic is JVM-unit-testable.

## Test plan (tests first)
- JVM unit tests: SSE parser (split chunks, [DONE], tool-arg streaming), tool-call accumulation, guard policy
  table (every tool x path class -> allow/confirm/deny), PKCE (RFC 7636 test vector), loopback callback state
  check, SVG validator, CSV escaping, Markdown->HTML, web_fetch URL policy (no file://, no private IPs).
- Robolectric: PDF creation produces a valid %PDF with N pages; encrypted settings round-trip.
- MockWebServer: full agent loop against a fake OpenAI-compatible server (stream + tool call + result).
- CI (GitHub Actions only): lint, unit tests, assembleDebug signed with a CI-generated debug key, APK artifact;
  gitleaks. Release workflow attaches the APK to a GitHub Release.

## Risks
- Account sign-in for third-party clients may be refused or changed by DeepSeek (developer preview, breaking
  changes). Mitigation: API key + custom endpoint paths; no spoofing.
- "Free": account inference may draw on the account's Platform balance/bonus, not unlimited free chat. Say so in README.
- Trademark: display name is "DeepSeek Android" with an explicit unofficial/not-affiliated notice and original artwork.
- Prompt injection from fetched pages -> mitigated by guard + confirm + shell off by default.
- Debug-signed APK is for testing, not Play distribution.

## v0.1 must-haves added by the owner (Oct 18)
1. **File upload.** Attach files to a message through the Android system picker (ACTION_OPEN_DOCUMENT and the
   Photo Picker), so still no storage permission. PDFs: text extracted on device (pdfbox-android, Apache-2.0).
   DOCX: text from word/document.xml. TXT/MD/CSV/JSON: read as text. Extracted text goes to the model as
   context (size-capped, with a visible "attached: name, N chars" chip). Images go as image parts to models that
   accept images; for text-only models the app says so instead of pretending (OCR is v0.2).
2. **Interface like the official DeepSeek app** (original code and assets, no DeepSeek logo or copied assets): clean chat,
   a left drawer with conversation history and New chat, a composer with attach (+), a "Think" (reasoning)
   toggle and a "Web" (search/fetch) toggle, the model name at the top as a picker.
3. **Latest models automatically.** After sign-in or entering a key, and on every app start, fetch GET /models
   from the active provider; default to the newest (by `created` when given, otherwise by the highest version in
   the id, aliases like deepseek-chat last); remember the user's choice; never hardcode a single model.

## Added from owner screenshots / rename (Oct 18)

4) MARKDOWN RENDERING done properly: core MarkdownParser (pure Kotlin, tested) producing blocks: headings, paragraphs, bullet/numbered lists, block quotes, fenced code (with language), GFM tables (header + alignment row + rows), horizontal rules; inline spans: bold, italic, inline code, links, strikethrough. App renders them with Compose (AnnotatedString for inline, a scrollable bordered table for tables, monospace surface with a Copy button for code). No raw ** or backticks ever shown. Streaming-safe (re-parse on each delta; unfinished fences render as code).
5) PLAIN-ENGLISH ERRORS: core ErrorExplainer maps HTTP status/body to user text, tested: 401 -> "Sign-in expired or key invalid. Sign in again or check your API key."; 402 or a body saying insufficient balance/quota -> "This account has no remaining DeepSeek balance or free quota. Add a DeepSeek API key in Settings, or top up on the DeepSeek platform."; 403 -> "DeepSeek refused this request for this account (403). This can happen when the account has no access or quota for this app. Try an API key in Settings."; 429 -> rate limit with retry; 5xx -> service busy; network errors. Shown as an error card with a "Open Settings" action. Never auto-switch accounts; there is exactly one account at a time.
6) INLINE ARTIFACT VIEWER: tapping an artifact card opens an in-app viewer screen: PDF via android.graphics.pdf.PdfRenderer (pages rendered to bitmaps in a LazyColumn, pinch-zoom optional), SVG and HTML via WebView with JavaScript disabled, file and content access disabled, loadDataWithBaseURL(null, ...); Markdown via the Markdown renderer; CSV as a table; images via Image. Each viewer has Share (FileProvider + ACTION_SEND) and Save (ACTION_CREATE_DOCUMENT) actions.
7) RENAME: the app is now called "DeepSeek Android" (display name, README title, About screen) with a clear "Unofficial. Not affiliated with or endorsed by DeepSeek." line in README and in an About screen reachable from the drawer. No DeepSeek logo; use an original simple launcher icon (adaptive icon vector, e.g. a stylised chat bubble with a spark). Package/namespace/applicationId is app.dsandroid (already renamed before this pass; verify no legacy project-name strings remain anywhere). ClientIdentity app name "DeepSeek-Android-Unofficial" so the server can always tell it apart from the official clients; never claim to be an official client.

8) THINKING AND TOOL-CALL TIMELINE (owner screenshot of dsh-android, copy the UX pattern only): under the user bubble, the assistant turn is a vertical timeline of light-grey rounded cards in the exact order they happened, interleaving: (a) "Thinking" cards: small grey label "Thinking" + chevron, collapsed by default to a single-line ellipsised preview of the reasoning text, tap to expand the full text (live-updating while streaming); a new Thinking card starts each time reasoning resumes after a tool call; (b) tool cards: wrench icon + tool name in the accent colour + a "Details" link; Details opens a bottom sheet with the pretty-printed arguments, the result (truncated, copyable) and duration; add a trailing status icon (spinner while running, check when done, red x on error, shield/"waiting for you" when a confirm is pending, "denied" when declined); (c) an animated three-dot typing indicator while the model is working between steps; (d) the final answer rendered with the Markdown renderer, then artifact cards. Core: AgentLoop records an ordered List<TurnStep> (Reasoning(text), ToolCall(id, name, argsJson, status, resultPreview, durationMs), Answer(text)) and emits updates; unit-test the ordering for reasoning -> tool -> reasoning -> tool -> answer.

9) HIGHEST PRIORITY: LOOK AND FLOW AS CLOSE AS POSSIBLE TO THE OFFICIAL DEEPSEEK ANDROID APP (from its public Play Store screenshots; original code/assets, no logo). Spec:
 - Background pure white (dark theme: near-black #121212); one accent blue around #3D63F5 (do not copy brand assets). Clean sans-serif, generous spacing, no heavy elevation.
 - Top bar: left a two-line "menu" icon opening the drawer; centered the conversation title (or the model name with a small chevron dropdown on a new chat); right a circled "+" New chat icon. The model picker lives in the title dropdown.
 - Empty state: centered original app mark (simple vector, not a whale) and "How can I help you?".
 - Composer: a rounded (24dp) white card with a soft shadow pinned to the bottom; placeholder "Type a message"; second row inside the card: pill chips "Think" (icon + label) and "Search" (globe icon); selected chip = light blue fill (#E8EEFF) with accent text; right side a circled "+" (attach) and a circled send button (stop square while generating).
 - Attach "+" opens a bottom sheet with three big rounded tiles: Camera (TakePicture into cache via FileProvider, no CAMERA permission needed for the intent), Photo (Photo Picker), Document (OpenDocument).
 - User message: right-aligned bubble, light blue fill (#EEF3FF light / #24304F dark), dark text, 18dp corners. Assistant message: NO bubble, plain text full width on the background.
 - Reasoning: header "Thinking ⌄" in grey while streaming, then "Thought for N seconds ›" when done (collapsed by default after finishing); body in grey smaller text with a thin vertical line on the left, steps as a timeline with small icons: tool steps like "Searched the web", "Read 3 pages", "Created butterfly.svg" (human-readable label per tool) with a Details link and status icon, interleaved in order (this refines item 8: keep the cards compact and inside the grey timeline the way the official app shows search steps).
 - Final answer in Markdown (headings bold, tables, code). LaTeX math: v0.1 shows $...$ as monospace inline text; real math rendering is a v0.2 TODO.
 - Drawer: search box at top, "New chat", conversations grouped Today / Yesterday / Earlier, Settings and About at the bottom.
 - "Search" chip enables the web tools for that message; "Think" selects reasoning (reasoning model or reasoning flag) for that message.

## Next project (not started)
OpenCode Android: same pattern for OpenCode (sign in to OpenCode, use its free models and features). Do not start until DeepSeek Android v0.1.0 ships.

10) WORKSPACE FILE + OFFICE TOOLS (owner screenshot 7:55 AM: dsh-android building a .pptx by hand-writing OOXML and zipping). All in the app sandbox (filesDir/workspace), no Python, no shell needed:
 - create_file(path, content) [text], read_file(path, max_chars), list_dir(path), zip_files(sources[], zip_path), list_zip(zip_path). Core, pure JVM, path-traversal safe via WorkspacePath, tested.
 - visit_web(url) = the web_fetch tool renamed/aliased: fetch https page -> readable text (title, headings, paragraphs, links list), size/time capped. web_search(query): default backend the DuckDuckGo HTML endpoint (https://html.duckduckgo.com/html/?q=) parsed for title/url/snippet, honest User-Agent, max 8 results; backend configurable in Settings (DuckDuckGo HTML, or a user SearXNG URL). Search/visit only run when the "Search" chip is on or the user allows it.
 - Real Office output with tested Kotlin builders (core, pure JVM, java.util.zip, minimal hand-written OOXML templates, original code): create_pptx(title, slides[{title, bullets[], notes?}]) -> .pptx (16:9, one title slide + content slides, [Content_Types].xml, _rels, presentation.xml, slideMaster/slideLayout/theme minimal valid parts); create_docx(title, blocks[{heading|paragraph|bullets|table}]) -> .docx; create_xlsx(sheets[{name, rows[][]}]) -> .xlsx (inline strings, numbers detected). Unit tests reopen each file with ZipInputStream, check every part listed in [Content_Types].xml exists, every _rels target exists, and every XML part parses with javax.xml DocumentBuilder; plus a docx round trip through DocxTextExtractor.
 - Keep create_pdf and create_svg. Viewer: pptx/docx/xlsx open with a simple in-app text/table preview plus Share/Save (open with an installed Office app via ACTION_VIEW).
 - Tool cards show human labels: "Created deck.pptx", "Visited example.com", "Searched: query", "Zipped 12 files".

11) PRODUCT VISION (owner, 7:55 AM): "Study the DeepSeek official Android app. It is text-based only. I am trying to get an app just like ChatGPT or Claude so that people can have a much richer user experience." Keep the official look and feel; add the richer ChatGPT/Claude-app experience: visible thinking and tool steps, files made in-app (PDF, SVG, PPTX, DOCX, XLSX, charts, images) that open in a preview and can be shared, web browsing and search, file and image upload. Every v0.1 choice is judged by whether it is richer than the official app. Concretely for this pass: create_chart(type bar|line|pie, title, labels[], series[{name, values[]}]) -> core ChartSvgBuilder (pure JVM, tested, original) producing a clean SVG; create_image(svg) and chart PNG export rendered on device with com.caverock:androidsvg-aar:1.4 (Apache-2.0; add to NOTICE), which is also used for SVG previews instead of a WebView when possible.

12) RESILIENT RUNS (owner screenshot 7:56 AM: dsh-android lost a long agent run to a momentary DNS failure "Unable to resolve host api.deepseek.com"). v0.1 must:
 - Core RetryPolicy (pure JVM, tested): on IOException/UnknownHostException/timeout/5xx/429, retry with exponential backoff and jitter (1 s, 2 s, 4 s ... capped at 30 s, up to ~5 min total), never retry 400/401/402/403; honour Retry-After.
 - Core RunCheckpoint: AgentLoop is resumable. After every completed tool call and every finished assistant step, serialise the run (messages so far incl. tool results, ordered TurnSteps, pending step index, model, flags) to JSON via a CheckpointStore interface; resume(checkpoint) continues from the last completed step without re-running finished tools (tool calls are only re-issued if they had not completed). Unit tests: kill the fake LLM mid-run with UnknownHostException after a tool result, then resume and assert the tool did not run twice and the final answer arrives; plus a JSON round-trip test.
 - App: FileCheckpointStore in filesDir/runs; a ConnectivityManager.NetworkCallback (needs android.permission.ACCESS_NETWORK_STATE, a normal install-time permission; the only addition to INTERNET) resumes a waiting run as soon as the network returns; on app start an unfinished run shows "Resume this task" on its conversation.
 - UI: a calm banner "Connection lost, retrying..." (with a countdown and a Retry now button) instead of a raw red error; raw details only in the Details sheet. Persist conversations to disk too (simple JSON files in filesDir/conversations) so the drawer history survives restarts.

## Roadmap (owner feature spec, Oct 18) — authoritative
The owner's full spec is now the roadmap. Items 1-12 above map onto it as shown. `[x]` = in the tree, `[ ]` = to do.

### Phase 1 — parity MVP (v0.1.0)
- [ ] Sign in with DeepSeek via PKCE in the system browser (Custom Tab); configurable standard S256 flow exists, but a supported DeepSeek-specific endpoint contract and device verification are still required (Sign-in 1)
- [x] API key and any OpenAI-compatible endpoint as a second path (Sign-in 2-3)
- [x] Tokens and keys encrypted with the Android Keystore AES-GCM master key
- [x] Clear "sign in again" prompt on 401/403/session expiry with a tested renewal state machine and pending-run resume (item 5)
- [x] Streaming chat (SSE)
- [x] Markdown, tables, code blocks with per-block copy, message copy (item 4)
- [x] Deep Think toggle with a visible thinking + tool-step timeline (item 8)
- [x] Web search toggle (item 10: web_search + visit_web; DuckDuckGo HTML or configured SearXNG)
- [x] File and image upload through the system picker (item 1)
- [x] Text extraction from PDF, Word, Excel, PowerPoint and text; up to 50 files per chat with visible limits. Images attach as image parts where supported.
- [ ] OCR for scanned PDF/image text (v0.2; text-only models explicitly identify unreadable images)
- [x] Press-and-hold voice dictation in the input (Android speech recogniser, microphone permission requested only on first use)
- [x] Quick / Expert = fast and pro model tiers, newest models picked automatically from /models (item 3)
- [x] Chat history that persists across restarts (atomic JSON store); drawer search, rename and delete. Cloud sync requires a legitimate API.
- [x] App UI strings in resources, English default, System/English per-app language toggle in Settings
- [x] Look and flow of the official app, original assets (items 2, 9)
- [x] Plain-English errors (item 5); in-app viewer for outputs (item 6)
- [x] Resilient runs: retry with backoff, checkpoint each step, resume after a network drop, reconnecting countdown/Retry now and restart Resume (item 12)
- [x] File outputs PDF, SVG, Markdown, CSV saved and shared; confirm before any write outside the app folder
- [x] PowerPoint, Word and Excel makers with XML/relationships tests and in-app previews (item 10)
- [x] Sandbox create/read/list/ZIP tools, SVG charts, on-device PNG generation/export and AndroidSVG preview (items 10-11)

### Pass 3 remaining validation and TODOs

Checkboxes above mean implementation is in the tree; they do not claim executed tests or a successful build.

- [ ] Run CI Kotlin compilation, JVM suites, Android lint and assemble. The requested no-Gradle/no-SDK/no-network rule prevented execution here; no first-build guarantee is claimed.
- [ ] Supply/validate a documented DeepSeek account flow. The existing standard browser PKCE adapter intentionally has no invented authorize/exchange URLs. API key and custom endpoints remain usable alternatives.
- [ ] Device-check Keystore persistence, Custom Tab callback, microphone permission/hold/release/unavailable recognizer, locale recreation, network loss/return, Retry now and restart Resume.
- [ ] Open generated PPTX/DOCX/XLSX in installed Office apps and verify PDF/AndroidSVG previews, PNG export, Share/Save and large-font layouts against the supplied references.
- [ ] Provider-side account deletion, global logout, account email/binding details, shared links and voice catalogs need supported APIs. Local data/account screens explicitly describe local-only behavior.

### Phase 2 — differentiators
- [x] Artifacts panel: live HTML / SVG / Markdown preview with edit-and-rerun (v0.7.0, #20: account mode; versions, "Ask DeepSeek to change this", side by side at 840 dp; CI pending)
- [ ] Save As to any folder through the Storage Access Framework
- [x] In-app editor with syntax highlighting, Save As and share (v0.7.0, #21: original :core tokenizer, undo/redo, find, Save As via SAF, Run for Python; CI pending)
- [x] Account manager: several accounts listed with each one's quota; the user switches manually. NO automatic switching or rotation. (v0.7.0, #22: balance for API keys, "not available" for web sign-ins; docs/ACCOUNTS.md; CI pending)
- [ ] Deep research: multi-step browsing into a report with inline clickable citations
- [ ] Vision: charts, diagrams, camera input; SVG and chart generation (item 11)
- [ ] Text-to-speech with sentence highlighting
- [ ] Custom instructions / per-chat system prompt; text size and display controls

### Phase 3 — ecosystem (every device action behind a per-action confirm; permissions asked at the moment of use)
- [ ] Share target (send text or images from any app into a new chat)
- [ ] Storage, shell and app-install tools
- [ ] Screen awareness and phone control (screenshots, taps)
- [ ] System actions: SMS draft, calendar, alarms, maps, Health Connect
- [ ] Floating bubble; Chat / Agent mode toggle; pinned chats and project folders
- [ ] Real-time voice mode; long-context document view; batch analysis of ~50 files

### Never
- Automatic account rotation, impersonating DeepSeek's own clients, a third-party relay, or copying code from unlicensed projects (ideas only; we build on the MIT harness).

## Agreed plan v0.1 (Claude Code review)

**Before anything else:** another session is writing an uncommitted "pass 3" into this working tree right now. It is about 20 modified and 16 new files: retry/checkpoint, saved conversations, Office builders, dictation and a new Settings screen. None of it has been compiled or tested. This review is based on HEAD `8acd803`. Let pass 3 finish, get CI green, commit it, then re-check this list against it. No parallel edits.

I read all 10 of your official-app screenshots, so the UI section is checked against them.

### 1. Must-haves 1–12 at HEAD
| # | Status | Where | Missing |
|---|---|---|---|
| 1 Upload | Partly done | `ChatScreen.kt`, `AttachmentImporter.kt`, `PdfTextExtractor.kt`, `DocxTextExtractor.kt` | Excel/PowerPoint text, OCR for images, 8 files per message instead of ~50 per chat |
| 2, 9 Look of the official app | Partly done | `DsAndroidApp.kt`, `ChatScreen.kt` | Doesn't match the screenshots (see §3) |
| 3 Newest models | Done | `ModelSelector.kt` | Quick/Expert labels |
| 4 Markdown | Done | `MarkdownParser.kt`, `MarkdownText.kt` | Copy/Regenerate row under answers; slow on long answers |
| 5 Plain errors | Partly done | `ErrorExplainer.kt` | No one-tap "Sign in again" on 401; wrong catch-all message at `ChatViewModel.kt:225` |
| 6 Viewer | Done | `ArtifactViewer.kt` | Office preview and "Open with" |
| 7 Rename | Done | `strings.xml`, `ClientIdentity.kt`, `ic_mark.xml` | — |
| 8 Timeline | Done | `AgentLoop.kt`, `AssistantTurn.kt` | "Auto-collapse thinking" setting |
| 10 Office and workspace tools | Missing (pass 3 in progress) | — | — |
| 11 Charts | Missing (pass 3 in progress) | — | Your roadmap puts this in Phase 2 |
| 12 Resilient runs | Missing (pass 3 in progress) | At HEAD the manifest even removes the network-state permission this needs | — |

### 2. Bugs and risks, each with its fix
- **Second message fails with thinking models.** The app sends the model's reasoning back in the history, and DeepSeek rejects that with a 400 error. Fix: drop it from earlier turns (new `HistoryPolicy`), with a test on the request body.
- **Long thinking gets cut off.** Streams have a 5-minute total limit (`OpenAiCompatibleClient.kt:42`). Fix: no total limit, a 120-second idle limit, and Stop still works.
- **Each message creates a new network client.** Fix: one shared client.
- **Runs stop after 8 steps** (`AgentLoop.kt:44`). Fix: default 25, maximum 50.
- **Web fetch rejects every redirect**, so most links fail. Fix: follow up to 5 redirects, re-checking each against the private-network rules.
- **The app is stuck if encrypted settings won't load.** Fix: a "Reset secure storage" option that keeps chats.
- **The thinking flag goes to every provider.** Fix: send it only to DeepSeek.
- **A web page could make the model leak chat data** by visiting a URL with the data in it. Fix: visiting any URL not seen in search results, your message, or an earlier fetched page asks you first.
- **Photos do nothing with DeepSeek**, because its models only read text. Fix: on-device OCR.
- **Streaming is heavy.** Fix: limit screen updates to about 30 per second and re-read only the last part of the Markdown.
- **56 UI texts are hard-coded** instead of in the strings file.
- **Each CI build is signed with a different debug key.** Updating means uninstalling, which deletes saved chats. Fix: one stable release key.
- **Settings looks like a developer form** and has shell/install switches for tools that don't exist. Some colours ignore the Appearance setting.
- **The confirm dialog shows raw JSON.** Fix: a plain sentence such as "Save deck.pptx to Downloads", with details on request.
- **"Sign in with DeepSeek" can't work yet.** It is a generic sign-in form with empty addresses. Fix: implement the real sign-in steps from the MIT harness source, still identifying as `DeepSeek-Android-Unofficial`. If DeepSeek refuses third-party apps, the button says so and v0.1.0 ships with API key as the main path. No workarounds.
- **Legal:**
  - "DeepSeek" in the app's name is a trademark risk. Keep the "Unofficial" notice and make renaming a one-line change.
  - Never use the whale, the voice names or the voice artwork.
  - Using DuckDuckGo's HTML page for search is a terms-of-service grey area. Make the search backend switchable.
  - List androidsvg and the chosen OCR engine in NOTICE.
  - The README must say API use costs money.
  - Don't copy the "Improve the model" switch; we can't control training.
- **Android pitfalls:**
  - Move appcompat and androidsvg into the version catalog.
  - A partly translated language makes lint fail.
  - Ask for the microphone only on the first hold.
  - Android 15 edge-to-edge can double the bottom padding.
  - Turn on R8 with rules for pdfbox.
  - Free memory used by PDF page images when they leave the screen.

### 3. UI, screen by screen from your screenshots
- **Empty chat:** menu icon on the left, new-chat icon on the right, our own logo, "Hi! What's on your mind today?". The example prompts go.
- **Composer:** "Type a message or hold to speak", Think and Search pills, a "+" button, and a voice button that becomes Send when you type and Stop while a reply runs.
- **Drawer:**
  - "Search chat content…" searches the messages themselves.
  - Groups: Today, Yesterday, 7 Days, 30 Days, then months like "Sep 2026".
  - The current chat is a light-blue pill with a "⋯" menu (Rename, Delete).
  - At the bottom: avatar, account name and "⋯", which opens Settings.
- **Settings:** white rounded groups on light grey.
  - Profile: Account settings, Data controls.
  - App: Language, Appearance, Font size, Personalization.
  - Audio: Main language, Voice (v0.2).
  - About: Check for updates, Service agreement.
  - Then Help & Feedback, Log out, and the AI-generated/unofficial footer.
  - Pop-ups match yours: icon, title, radio list, blue "Confirm".
- **Personalization:** the auto-collapse thinking switch.
- **Data controls:** an honest "stored only on this device" note and a red "Delete all chats".
- **Account settings:** sign-in method, masked key, balance, Sign out, and a link to manage your account on DeepSeek. There are no fake "log out of all devices" or "delete account" buttons. The technical fields sit under Advanced.

### 4. v0.1.0 (Phase 1), in order; tests are written first
1. Commit pass 3 once CI is green.
2. Fix the protocol bugs above. Tests: the request body, a slow stream, a 25-step run.
3. Error types with their own actions, and sign-in again on 401. Tests: a status-code table; a run resumes without repeating your message.
4. A welcome screen with "Sign in with DeepSeek", "Use an API key" and "Other provider". Tests: valid key, bad key, balance.
5. The real DeepSeek sign-in (`HarnessAuthClient`). Tests: each sign-in step, Cancel, sign-out, headers, the refusal message.
6. Saved chats plus pick-up-where-it-left-off after a dropped connection. Tests: a tool never runs twice; retry timing; damaged files are skipped.
7. Web search and page visits. Tests: result parsing, redirects, private addresses blocked, unknown URLs ask first.
8. Excel/PowerPoint reading, OCR, and 50 files per chat with a size budget. Tests: the budget split.
9. Making PowerPoint, Word and Excel files, plus file and ZIP tools. Tests: each file opens as a valid document; ZIP safety limits.
10. Hold to dictate. Tests: the listening steps and the fallback when the microphone is refused.
11. All texts in resources, plus the Language setting. Test: fails if any text is hard-coded.
12. The UI from §3. Tests: date groups, the settings logic.
13. Smoother streaming. Tests: the faster Markdown matches a full re-read; update count stays low.
14. A properly signed release with a permission check in CI, an install guide for non-technical users, and a device checklist.

### 5. v0.2.0 (Phase 2)
- Artifact panel with live preview, edit and re-run (web pages run in a separate, locked-down process).
- Save to any folder, and save everything as a ZIP.
- Code editor with syntax colours.
- Several accounts with their balances; you switch yourself, never automatically.
- Deep research reports with numbered sources you can tap.
- Charts and diagrams, and photos with models that can see.
- Read aloud with sentence highlighting, the Voice picker and the speaker button.
- Custom instructions and font size.
- Upkeep: replace the deprecated secure-storage library, move to a database with full-text search, maths formulas, chat export.

### 6. v0.3.0 (Phase 3): every device action asks first; permissions are asked when needed
- Share from any app into a new chat.
- Storage (through the system folder picker), a sandboxed command line and app install, in a separate "agent" version.
- Screenshots and taps in the same "agent" version, with an on-screen indicator and a stop button.
- SMS drafts, calendar, alarms, maps and Health Connect (read-only).
- Floating bubble, Chat/Agent mode, pinned chats and folders.
- Real-time voice built from on-device speech plus read-aloud (DeepSeek has no live audio API, and the app says so).
- A long-document view and analysis of about 50 files at once.

### 7. Decisions I need from you
1. **Chat storage:** simple files for v0.1 and a database in v0.2 (my recommendation), or the database now as your roadmap says.
2. **OCR engine:** Google's ML Kit reads better but isn't open source. Tesseract is open source but bigger and slower.
3. **Languages:** which ones ship besides English.
4. **Default search:** DuckDuckGo, SearXNG, or a search service that needs its own key.
5. **Release key:** you keep the signing key, stored in GitHub Secrets.
6. **App name:** keep "DeepSeek Android" despite the trademark risk, or choose another.


### 8. Decisions taken (owner asked for a fully autonomous build)
1. Chat storage: persistent local history in v0.1 (whatever pass 3 ships), Room database by v0.2 at the latest.
2. OCR: ML Kit on-device text recognition by default, behind an interface so a Tesseract build flavour can replace it.
3. Languages: English default; Bengali and Simplified Chinese as the first extra languages; all strings externalised.
4. Default search: DuckDuckGo (no key); SearXNG URL or a keyed search service optional in Settings.
5. Release key: a dedicated release keystore stored in GitHub Secrets; the APK is signed in CI only.
6. App name: "DeepSeek Android (Unofficial)" with a clear not-affiliated notice in About and README.
