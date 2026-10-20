# PROJECT_BRAIN — DeepSeek Android (Unofficial)

## Goal
v1.1.0 (versionCode 11) on branch feature/v1.1.0 from main (v1.0.0 released, merge be88037): live-service fixes and official-parity
pass. A real signed-in run against the live service found what the mocked CI hid (gap list kept outside the repo; summary in
docs/RELEASE_NOTES_110.md once written): uploads need PoW + fetch_files polling, Search streams use negative fragment indices,
relative BATCH paths, TOOL_SEARCH/TOOL_OPEN and [reference:N], no server history, blunt errors, first send flaky (WebView PoW).
Done means: native DeepSeekHashV1 solver for completion and upload, upload polling, tolerant reducer with citation chips and
search timeline, server history merged in the drawer (open + continue), honest errors with one automatic retry, UI parity items
(no title on empty chat, outlined composer buttons, Think glyph, inline attach panel with recent photos, file chips, thinking
timeline with favicons, Like/Dislike via message_feedback, magnifier, neutral 20 dp dialogs, Saved-to-Downloads snackbar, share
a reply as an image card), mocked E2E fixture emitting the live shapes, docs, 1.1.0 / 11. Draft PR only; never merge or tag.

## Status
- v1.1.0 in progress on feature/v1.1.0, draft PR #31. Live contracts verified 2026-10-20 (QA account, probes outside the
  repo): PoW = SHA3-256 sponge with Keccak rounds 1..23 over "<salt>_<expire_at>_<n>"; upload_file needs X-DS-PoW-Response
  for its own path (40300 MISSING_HEADER, 40301 INVALID_POW_RESPONSE), then GET fetch_files?file_ids= until SUCCESS;
  fetch_page count 2..100 with lte_cursor.updated_at + lte_cursor.pinned (boundary item repeats), has_more;
  history_messages gives chat_session.current_message_id and FILE/REQUEST/THINK/TOOL_SEARCH/TOOL_OPEN/RESPONSE fragments;
  POST chat_session/update_pinned {chat_session_id, pinned}; POST chat/message_feedback {chat_session_id, message_id,
  feedback_type: GOOD|BAD|null}; streams with files carry an `update_file` event; Search-only uses SEARCH + [citation:N]
  (cite_index), Think+Search uses TOOL_SEARCH/TOOL_OPEN + [reference:k] into the RESPONSE fragment's references.
  Done: a3dbad7 native solver; b1e23c5 reducer (negative indices, batches, citations, timeline, typed errors); b1987f8
  client (upload PoW + polling, history paging, history_messages, pin, feedback, ServerHistory.merge); 5f128a8 app wiring
  (native PoW, WebView solver removed, one retry + Retry, server history refresh/open/continue, pin/feedback sync); 76a13e6
  UI parity (empty-chat title, outlined composer, inline attach panel + recent photos with runtime READ_MEDIA_IMAGES, file
  chips, citation chips, thinking timeline + site icons, Like/Dislike, share card, neutral 20 dp dialogs, Saved-to-Downloads
  snackbar); 3db716f E2E fixture with live shapes (real PoW at difficulty 144000, upload PENDING->SUCCESS, Think+Search
  shape, per-account history with a seeded "other device" chat) + 3 new journeys; 9b25644 / 6fc275d UI and retry tests;
  fbf91eb / e6aaa41 docs + 1.1.0/11; 9e332ea lint fix (produceState); 7ab2fd1 chip accessibility action. CI runs: on
  3db716f Release E2E green (2 journeys, 22/22 coverage) and emulator green; on 9e332ea CI and Release E2E green, emulator
  failed only on the new chip test (fixed in 7ab2fd1).
