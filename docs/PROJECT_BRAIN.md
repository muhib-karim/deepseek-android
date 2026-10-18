# PROJECT_BRAIN — DeepSeek Android (Unofficial)

## Goal
Prepare v0.3.0 Pass D: files in/out, multi-file artifacts, response styles/Study, follow-ups, branching and library. Preserve the released v0.2.0 registry, confirmations, export and attachment paths; CI gates any release.

## Status
- Pass D implementation complete for rows 17, 18, 21, 22, 23, 30, 50–54. Includes 24 new JVM test cases and English/Bangla resources. Static resource/permission/SDK/Kotlin lexical checks and redacted Gitleaks scan pass; compilation, JVM execution, lint and device checks remain pending CI.
- v0.2.0 released: Pass A (agent tools, confirmations, cited web search/fetch, QC notes from v0.1.0), Pass B (code interpreter sandbox, charts, artifacts canvas, OCR input) and Pass C (deep research, memory, custom instructions, tool servers, export, checkpoints, projects, temporary chat, pins, full Bangla) all merged to main with CI green.
- Bangla: values-bn covers all app strings; app and voice language settings offer Bangla; locales_config lists en and bn.
- Known gap: project shared-files UI (model field exists, no screen).
- No local Gradle execution; compilation, JVM tests and Android lint run in CI.

## Next step
Run GitHub Actions compile/tests/lint/assemble, then device-check file formats, pickers/camera/share intake, offline project previews/ZIP, branch gestures, library deletion and Bangla/large-font layouts. No Gradle, browser, sub-agent, commit, push or tag was run in this session. BUILD_NOTES contains the handoff and limits.

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
