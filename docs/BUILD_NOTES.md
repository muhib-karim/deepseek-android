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

## Pass E (v0.3.0)

Completed the resumed working tree for feature-matrix rows 29, 33, 45, 48, 49,
55 and 56, building on Pass D and the released registry, guard, tool cards,
artifact/export, attachment and secure-storage paths. This is an implementation
handoff, not a tagged release or a claim that compilation/tests have passed.

- **Saved assistants:** bounded local CRUD for name, built-in icon, instructions,
  default tools and response style; start a new chat from the Assistants screen.
  Chats persist an assistant snapshot so editing/deleting the saved persona does
  not rewrite previous conversations. Tool choices include configured connectors
  and remote tools, always intersected with global disables and the existing guard.
  JSON export uses the existing workspace writer and explicit SAF Save flow.
- **Manual accounts:** up to 20 DeepSeek API-key/account profiles in the existing
  Keystore-backed encrypted settings. Settings and the chat header provide a manual
  picker. Adding a profile does not select it, and deleting the selected profile
  clears credentials without selecting another. Quota/rate errors never switch
  accounts; app runs surface HTTP 429 without automatic retry. Pending runs remain
  bound to their original identity. Settings drafts retain edits across model-list
  refreshes and follow explicit account changes.
- **Browser agent:** registered open_url/read_page/click/type/submit/scroll/back,
  backed by a visible restricted WebView and a bounded public-HTTPS loader. Reads
  use the existing fetch host/query/length rules. Click/type/submit/back require
  previews with page revision, target, text, destination and fields; execution
  revalidates the preview. Stale approvals, invalid targets and oversized entries
  are rejected. Fixed internal scripts supply DOM inspection, field entry and
  scrolling; there is no arbitrary model JavaScript. Take over stops the run and
  offers native controls; clearing/switching chats disposes the browser.
- **Connectors:** a working configurable OAuth2 public-client PKCE adapter with
  read-only GET search and item-read operations. Users enter their provider's
  endpoints/client ID/read-only scopes and sign in in the system browser through
  the existing loopback/state flow. No client secret is required or bundled.
  Search/read origins are bound, item IDs encoded, responses and DNS constrained,
  and redirects refused. First use shows the initial request and an explicitly
  labelled Allow for this chat grant; disconnect deletes the encrypted token and
  revokes grants. Added setup and compatibility documentation in CONNECTORS.md.
- **Parallel agents:** a validated plan launches one to four independent guarded
  read-only loops using structured coroutines. Parent and children share 24
  inference attempts and 40 tool executions; children leave one request for final
  synthesis. Each child has separate history/source numbering and a six-step cap.
  The card shows task status/progress; successful evidence and individual failures
  return to the parent for a merged answer with source URLs. Stop cancels the group
  and confirmation waits. No recursive delegation or child browser/write actions.
- **Memory upkeep:** opt-in, tool-free suggestions after answers from bounded
  recent messages in up to three saved chats and saved facts. Context is valid JSON
  even at its size limit. Up to eight proposals per response and 50 pending proposals
  are stored separately from memory. Settings → Memory approves/rejects each
  addition/edit/deletion; stale edits/deletions cannot overwrite a changed fact.
  Temporary chats are excluded, and deleting a chat clears its pending suggestions.
- **Scheduled prompts:** one-off/repeating WorkManager tasks, created explicitly
  in Tasks or through the CONFIRM schedule_prompt tool. The preview includes exact
  prompt, time, interval and model. Tasks bind to the original account and never
  select it automatically; a changed account produces an inbox error. Each occurrence
  makes one tool-free inference request with a 120-second/32,000-character cap,
  rejecting unsolicited tool calls. Durable claims prevent replay after interruption;
  app-launch reconciliation repairs an interrupted enqueue. Results/errors have
  persistent unread state and a chat-header badge. The journal prunes old results
  and completed schedules by UTF-8 bytes so Bangla output stays within its 8 MiB cap.
- **Bangla and integration:** every new control/notice, including the browser sign-in
  callback, has English and natural Bangla resources. Existing minSdk 26/targetSdk 35,
  MIT harness attribution and version values remain unchanged. WorkManager 2.9.1 is
  the only new dependency and is credited in NOTICE. Its transitive boot/wake-lock/
  foreground/notification permissions and foreground service are removed; source
  manifest requests remain INTERNET plus runtime RECORD_AUDIO, with
  ACCESS_NETWORK_STATE still removed.

Files touched, grouped (52 files including this section):

