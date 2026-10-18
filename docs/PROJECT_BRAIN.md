# PROJECT_BRAIN — DeepSeek Android (Unofficial)

## Goal
Deliver v0.3.0 Pass E in order: assistants, manual accounts, browser, connectors, sub-agents, memory upkeep, scheduled tasks. Preserve the released v0.2.0 registry, confirmations, export and attachment paths; CI gates any release.

## Status
- All seven Pass E features and English/Bangla UI are implemented, extending existing paths. Added 39 JVM cases across nine test files; execution, compilation and lint remain deferred to CI.
- Static resource/XML/TOML, Kotlin lexical/import, fixed-script syntax, SDK/source-permission and diff checks passed, as did a redacted secret scan. README, matrix, security and connector setup notes are updated. No Gradle, browser, sub-agents, commits or pushes.

## Next step
Run CI tests/lint/assembly and validate browser, OAuth, secure accounts and WorkManager on devices before release. The final local edit records the Pass E BUILD_NOTES handoff.

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
