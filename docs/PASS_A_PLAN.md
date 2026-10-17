# Pass A implementation plan

Goal: implement feature matrix rows 1–8 and the security review requirements.
Architecture: extend the existing core agent/registry/checkpoint contracts, then
connect durable turn state to Compose. Preserve the Kotlin 2.0.21, Compose BOM
2024.10.01 and minSdk 26 / targetSdk 35 configuration.

- [x] Protocol and loop: JVM tests for multi-step streamed tool requests, Chat mode,
  current-turn reasoning, cap and cancellation; extend `AgentLoop`, `RunCheckpoint`
  and request history preparation. Network tools cancel promptly; bounded local
  tools retain their durable-result guarantee.
- [x] Web: test the 200-character/query/exact-host confirmation rule, turn-scoped
  trust, source numbering and duplicate URLs. Keep DNS/private-network blocking,
  byte/time caps and redirect refusal. Search is CONFIRM; safe fetches are conditional.
- [x] Chat: persist sources/mode, add Chat/Agent composer selection, source sheet,
  inline tool outputs and confirmation sheet. Add tested history truncation for
  retry/regenerate/edit, retaining attachment context and removing superseded turns.
- [x] Security/release: deny cleartext, pin action SHAs, restrict token permissions
  and gate tagged releases on tests/lint. Document the exact risk table.
- [x] Verification: inspect imports/APIs, XML/resources/workflow syntax, security
  boundaries and diff; record pending CI/device checks in BUILD_NOTES.

Review focus: cancellation during confirmation/network I/O; crash recovery after a
completed tool; stale search trust crossing turns; editing a turn with attachments;
tagged release publishing without validation. No local Gradle, commits or pushes.

Implementation/source checks are complete. All test cases above are written; JVM
execution and Android compilation/lint remain pending CI, as required by the brief.