- Core: new accounts/AccountProfiles.kt, assistants/SavedAssistant.kt,
  browser/BrowserTools.kt and BrowserScripts.kt, connectors/Connectors.kt,
  network/PublicHttps.kt, agents/ParallelAgents.kt, memory/MemoryUpkeep.kt,
  tasks/ScheduledTasks.kt and ScheduledPromptRunner.kt; extended AgentLoop.kt,
  GuardPolicy.kt and ConversationStore.kt.
- Android: new browser/BrowserController.kt, tasks/TaskScheduler.kt and
  ScheduledPromptWorker.kt; new AccountPicker.kt, AssistantsScreen.kt,
  BrowserScreen.kt, ConnectorsScreen.kt, SubAgentsCard.kt, MemoryUpkeepSettings.kt
  and TasksScreen.kt. Extended SettingsStore.kt, AccountSignIn.kt, ChatViewModel.kt,
  ChatScreen.kt, DsAndroidApp.kt, SettingsScreen.kt, ResponseStylePicker.kt and
  ToolConfirmationSheet.kt.
- Resources/build: app/build.gradle.kts, AndroidManifest.xml,
  gradle/libs.versions.toml, values/strings.xml and values-bn/strings.xml.
- JVM coverage: nine new files, 39 cases — AccountProfilesTest, AssistantStoreTest,
  BrowserPolicyTest, ConnectorTest, ParallelAgentsTest, MemoryUpkeepTest,
  TaskStoreTest, ScheduledPromptRunnerTest and PassEAgentTest.
- Documentation: README.md, NOTICE, SECURITY.md, FEATURE_MATRIX.md,
  CONNECTORS.md, PROJECT_BRAIN.md and this file. Delivered feature rows carry
  the requested “shipped in v0.3.0 pass E” implementation status note.

Validation performed locally:

- XML/TOML parsing; duplicate/missing resource checks; English/Bangla placeholder
  parity; source-manifest permission and SDK checks.
- Kotlin string/comment/delimiter checks across 199 source/test files, explicit
  project-import resolution and constructor/call-site inspection against the
  existing dependency catalog. These are static checks, not Kotlin compilation.
- Fixed browser-script syntax checked with Node's parser only; no browser or
  WebView was launched. Machine-path and diff-hygiene checks passed, as did a
  redacted Gitleaks working-tree scan before this final documentation edit.
- JVM cases cover assistant persistence/export/input validation; manual selection
  and removal without fallback; browser policy, invalid targets and stale previews;
  connector ID/origin constraints, token removal and chat-scoped grants; shared
  budget reservation, overlapping children, partial failures and cancellation;
  memory approval/staleness/context bounds/deletion; schedule confirmation,
  timing/claims/storage limits/unread state, account binding and tool-free inference;
  and rate-limit surfacing plus exact preview/denial in the existing agent loop.
- **No Gradle, Kotlin compiler, JVM suite, Android lint or APK assembly was run.**
  No Chromium, Playwright, sub-agents, review skills, commit, push, version bump or
  tag was used. This section was written as the final edit.

Remaining verification and supported limits:

- GitHub Actions must compile both modules, run existing/new JVM suites, lint and
  assemble. Verify the merged manifest after adding WorkManager, especially the
  removed transitive permissions and foreground service, before release.
- Device-check encrypted profile migration/selection/removal, account-bound resume,
  persona snapshots/export, process death, large fonts, navigation and Bangla UI.
- Device-check browser preview expiry, takeover/Stop during loading, fixed DOM
  scripts, simple forms, blocked credentials/navigation/resources and renderer
  failure. Site scripts, authenticated browsing, password entry, remote assets and
  downloads are deliberately unavailable; this is not a general-purpose browser.
  Sensitive-field detection is heuristic and WebView has no hard heap quota.
- Exercise a compatible provider's public-client PKCE flow, loopback redirect,
  search/read payloads, denied grants, expiry, reconnect and disconnect. The generic
  transport is implemented, but no live provider integration was exercised locally.
  Providers requiring client secrets, POST-based search, custom headers, binary
  downloads or token refresh need another adapter; expired tokens require sign-in.
- Device-check parallel progress/cancellation and merged answers, memory proposal
  review and temporary-chat exclusion, and WorkManager one-off/repeat/cancel/inbox
  behavior across process loss, reboot, force-stop and account changes. Scheduled
  runs are approximate and tool-free, with a 15-minute minimum repeat interval;
  reopen after reboot/force-stop because boot access is absent. No notifications
  or exact alarms are implemented. In-flight provider requests cannot be withdrawn.
