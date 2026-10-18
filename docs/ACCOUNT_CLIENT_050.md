# v0.5.0 account client: implementation and validation boundary

This branch implements a default DeepSeek account shell. It has not yet been compiled,
run on an emulator, or validated against an authenticated account. It is not a claim
of official-app equivalence or a verified working Google login.

## Implemented paths

- A fresh launch shows chat with a one-tap **Log in with DeepSeek** CTA. No API key is
  requested there. Settings has an explicit **API-key fallback** entry to the existing
  client; this choice is never made after a session, PoW, quota or network error.
- Login loads `https://chat.deepseek.com` in an Android WebView. Native polling reads
  only `localStorage.userToken`, on the exact HTTPS origin, accepting raw strings,
  JSON strings and `{ "value": "..." }`. Both native URL and JS origin checks apply.
- Tokens use a separate EncryptedSharedPreferences store. CookieManager retains
  `ds_session_id`; native requests read cookies only for the fixed DeepSeek host.
  Token and cookie objects redact their string representations; UI state and local
  history contain neither. There is no HTTP logging interceptor or native JS bridge.
- Explicit logout clears the encrypted token, DeepSeek local storage, applicable
  host cookies and this mode's local history. It does not call `removeAllCookies`,
  `deleteAllData` or clear other browser data. Cookie deletion covers the root and
  known API paths; Android's CookieManager does not expose a complete domain/path
  inventory. An inherited `.deepseek.com` `ds_session_id` is also expired. This may
  sign out other DeepSeek subdomains sharing that cookie, but leaves unrelated sites.
- Session creation → challenge → solver → native SSE completion uses the documented
  request fields and web client headers. Bearer and cookies accompany all three
  requests. Completion also receives a Base64 UTF-8 `X-DS-PoW-Response`.
- Native streaming reduces THINK and RESPONSE fragments, SET/APPEND/BATCH patches,
  compressed path/operation state and response IDs. The last validated response ID
  is persisted as the next parent. Interruptions and unknown content shapes surface
  errors. Stops/failures block that thread from further sends until New Chat, avoiding
  accidental replay or an unverified parent after an interrupted turn.
- The shell uses the supplied light reference measurements, Android sans-serif and
  an original three-stroke abstract mark. Dark colors are an app interpretation.
  Markdown/code-copy reuse the existing renderer. Full mathematical typesetting is
  not added; this is not a claim of parity with every official rendering feature.
- History is local to this installation/session, not a server-history sync. Logging
  out or accepting a changed token clears previous local account history. Existing
  API-mode history remains separate.
- Camera, Photo and Document use system intents/pickers and the existing bounded
  local importer. Up to five attachments contribute extracted text/OCR after an
  explicit send confirmation. Original files/images are not uploaded; `ref_file_ids`
  stays empty. Voice uses the existing runtime RECORD_AUDIO dictation control.
- The new shell is English. Existing API-client localization and advanced agent
  features remain in the explicit fallback. No feature parity between modes is claimed.

## Worker evidence and blocker

On 2026-10-18 the public chat page returned HTTP 403 to this development environment.
The supplied worker URL was readable:
`https://fe-static.deepseek.com/chat/static/76608.8f2a9fa413.js`.
Inspection confirmed `type: "pow-challenge"`, the challenge's camel-case `expireAt`,
and `type: "pow-answer"` with a numeric `answer.answer`; failures use `pow-error`.
The worker's public base points to the same official CDN and imports a companion chunk.
No worker source, APK, official image or font is bundled or committed.

The independent loader creates a blob Worker from the authenticated same-origin
WebView page and imports that fixed official worker at runtime. Only challenge data
is passed to it, never a session token/cookie through a JS interface. Whether this
works under the live site's CSP, WebView worker rules, CDN responses and authenticated
session state is **unverified**. Failure, navigation, renderer death or timeout produces
an update-required error; no solver substitute, paid API or fabricated response is used.
Google sign-in may reject embedded browsers; the login screen always explains this,
and blocked windows/Google errors surface the same notice. Google login was not tested.

## Tests and CI handoff

Tests were authored before implementation in commit `99009f5`; that checkpoint references
new production types that did not yet exist. No red/green execution is claimed because
local Gradle, pushes and CI dispatch were excluded from this worker's scope.

- Core JUnit: token formats/redaction/expiry/origin/routing, PoW UTF-8 headers and worker
  mapping, SSE chunk boundaries/patches/error cases, parent persistence, native request
  contract, terminal events without socket close, expired sessions and redirect refusal.
- Android instrumentation: actual production shell with deterministic fake state;
  light/dark empty chat, populated chat, drawer, settings and logged-out login screen.
  Login fixture contains no live WebView. Also tests routing, attachment actions,
  thinking expansion, search filtering, 200% fonts and legacy Settings → chat navigation.
- WebView/storage instrumentation: synthetic locally loaded pages, blocked worker,
  exact-origin capture, encrypted storage and unrelated-cookie preservation. No real
  account or network is needed by these fixtures.
- Screenshot captures assert no synthetic session canary or Bearer header in semantics
  and no canary in app logs. Uploaded sidecar fields are strictly allowlisted. Raw
  logcat and authentication headers are not uploaded. Captures are visual-review
  artifacts, not pixel-diff tests against official screenshots or approved goldens.
- Emulator CI sets 1080px/440dpi (392.7dp) and captures ten PNGs plus safe metadata under
  artifact `official-ui-screenshots`. Capture collection runs even after test failure;
  missing images and unsafe metadata fail validation. Upload is gated on safety validation,
  independently of test success, so rejected captures are never published. Existing smoke reports remain.

The next worker should run CI unit tests/lint/debug/release assembly and the emulator
workflow, inspect all ten images, and test an actual DeepSeek login plus two consecutive
native chat turns (Think/Search separately). Specifically verify CSP/worker access,
expiry recovery, logout with HttpOnly cookies, rotation/Stop, small landscape layouts
and large fonts. Only then decide whether the build is suitable for release.
VersionCode is 5/versionName 0.5.0. minSdk 26, targetSdk 35 and permissions are unchanged.
