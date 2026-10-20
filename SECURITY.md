# Security

DeepSeek Android (Unofficial) requires validation before release. CI compilation, lint and unit tests are
required before distributing an APK. It has not received a production security
audit. Do not treat model output or web content as trusted instructions.

## DeepSeek web account mode (v0.5.0)

The default account mode uses a restricted embedded WebView at `https://chat.deepseek.com`.
Passwords are entered into DeepSeek's own page within that WebView; the app does not read
password fields. Only `localStorage.userToken` is read, with native and JavaScript exact-origin
checks. Google sign-in may reject embedded browsers and has not been tested. There is no
native JavaScript interface. File/content access, mixed content, popups and page permission
grants are disabled; navigation is restricted to the exact DeepSeek and Google account hosts.

The token is stored in separate EncryptedSharedPreferences. Native requests to fixed DeepSeek
endpoints carry Bearer plus CookieManager cookies; completions and file uploads also carry a
challenge-specific PoW header solved for that path. Since v1.1.0 the proof of work is solved natively
(DeepSeekHashV1 in Kotlin, cancellable); no DeepSeek script runs in the app or its WebView. Redirects,
OkHttp connection retries and automatic API/account switching are disabled. One automatic retry exists
(v1.1.0): a connection failure, server error, rejected proof of work or interrupted stream before any
reply text arrives is retried once with a fresh challenge; a send that still fails restores its draft
and files and offers Retry, which goes through the same confirmation as Send. HTTP/server errors are
mapped to fixed messages by type (network, server, session, interrupted reply, service changed, file),
with no raw bodies, credentials or headers in app diagnostics. Login-page console messages are
suppressed. See [validation notes](docs/ACCOUNT_CLIENT_050.md).

Logout clears the encrypted token, host storage, known-path host cookies and local account chat
history. It preserves unrelated-site cookies and all legacy API settings/history. The shared
`.deepseek.com` session cookie, if present, is expired as well. CookieManager cannot enumerate
all cookie domain/path attributes, so device logout verification remains required. There is no
server-side revocation claim. Local chat history contains no session secrets and follows the
app's existing private-files/backup-disabled boundary. Attachments are uploaded to DeepSeek
(`file/upload_file`, then `file/fetch_files` until processed) only after the send confirmation.

### v1.1.0 additions

- **History sync:** the signed-in account's own chat list (`chat_session/fetch_page`) is read at start, after sign-in or an
  account switch and when the drawer opens (at most every 15 s); a chat's messages (`chat/history_messages`) are read when
  it is opened. Results are stored in that account's private history file; a chat deleted elsewhere is removed here only
  when the whole list was read and it has no local files (otherwise it stays, read-only). Imported tool fragments are
  display data and never run.
- **Pins and feedback** (`update_pinned`, `message_feedback`) are sent only when the person taps Pin or Like / Dislike.
- **Site icons** for search steps: HTTPS GETs without cookies or credentials, at most 64 KB, to DeepSeek's icon service
  named in the search result (`cdn.deepseek.com`) or the result site's `/favicon.ico`, only when the step is shown;
  cached in the app's cache folder. They reveal to that site only that an icon was fetched.
- **Photo access** (`READ_MEDIA_IMAGES`, or `READ_EXTERNAL_STORAGE` up to API 32) is a runtime permission asked only when
  the person taps Recent photos in the attach panel; thumbnails are read locally and a photo is uploaded only when added
  to a message and the send is confirmed. Without the permission the system photo picker is used.
- **Create image** renders one chosen turn into a PNG in the app's cache (`share-cards/`, cleared on the next card); it
  leaves the phone only through the system share sheet the person opens.

The remaining provider/OAuth sections describe the explicit legacy API client.

## Agent mode (v0.6.0)

- **Approvals.** `pip_install` asks before every download. `web_fetch` keeps the existing policy:
  query strings, URLs over 200 characters and hosts not returned by this turn's search ask first;
  private networks and redirects are refused. `run_python` and skill functions ask before every run
  (trusted execution, see below). Other agent tools work only inside the app's private
  storage and run without a prompt. No tool writes outside the app sandbox; Save to Downloads,
  Share and Open in another app are explicit user actions on a file card (FileProvider grants
  read access to that one file).
- **Folders.** Agent tools resolve paths inside the chat's folder (`files/workspace/account/<chat>`
  or `files/workspace/chats/<chat>`); absolute paths, `..`, encoded tricks and symlinks are refused.
  Account-mode agent files are deleted with their chat and on logout.
