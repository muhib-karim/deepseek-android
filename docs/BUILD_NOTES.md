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

## Pass D (v0.3.0)

Delivered feature-matrix rows 17, 18, 21, 22, 23, 30, 50, 51, 52, 53 and 54,
extending the released tool registry, artifact canvas, export renderer and
attachment/OCR paths. This is an implementation handoff; no v0.3.0 tag or passing
Android build is claimed. Existing versions, dependencies, SDK levels and MIT
harness attribution remain unchanged.

- **Office output:** registered `create_document`, `create_spreadsheet` and
  `create_presentation` over the existing bounded java.util.zip OOXML builders.
  Legacy tool names remain supported for saved runs. Private writes use SAFE
  policy and new-file semantics. File cards now have Open/Share actions; previews,
  external Office opens and sharing reuse the existing FileProvider/viewer flow.
- **PDF output:** `create_pdf` accepts a title and heading/paragraph/table blocks,
  with plain `content` retained for conversation exports. The existing PdfDocument
  renderer now lays out headings and bordered tables, splitting long rows across
  pages. Input is capped at 100,000 characters and 2,000 layout rows, tables at eight
  columns, rendering at 200 pages, and output at the existing 2 MB artifact limit.
- **Batch input:** the existing OpenMultipleDocuments SAF picker accepts 50 files
  per message, with 20 MB per-file and 100 MB combined private-copy limits. Added
  common code extensions. Existing Office/PDF/text/OCR extraction is retained;
  model context includes a compact inventory and a fair per-file share of a
  120,000-character text budget, with truncation markers. At most four images are
  encoded for vision-capable models; all selected images still use local OCR.
- **Camera:** retained the existing TakePicture system intent, FileProvider cache,
  OCR import and cancellation/no-camera handling. No CAMERA permission was added.
- **Share intake:** ACTION_SEND/SEND_MULTIPLE filters accept text, images and PDFs.
  Bounded content URI grants are imported into a new chat and shared text fills
  the composer. Nothing is automatically sent. File/network URIs and the app's
  own provider, including encoded authorities, are rejected. Consumption survives
  activity recreation; an incoming share waits while a current run/import is busy.
- **Styles and Study:** Default, Concise, Explanatory and Formal presets plus an
  independent Study toggle live in the composer, persist in conversation records
  and compose into the system prompt. Study requests guiding questions and hints.
  Old saved conversations deserialize with default settings.
- **Multi-file builder:** `create_web_project` creates a website or HTML slide deck
  containing index.html and CSS/classic JavaScript/SVG/JSON source files. Validated
  relative paths, at most 50 files, 500,000 characters per file and 1.5 MB combined
  source keep packages bounded. Existing canvas versions now hold the project;
  the source tab selects individual files. Offline previews serve only exact local
  resources through the existing sandbox WebView interceptor. Share/Save export
  the original files as a traversal-safe ZIP, including sharing from Library.
- **Follow-ups:** a bounded trailing JSON block supplies two or three question
  chips under the latest completed answer. Tapping fills the composer. Settings
  can disable suggestions; temporary chats disable both requesting and displaying
  them. Valid metadata is hidden from answers and conversation exports; partial
  metadata is hidden while streaming. This uses no extra provider call.
- **Branches:** long-press any message or use its Branch from here action. The new
  chat copies only that transcript prefix, attachment context, complete tool/result
  exchanges and chat preferences. New identifiers, no pending checkpoint, and no
  copied approval prevent tool replay. Temporary branches remain temporary.
- **Library:** one drawer screen lists generated files, canvas versions, images,
  fenced artifacts and exports. Type filters, file/chat-title search, preview,
  share, confirmed deletion and source-chat navigation are wired. Provenance is
  persisted privately and recovered for existing chat artifacts. Internal metadata,
  symlinks and known temporary outputs are excluded. Tombstones prevent deleted
  fenced artifacts from reappearing during refresh or reopening their message card.
- **Bangla:** every new control and notice has English and natural Bangla resources;
  existing attachment-limit messages were updated in both languages.

Files touched, grouped (52 files including this section):