- Per-agent progress and connector grants are session-only. Completed parent tool
  results persist through the existing checkpoint path; explicitly resuming an
  interrupted read-only delegation may repeat reads and begins a fresh shared budget.
  Memory upkeep uses an extra provider call. The inbox retains up to 100 results
  and may prune earlier results to meet its byte cap.
- No requested Pass E implementation item was intentionally deferred within these
  documented boundaries. Other v0.3.0 rows, the older project shared-files UI gap,
  supported DeepSeek-specific account contracts and production signing remain
  outside this pass. CI and device validation are still required before release.

## Pass F (v0.3.0)

Implemented in the working tree after Passes D/E. This is not a tagged release
or a claim that compilation, JVM tests, lint or device validation have passed.
No Gradle, Kotlin compiler, browser, sub-agent, code-review skill, commit or push
was used. Version values, minSdk 26, targetSdk 35 and MIT harness attribution are
unchanged. No dependency or install permission was added.

Delivered, in scope order:

1. **Voice conversation:** a full-screen Voice entry in the chat header uses
   runtime microphone permission and an explicit Start listening tap. System
   recognition sends through the existing ChatViewModel/history path; completed
   answers play through Android TTS, then listening resumes. Generation checks
   reject stale/duplicate callbacks. Tap to interrupt speech or stop a reply.
   Activity pause and audio-focus loss pause voice; reopening never automatically
   activates the microphone. The on-device recognizer is preferred when available,
   with one system-service fallback for an unavailable language. The UI discloses
   that the system service may use its network. An Agent switch exposes the existing
   guarded tool flow, including phone-action confirmations.
2. **Read aloud:** completed answers have play, pause/resume, sentence highlighting
   and 0.5×–2× speed controls. Resume repeats the interrupted sentence because TTS
   has no native pause primitive. The existing Markdown parser supplies readable
   text without formatting delimiters or hidden link targets; normal Markdown and
   artifact rendering return when playback stops. Long sentences are chunked with
   UTF-16-safe boundaries. Installed offline English/Bangla voices are selected by
   sentence language; missing voices and engine failures surface a localized notice.
   Audio focus is released after playback, and focus gain never resumes it silently.
3. **Images in voice:** system camera capture uses the existing TakePicture intent,
   FileProvider cache and importer without CAMERA permission. Camera and screen
   images are reviewed before attaching to the next spoken turn. Capture pauses
   voice; the user explicitly restarts listening after review/import. Existing
   batch limits and model capabilities decide image bytes versus OCR text. Imported
   PNG screenshots receive the same source-cache cleanup as camera JPEGs, including
   failed/cancelled imports. The existing Latin OCR model may not read Bangla text.
4. **Phone actions:** `draft_sms`, `draft_email`, `add_calendar_event` and `open_map`
   extend the existing registry, tool switches, guard and confirmation sheet.
   Bounded typed inputs reject unknown fields and recipient/URI injection before
   confirmation. Calendar previews include localized start/end times and timezone.
   Only standard SENDTO, CalendarContract INSERT and encoded geo VIEW intents are
   built. Launch requires the resumed activity. Results distinguish a successful
   app launch from a missing handler; the user finishes sending/saving in that app.
   **Alarms are omitted:** AlarmClock.ACTION_SET_ALARM requires
   `com.android.alarm.permission.SET_ALARM`, which violates the install-permission
   rule. No alarm tool or permission is declared.
5. **Screen awareness:** opt-in, one screenshot per request with image review and
   no automatic send. On API 26–28, every request obtains fresh MediaProjection
   consent and captures only while this activity remains visible. Display/reader/
   projection resources close after one frame, timeout or cancellation. On API 29+,
   MediaProjection requires foreground-service permissions, so the app captures
   **only its own visible window** using PixelCopy after per-request confirmation.
   The longest edge is capped at 1280 pixels. There is no foreground capture service,
   continuous recording, other-app capture on modern Android, accessibility access,
   overlay or protected-surface bypass. Process-death leftovers may remain in private
   cache until Android clears them; they are never automatically attached or sent.
6. **Quick launch:** New chat, Voice and Camera static shortcuts and dynamic
   counterparts, limited by the launcher's combined static/dynamic capacity. A
   Quick Settings tile opens paused voice mode after unlock, using the PendingIntent
   API on Android 14+. Its BIND_QUICK_SETTINGS_TILE component permission restricts
   system binding and is not an install permission requested by this app.
   ACTION_ASSIST routes to voice where supported; assist payloads are ignored and
   no full VoiceInteractionService is installed. Cold/warm intents are consumed
   once, and camera launch reuses the existing attachment flow.
