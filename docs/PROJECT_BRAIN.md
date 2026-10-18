# PROJECT_BRAIN — DeepSeek Android (Unofficial)

## Goal
Ship v0.2.0: the agent loop (real DeepSeek function calling, outputs inside the chat) plus the v0.2.0 rows of docs/FEATURE_MATRIX.md, in three passes (A, B, C), each committed when CI is green.

## Status
- v0.2.0 prepared: Pass A (agent tools, confirmations, cited web search/fetch, QC notes from v0.1.0), Pass B (code interpreter sandbox, charts, artifacts canvas, OCR input) and Pass C (deep research, memory, custom instructions, tool servers, export, checkpoints, projects, temporary chat, pins, full Bangla) all merged to main with CI green.
- Bangla: values-bn covers all app strings; app and voice language settings offer Bangla; locales_config lists en and bn.
- Known gap: project shared-files UI (model field exists, no screen).
- No local Gradle execution; compilation, JVM tests and Android lint run in CI.

## Next step
Tag v0.2.0, confirm the release APK downloads, request QC. Then Phase 3 (v0.3.0).

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
