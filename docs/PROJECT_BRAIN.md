# PROJECT_BRAIN — DeepSeek Android (Unofficial)

## Goal
Ship v0.2.0: the agent loop (real DeepSeek function calling, outputs inside the chat) plus the v0.2.0 rows of docs/FEATURE_MATRIX.md, in three passes (A, B, C), each committed when CI is green.

## Status
- v0.1.0 released (debug testing build); QC GO with 4 notes, all scheduled for v0.2.0: network-security-config, confirm fetches with query strings or long URLs, SECURITY.md wording on confirmations, CI SHA-pinning/permissions/release gate.
- Pass A (agent tools, confirmations, Chat/Agent toggle, thinking display, turn controls, cited web search/fetch, the 4 notes) committed; CI result pending at commit time.
- Chat controls, source persistence, web guards and release/network hardening are integrated.
- Independent recovery/UI and web-policy reviews found no remaining concrete issues after fixes.
- Static XML/TOML/YAML, resource references, workflow/permission checks and diff hygiene passed.
- No local Gradle execution; compilation, JVM tests and Android lint require CI.

## Next step
1. CI green for pass A (compile, JVM tests, lint, assemble); fix exact errors only.
2. Pass B, then pass C (remaining v0.2.0 matrix rows), each committed when green.
3. Release v0.2.0: identity check, manifest permissions check, secret scan, tag, APK link check, QC request.
4. Then Phase 3 / v0.3.0 (phone control with a confirm before every action).

## Decisions
- Keep Kotlin/Compose and the existing two modules; preserve SDK levels and permissions.
- Search requests require confirmation; fetch trust is limited to search hosts in the current turn.
- Safe private workspace tools keep their existing unconfirmed behavior; document it explicitly.
- Never run Gradle locally; CI is the build.

## Gotchas
- Resume must retain source numbering and mode, and never replay completed tools.
- Only current-turn reasoning belongs in follow-up tool requests; omit older reasoning.

## Session log
- 2026-10-17 22:28Z Codex: integrated agent controls, web sources, network/CI hardening and recovery fixes; next: final review and static validation.
- 2026-10-17 22:33Z Codex: completed Pass A code/docs and static reviews; next: CI and device validation, with no local Gradle, commit or push.
- 2026-10-17 22:40Z maintainer: added AGENTS.md/CLAUDE.md project memory, committed pass A; next: CI green, then pass B.