7. **Home-screen widget:** RemoteViews provides an Ask DeepSeek field launcher,
   Voice button and the active saved chat title, with fallback to the newest saved
   title. Rename/delete/open/send refresh the summary; clearing history clears it.
   Temporary chats and message bodies are excluded. Only the bounded displayed
   title is stored in private widget preferences and exposed to the launcher.
   Actions use explicit immutable PendingIntents; no periodic work or new permission.
8. **Bangla and documentation:** every added UI label, notice and error has English
   and natural Bangla resources. README and SECURITY describe behavior and limits;
   feature rows 34, 35, 36, 37, 38, 39 and 57 carry the requested status note
   “shipped in v0.3.0 pass F”, explicitly identifying implementation rather than a
   validated release. Existing artifacts and export implementations remain intact.

Files touched, grouped (52 files including this section):

- Core: new launch/QuickAction.kt, phone/PhoneActions.kt,
  screen/ScreenCapturePolicy.kt, voice/VoiceSession.kt, SpeechText.kt and
  SpeechPlayback.kt, and widget/WidgetSummary.kt; extended guard/GuardPolicy.kt.
- Android adapters: new launch/QuickLaunch.kt and VoiceTileService.kt,
  phone/PhoneActionLauncher.kt, screen/OneShotScreenCapture.kt,
  voice/VoiceRecognizer.kt and SpeechPlayer.kt, and widget/AskDeepSeekWidget.kt.
- Android UI: new VoiceScreen.kt, VoiceAttachments.kt, ScreenCaptureButton.kt,
  ReadAloud.kt and PhoneActionDetails.kt. Extended MainActivity.kt,
  attachments/AttachmentImporter.kt, AssistantTurn.kt, ChatScreen.kt,
  ChatViewModel.kt, DsAndroidApp.kt, PersonalizationSettings.kt and
  ToolConfirmationSheet.kt.
- Resources: AndroidManifest.xml, values/strings.xml and values-bn/strings.xml;
  three original shortcut vectors, two widget shapes, ask_deepseek_widget.xml,
  ask_deepseek_widget_info.xml and shortcuts.xml. INTERNET and runtime RECORD_AUDIO
  remain the only requested permissions; ACCESS_NETWORK_STATE remains removed.
- JVM tests: VoiceSessionTest, SpeechTextTest, SpeechPlaybackTest, PhoneActionsTest,
  PhoneAgentTest, ScreenCapturePolicyTest, QuickLaunchTest and WidgetSummaryTest.
- Documentation: README.md, SECURITY.md, FEATURE_MATRIX.md, PROJECT_BRAIN.md and
  this file. NOTICE and dependency/version configuration are unchanged.

Validation performed locally before this final documentation edit:

- Parsed resource/manifest XML and the dependency catalog; checked duplicate and
  missing string resources, English/Bangla placeholder parity, source permission
  declarations, preserved SDK values and absence of machine paths.
- Checked literals/comments/delimiters and explicit project imports in 36 new or
  modified Kotlin files. Inspected Android/Compose call sites against the existing
  dependency versions. These are lightweight source checks, not compilation.
- Diff whitespace checks and a redacted Gitleaks working-tree scan passed.
- Wrote **40 JVM cases across eight files**: voice sequencing and stale events;
  playback pause/resume/duplicates; sentence offsets, Bangla punctuation, long
  Unicode text, Markdown conversion and speed limits; phone validation, exact
  approval/denial and Chat-mode refusal through the real agent loop; capture API
  policy and pixel bounds; launch routing/capacity; widget privacy, rename/deletion
  and title bounds. **The tests were not executed locally.**
- This section was written as the final edit, after the project handoff and checks.

Remaining verification and supported limits:

- GitHub Actions must compile both modules, run existing/new JVM suites, run
  lintDebug and assemble. Inspect the merged manifest to confirm the existing
  WorkManager permission removals and the new tile/widget declarations before release.
- Device-test English/Bangla recognition and installed/missing offline voices,
  permission denial/revocation, interrupted/failed inference, TTS errors, audio-focus
  changes, tap interruption, lifecycle/process recreation, large fonts and Bangla UI.
  Check sentence highlighting, long/code/table answers and rate/pause/resume behavior.
