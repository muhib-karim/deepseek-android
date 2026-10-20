# PROJECT_BRAIN — DeepSeek Android (Unofficial)

## Goal
v1.0.0 (versionCode 10) on branch feature/v1.0.0, built on PR #28 (sign-in card, composer off while signed out). Plan: docs/PLAN_v1.md, slices S1-S10 in order (supersedes the queued v0.8.0). Owner's definition of done: official-reference capture workflow + SCREEN_SPEC.md, UI matching the official app in both themes, welcome screen and full sign-in gate, agent tools that output real files with a Plugins/Tools screen and docs/TOOLS.md, a release-config emulator E2E suite with video, versionCode 10 / 1.0.0 with RELEASE_NOTES_100.md rows "PENDING CI". The sign-in browser must keep working behind DeepSeek's AWS WAF check; Google sign-in goes through the phone's browser, never an embedded or spoofed WebView. CI validates (no local Gradle).

## Status
- v1.0.0 in progress on feature/v1.0.0 (nothing compiled locally; CI runs only after the branch is pushed). Done: docs/PLAN_v1.md; S1 reference workflow + SOURCE.json + SCREEN_SPEC.md (4b80ed4); S2 release E2E harness (:e2e UiAutomator module, e2e/fixture/server.py, emulator CA + iptables routing, release-e2e.yml, docs/EVIDENCE_v1.md) (f4d0fb2); S3 welcome screen + sign-in gate (users/current must confirm every session; SignInGateTest; LaunchTest updated to the welcome screen). S2 was rewritten before push (gitleaks flagged a synthetic fixture value): pushed history is 4b80ed4, 6fbd7d8 (S2), 3334c9f (S3), 437d953 (S4 core); reviewer runs CI on 437d953.
- v0.7.1 "Sign-in fix" released 2026-10-20 (PR #27, merge ffd2608; tag v0.7.1; versionCode 9). CI green and 72/72 emulator tests; opt-in live_login run passed twice (real sign-in page passes the WAF check and renders in the app's WebView on an emulator). One CI fix: local non-https documents had begun receiving a 403 (WebViewBoundaryTest), restored to the empty response. Not tested on a real phone: the sign-in page on the reporter's device, Google sign-in end to end, the bookmark hand-off in real Chrome.
- v0.7.0 "Workbench" released 2026-10-20 (PR #23, merge ed910d8; tag v0.7.0; versionCode 8). CI green and 68/68 emulator tests; one CI fix (BalanceTest: MockWebServer ignores DISCONNECT_AT_START on a reused connection, now DISCONNECT_AFTER_REQUEST). Contents: :core workbench/editor/accounts with JVM tests, :app artifacts panel, code editor, account manager, SyntaxPaletteTest, emulator WorkbenchTest + AccountManagerTest, versionCode 8 / 0.7.0, docs (README badges + section, RELEASE_NOTES_070, ACCOUNTS.md, FEATURE_MATRIX 65-67, PLAN Phase 2).
- v0.6.0 "Agent" released 2026-10-18 (PR #18, merge 63897c9; tag v0.6.0; versionCode 7). Signed release APK, debug APK and SHA256SUMS.txt on the release.
- :core: ToolCallProtocol + WebToolProtocolClient (web backend), AgentLoop per-tool timeout, PdfLayoutEngine + DocumentModel (images, tables, lists, page breaks), DocxWriter/CsvWriter, write/read/present/write_file, run_python/pip_install + WheelInstaller, skills. JVM tests written for all.
- :app: Chaquopy 17.1.0 / Python 3.13 (numpy, matplotlib) in a :python worker process, account-mode agent UI (toggle, tool rows, artifact cards, Export PDF, Run, Skills page, approvals), API-mode agent tools, built-in skills, emulator tests AgentModeTest + PythonRuntimeTest. CI workflows install Python 3.13.
- CI green (unit tests, lint, debug + signed release) and 62/62 emulator androidTests; docs/AGENT_MODE.md rows 1-14 PASS, row 15 (live account agent turn) NOT RUN.

## Next step
S4 chat/composer/drawer/themes, S5 settings/profile/share, S6 tool catalog + Plugins/Tools, S7 sandbox, S8 file journey, S9 full E2E + docs/TOOLS.md, S10 version 1.0.0 / 10 and release gates.
Reference facts (verified 2026-10-20): APKPure's API (api.pureapk.com, as used by EFF's apkeep) serves com.deepseek.chat 2.6.2 (versionCode 282) as an XAPK (base + armeabi-v7a + language/density splits, minSdk 29, targetSdk 36). Its signer certificate SHA-256 digests (v2/v3 for API 24-32: 1E:EC:C4:6F:..., rotated v3.1 for API 33+: C6:89:3C:53:...) equal the two DeepSeek publishes in https://chat.deepseek.com/.well-known/assetlinks.json. The same API with x-sv 34 also lists an arm64-v8a build of 2.6.2 (token com.deepseek.chat_282_19557999: base + config.arm64_v8a + config.hdpi, minSdk 32, same signers); reference.yml now defaults to it on an API 34 x86_64 image (ARM translation). First dispatch (run 39436094511, API 30, armeabi-v7a) installed the app but found no sign-in screen and saved no diagnostics; the driver now saves diagnostics and tolerates consent sheets and slow first launches.
Design: web account "default" keeps legacy slots (session key "session", deepseek-web-chats.json, workspace/account); others use session_<id>, deepseek-web-chats-<id>.json, workspace/accounts/<id>, workbench/<id>. One active id across both kinds; API-key profiles stay in SettingsStore.accounts (Settings key = profile "api-key"); API chats in conversations[-<profile>]. See docs/ACCOUNTS.md.

## Decisions
- Keep Kotlin/Compose and the existing two modules; preserve SDK levels and permissions.
- Search requests require confirmation; fetch trust is limited to search hosts in the current turn.
- Safe private workspace tools keep their existing unconfirmed behavior; document it explicitly.
- Never run Gradle locally; CI is the build.

## Gotchas
- Only AccountRegistry.switchTo (a tap in Settings > Accounts) may change the active account; failures go through AccountFailurePolicy (docs/ACCOUNTS.md).
- Tags inside clickable (merged) rows need useUnmergedTree = true in Compose tests.
- Resume must retain source numbering and mode, and never replay completed tools.
- Only current-turn reasoning belongs in follow-up tool requests; omit older reasoning.

## Session log
- 2026-10-17: pass B merged to main after two CI fixes; pass C started.
- 2026-10-17 22:28Z Codex: integrated agent controls, web sources, network/CI hardening and recovery fixes; next: final review and static validation.
- 2026-10-17 22:33Z Codex: completed Pass A code/docs and static reviews; next: CI and device validation, with no local Gradle, commit or push.
- 2026-10-17 22:40Z maintainer: added AGENTS.md/CLAUDE.md project memory, committed pass A; next: CI green, then pass B.
- Pass C parts 1 to 4 merged; version set to 0.2.0; build notes written.

- 2026-10-18 Codex: saved Pass D steps 1–7 and JVM cases; next: chat ergonomics and library, then static checks. No local build or commit.
- 2026-10-18 01:22Z Codex: completed Pass D code/tests/resources and README/matrix/security updates; lightweight checks and secret scan pass. Final edit writes BUILD_NOTES; next: CI and device validation.
- 2026-10-18 02:02Z Codex: completed resumed Pass E implementation, 39 JVM cases and documentation; final edit writes BUILD_NOTES, then CI and device validation remain.

- 2026-10-18 02:32Z Codex: saved Pass F implementation, 40 JVM cases and documentation; lightweight checks pass, CI/device validation remains. Final edit is the Pass F BUILD_NOTES section; no commit or push.

- 2026-10-18 03:19Z Codex: saved Pass G code, tests and docs; lightweight checks pass, CI/device validation pending. Final edit writes Pass G BUILD_NOTES; no commit or push.

- 2026-10-18 Codex: authored v0.5.0 protocol and screenshot tests before production changes; CI execution pending. Public worker contract inspected; public chat page returned HTTP 403.

- 2026-10-18 Codex: core session transport and patch reducer implemented against first-written tests; static diff check passed, compilation and execution remain CI-only. Next: Android login and shell.

- 2026-10-18 Codex: implemented account login, original Compose shell, history, text-only attachment imports and explicit legacy fallback. Independent review found stream/failed-page edge cases; regression tests and fixes added. CI-only validation remains.

- 2026-10-18 08:27Z Codex: added fourteen account feature paths, test-first mocked coverage, review fixes and screenshot completeness checks; next: CI compilation/tests/captures. No local build, commit or push.
- v0.5.0 released: account mode merged; CI and 54 emulator tests green (light and dark); version 0.5.0 (versionCode 5); debug APK, signed release APK and SHA256SUMS.txt on the v0.5.0 GitHub release. Open: live app-originated completion on a device.
- 2026-10-18 Claude Code (Opus 5.5): v0.6.0 Agent implemented (core protocol/loop/PDF/writers/Python/skills, Chaquopy worker, account-mode agent UI, API wiring, emulator tests, docs); nothing compiled locally; next: push, CI, fix, record results.
- 2026-10-20 maintainer: README/PROJECT_BRAIN updated to the released v0.6.0 state; Chaquopy pip given --timeout/--retries for release builds.
- 2026-10-20 11:09Z Claude Code (Opus 5.5): v0.7.0 Workbench implemented (artifacts panel, editor, account manager, core + emulator tests, docs); nothing compiled locally; next: push, CI, fix, record results.
- 2026-10-20 12:45Z maintainer: v0.7.1 sign-in fix released (WAF-safe sign-in browser, blank-page notice, Continue with Google through the browser, Paste session, locked signed-out composer, opt-in live_login diagnostic); next: v0.8.0.
