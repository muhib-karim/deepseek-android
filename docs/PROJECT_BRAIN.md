# PROJECT_BRAIN — DeepSeek Android (Unofficial)

## Goal
v0.6.0 "Agent": one AgentLoop + ToolRegistry with two backends (native tool_calls on the API-key client; fenced ```tool JSON protocol on account-mode web chat), on-device Python (Chaquopy), PDF/DOCX/CSV files with artifact cards, installable skills. CI validates.

## Status
- v0.6.0 "Agent" released 2026-10-18 (PR #18, merge 63897c9; tag v0.6.0; versionCode 7). Signed release APK, debug APK and SHA256SUMS.txt on the release.
- :core: ToolCallProtocol + WebToolProtocolClient (web backend), AgentLoop per-tool timeout, PdfLayoutEngine + DocumentModel (images, tables, lists, page breaks), DocxWriter/CsvWriter, write/read/present/write_file, run_python/pip_install + WheelInstaller, skills. JVM tests written for all.
- :app: Chaquopy 17.1.0 / Python 3.13 (numpy, matplotlib) in a :python worker process, account-mode agent UI (toggle, tool rows, artifact cards, Export PDF, Run, Skills page, approvals), API-mode agent tools, built-in skills, emulator tests AgentModeTest + PythonRuntimeTest. CI workflows install Python 3.13.
- CI green (unit tests, lint, debug + signed release) and 62/62 emulator androidTests; docs/AGENT_MODE.md rows 1-14 PASS, row 15 (live account agent turn) NOT RUN.

## Next step
v0.7.0: see the open milestone issues on GitHub (editor + artifacts panel with edit-and-rerun, account manager with manual switching, share target). Release-build Chaquopy pip now retries (PyPI timeouts broke the first v0.6.0 release run).

## Decisions
- Keep Kotlin/Compose and the existing two modules; preserve SDK levels and permissions.
- Search requests require confirmation; fetch trust is limited to search hosts in the current turn.
- Safe private workspace tools keep their existing unconfirmed behavior; document it explicitly.
- Never run Gradle locally; CI is the build.

## Gotchas
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