- Core tools/security: `OfficeTools.kt`, new `PdfContent.kt` and
  `CreateWebProjectTool.kt`, `GuardPolicy.kt`, `SandboxPreviewDocument.kt`.
- Core input/chat/persistence: `Attachments.kt`, new `SharedContentPolicy.kt`,
  `FollowUps.kt` and `ConversationBranch.kt`, `Personalization.kt`,
  `ConversationStore.kt`, `ConversationExporter.kt`.
- Core artifacts: `ArtifactStore.kt`, new `WebProject.kt` and `ArtifactLibrary.kt`.
- Android entry/data/input: `AndroidManifest.xml`, `MainActivity.kt`,
  `AttachmentImporter.kt`, new `SharedInput.kt`, `SettingsStore.kt`,
  `CreatePdfTool.kt`, `SandboxWebViews.kt`.
- Android UI: `ChatViewModel.kt`, `ChatScreen.kt`, `DsAndroidApp.kt`,
  `AssistantTurn.kt`, `ArtifactViewer.kt`, `ArtifactCanvas.kt`, `SettingsScreen.kt`;
  new `LibraryScreen.kt`, `ResponseStylePicker.kt`, `WebProjectEditor.kt` and
  `MessageActions.kt`.
- Resources: `values/strings.xml`, `values/root_strings.xml`,
  `values-bn/strings.xml`.
- JVM tests: new `OfficeAliasesTest`, `PdfContentTest`, `AttachmentBatchTest`,
  `SharedContentTest`, `ResponseStyleTest`, `WebProjectTest`, `FollowUpsTest`,
  `ConversationBranchTest`, `ArtifactLibraryTest`; updated `AttachmentsTest` and
  `ConversationTurnsTest` for the new attachment summary.
- Documentation: `README.md`, `SECURITY.md`, `docs/FEATURE_MATRIX.md`,
  `docs/PROJECT_BRAIN.md` and this file. Delivered rows carry the requested
  “shipped in v0.3.0 pass D” implementation status note; other v0.3.0 rows remain planned.

Validation performed locally:

- XML/TOML parsing, resource-reference checks, English/Bangla translation and
  placeholder parity, source manifest permission checks and minSdk 26/targetSdk 35.
- Lightweight Kotlin string/comment/delimiter checks across 170 source/test files,
  `git diff --check`, a machine-path check and a redacted Gitleaks working-tree scan.
  These checks passed. Lexical checks do not establish Kotlin compilation.
- Added 24 JVM test cases covering Office aliases/new-file guards, structured PDF
  input, bounded batch context, share URI policy, style persistence, project ZIPs
  and preview policy, follow-up parsing/streaming, branch truncation/tool pairing,
  library provenance/filtering/deletion and temporary-output exclusion.
- No Gradle, Kotlin compiler, JVM suite, Android lint, APK build, browser, Playwright,
  sub-agent, commit, push, version bump or tag was run. BUILD_NOTES was the final edit.

Remaining verification and supported limits:

- GitHub Actions must compile both modules, run the full existing/new JVM suites,
  lint and assemble. Verify the merged manifest before any release.
- Device-check DOCX/XLSX/PPTX interoperability; PDF page/table breaks and Bangla
  shaping; 50-file batches, failure/cancellation and large images; camera absence,
  rotation and capture handoff; cold/warm share intents and URI grant failures.
- Device-check project file edits, offline scripts/styles/SVGs, blocked network,
  files/frames/permissions, ZIP contents and selected-version export. Test branches
  across selectable text and tool turns, library deletion/source links/process
  restart, temporary sessions, large fonts and Bangla layouts.
- Office output remains minimal OOXML. PDF conversation exports render Markdown
  punctuation as text. Scanned PDFs may have no extractable text; bundled OCR is
  still Latin-script recognition, not Bangla OCR. Multi-file preview supports
  classic JavaScript and quoted relative resources, without module imports,
  fetch, remote dependencies or multi-page navigation. WebView still has no hard
  renderer heap quota. Follow-up chips require a valid model-supplied trailing
  block; malformed/missing suggestions produce no fabricated questions.
- Other v0.3.0 feature rows and the older shared-project-files UI gap are outside
  this pass. No requested Pass D implementation item was intentionally deferred.