- Device-test camera success/cancel, image review/discard/import failures, OCR versus
  vision routing, batch limits, and image-cache cleanup. Check API 26–28 fresh consent,
  screen timeout/cancellation and rotation; check API 29+ own-window capture, secure
  surfaces and the explicit absence of other-app capture. No continuous capture.
- Exercise allow/deny for each phone action, missing/default handlers, exact SMS/
  email body and recipient encoding, calendar times/timezones, map queries and
  background launch refusal. The app cannot guarantee how third-party handlers
  render drafts; no actual send/save result is inferred from opening them.
- Check cold/warm shortcut launches, launcher capacity, camera result recreation,
  locked/unlocked Quick Settings behavior on both tile APIs, ACTION_ASSIST support,
  and widget add/resize, locale change, rename/delete and temporary-chat exclusion.
- Alarms and modern other-app screen capture are intentionally unavailable under
  the permission rules. Full default-assistant service integration and floating
  overlays are not implemented. Runtime device behavior, production signing and
  a v0.3.0 release remain unverified; no commit, push or tag was created.

## Release v0.3.0

Version 0.3.0 (versionCode 3) bundles Passes D, E and F. CI compiles, runs the core and app unit tests, runs lint and assembles the debug APK; the tag workflow attaches the debug APK and SHA256SUMS to the GitHub release. Install permissions remain INTERNET only (RECORD_AUDIO is a runtime request). Alarms stay omitted (SET_ALARM would be an install permission). Device validation is still manual.

## Pass G (v0.4.0)

Hardening and packaging implementation is saved in the working tree. No new
assistant feature, version bump, commit, push or tag is included. This section was
written as the final edit. Compilation, lint and Android/JVM execution remain CI
gates; no release or passing-build claim is made.

Delivered:

- Release-only R8 and resource shrinking, with environment-only signing through
  `DSA_KEYSTORE_PATH`, `DSA_KEYSTORE_PASSWORD`, `DSA_KEY_ALIAS` and
  `DSA_KEY_PASSWORD`. All four must be present to sign; otherwise release still
  configures unsigned. Debug signing/minification, minSdk 26, targetSdk 35 and
  versionCode/versionName remain unchanged. Existing `*.jks` and `*.keystore`
  ignore entries were already present and retained.
- Conservative PDFBox/fontbox and AndroidSVG keep rules, persisted Worker
  constructor/name retention, and ML Kit/Firebase registrar retention. Library
  consumer rules handle serialization, Compose/Lifecycle, OkHttp/Okio, coroutines,
  security-crypto/Tink and WorkManager, including its internal Room database.
  There are no app-defined Room/DataStore models or POI dependency; Office uses
  the existing original ZIP/XML code. No blanket app keep or ignorewarnings rule.
- Tag validation assembles unsigned release as well as debug. With nonempty
  `DSA_KEYSTORE_BASE64`, CI decodes a restricted temporary keystore, builds the
  signed release with configuration caching disabled, and removes the keystore
  on exit. Debug is always retained; signed release is conditional. SHA256SUMS.txt
  covers every attached APK and is verified again in the publishing job. Existing
  action SHAs are unchanged. PR CI also assembles unsigned release after debug and
  checks the merged release manifest for INTERNET/runtime RECORD_AUDIO only.
- Four instrumentation cases: actual launcher activity, real app composable
  launch and 48dp controls, Settings at 200% font scale, and partial/completed
  streaming followed by opening an artifact. The latter uses an in-process fake
  LlmClient behind the existing interface; no real provider key or transport.
  The host activity is debug-only, and all test dependencies use androidTest.
  API 34 x86_64 smoke CI has a 30-minute timeout and uploads test reports plus
  actual chat/settings/artifact screenshots when the tests run successfully.
- Accessibility: resource-backed meaningful image/selection labels, 48dp custom
  targets, scrollable confirmation/large-font content and action rows, no fixed
  composer text-height cap, and dark text/accent contrast coverage. Existing
  decorative logos/icons remain unlabeled. New strings have Bangla translations.
- Settings Backup uses SAF create/open contracts and one versioned JSON file.
  Strict version/type/ID/path/count validation, bounded nesting and a 16 MiB input
  limit precede a confirmed merge. Existing IDs win; display/personalization
  preferences are restored. Imported schedules remain paused and unbound, with
  no enqueue or account selection. Incomplete tool exchanges are removed using
  the existing history sanitizer; unfinished timeline steps become stopped.
  Typed settings allowlists exclude credentials and provider/connector config;
  known credentials and recognizable secrets/emails in text are redacted. No
  encrypted-preference dump, temporary chats, checkpoints or workspace binaries
  are exported. Save generated files separately. Per-record atomic writes make
  retries idempotent; a storage failure/process exit can leave a partial merge,
  explicitly explained in the failure message and SECURITY.md.
