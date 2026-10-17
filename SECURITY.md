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
  elements/attributes. Static SVG/HTML previews use a WebView with JavaScript,
  file/content access, network requests and navigation disabled. PDF creation
  renders plain text; PDF previews use the system PdfRenderer.
  CSV quoting does not disable spreadsheet formula interpretation.

## Registered tool policy (Pass A)

| Tools | Risk and execution policy |
|---|---|
| `list_files`, `list_dir`, `read_file`, `list_zip` | SAFE: reads inside the private workspace run without asking. Outside reads, traversal and invalid paths are denied. |
| `create_file`, `create_markdown`, `create_csv`, `create_svg`, `create_pdf`, `create_image`, `create_docx`, `create_pptx`, `create_xlsx`, `create_chart`, `zip_files` | SAFE: private workspace writes run **without confirmation**, using new-file semantics. The guard requests confirmation for an outside write, but current built-in tools still reject writes outside the workspace. |
| `web_search` | CONFIRM: every call displays the exact query before sending it to the configured DuckDuckGo or SearXNG service. Invalid/empty/oversized queries are denied. |
| `web_fetch`, `visit_web` | Declared SAFE with a dynamic guard: run without asking only for public HTTPS URLs of at most 200 characters, without a query string, on an exact host returned by search in this turn. Otherwise CONFIRM; malformed, private/local or non-HTTPS destinations are denied even if a user would approve. DNS checks also apply at connection time. |

Share, Save and opening source links are explicit UI actions, not model-callable
registered tools. Sources are numbered within each assistant turn; only registered
numbers become citation links. The model can still make unsupported claims, so check
the underlying sources. URL/path exfiltration on a trusted host cannot be ruled out
by this rule alone, and allowing a search query can disclose its contents.

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
