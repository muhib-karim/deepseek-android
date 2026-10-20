# v1.0.0 (versionCode 10)

The interface follows the official DeepSeek app screen by screen, in light and dark. Agent mode can now make real files
of every common type, run code in a sandbox, search the web and use remote tools, and every tool can be switched off in
**Plugins & tools**. Releases are published only after the release build passes an emulator suite with video.

**Status: not released.** Nothing was compiled or run locally: GitHub Actions builds, tests and records the results
after the branch is pushed. Every row below says PENDING CI until a run fills it in.

## New

- **Official reference** (`reference.yml`, manual): downloads the official app from APKPure, checks its signer against
  the digests DeepSeek publishes in `chat.deepseek.com/.well-known/assetlinks.json`, installs it unchanged on an emulator
  and captures its screens in both themes. [docs/reference/SCREEN_SPEC.md](reference/SCREEN_SPEC.md) describes them in
  text; no official asset is in the repository. The 2.6.2 Play build is wrapped in Google's PairIP license check, so the
  default capture uses the pinned 1.4.1 APK, offline (its server version check covers every screen when online).
- **Official-style interface:** welcome screen with DeepSeek's terms box and dialog, top bar, drawer (search chat
  content, Pinned / Today / Yesterday / 7 Days / 30 Days groups, Rename / Pin / Delete), user bubbles with a long-press
  menu, "Thought for N seconds", "Read N web pages" with the sources sheet, the composer with Think and Search pills
  (the microphone becomes send), the attach sheet, Settings, Account settings, Data controls (delete all chats) and
  Share chat (text or PDF).
- **Sign-in gate:** signed out, only the welcome screen, the sign-in page and the account list exist. A session unlocks
  the app only after DeepSeek confirms it (`GET /api/v0/users/current`), every time.
- **Plugins & tools** ([docs/TOOLS.md](TOOLS.md)): one switch per tool, shared with the API-key client. A switched-off
  tool is not offered to the model and is refused if asked for; a tool switched off while its approval shows does not
  run. Remote MCP tools work in account mode too and ask before every call.
- **More files in account mode:** Excel, PowerPoint, charts, PNG images and ZIP archives join Word, CSV, Markdown, PDF
  and code. Every file stays in its chat's folder. File cards offer Open, Edit, Download, Save as and Share.
- **Code sandbox in account mode:** JavaScript, or Python after a one-time download of Pyodide 0.26.4 whose files are
  checked against pinned SHA-256 digests.
- **Release E2E** ([docs/EVIDENCE_v1.md](EVIDENCE_v1.md)): UiAutomator journeys on the signed release APK against a
  local fixture (mocked backend) with video and step screenshots, required tool coverage from the fixture's own log,
  and a separate live check of DeepSeek's real sign-in page.

## Changed

- **Native Python is trusted execution.** `run_python`, `pip_install` and skill functions run with the app's rights
  (not a sandbox), so agents get them only after you switch on **Trusted execution** in Plugins & tools, and each run
  asks first. It is off after updating; the Run button on a code block still runs directly.
- Workspace tools of the API-key client (CSV, Markdown, SVG, Excel, PowerPoint, Word, charts, ZIP, read and list) now
  work in the chat's own folder instead of one shared workspace. Files made by earlier versions stay where they are.
- The tag workflow requires the release signing secrets, runs the full Release E2E suite on the signed APK and
  publishes only that APK and its checksum. No debug APK is published.

## Fixed

- `zip_files` could pack files from another chat's folder; generated files from the older tools landed outside their
  chat (not deleted with it, not listed in Files in this chat).

## Results

| Check | Where | Result |
|---|---|---|
| Unit tests (`:core:test`, `:app:testDebugUnitTest`) | CI | PENDING CI |
| Android lint | CI | PENDING CI |
| Debug and release builds | CI | PENDING CI |
| Emulator androidTests, light and dark (incl. PluginsToolsTest, FileJourneyTest, SandboxExecutionTest, SandboxPythonTest, SettingsFlowsTest, SignInGateTest) | Emulator smoke | PENDING CI |
| Official screenshots light and dark | Emulator smoke | PENDING CI |
| Release E2E, mocked backend, API 35 light (all journeys, tool coverage) | Release E2E | PENDING CI |
| Release E2E, mocked backend, API 35 dark and API 26 light (baseline) | Release E2E | PENDING CI |
| Release E2E, live login-page check | Release E2E | PENDING CI |
| Official reference capture | reference.yml | Welcome, terms, phone, password and reset screens of 1.4.1 captured offline in both themes (runs 39447927215, 39449534982); signed-in screens need a reference account |
| Signed release APK published with SHA256SUMS.txt | Tag workflow | PENDING CI |

Not tested on a real phone. Live model behaviour (DeepSeek's real replies to agent instructions) is not covered by
the mocked suite.
