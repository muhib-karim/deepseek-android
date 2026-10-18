# PROJECT_BRAIN — DeepSeek Android (Unofficial)

## Goal
v0.4.0 is released (Pass G, versionCode 4). Next: plan v0.5.0 from FEATURE_MATRIX open rows and QC feedback.

## Status
- v0.4.0 released: Pass G merged; CI green (core tests, app unit tests, lint, assembleDebug, assembleRelease); version 0.4.0 (versionCode 4); debug APK, signed release APK and SHA256SUMS.txt on the v0.4.0 GitHub release.
- Pass G implementation saved: optional signed R8 release, APK checksums, unsigned release CI and merged-permission gate; emulator workflow and fake-backend smoke tests need the human action pin and CI execution.
- Accessibility changes, English/Bangla strings, confirmed SAF backup merging and a bounded redacted local diagnostics screen are saved. Restore preserves existing IDs, pauses imported tasks and strips unfinished execution state; credentials and workspace binaries are excluded.
- Added 18 JVM cases and four instrumentation cases. Lightweight XML/TOML/YAML/shell, localization, Kotlin lexical, checksum/manifest/redaction fixtures and contrast checks passed; Gitleaks found no leaks. No compilation, lint, JVM or emulator test success is claimed.
- SDK levels, version, main manifest/removal rules and existing action SHAs retained. No Gradle, browser, sub-agent, commit, push or tag was run.
- README, SECURITY and matrix delivery notes updated. Pass G BUILD_NOTES is the final edit; real screenshots and CI/device validation remain pending.

## Next step
Pin the emulator action to a verified full SHA. Run CI tests/lint/debug and R8 release assembly, then emulator smoke and device checks (SAF round-trip, TalkBack/200% fonts, release library paths). Configure CI signing secrets for a signed asset; collect real screenshots. No version bump or release is authorized by this pass.

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