- v1.0.0 released (PR #29, merge be88037, tag v1.0.0; versionCode 10): reference workflow + SCREEN_SPEC, official-style UI,
  welcome + sign-in gate, ToolCatalog + Plugins & tools, verified Pyodide sandbox + trusted execution, file cards, release E2E
  suite with coverage.json, signed + E2E-gated release workflow. Verified only against the mocked backend.
- v0.7.1 "Sign-in fix" released 2026-10-20 (PR #27, merge ffd2608; tag v0.7.1; versionCode 9). CI green and 72/72 emulator tests; opt-in live_login run passed twice (real sign-in page passes the WAF check and renders in the app's WebView on an emulator). One CI fix: local non-https documents had begun receiving a 403 (WebViewBoundaryTest), restored to the empty response. Not tested on a real phone: the sign-in page on the reporter's device, Google sign-in end to end, the bookmark hand-off in real Chrome.
- v0.7.0 "Workbench" released 2026-10-20 (PR #23, merge ed910d8; tag v0.7.0; versionCode 8). CI green and 68/68 emulator tests; one CI fix (BalanceTest: MockWebServer ignores DISCONNECT_AT_START on a reused connection, now DISCONNECT_AFTER_REQUEST). Contents: :core workbench/editor/accounts with JVM tests, :app artifacts panel, code editor, account manager, SyntaxPaletteTest, emulator WorkbenchTest + AccountManagerTest, versionCode 8 / 0.7.0, docs (README badges + section, RELEASE_NOTES_070, ACCOUNTS.md, FEATURE_MATRIX 65-67, PLAN Phase 2).
- v0.6.0 "Agent" released 2026-10-18 (PR #18, merge 63897c9; tag v0.6.0; versionCode 7). Signed release APK, debug APK and SHA256SUMS.txt on the release.
- :core: ToolCallProtocol + WebToolProtocolClient (web backend), AgentLoop per-tool timeout, PdfLayoutEngine + DocumentModel (images, tables, lists, page breaks), DocxWriter/CsvWriter, write/read/present/write_file, run_python/pip_install + WheelInstaller, skills. JVM tests written for all.
- :app: Chaquopy 17.1.0 / Python 3.13 (numpy, matplotlib) in a :python worker process, account-mode agent UI (toggle, tool rows, artifact cards, Export PDF, Run, Skills page, approvals), API-mode agent tools, built-in skills, emulator tests AgentModeTest + PythonRuntimeTest. CI workflows install Python 3.13.
- CI green (unit tests, lint, debug + signed release) and 62/62 emulator androidTests; docs/AGENT_MODE.md rows 1-14 PASS, row 15 (live account agent turn) NOT RUN.

## Next step
Confirm CI, emulator smoke and Release E2E green on 7ab2fd1 (PR #31); then write DONE_110.md in the task folder (outside the
repo) and stop. The supervising lane merges, tags and runs the signed-in live check; RELEASE_NOTES_110 rows stay PENDING
for it. Do not edit scripts/capture_reference.sh, e2e/reference/driver.py, scripts/check_reference.py or
.github/workflows/reference.yml (reviewer's).
Reference facts (verified 2026-10-20): APKPure's API (api.pureapk.com, as used by EFF's apkeep) serves com.deepseek.chat 2.6.2 (versionCode 282) as an XAPK (base + armeabi-v7a + language/density splits, minSdk 29, targetSdk 36). Its signer certificate SHA-256 digests (v2/v3 for API 24-32: 1E:EC:C4:6F:..., rotated v3.1 for API 33+: C6:89:3C:53:...) equal the two DeepSeek publishes in https://chat.deepseek.com/.well-known/assetlinks.json. The same API with x-sv 34 also lists an arm64-v8a build of 2.6.2 (token com.deepseek.chat_282_19557999: base + config.arm64_v8a + config.hdpi, minSdk 32, same signers); reference.yml now defaults to it on an API 34 x86_64 image (ARM translation). First dispatch (run 39436094511, API 30, armeabi-v7a) installed the app but found no sign-in screen and saved no diagnostics; the driver now saves diagnostics and tolerates consent sheets and slow first launches. Run 39440391796 found the real block: Play builds from versionCode 110 wrap the app in PairIP (com.pairip.licensecheck.LicenseActivity, 'Check that Google Play is enabled'). reference.yml now defaults to build=pre-pairip on an API 34 google_apis image (API 33 x86_64 images have no ARM translation: runs 39444214055/39444218498 failed with INSTALL_FAILED_NO_MATCHING_ABIS); build=current (Play image, Play as installer) stays selectable. pre-pairip = pinned 1.4.1 APK (versionCode 107, file token com.deepseek.chat_107_d7d7af11, sha256 a7d1dba5..., signer C6:89 only, arm64-v8a, no PairIP; 100 and 107 lack com/pairip, 110/134/154/200/284 have it).
Design: web account "default" keeps legacy slots (session key "session", deepseek-web-chats.json, workspace/account); others use session_<id>, deepseek-web-chats-<id>.json, workspace/accounts/<id>, workbench/<id>. One active id across both kinds; API-key profiles stay in SettingsStore.accounts (Settings key = profile "api-key"); API chats in conversations[-<profile>]. See docs/ACCOUNTS.md.

## Decisions
- Keep Kotlin/Compose and the existing two modules; preserve SDK levels and permissions.
- Search requests require confirmation; fetch trust is limited to search hosts in the current turn.
- Safe private workspace tools keep their existing unconfirmed behavior; document it explicitly.
- Never run Gradle locally; CI is the build.

## Gotchas
- Only AccountRegistry.switchTo (a tap in Settings > Accounts) may change the active account; failures go through AccountFailurePolicy (docs/ACCOUNTS.md).
- Tags inside clickable (merged) rows need useUnmergedTree = true in Compose tests.
- No local compiler: before each commit check cross-package extension imports, BoxWithConstraints reads in nested scopes, and in test files with explicit (non-wildcard) androidx.compose.ui.test imports every extension call (performScrollTo, assertIsOff, ...); members like assertDoesNotExist need none.
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
- 2026-10-20 15:53Z Claude Code (Opus 5.5): S6 catalog + Plugins & tools, S7 verified sandbox + trusted execution, S8 file journey + chat-folder isolation fix, welcome terms gate, reviewer CI/E2E/reference fixes; nothing compiled locally; next: S9 TOOLS.md + E2E journeys, S10 version/release gates.
- 2026-10-20 16:05Z Claude Code (Opus 5.5): S9 (coverage table + checker, AgentJourneyTest with share read-back, expiry and restart, TOOLS.md) and S10 (1.0.0 / 10, signed + E2E-gated release, release notes, README, matrix) committed; nothing compiled locally; next: CI results from the reviewer, fixes, release.
- 2026-10-20 16:10Z Claude Code (Opus 5.5): sandbox fix for UPDATE 15 (a1f8ee2), expiry journey (c6e8c09), removed tracked .pyc (e7176f6); next: CI results.
- 2026-10-20 22:55Z Claude Code (Opus 5.5): v1.1.0 core + app + UI parity + live-shape E2E fixture committed and pushed (PR #31); next: CI results, UI tests, docs, version.
- 2026-10-20 23:30Z Claude Code (Opus 5.5): v1.1.0 complete on PR #31 (live fixes A1-A8, parity B2-B7/B9/B12/B13/B18, live-shape E2E fixture, tests, docs, 1.1.0/11); next: final CI confirmation, DONE_110.md, supervisor merges/tags.