- **Python (trusted execution).** Native Python, its packages and skill functions are offered to the agent only
  while Trusted execution is on (off by default since v1.0.0), and each call asks first. They are not a sandbox:
  code runs in a separate, non-exported `:python` process under the app's UID with the
  app's file access and no extra permissions. The app kills that process on timeout or Stop.
  Socket connections and DNS lookups are refused inside it; this is best effort (native code could
  bypass it), and the app's INTERNET permission is shared by all its processes.
- **Packages.** `pip_install` and skill `pip` requirements accept only `py3-none-any` wheels from
  PyPI over HTTPS, verify PyPI's SHA-256, refuse native code and unsafe archive paths, and record
  installed files for removal. A package's own code runs with the same rights as run_python.
- **Skills.** A skill's `main.py` runs as Python with the rights above, and its SKILL.md becomes
  instructions to the model. Install skills only from sources you trust; the install dialog lists
  a skill's tools and packages first. Archives are limited to 5 MB, 64 files and safe relative paths.
  Skill links must be public HTTPS (redirects may not leave HTTPS or reach private networks).
- **Web chat protocol.** In account mode the agent instructions, tool calls and tool results are
  ordinary messages in your DeepSeek chat and are stored by DeepSeek like any other message.
  Tool results and fetched pages are untrusted data; the guard checks every call regardless of
  what a page or result says.
