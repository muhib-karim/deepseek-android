# v0.1 implementation record

Historical first-pass notes below are superseded by `PASS3.md` and the authoritative
Roadmap in `PLAN.md`. Pass 3 adds JSON persistence, Office, charts, PNG and dictation.

The approved design is in PLAN.md. The build-session brief is the current scope.

- Ruling: use two modules, in-memory history, plain JVM tests and no wrapper JAR,
  as required by the build brief. Room, SVG rasterization, charts and voice are v0.2.
- Ruling: write core tests before implementations, but defer all execution to CI.
  The session forbids Git, Gradle, Android SDK, downloads and network access.
- Ruling: account endpoints and client ID remain empty; implement configurable
  standard authorization-code PKCE without assuming undocumented provider endpoints.
- Contracts: the streaming client emits chat chunks; the agent accumulates messages
  and invokes registered tools through the guard; Android owns UI, secrets and PDFs.
- Verification status: source inspection only; no build or test pass is claimed.

Final source review and corrections:

- Independent read-only source review completed; no build, test, SDK, network or
  Git operations were used. The planned external review remains separate.
- Added tests before fixing ambiguous guard path fields, absent optional wire
  fields, excessive SVG nesting and non-string OAuth token responses.
- Added transport cancellation coverage with MockWebServer.
- Preserved drafts rejected by provider validation; kept sign-in alive across
  activity recreation while cancelling on explicit Back/Cancel.
- Closed loopback sockets on setup cancellation; bounded fetch coroutine duration
  in addition to the HTTP timeout.
- Source checks parsed XML, TOML and workflow YAML and checked Kotlin delimiters.
  These checks do not establish compilation, lint or test success.
- Remaining validation: first CI run, merged manifest permission check, device
  checks for PDF pagination, encrypted preferences, Custom Tabs, rotation and
  document actions, and a supported provider account contract.


## Pass A (v0.2.0)

Implemented feature matrix rows 1–8 in the working tree; this is not a tagged
v0.2.0 release. The existing MIT harness attribution in NOTICE/README is retained.

- Extended the real OpenAI-compatible streaming tool loop: JSON-schema tool
  definitions, indexed argument accumulation, tool-call IDs/results, multi-step
  continuation, an eight-step cap, retry/checkpoints, and cancellation. Chat mode
  omits tools/tool_choice and rejects unsolicited calls. Current tool-turn reasoning
  is retained for DeepSeek follow-ups; older reasoning is removed from request
  history. Streams have a 120-second idle limit without a total duration limit.
- Added Chat/Agent composer controls, explicit Allow once/Deny sheets, inline tool
  names, summaries, status and collapsible output. Existing streamed thinking cards
  show duration and collapse independently of the answer.
- Added Stop, Retry, Regenerate and Edit/Save and resend. Retry resumes an unfinished
  run without replaying committed tools; regeneration/edit replaces the selected
  turn's suffix while preserving attached context. Stop closes pending tool results
  and preserves partial answers. Rewrites record superseded runs to prevent stale
  checkpoint recovery; attachment preparation completes before Stop finalizes it.
- Added numbered search/fetch citations, clickable `[n]` references and a Sources
  sheet with title/domain/URL. Source numbers and current-turn search hosts persist
  across resume and reset for new/replaced turns. Fetch retains readable extraction,
  decoded-byte/time caps, DNS checks and redirect refusal.
- Every search query asks first. Fetches with a query string, over 200 characters,
  or outside the turn's exact search-result hosts ask first. Workspace writes remain
  SAFE and unconfirmed; SECURITY.md lists every registered tool's actual policy.
- Removed app-wide cleartext access; network_security_config denies all cleartext.
  The inbound loopback sign-in listener needs no exemption. Android provider settings
  now require HTTPS. INTERNET/runtime microphone declarations and SDK levels remain
  unchanged; ACCESS_NETWORK_STATE remains removed.
- Pinned workflow actions to full release SHAs, restricted token permissions, and
  made tagged publication depend on tests/lint/assembly of the tagged commit and
  the checked artifact. Version pins were not refreshed online because DNS access
  was unavailable; action resolution must be confirmed by CI.

