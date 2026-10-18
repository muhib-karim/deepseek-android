# PROJECT_BRAIN — DeepSeek Android (Unofficial)

## Goal
Deliver v0.3.0 Pass F in numbered order: voice, read aloud, voice images, phone intents, screen capture, quick launch and widget. Preserve existing registry, attachments and export.

## Status
- Pass F implementation and English/Bangla resources are saved: foreground voice/TTS, sentence reading controls, reviewed camera/screen attachments, confirmed phone intents, quick launch and RemoteViews widget. Existing chat, registry, importer, artifacts and export paths are retained.
- Added 40 JVM cases in eight files. Lightweight XML/TOML, resource/localization, Kotlin lexical/import, SDK/source-permission, machine-path and diff checks passed; redacted secret scanning passed. Compilation, tests and lint are unrun and remain CI gates.
- Alarms are omitted because SET_ALARM requires an install permission. API 26–28 projection uses fresh consent while visible; API 29+ capture only this app window via PixelCopy. Dynamic shortcuts respect launcher capacity. No new dependencies or install permissions.
- README, SECURITY and feature rows 34–39/57 are updated. No Gradle, browsers, sub-agents, review skills, commits, pushes, tags or version changes.

## Next step
Final edit adds the Pass F BUILD_NOTES handoff. Then run GitHub Actions tests/lint/assembly and device-check microphone/voice availability, interruption, capture lifecycle/review, phone handlers, launcher/tile/assist and widget privacy before release.

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
