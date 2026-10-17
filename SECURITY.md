# Security

DeepSeek Android v0.1 is a testing scaffold. CI compilation, lint and unit tests are
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
- Inference and token exchange refuse redirects. API/account credentials require
  HTTPS. Explicit custom HTTP endpoints are a user-selected development option.
- Tool calls pass through the registry and guard before execution. Confirmation is
  per call, never remembered as a blanket grant. Unknown tools are denied, and no
  shell or install implementation is registered. Cancellation stops pending work.
- Built-in file tools reject traversal and symlinks and stay in private artifact
  storage. FileProvider is not exported and exposes only artifacts and a narrow camera-cache directory. User exports
  use the system picker. Writes use new-file semantics to avoid overwrites.
- Web fetch rejects private, local and reserved destinations, validates every DNS
  result used by the connection, refuses redirects, and applies decoded-byte and
  time limits. It labels fetched text as untrusted; prompt injection is still a
  model-level risk, not something HTML stripping can solve.
- SVG is parsed without DTD/entity resolution and reduced to static allowed
  elements/attributes. Static SVG/HTML previews use a WebView with JavaScript,
  file/content access, network requests and navigation disabled. PDF creation
  renders plain text; PDF previews use the system PdfRenderer.
  CSV quoting does not disable spreadsheet formula interpretation.

## Limitations

Guard policy cannot make untrusted language-model reasoning safe. Read every
confirmation and review artifacts before sharing them. A device compromise can
expose in-memory credentials or private files. Filesystem checks assume the app's
private directory is not concurrently modified by a privileged attacker.
Dependency and OS vulnerabilities are outside this policy's guarantees.

History and streaming state are in memory. Settings and artifact files persist.
Private upload copies are removed after preparation/discard. Imports are size-bounded and DOCX entity declarations are
rejected. PDFBox extraction is not a sandbox; malformed documents and dependency
vulnerabilities remain device-validation concerns.
Uninstalling clears them. Debug APKs are not production-signed and CI keys can
change between runs. Never commit API keys, account grants, signing keys,
`local.properties`, or private conversation fixtures.
