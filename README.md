# DeepSeek Android

An English, open-source Android agent app for DeepSeek and OpenAI-compatible
models. MIT licensed. **Unofficial: not affiliated with or endorsed by DeepSeek.**

This is the v0.1 scaffold. Code and tests are included; the scaffold session did
not run a build, tests, Android SDK tools or network requests. GitHub Actions is
the build and test gate. Android 8.0 (API 26) or newer is required.

## What v0.1 includes

- Material 3 chat with light/dark themes, streamed replies, collapsible reasoning,
  paragraphs, bold text, inline code and fenced code blocks.
- Tool calling with Markdown, RFC 4180 CSV, sanitized static SVG and multipage A4
  text PDF artifacts; workspace file listing/reading and public HTTPS web fetch.
- Artifact cards with **Open**, **Share**, and **Save as…** through the system
  document picker. PDF and SVG viewing uses a compatible external viewer.
- A confirmation dialog showing the tool name and arguments, with **Allow once**
  and **Deny**, plus an eight-step agent limit and a Stop button.
- Keystore-backed encrypted provider settings. No analytics or account rotation.
- Pure Kotlin `:core` for protocol, authentication primitives, guards and tools;
  `:app` for Android UI, sign-in, encrypted storage and PDF creation.

Conversation history is in memory and disappears when the process ends. Artifacts
remain in the private app workspace until app data is cleared or the app is
uninstalled; their cards currently belong to the in-memory conversation. Use
Save as… or Share for durable copies. PDF output lays out text; Markdown and HTML
are not converted into rich PDF styling. SVG-to-PNG rendering is a v0.2 TODO.

## Connect a provider

Open **Settings**, choose a provider, save the settings, and return to chat.

| Provider | Configuration | Authentication |
| --- | --- | --- |
| API key | Enter your own Platform API key | `Authorization: Bearer …` |
| DeepSeek account | Configure supported endpoints in Advanced, then sign in | `x-dsh-auth-token`, without Bearer |
| Custom OpenAI-compatible | Base URL, model and optional separate key | Bearer key when supplied |

The default base URL is `https://api.deepseek.com`. Model presets are
`deepseek-chat` and `deepseek-reasoner`; you can enter another model ID. Available
models and tool/reasoning support depend on the provider. Models are not fetched
automatically. The client appends `/chat/completions` to the base URL; include
`/v1` in your base URL if the server needs it. Custom HTTP endpoints are permitted
for local development, but HTTP exposes prompts and credentials on the network.
Use HTTPS elsewhere. API-key and account providers require HTTPS.

### Account sign-in caveats

Authorize URL, token exchange URL and OAuth client ID **default to empty**. Until
configured the app says: **"Account sign-in endpoint not configured; use an API key"**.
No DeepSeek authorize or exchange endpoint is invented or bundled.

The configurable adapter sends a standard authorization-code request with S256
PKCE, random state, `login_source=web`, and a loopback redirect bound to
`127.0.0.1` on a random port. It opens a Custom Tab; passwords stay on the
provider's page. The token exchange is a form-encoded OAuth request and accepts
an `access_token` or `auth_token` JSON field. Configure it only for a provider
that explicitly supports this flow and its loopback redirect. The account grant
is bound to the selected inference origin; changing that origin requires sign-in
again. Removing the local grant does not revoke it on the provider's servers.

DeepSeek's harness-specific `auth_init` / `auth_exchange` / `auth_cancel` protocol
may need an additional adapter once a supported public contract is available.
This scaffold does **not** claim that third-party DeepSeek account sign-in works.
If refused or unavailable, use the first-class API-key option. It does not bypass
provider restrictions, spoof another app, rotate accounts or pool credentials.
Account inference may draw on Platform balance or bonuses; it is **not unlimited
free chat**. Tokens may expire; automatic refresh and remote revocation are not
implemented in v0.1.

Client identity headers describe **DeepSeek Android**, its version, Android platform,
language and UTC offset. Their provider-specific acceptance remains to be
verified against a supported integration. Inference and token-exchange redirects
are refused to avoid forwarding credentials to a different origin.

