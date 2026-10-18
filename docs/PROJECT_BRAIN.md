# PROJECT_BRAIN — DeepSeek Android (Unofficial)

## Goal
Ship v0.2.0: the agent loop (real DeepSeek function calling, outputs inside the chat) plus the v0.2.0 rows of docs/FEATURE_MATRIX.md, in three passes (A, B, C), each committed when CI is green.

## Status
- Pass B (code interpreter sandbox, charts, artifacts canvas, file downloads, image OCR input)
  merged to main after CI green (compile, JVM tests, lint, assemble). Two CI fixes:
  explicit OkHttp Dns objects, and the attachment-edit test now covers the OCR block.
- Pass C Part 1 is committed; clean tree confirmed. Core contracts and ViewModel routing read; missing UI/actions identified. Display controls already exist.
- Core Pass C contracts/tests and implementations written: facts/projects/prompt composition, temporary checkpoint routing, exports, HTTPS MCP and research phases.
- Existing registry/guard/timeline retained; disabled tools are removed from specs and execution.
- Current brief forbids local Gradle, heavy tools, sub-agents, commits and pushes.
- v0.1.0 released (debug testing build); QC GO with 4 notes, all scheduled for v0.2.0: network-security-config, confirm fetches with query strings or long URLs, SECURITY.md wording on confirmations, CI SHA-pinning/permissions/release gate.
- Pass A (agent tools, confirmations, Chat/Agent toggle, thinking display, turn controls, cited web search/fetch, the 4 notes) committed and pushed; CI green.
- Chat controls, source persistence, web guards and release/network hardening are integrated.
- Independent recovery/UI and web-policy reviews found no remaining concrete issues after fixes.
- Static XML/TOML/YAML, resource references, workflow/permission checks and diff hygiene passed.
- No local Gradle execution; compilation, JVM tests and Android lint require CI.

## Next step
Finish Pass C UI: Memory/Tools/instructions/research/export, then Projects, temporary chat, display and full Bangla parity. Add focused JVM cases and static checks; CI runs compilation/tests/lint. Write BUILD_NOTES last. No commit or push.

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
