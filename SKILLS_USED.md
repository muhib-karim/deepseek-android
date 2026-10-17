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

## Pass 2 by Codex GPT-6 Astra

Implemented the owner's nine v0.1 additions: uploads, original DeepSeek-style
Compose interface, automatic model discovery, core Markdown parsing/rendering,
plain-English errors, in-app artifact viewers, rename/About/original adaptive
icon, and an ordered thinking/tool timeline refined by the final layout spec.

Applied test-driven-development, frontend-ui-engineering,
android-intent-security, requesting-code-review and
verification-before-completion guidance. Read the brainstorming and workflow
guidance against the already approved owner specification; the user's explicit
no-git/no-Gradle/no-SDK/no-network constraints take precedence over skill commands.
All implementation and assets are original. No upstream project code, third-party
installer or proxy was used.

Core behavior and wire-contract tests were authored before implementation, with
additional regression tests before core corrections. The existing app Markdown helper
tests were replaced by the broader pure-core Markdown suite. A separate read-only
source reviewer checked compiler risks and integration behavior. Review findings
covered cross-module nullability, editable settings during refresh, upload cleanup,
and streaming scroll behavior; these were addressed in source.

Only local source/configuration checks ran. No Gradle, SDK, network or git commands
ran. Tests have not run and no successful compile, lint or device result is claimed.
CI and the device checklist in docs/PASS2_VALIDATION.md are the remaining gates.