- **Plugins & tools (v1.0.0).** Every tool has a switch (drawer or Settings > Plugins & tools; the
  same switches as the API-key client's Settings > Tools). A switched-off tool is left out of the
  tool list sent to the model, and a call for it is refused. The switches are read again before a
  tool runs, so a tool switched off while its approval is showing does not run. In account mode
  `web_search` asks before every search and names the search service; remote MCP tools ask before
  every call (see Remote tool servers), and their answers are sent to DeepSeek as tool results.

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
  at dictation or voice-mode use. ACCESS_NETWORK_STATE is explicitly removed during manifest merge;
  there are no storage, camera, overlay, accessibility or package-install permissions.
- Tool calls pass through the registry and guard before execution. Chat mode advertises
  no tools and refuses unsolicited tool calls. Agent mode runs only registered tools;
  unknown tools are denied, and no shell or install implementation is registered.
  The exact SAFE/CONFIRM behavior is listed below. Allow once or Deny applies to
  that one CONFIRM call, including after retry. Read-only connectors use a separately
  labelled per-chat grant, described below.
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
  Stop, timeout and renderer failure dispose the view. The code page has the reserved
  origin `https://sandbox.invalid` (never resolved: every request is intercepted, and
  only the cached runtime files are answered), so its blob worker is same-origin. CSP, request interception
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
  Since v1.0.0 every runtime file must also match a pinned SHA-256
  (`core/.../sandbox/PythonRuntime.kt`; the CDN files were checked byte for byte
  against the npm package pyodide@0.26.4, whose tarball matches the registry's
  sha512). A download with another digest is discarded; a cached runtime is hashed
  again once per app process, and a cache that no longer matches is deleted and
  downloaded again only after a new confirmation. The sandbox is offered in account
  mode too (Plugins & tools > Code sandbox).
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
| `code_interpreter` | SAFE: cancellable isolated computation with time/output limits and no app/device access. Python runtime download has its own exact-resource confirmation and pinned SHA-256 digests; downloaded code never receives a network grant. |
| `run_python`, `pip_install`, skill functions | Trusted execution (v1.0.0): native code with this app's rights, **not a sandbox**. Offered to the model only while Trusted execution is switched on (Plugins & tools, or Settings > Tools in the API-key client; off by default, never restored from a backup), and then every call asks first (TrustedTool, risk CONFIRM). The Run button on a Python code block is an explicit user action. |

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

## Pass E assistants and manual accounts

- Assistants are bounded private JSON records. Chats retain a snapshot of the
  selected instructions, tool allowlist and response style. An assistant cannot
  enable a globally disabled tool or bypass the guard. JSON exports contain only
  assistant configuration, through the existing explicit Save action.
- Up to 20 DeepSeek API-key/account profiles use the existing encrypted settings.
  Selecting one is an explicit UI action in Settings or the chat header. Adding
  a profile does not select it. Deleting the selected account clears credentials;
  no remaining profile is selected as a fallback. Model lists and pending runs
  are bound to the chosen provider/account identity.
- Rate-limit and quota errors do not switch accounts. Interactive runs surface
  HTTP 429 without automatic rate-limit retries; existing bounded transient
  network/server-error recovery uses the same account. Scheduled runs never retry
  inference automatically. Credentials are not written to chat/task/assistant JSON.

## Browser agent

- The registry exposes `open_url`, `read_page`, `click`, `type`, `submit`, `scroll`
  and `back`. Opening/reading follows `web_fetch`: SAFE only for public HTTPS URLs
  at most 200 characters long, with no query string, on an exact search-result
  host trusted in that turn. Other public URLs ask first; malformed, non-HTTPS,
  private/local destinations are denied. Scroll is local and SAFE. Every click,
  text entry, submission and back action is CONFIRM.
- The preview contains the current page/revision, target, text and, for navigation,
  exact destination, method and form fields. The controller re-inspects the target
  before execution and rejects an obsolete approval. A denied preview cannot
  authorize a later call. Confirmations are serialized across the parent and
  child loops.
- A public-HTTPS client performs only the approved request, with connection-time
  DNS validation, no redirects, proxy, cookie jar, provider credentials or referrer.
  Responses are capped at 1 MiB and 30 seconds. Link navigation and simple GET/POST
  forms use this client; no native WebView submission can bypass the preview.
- Pages render in the existing isolated, opaque-origin WebView. CSP denies site
  scripts, connections, forms, frames, objects and external resources. Only fixed
  internal scripts inspect targets, set a value or scroll; model input is JSON
  data, never executable JavaScript. There is no native bridge, file/content access,
  web storage, popup, download, device permission grant or saved form data.
- Password/login/sensitive form fields are refused; no automatic login or password
  manager is available. Native WebView touch, focus and accessibility actions are
  disabled so they cannot bypass the controller. Take over stops the agent and
  provides native read/scroll/navigation/field controls with the same previews.
  Users can inspect page text and target numbers in those controls. Clearing or
  switching chats disposes the browser and its bounded in-memory history.
- This supports static pages and simple forms, not arbitrary scripted sites or
  authenticated browsing. Field heuristics cannot identify every kind of sensitive
  value; review entered text and destination fields. Untrusted page instructions
  never authorize an action. WebView renderer memory is not subject to a hard heap
  quota; hostile documents still require adversarial device validation.

## Read-only connectors

- The generic connector uses user-configured public-client OAuth2 authorization
  code with S256 PKCE, random state and the existing IPv4 loopback callback.
  Sign-in occurs in the system browser. No client secrets are stored or bundled.
  Authorization and exchange endpoints must be public HTTPS. Access tokens are
  encrypted separately from configuration; account/inference keys are never reused.
- Search and read are GET-only, with bounded queries, encoded item IDs, and a read
  placeholder confined to one path segment on the same HTTPS origin as search.
  Requests use connection-time public-DNS checks, no redirects, proxy or cookies,
  a 30-second deadline and 1 MiB response cap. At most 40,000 characters are returned
  to the model. No connector write operation is exposed. Use read-only scopes and
  endpoints; the app cannot verify a configurable provider's scope or GET semantics.
- First use in each chat shows the exact initial request and **Allow for this
  chat**. It grants only search/read on that connector for that chat. Child agents
  in the same chat share it; branches and other chats do not. Grants stay in memory
  and are lost after process death. Disconnect deletes the token and revokes local
  grants. Local deletion is not server-side revocation. Expired tokens require an
  explicit Sign in again; background refresh is not implemented.
- Returned data remains untrusted and goes to the selected inference provider as
  tool context. A connector sees queries/item IDs, not the entire conversation.
  Setup requirements and adapter limitations are in `docs/CONNECTORS.md`.

## Parallel agents, memory upkeep and tasks

- `parallel_agents` validates a nonempty plan and one to four independent tasks.
  Each child has separate history, source numbering and a guarded tool loop,
  capped at six assistant steps. The group shares 24 inference attempts and 40
  tool executions with the parent; children reserve one request for final synthesis.
  Allowed child tools are private reads, enabled web search/fetch, and read-only
  connectors. Browser actions, writes, remote tool servers, memory writes, scheduling
  and recursive delegation are excluded. Stop propagates cancellation to all
  children and pending confirmations. Progress is session-only; completed results
  enter the existing parent tool-result checkpoint. Explicitly resuming an
  interrupted read-only group may repeat reads and starts a fresh budget.
- Memory upkeep is off by default. When enabled, a separate tool-free provider
  request after an answer receives bounded recent user/assistant messages from up
  to three non-temporary chats and bounded saved facts. It proposes at most eight
  changes; at most 50 pending suggestions persist privately. No proposal mutates
  memory until the user approves it in Settings → Memory. Edit/delete approvals
  compare the prior text to reject stale changes. Deleting a chat also removes its
  pending suggestions, while previously approved facts remain user-managed.
- `schedule_prompt` is CONFIRM; manual creation has an explicit schedule-approval
  button. It records the exact prompt, time/repeat interval, model and account
  identity, without credentials. WorkManager 2.9.1 uses inexact JobScheduler work on
  API 26+, with no network constraint, foreground execution, notifications or exact
  alarms. Transitive network-state, wake-lock, boot, foreground-service and
  notification permissions are explicitly removed, along with the foreground
  service. INTERNET and runtime RECORD_AUDIO remain the only requested permissions.
- Each scheduled occurrence checks that the original account is still selected,
  makes at most one bounded inference request (120 seconds/32,000 output characters,
  including reasoning), advertises no tools and rejects unsolicited tool calls.
  It never signs in, switches accounts, approves actions or uses chat history,
  attachments, memory or connector tokens. Repeats have a minimum 15-minute interval;
  at most 50 schedules are active. Errors and answers go to a private Tasks inbox.
- A durable occurrence claim prevents automatic replay after interruption; an
  interrupted occurrence reports an error rather than risking duplicate billing.
  Schedule reconciliation on app launch repairs a journal/enqueue interruption.
  Reopen after reboot/force-stop because boot access is absent. The inbox retains
  up to 100 results, pruning older results/completed schedules to an 8 MiB byte
  limit. Cancel stops future executions and requests cancellation of current work;
  a request already delivered to a provider cannot be withdrawn.

## Remote tool servers

- Only public HTTPS endpoints are accepted (at most 2,048 characters). Plain
  HTTP, localhost, private or link-local addresses, credentials in the URL and
  fragments are rejected; every resolved address is checked again before
  connecting, so a public name that resolves to a private address is blocked.
- No redirects, no proxy, no cookies, and no DeepSeek or other provider
  credentials are ever sent to a tool server.
- Responses are capped at 1 MiB.
- Every remote tool is registered as CONFIRM: each call shows the server, tool
  name and arguments and runs only after you approve it. Each tool can be
  switched off in Settings → Tools (or Plugins & tools in account mode), and
  removing a server removes its tools. Account mode and the API-key client
  share the list of servers.
- A tool server sees the arguments you approve and can return misleading text;
  treat its output like any web page.

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

## Pass F voice, capture and phone boundaries

- Voice is an explicit foreground session. Runtime RECORD_AUDIO permission and a
  deliberate Start listening tap precede capture; shortcuts, the widget, the tile,
  assist intents and process recreation never automatically activate the microphone.
  System recognition prefers the on-device service on API 31+ when available;
  fallback recognition can send audio to the selected system service. The app does
  not store audio. Recognized text enters the existing send/history path, including
  temporary-chat behavior. Starting voice means each recognized utterance is sent
  to the selected inference provider. No voice-specific relay or account switching.
- Playback uses installed offline TTS voices only, selecting English/Bangla by
  sentence text. TTS engines are platform/user-installed components and receive
  the text being read. Missing voices surface an error instead of selecting a
  network voice or downloading one. Pause/resume repeats the interrupted sentence.
  Callback generations prevent old recognition/TTS events from submitting twice,
  skipping sentences or restarting the microphone after interruption. Activity
  pause or audio-focus loss stops the voice loop; standalone read-aloud stays
  paused until explicitly resumed. Audio focus is released when speech ends.
- Camera snapshots use ACTION_IMAGE_CAPTURE through the existing TakePicture and
  narrow FileProvider path, without CAMERA permission. Voice snapshots are shown
  before attachment. Screen capture is always user-initiated, one frame per request,
  bounded to a 1280-pixel longest edge, and requires an image review before attachment.
  Capture resources close after the frame, cancellation, timeout or activity pause.
  A capture request never automatically sends a message or grants an agent screen access.
- On API 26–28, a fresh system MediaProjection consent is obtained per request and
  the app must remain resumed. Consent tokens are never persisted or reused. On
  API 29+, MediaProjection requires a foreground service; newer versions also need
  its mediaProjection type permission. Those install permissions are forbidden,
  so only the app’s own visible window is captured with PixelCopy after explicit
  per-request confirmation. There is no foreground capture service, other-app
  capture on those versions, overlay, accessibility access or continuous recording.
  Secure surfaces remain protected. Capture previews remain in private cache until
  discarded/imported or cleared by Android; successful/failed imports remove the
  original camera/PNG source. Process-death leftovers can remain in private cache.
- Attaching approves the staged image for the next spoken send. The existing batch
  limits, OCR and selected-model vision checks apply unchanged: image bytes are
  sent only for vision models; other models receive extracted text/failure context.
  Review sensitive content before attaching. The existing Latin OCR model may not
  extract Bangla text; Bangla TTS support does not imply Bangla OCR support.
- `draft_sms`, `draft_email`, `add_calendar_event` and `open_map` are ACTION tools
  with CONFIRM risk. The guard rejects invalid recipients, extra fields, malformed
  times and oversized payloads before confirmation. Each preview shows exact
  arguments; calendar previews also show localized times and the device timezone.
  The adapter builds only ACTION_SENDTO (`smsto:`/`mailto:`), ACTION_INSERT with
  CalendarContract.Events, or ACTION_VIEW with an encoded `geo:` query. User input
  cannot choose intent actions, components, flags or extra permission grants.
  Launch requires the resumed activity and uses the existing durable tool-result
  path. No SMS is sent, email delivered or calendar row written by the app; the
  user finishes in the target app. Maps can disclose the approved place query to
  the selected maps provider. No SMS, contacts, calendar or location permission.
- `set_alarm` is not registered: AlarmClock.ACTION_SET_ALARM requires
  `com.android.alarm.permission.SET_ALARM`, contrary to the install-permission rule.
  There is no silent alarm fallback. Existing scheduled prompts are unrelated.
- Quick launch accepts only fixed actions and ignores assist payload/context.
  The tile component is protected by BIND_QUICK_SETTINGS_TILE; this is a system
  binding restriction, not a uses-permission granted to this app. Widget actions
  use explicit immutable PendingIntents. The widget receiver is not exported,
  requests no periodic background work, and stores only a bounded saved title in
  private preferences. The launcher can read that displayed title; message bodies,
  credentials and temporary-chat titles are excluded. Renaming/deleting chats
  refreshes/clears the title. No new install permissions or dependencies are added.


## Pass G packaging and local data

- Release signing keys live only in repository CI secrets. Signing uses
  `DSA_KEYSTORE_BASE64`, decoded to a restricted temporary runner file and removed
  on exit, plus `DSA_KEYSTORE_PASSWORD`, `DSA_KEY_ALIAS` and `DSA_KEY_PASSWORD`.
  Gradle reads only `DSA_KEYSTORE_PATH` and the three signing environment variables;
  missing values leave release unsigned. Keystores are ignored and never committed.
  R8/resource shrinking affects release only. Debug remains debug-key signed, not
  audited; neither build variant has received a security audit. SHA256SUMS.txt
  covers every published APK and is checked again before publication.
- OS backup remains disabled. Explicit SAF backup uses a versioned, unencrypted
  JSON file with saved chats, project text, memory, assistants, task definitions
  and allowlisted non-secret settings. It never serializes encrypted preferences,
  accounts, API keys, cookies, connector tokens, provider/connector configuration,
  checkpoints, diagnostics or temporary chats. Workspace binaries are separate
  exports. Known stored credentials and recognizable secrets/emails in free text
  are redacted before export; arbitrary sensitive prose is still private data.
  Keep backups private and review their destination. No storage permission is used.
- Restore reads at most 16 MiB, bounds nesting and validates version, types,
  identifiers, paths, counts and store limits before changing live records. A
  confirmation shows the merge policy: existing IDs win, display/personalization
  settings are restored, accounts remain unchanged. Imported schedules are paused
  and unbound, with no enqueue or provider call; recreate a task to schedule it.
  Each record is saved atomically. Whole-archive writes are not one filesystem
  transaction: storage failure or process death may leave a partial merge, which
  can safely be retried without duplicate IDs. Invalid input fails before writes.
- Diagnostics persist only the last 200 errors/tool failures locally, each capped
  at 2,000 characters. Keys, token assignments, bearer/cookie headers, email
  addresses, private-key blocks and long token-like strings are redacted before
  storage. Call sites omit prompts, tool arguments/results, URLs and stack traces.
  Copy is explicit and Clear asks for confirmation. No diagnostics upload,
  analytics, new network call or crash-reporting SDK is added. Pattern redaction
  is conservative, not a classifier for arbitrary private prose; review copied text.
- Instrumentation dependencies and the smoke host are excluded from release.
  CI verifies the merged release manifest allows only INTERNET, runtime
  RECORD_AUDIO and (since v1.1.0) runtime photo access (READ_MEDIA_IMAGES, READ_EXTERNAL_STORAGE up to
  API 32); existing manifest-removal rules remain intact. Tests use an
  in-process fake LlmClient and never configure a real provider credential.