Files touched: core agent/checkpoints, LLM request history, web tools/guard/sources,
conversation persistence and Markdown parsing; app chat/view-model/timeline/Markdown,
confirmation and sources sheets, string resources and network manifest/config;
CI/release workflows; JVM tests; SECURITY.md and project handoff/plan documentation.

Validation: added JVM coverage for multi-step MockWebServer requests, streamed
arguments/reasoning, Chat mode, cancellation, confirmation, source restoration,
query/length/host rules, edit/attachment preservation, interrupted rewrites and
mixed citations/Markdown links. Local verification is static only: XML/YAML/TOML,
resource references, Kotlin delimiter/import/API inspection and diff hygiene.
**Gradle, JVM tests, Android lint and APK assembly were not run locally.** No commit
or push was made. A successful CI run is required before merging or distributing.

Remaining validation: CI must compile and execute all suites/lint/assembly and
resolve action pins. Device-check narrow/large-font layouts, live reasoning/citation
interaction, Stop during network/confirmation/attachment preparation, process death
and resume, edited turns with attachments, and provider-backed tool calling. Debug
release signing is unchanged. Passes B and C and feature-matrix rows beyond 1–8 are
outside this pass; no version bump or release was performed.

## Pass B (v0.2.0)

Continued the interrupted working tree for feature-matrix rows 11–15 and 20,
retaining Pass A's registry, guard, timeline cards and export flow. This is an
implementation handoff, not a release or a claim of a passing Android build.

- Registered `code_interpreter` with JavaScript and optional Python. A disposable
  offscreen WebView runs a dedicated worker; there is no native JavaScript bridge,
  app file access or unrestricted network. Code is capped at 100,000 characters,
  combined output at 16,000 characters and execution at 15 seconds. Stop cancels
  computation. Cards show source, stdout, stderr, return value, timeout and truncation.
  The timeline preserves the bounded JSON envelope instead of cutting it at the
  generic 8,000-character preview limit.
- Optional Pyodide 0.26.4 downloads only after confirmation. Five exact CDN files
  are streamed into an atomic private cache (64 MiB/120-second limits, no redirects).
  Subsequent runs use that cache. WebView requests for a synthetic runtime origin
  are intercepted as exact cache reads; all other requests are refused. This avoids
  copying the runtime into multiple large base64 strings. No package downloads.
- Added `render_chart(data, spec)` using the existing original SVG builder for bar,
  line and pie charts, displayed inline with the shared sandbox WebView policy.
- Added `create_artifact` and completed fenced-block cards. The full-screen canvas
  extends ArtifactViewer with preview/source tabs, edit/apply/rerun, immutable version
  history (original plus 19 edits), stable fence imports and reopening after process
  death. HTML permits JavaScript with networking initially disabled. A separate
  confirmation permits bounded public HTTPS resources for the current preview;
  rerun/version changes/close revoke that grant. Other formats disable scripts.
  SVG is sanitized; Markdown and CSV use static original renderers. Existing nested
  workspace artifacts can be edited without breaking their history.
- Save uses ACTION_CREATE_DOCUMENT and Share uses the existing FileProvider paths.
  Both now receive the selected canvas version, including `.md`, `.html`, `.csv`,
  `.txt`, `.svg` and `.json`; SVG PNG export also uses the selected version.
- Image attachments use bundled on-device ML Kit OCR, bounded bitmap sampling,
  EXIF orientation and a recognition timeout. OCR state is shown in attachment
  chips. Recognized text is marked untrusted in model context; image bytes are
  included only for vision-capable models. OCR failure retains the image for vision.
  No storage/camera permission or automatic account switching was added.

Files touched (grouped):

- Core: `agent/AgentLoop.kt`, `tools/Tool.kt`, `guard/GuardPolicy.kt`,
  `attachments/Attachments.kt`; new `tools/CodeInterpreterTool.kt`,
  `CreateArtifactTool.kt`, `RenderChartTool.kt`; new `artifacts/` store, fences,
  Markdown/Mermaid/CSV previews and `sandbox/` execution, result and runtime policies.
