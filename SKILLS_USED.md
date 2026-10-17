# Build attribution and workflow

Requested project roles:

- Plan: Claude Code plan mode — Opus 5.5; approved design in docs/PLAN.md.
- Scaffold build: Codex GPT-6 Astra.
- Review: Claude Code Opus 5.5 — planned separate review, not claimed as completed
  by this scaffold session.

Applied skill guidance: using-superpowers, executing-plans,
test-driven-development, verification-before-completion,
api-and-interface-design, android-intent-security and
android-permissions-security. Original implementation; no project code copied.

Tests first: core contracts and behavior tests were written before core
implementations. The Markdown helper test also preceded its implementation.
The user explicitly forbade local Gradle, Android SDK, Git, downloads and network
operations. Test execution and build validation are delegated to GitHub Actions;
no red/green execution or successful build is asserted for the scaffold session.

The final source review checks conservative API usage and security boundaries.
It does not replace the planned separate review, CI or device validation.
