# Security

DeepSeek Android (Unofficial) requires validation before release. CI compilation, lint and unit tests are
required before distributing an APK. It has not received a production security
audit. Do not treat model output or web content as trusted instructions.

## Reporting

Use the repository's private vulnerability reporting feature if enabled. Do not
post credentials, private conversations or exploitable details in public issues.
If private reporting is unavailable, open an issue asking maintainers for a private
reporting channel without disclosing the vulnerability. No reporting address is
assumed by this scaffold.

## Boundaries

- Provider credentials use Android Keystore-backed EncryptedSharedPreferences;
  backup is disabled. Settings are never persisted unencrypted on failure.
- Passwords are entered in the browser, not in DeepSeek Android. OAuth state is checked,
  PKCE uses S256, and loopback listening is restricted to the local IPv4 interface.
  Endpoints must be explicitly configured. Unknown third-party account contracts
  are not bypassed. Local grant removal is not server-side revocation.
- Inference and token exchange refuse redirects. All provider and search endpoints
  require HTTPS. The manifest references `res/xml/network_security_config.xml`, whose
  base configuration denies cleartext with no domain exemptions. Sign-in receives an
  inbound browser callback through a raw socket bound to IPv4 loopback; that listener
  does not need an outbound Android cleartext exemption. Previously saved custom HTTP
  providers must be changed to HTTPS. Inference goes directly to the selected endpoint;
  the app adds no relay and never switches accounts automatically.
- Install-time access is INTERNET only. RECORD_AUDIO remains declared and is requested
  at dictation use. ACCESS_NETWORK_STATE is explicitly removed during manifest merge;
  there are no storage, camera, overlay, accessibility or package-install permissions.
- Tool calls pass through the registry and guard before execution. Chat mode advertises
  no tools and refuses unsolicited tool calls. Agent mode runs only registered tools;
  unknown tools are denied, and no shell or install implementation is registered.
  The exact SAFE/CONFIRM behavior is listed below. A confirmation is never a blanket
  grant: Allow once or Deny applies to that one CONFIRM call, including after retry.
  Stop cancels inference, confirmation waits and network tools. Bounded local file
  operations finish and durably save their results before cancellation takes effect,
  preventing a subsequent resume from replaying a completed write.
- Built-in file tools reject traversal and symlinks and stay in private artifact
  storage. FileProvider is not exported and exposes only artifacts and a narrow camera-cache directory. User exports
  use the system picker. Writes use new-file semantics to avoid overwrites.
- Web fetch rejects private, local and reserved destinations, validates every DNS
  result used by the connection, refuses redirects, and applies decoded-byte and
  time limits. Redirects are refused so a previously approved URL cannot redirect to
  an unreviewed destination. Query strings (including an empty `?`), URLs longer than
  200 characters, and hosts not returned by a search in this user turn require
  confirmation before any fetch. This applies equally to `web_fetch` and `visit_web`.
  Host matching is exact after normalization, not suffix/subdomain matching. Search
  queries always require confirmation because they are sent to the search provider.
  Search result hosts and numbered sources survive retries/resume of the same turn;
  new turns, regeneration and edits start with no trusted hosts. A visited page or a
  link inside it never grants host trust. HTML and search snippets are untrusted data;
  prompt injection is still a model-level risk, not something HTML stripping solves.
- SVG is parsed without DTD/entity resolution and reduced to static allowed
  elements/attributes, including before inline preview. SVG, Mermaid, Markdown,
  CSV and text previews disable JavaScript. HTML canvas previews permit JavaScript
  inside an opaque-origin WebView with no native bridge, file/content access,
  persistent web storage, device permission grants, popups, downloads or navigation.
  Network starts blocked. An explicit preview confirmation can allow bounded public
  HTTPS GET resources (scripts, images, fonts and styles) for that version until
  rerun, version change or close. The resource loader strips page headers/cookies,
  rejects private DNS addresses and redirects, and caps requests and decoded bytes.
  CSP continues to block forms, frames, workers and fetch/WebSocket connections.
  PDF creation renders bounded plain text, headings and table cells; PDF previews use the system PdfRenderer.
  CSV quoting does not disable spreadsheet formula interpretation.
- Code runs in a disposable worker inside an offscreen WebView, with no Android
  JavaScript interface. A trusted page validates and caps the result before native
  polling. Code is limited to 100,000 characters, total stdout/stderr/value to
  16,000 characters, and execution (including runtime startup) to 15 seconds.
  Stop, timeout and renderer failure dispose the view. CSP, request interception
  and disabled worker APIs prevent app file/device access and outbound networking.
  WebView does not expose a hard per-worker heap quota: hostile allocations can
  still exhaust the renderer; this boundary needs adversarial device testing.
- Python requires an explicit confirmation before downloading the version-pinned
  Pyodide runtime from `https://cdn.jsdelivr.net/pyodide/v0.26.4/full/`. Only five
  exact runtime resources are fetched, without redirects, credentials, code or chat
  payloads, with a 64 MiB combined limit and 120-second download deadline. Complete
  downloads are cached privately; incomplete downloads are never executed. Later
  runs reuse the cache without another download. Synthetic runtime URLs inside the
  WebView are intercepted and streamed from those exact cached files; no request
  falls through to the network. Package downloads and installs are unavailable.
  The version is pinned; runtime bytes are trusted from the HTTPS CDN, not verified
  against an independently pinned content hash.
- Artifact edits create separate immutable files and an atomically replaced private
  history journal, retaining the original and up to 19 edits. Completed fenced
  artifacts receive a stable import identity. HTML source is untrusted even when
  saved. Save uses ACTION_CREATE_DOCUMENT; Share uses a read-only FileProvider URI
  grant. Both export the selected version and require an explicit UI action.