- Android: `attachments/AttachmentImporter.kt`, new image decoder/OCR adapters,
  new `sandbox/` WebView executor, resource policy and Python cache loader;
  `ui/ArtifactViewer.kt`, new `ArtifactCanvas.kt`, `InlineSvgArtifact.kt`,
  `InterpreterCardContent.kt`; `AssistantTurn.kt`, `ChatScreen.kt`,
  `ChatViewModel.kt`, `DsAndroidApp.kt`, `ToolConfirmationSheet.kt`.
- Resources/dependencies: `app/src/main/res/values/strings.xml`,
  `app/build.gradle.kts`, `gradle/libs.versions.toml` (bundled ML Kit and ExifInterface).
  Existing manifest SDK/permission rules and FileProvider paths are retained.
- Tests: core agent-mode/cancellation, attachment context and model capability cases;
  new artifact store/fences/preview, chart/interpreter and sandbox policy/result cases;
  app JVM bitmap-sampling cases.
- Documentation: this section, `SECURITY.md`, `NOTICE`, `docs/PASS_B_PLAN.md` and
  `docs/PROJECT_BRAIN.md`. MIT harness attribution in README/NOTICE is retained.

Validation performed locally: XML/TOML parsing, default-resource reference and
duplicate checks, dependency alias checks, source manifest/SDK constraints, Kotlin
delimiter/string/comment checks, embedded JavaScript syntax checks using Node's
parser only, `git diff --check`, and a redacted working-tree gitleaks scan. These
checks passed. No browser, WebView, Gradle, JVM suite, Android lint or APK build was
run locally; no sub-agents, commit, push, version bump or release was used.

Remaining validation and supported limits:

- CI must compile both modules, run core/app JVM tests and lint, assemble, and
  verify the merged manifest after adding ML Kit. Test cases were written/updated
  but have not been executed here.
- Device-check JavaScript output, infinite loops, cancellation, renderer crashes,
  blocked network/files/iframes/permissions, and actual Pyodide initialization from
  intercepted cache resources. Verify declined/interrupted downloads and offline
  reuse. WebView has no hard heap quota; runtime bytes use version-pinned HTTPS,
  without an independently pinned content hash.
- Device-check selected-version Save/Share/PNG, process-death history recovery,
  preview grant revocation, large-font layouts and OCR with rotated/large/empty
  images and both model capabilities. Unapplied editor drafts are in-memory;
  applying creates the durable version.
- The offline Mermaid renderer supports basic flowcharts and sequence diagrams,
  not the complete Mermaid language. Unsupported syntax gets an explicit notice
  while source stays editable/exportable. CSV previews cap at 500 rows/50 columns.
  Bundled OCR is Latin-script recognition; additional scripts are not included.
  Full Mermaid grammar, extra OCR scripts and package-enabled Python are not
  implemented. Feature-matrix release status remains unchanged until a tag ships.

## Pass C (v0.2.0)

Feature-matrix rows for personalization, research and organisation, delivered in
four pull requests (#2 to #5), each merged only after CI passed (compile, JVM tests,
lint, assemble).

- Part 1 (core): deep research policy with plan, search, read and report phases;
  memory facts and global or per-chat custom instructions composed into the system
  prompt; HTTPS tool servers (MCP style) whose tools always ask before running;
  conversation export to Markdown, HTML and PDF; session checkpoints so a stopped
  task can resume with the provider it started on.
- Part 2 (settings): Memory, Tools and Custom instructions screens wired to the
  encrypted settings store; export format dialog; tool confirmation sheet shows the
  destination server and the arguments that will be sent.
- Part 3 (chat and drawer): deep research chip in the composer; chat instructions and
  export in the top-bar menu; drawer support for temporary chats (not saved to
  history), pinned chats, projects with their own instructions, new chat in project,
  move to project and project deletion (chats stay in history without a project).
- Part 4 (Bangla): values-bn covers every string in values/strings.xml and
  values/root_strings.xml (333 strings, placeholders checked). The app language and
  voice dictation language settings offer Bangla, and locales_config lists en and bn
  for the system per-app language picker on Android 13 and later.
- Display controls (appearance and text size) were already present from earlier passes.
- Not included: a project shared-files panel (the data model has the field; no UI yet).
