# HANDOFF — v1.2.0 "Agent-first"

Branch feature/v1.2.0, draft PR #32. Items 1-11 implemented (see TASKS.md). Owner direction: no new features; only fix
CI (CI, Emulator smoke, Release E2E) until green on the PR head, then report DS120_DONE <sha> <ci run> <emulator run>.
Never merge or tag. Live DeepSeek API is not reachable from CI; all tool loops are verified against mocks.
Docs screenshots (docs/screenshots/v120-*.png, referenced in README) come from the emulator-smoke artifact
(smoke-screenshots/) once a green emulator run exists.