## Safety and permissions

**Only `android.permission.INTERNET` is requested.** There are no storage,
notification, microphone, install, location or broad package-query permissions.
Artifacts live under the app's private `files/artifacts` directory. The
non-exported FileProvider grants read access only to selected artifacts. Exports
use Android's Storage Access Framework; no broad storage permission is needed.

Known, safe workspace tools are allowed. Unknown tools are denied. Reads stay
inside the workspace; path traversal, encoded path tricks and symlinks are
rejected. Built-in writers create new files and never silently overwrite existing
ones. Guard policy requires confirmation for writes outside the workspace, but
v0.1 built-ins cannot perform those writes; the user exports through Save as….
Shell/install policy toggles default off and require confirmation every time when
enabled. No shell/install tools are actually registered in this scaffold.

Web fetch accepts public HTTPS only, checks IP literals and resolved addresses,
disables proxies and redirects, and limits responses to 2 MB with a 20-second
call timeout. HTML extraction is intentionally simple. Fetched text remains
untrusted and may contain prompt injection; the guard checks subsequent tool
calls regardless of model instructions. SVG output uses a conservative element
and attribute allowlist; scripts, event handlers, styles, external references,
DTDs and processing instructions are removed or rejected. It supports static
shapes, not every SVG feature. CSV cells follow RFC 4180; spreadsheet applications
may interpret formula-like cells when opened, so review untrusted CSVs there.

Do not send secrets in chat or model-generated tool arguments. Prompts, reasoning,
tool results and fetched text can be transmitted to the provider you configure.
See [SECURITY.md](SECURITY.md) for limitations and reporting guidance.

## Build and download

No Gradle wrapper JAR is committed. Dependencies use a version catalog with
AGP 8.7.2, Kotlin/Compose plugin 2.0.21 and Gradle 8.10.2 in CI, using JDK 17 and
Android compile/target SDK 35.

1. Push to `main` or open a pull request targeting `main`.
2. In **Actions → CI**, inspect tests and lint, then download
   **deepseek-android-debug-apk**. Failure reports are attached when available.
3. Version tags matching `v*` trigger the debug release workflow. Download
   `deepseek-android-<tag>-debug.apk` from **Releases**.

CI runs:

```sh
gradle --no-daemon :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The separate secret scan runs Gitleaks 8.21.2 in Docker; it does not use the
license-gated Gitleaks action. APKs use Android's automatically generated debug
key and are intended for testing, not Play distribution. Different CI runs can
use different debug keys; an update may require uninstalling the previous build,
which clears private app data. Export important artifacts first.

## Tests and remaining validation

Core JUnit tests are written before their implementations and cover SSE framing,
streamed tool calls, wire models, auth headers, PKCE's RFC 7636 test vector, state
and callbacks, guard decisions, workspace paths, CSV, SVG, web URL policy and
agent continuation/denial/cancellation. MockWebServer tests exercise real HTTP
streaming and tool-call assembly. The Android module has a plain JVM test for the
Markdown helper and token-response validation; it does not use Robolectric.

CI must compile and execute these tests. Device validation remains necessary for
Custom Tabs, loopback callbacks, encrypted settings, PDF pagination, FileProvider,
the document picker, dark mode and cancellation across configuration changes.

## Roadmap

- v0.2: Room history and conversation list; recover existing artifact cards.
- v0.2: SVG → PNG, charts and voice with permission requests only when needed.
- Supported account-provider adapter, token lifecycle and integration validation.
- Device tests for PDFs, encrypted preferences and system document actions.

## Credits and license

The protocol and safety design follows
[deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness)
(MIT; Copyright DeepSeek). This implementation is original. No code from other
unlicensed projects is included. See [NOTICE](NOTICE), [LICENSE](LICENSE),
[docs/PLAN.md](docs/PLAN.md), and [SKILLS_USED.md](SKILLS_USED.md).