- Image imports use the system picker and a bounded private copy. A bundled Latin
  ML Kit model extracts text locally, with sampled/oriented bitmap decoding and
  a timeout; recognition needs no model download. OCR is marked as untrusted data
  in model context. Only vision-capable model requests include encoded image bytes;
  text-only requests include OCR/failure context. ML Kit may collect SDK diagnostic
  and usage metrics under its terms; on-device OCR is not a claim of zero SDK telemetry.

## Registered tool policy (Passes A and B)

| Tools | Risk and execution policy |
|---|---|
| `list_files`, `list_dir`, `read_file`, `list_zip` | SAFE: reads inside the private workspace run without asking. Outside reads, traversal and invalid paths are denied. |
| `create_document`, `create_spreadsheet`, `create_presentation`, `create_file`, `create_markdown`, `create_csv`, `create_svg`, `create_pdf`, `create_image`, `create_docx`, `create_pptx`, `create_xlsx`, `create_chart`, `zip_files` | SAFE: private workspace writes run **without confirmation**, using new-file semantics. The guard requests confirmation for an outside write, but current built-in tools still reject writes outside the workspace. |
| `web_search` | CONFIRM: every call displays the exact query before sending it to the configured DuckDuckGo or SearXNG service. Invalid/empty/oversized queries are denied. |
| `web_fetch`, `visit_web` | Declared SAFE with a dynamic guard: run without asking only for public HTTPS URLs of at most 200 characters, without a query string, on an exact host returned by search in this turn. Otherwise CONFIRM; malformed, private/local or non-HTTPS destinations are denied even if a user would approve. DNS checks also apply at connection time. |
| `render_chart`, `create_artifact`, `create_web_project` | SAFE: bounded private workspace outputs using new-file semantics. No external writes or network execution. HTML networking requires a separate preview confirmation. |
| `code_interpreter` | SAFE: cancellable isolated computation with time/output limits and no app/device access. Python runtime download has its own exact-resource confirmation; downloaded code never receives a network grant. |

Share, Save and opening source links are explicit UI actions, not model-callable
registered tools. Sources are numbered within each assistant turn; only registered
numbers become citation links. The model can still make unsupported claims, so check
the underlying sources. URL/path exfiltration on a trusted host cannot be ruled out
by this rule alone, and allowing a search query can disclose its contents.

## Pass D file and chat boundaries

- Batch imports use SAF content URIs with a 50-file message limit, 20 MB per file
  and 100 MB total. Model text context is capped at 120,000 extracted characters,
  with a sanitized inventory and truncation markers. At most four images receive
  encoded vision payloads; remaining images still supply local OCR context.
- Camera capture uses the existing system intent and narrow FileProvider cache;
  no CAMERA permission is declared. Share-target intake accepts bounded text and
  content URI grants, rejecting file/network URIs and this app's own provider,
  including encoded authority tricks. Shared content creates a new draft and is
  never automatically sent, opened externally or interpreted as an action.
- Multi-file artifacts accept up to 50 validated relative source paths with
  `index.html`, 500,000 characters per file and 1.5 MB combined UTF-8 source. They
  use the existing immutable canvas versions. Preview permits only exact local
  resources at a synthetic HTTPS origin; every request is intercepted, missing
  resources are blocked and none falls through to the network. CSP still denies
  connections, frames, forms, workers and base tags. There is no native bridge,
  file/content access, browser storage, downloads or device permission grant.
  ZIP export contains source files only, with no traversal paths or symlinks.
- Response styles and Study mode modify the system prompt. Follow-up suggestions
  are bounded trailing JSON parsed as text, not instructions to execute; tapping
  one fills the composer. They are disabled in temporary chats.
- Branches copy only the transcript prefix, retaining attachment context and
  complete tool/result pairs. They receive new chat/item identifiers, no pending
  run and no confirmation grants. Temporary branches remain temporary.
- Library indexes private workspace outputs and canvas versions, excludes internal
  metadata and known temporary outputs, and links only to existing saved chats.
  A per-file confirmation precedes deletion; a shared file is removed for every
  chat that references it. A deleted fenced artifact is not silently recreated by
  library refresh. Share, Save and external Open remain explicit user actions
  using read-only FileProvider grants or SAF, never model-triggered exports.

## CI and release boundary

All third-party workflow actions use full commit SHA pins with version comments.
Workflow tokens default to `contents: read`; only the release publication job has
`contents: write`. A tagged commit is checked out by a read-only validation job that
runs core/app JVM tests, Android lint and APK assembly. Publication depends on that
job and downloads its exact artifact ID, verifies its checksum, and publishes that
APK. A failed test, lint or assembly prevents publication. No signing credentials
are added by Pass A; the existing debug-release signing limitation remains below.

## Limitations

Guard policy cannot make untrusted language-model reasoning safe. Read every
confirmation and review artifacts before sharing them. A device compromise can
expose in-memory credentials or private files. Filesystem checks assume the app's
private directory is not concurrently modified by a privileged attacker.
Dependency and OS vulnerabilities are outside this policy's guarantees.

History and completed step checkpoints persist in private JSON files. Partial streaming
text remains in memory until an explicit Stop or a completed step is recorded. Source
numbers and search-host trust are stored with their turn; credentials are not in those
records. Replacing a turn saves the revised transcript and superseded-run identifiers
before its new checkpoint so recovery cannot restore removed replies. Settings and
artifact files persist.
Private upload copies are removed after preparation/discard. Imports are size-bounded and DOCX entity declarations are
rejected. PDFBox extraction is not a sandbox; malformed documents and dependency
vulnerabilities remain device-validation concerns.
Uninstalling clears them. Debug APKs are not production-signed and CI keys can
change between runs. Never commit API keys, account grants, signing keys,
`local.properties`, or private conversation fixtures.
