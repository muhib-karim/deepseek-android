# DeepSeek Android (formerly DeepSeek Android) — plan (v0.1)

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
  model picker (deepseek chat/reasoner ids from /models), conversation list stored locally (Room).
- Tool calling via the OpenAI-compatible `tools` API. v0.1 tools:
  create_pdf (Markdown/HTML -> PDF via Android PdfDocument), create_svg (validated, preview), create_markdown,
  create_csv, create_image (render SVG/chart to PNG), save/share (Android share sheet + SAF "Save as"),
  web_fetch (https only, size/time caps, text extraction), list/read files inside the app workspace.
- Every artifact appears as a card in chat: preview, Open, Share, Save to Downloads.
- Confirm dialog (shows the exact action + arguments) for: any write outside the app folder, any shell command,
  any package install/intent launch. Shell and install are OFF by default (Settings > Advanced).
- Minimal permissions: INTERNET only. Files outside the app go through the Storage Access Framework picker
  (no MANAGE_EXTERNAL_STORAGE). Notifications optional (POST_NOTIFICATIONS, asked at use).

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
- Trademark: name avoids "DeepSeek"; README states unofficial, not affiliated.
- Prompt injection from fetched pages -> mitigated by guard + confirm + shell off by default.
- Debug-signed APK is for testing, not Play distribution.

## v0.1 must-haves added by the owner (Oct 18)
1. **File upload.** Attach files to a message through the Android system picker (ACTION_OPEN_DOCUMENT and the
   Photo Picker), so still no storage permission. PDFs: text extracted on device (pdfbox-android, Apache-2.0).
   DOCX: text from word/document.xml. TXT/MD/CSV/JSON: read as text. Extracted text goes to the model as
   context (size-capped, with a visible "attached: name, N chars" chip). Images go as image parts to models that
   accept images; for text-only models the app says so instead of pretending (OCR is v0.2).
2. **Interface like the official DeepSeek app** (original code and assets, no DeepSeek logo or name): clean chat,
   a left drawer with conversation history and New chat, a composer with attach (+), a "Think" (reasoning)
   toggle and a "Web" (search/fetch) toggle, the model name at the top as a picker.
3. **Latest models automatically.** After sign-in or entering a key, and on every app start, fetch GET /models
   from the active provider; default to the newest (by `created` when given, otherwise by the highest version in
   the id, aliases like deepseek-chat last); remember the user's choice; never hardcode a single model.

## Added from owner screenshots / rename (Oct 18)

4) MARKDOWN RENDERING done properly: core MarkdownParser (pure Kotlin, tested) producing blocks: headings, paragraphs, bullet/numbered lists, block quotes, fenced code (with language), GFM tables (header + alignment row + rows), horizontal rules; inline spans: bold, italic, inline code, links, strikethrough. App renders them with Compose (AnnotatedString for inline, a scrollable bordered table for tables, monospace surface with a Copy button for code). No raw ** or backticks ever shown. Streaming-safe (re-parse on each delta; unfinished fences render as code).
5) PLAIN-ENGLISH ERRORS: core ErrorExplainer maps HTTP status/body to user text, tested: 401 -> "Sign-in expired or key invalid. Sign in again or check your API key."; 402 or a body saying insufficient balance/quota -> "This account has no remaining DeepSeek balance or free quota. Add a DeepSeek API key in Settings, or top up on the DeepSeek platform."; 403 -> "DeepSeek refused this request for this account (403). This can happen when the account has no access or quota for this app. Try an API key in Settings."; 429 -> rate limit with retry; 5xx -> service busy; network errors. Shown as an error card with a "Open Settings" action. Never auto-switch accounts; there is exactly one account at a time.
6) INLINE ARTIFACT VIEWER: tapping an artifact card opens an in-app viewer screen: PDF via android.graphics.pdf.PdfRenderer (pages rendered to bitmaps in a LazyColumn, pinch-zoom optional), SVG and HTML via WebView with JavaScript disabled, file and content access disabled, loadDataWithBaseURL(null, ...); Markdown via the Markdown renderer; CSV as a table; images via Image. Each viewer has Share (FileProvider + ACTION_SEND) and Save (ACTION_CREATE_DOCUMENT) actions.
7) RENAME: the app is now called "DeepSeek Android" (display name, README title, About screen) with a clear "Unofficial. Not affiliated with or endorsed by DeepSeek." line in README and in an About screen reachable from the drawer. No DeepSeek logo; use an original simple launcher icon (adaptive icon vector, e.g. a stylised chat bubble with a spark). Package/namespace/applicationId is app.dsandroid (already renamed before this pass; verify no "deepseek-android"/"DeepSeek Android" strings remain anywhere). ClientIdentity app name "DeepSeek-Android-Unofficial" so the server can always tell it apart from the official clients; never claim to be an official client.

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
