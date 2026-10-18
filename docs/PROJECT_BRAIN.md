# PROJECT_BRAIN — DeepSeek Android (Unofficial)

## Goal
v0.4.0 is released (Pass G, versionCode 4). Next: plan v0.5.0 from FEATURE_MATRIX open rows and QC feedback.

## Status
- Android account shell, encrypted session storage, scoped cookies, restricted login/PoW WebView and private local history are implemented. MainActivity now defaults to this shell; legacy API client is explicit in Settings.
- v0.5.0 account-client work started on the requested feature branch from refreshed origin/main; main unchanged.
- Read the supplied UI spec, current Compose architecture, tests, manifests, workflows and dependency catalog.
- Design: default DeepSeek web-session shell with native streaming; existing API client remains an explicit Settings fallback. Tokens stay out of UI state and diagnostics.
- Implemented core token/origin checks, bounded SSE patch reduction and cancellable Bearer+cookie native streaming. Added tests first for session parsing/expiry/origin/routing, PoW headers, incremental patch streams, native transport and ten light/dark screenshot fixtures. Builds, test execution and screenshots are CI-only; no push, tag or release in this session.

## Next step
Adapt emulator artifact capture, set version 0.5.0/5, document limitations and complete static/security review. Verify public worker protocol where reachable; document any unverified integration.

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
