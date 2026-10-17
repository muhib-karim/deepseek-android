# AGENTS.md — project rules for coding agents (Codex, Claude Code, others)

## Project
- What it is: DeepSeek Android (Unofficial), an agentic chat client for DeepSeek with tool calling, cited web tools and in-app outputs.
- Stack: Kotlin, Jetpack Compose, Gradle (Kotlin DSL). Package `app.dsandroid`.
- Entry points: `app/` (Android UI, Compose), `core/` (pure JVM logic: agent loop, LLM client, tools, guards, persistence).

## Commands
- Build: GitHub Actions only (`.github/workflows/ci.yml`). Do not run Gradle locally.
- Test: JVM tests in `core/src/test` run in CI.
- Lint: Android lint runs in CI.
- Release: tag `vX.Y.Z` triggers `.github/workflows/release.yml` (gated on tests and lint for that tag).

## Rules
- Read `docs/PROJECT_BRAIN.md` before changing code; keep its Status and Next step current.
- Tests first; CI green before any merge or tag. Never force-push.
- Run a secret scan (gitleaks) before every push. No secrets, tokens, signing keys, `local.properties` or machine paths in the repo.
- Keep changes small and committed: one working step = one commit, so a killed session loses one step at most.
- Preserve minSdk 26 and targetSdk 35. Install permission: INTERNET only; microphone is runtime only.
- Anything that leaves the app or changes the device needs a per-action confirmation (see SECURITY.md).
- Licence: MIT. Builds on deepseek-harness (MIT) with attribution. GPL/AGPL projects are ideas only; never copy their code. Original code and assets only.
- No automatic account switching.

## Memory
- `docs/PROJECT_BRAIN.md` is this repo's persistent memory. Read it first. Update Status / Next step after each meaningful step, and append a Session log line before you stop. Commit it with your work.
- Decisions that should outlive this repo go to the `memory` MCP tool, named by project.