- Settings Diagnostics persists the latest 200 errors/tool failures, each capped
  at 2,000 characters, with timestamps. Redaction runs before storage and covers
  known credentials, token/key assignments, bearer/cookie headers, emails,
  private-key blocks and long token-like strings. Call sites omit prompts, tool
  arguments/results, URLs and stack traces. Copy is explicit; Clear is confirmed.
  No upload, analytics, crash-reporting SDK or new network call was added.
- README install/checksum instructions and honest screenshot placeholder;
  SECURITY boundaries and FEATURE_MATRIX delivery notes (58–63 implemented,
  64 partial). Main manifest, all removal directives, account-selection policy
  and MIT attribution remain intact.

Files touched, grouped:

- Packaging: `app/build.gradle.kts`, `app/proguard-rules.pro`,
  `gradle/libs.versions.toml`, `.github/workflows/{ci,release,emulator}.yml`,
  `scripts/check_release_manifest.py`.
- Core: new `backup/{Backup,BackupRepository,SecretText}.kt` and
  `diagnostics/DiagnosticStore.kt`; `Personalization.kt`, `ScheduledTasks.kt` and
  the visibility of the existing `ConversationBranch.completeExchanges` helper.
- App: `data/BackupSettings.kt`; `ui/{BackupSettingsPanel,DiagnosticsPanel}.kt`;
  ChatViewModel, SettingsScreen and ScheduledPromptWorker integration;
  accessibility edits in ArtifactCanvas/Viewer, AssistantTurn, ChatScreen/Theme,
  DsAndroidApp, InlineSvgArtifact, MessageActions, ReadAloud, ToolConfirmationSheet
  and VoiceDictation; English/Bangla string resources.
- Tests: core BackupTest/DiagnosticsTest, app BackupSettingsTest/ContrastTest,
  androidTest LaunchTest/SmokeTest and the debug SmokeActivity/manifest.
- Documentation: README, SECURITY, FEATURE_MATRIX, PROJECT_BRAIN and BUILD_NOTES.

Validation performed without Gradle:

- XML/TOML/YAML parsing, workflow shell syntax, string-reference and Bangla
  placeholder coverage, preserved action pins/main manifest, Kotlin lexical and
  delimiter sanity, and `git diff --check` passed. These do not prove compilation.
- Executed the checksum packaging script against debug-only and two-APK fixtures;
  both checksum manifests verified. Absent signing secrets skip signing, while
  incomplete configured signing fails before invoking Gradle. The manifest gate
  accepted allowed permissions and rejected an added CAMERA permission/missing
  manifest in disposable fixtures. Redaction pattern fixtures retained UUIDs.
- Custom dark text/send-accent pairs measured at least 4.88:1 contrast. Added
  **18 JVM test cases and four instrumentation cases**, all unrun locally.
  Gitleaks scanned the working tree with redacted output and found no leaks.

Remaining / human pin:

- `.github/workflows/emulator.yml` uses `reactivecircus/android-emulator-runner`
  pinned to the full commit SHA of tag v2.34.0
  (`1dcd0090116d15e7c562f8db72807de5e036a4ed`, verified against the upstream tag).
- Run CI core/app JVM tests, lintDebug, assembleDebug and unsigned assembleRelease;
  resolve any compiler/R8 findings before release. Exercise the minified build's
  PDFBox/OCR/SVG, encrypted settings, serialization and scheduled-worker paths.
- Configure the four repository signing secrets to publish a signed release APK;
  validate signature/install/update behavior. The workflow also supports no-key
  debug-only publication. Version metadata is deliberately still 0.3.0.
- Run emulator smoke after pinning, inspect TalkBack and light/dark layouts at
  large font sizes, and device-test SAF cancellation, bad files, merge/retry,
  app recreation, credential exclusion and local diagnostics Copy/Clear.
- Real screenshots of every major screen remain pending. Only real emulator
  captures should be added; no fake images, local browser or emulator were used.

### Pass G CI fix: network callback removed

The chat screen no longer registers a default-network callback: that API needs ACCESS_NETWORK_STATE, which stays removed from the install permissions, so the call always failed silently. A message waiting for the network is retried from the Retry action.
