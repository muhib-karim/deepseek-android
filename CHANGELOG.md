# Changelog

Release notes per version are in [docs/](docs/) (`RELEASE_NOTES_*.md`).

## 1.3.0 "Official DeepSeek engine" (versionCode 13), in review

- New **full** build (default, `app.dsandroid.full`, targetSdk 28): DeepSeek's own harness `@deepseek-ai/dsh` on a
  bundled Node runtime, full screen; based on dsh-android (MIT). **play** build: the 1.2 app unchanged.
- First screen with two routes (Continue with Google, Use API key); one ⋮ menu; opaque, inset-correct harness UI
  without wallpaper or glass; Installed plugins (GitHub/npm/file/folder) in Settings > Plugins; daily verified harness
  updates with fallback; this app's own agent behind Advanced, off by default.
- Payload built in CI from sha256-pinned Termux packages and npm, with Android patches; per-ABI APKs.
- Details: [docs/RELEASE_NOTES_130.md](docs/RELEASE_NOTES_130.md), [docs/DSH_ENGINE.md](docs/DSH_ENGINE.md).

## 1.2.0 "Agent-first" (versionCode 12), 2026-10-21

- Agent mode on by default on both routes; only a person's own "off" sticks (`*_set_by_user` flag). API mode remembers it.
- Welcome screen: "Use your DeepSeek API key (agent with tools)" without "More sign-in options"; Settings > Accounts >
  Add API key; "the API-key client" is now "API mode".
- Tool calls render as collapsible cards (icon, human title, status, arguments and result); file cards with Open, Save to
  Downloads and Share in both modes.
- API mode routes tool turns to deepseek-chat; deepseek-reasoner only with Agent off.
- Step counter ("Step 3 of up to N") and Settings > Agent > Maximum steps (default 8, 1-20); Stop keeps finished steps.
- "What Agent can do" card with four example prompts on an empty chat.
- Tests: ModelSelector routing, agent loop error results, step limit and Stop, split tool_call deltas, an emulator and a
  release E2E journey from the welcome screen's API key button to a tool card and a shared file.
- Details: [docs/RELEASE_NOTES_120.md](docs/RELEASE_NOTES_120.md).

## 1.1.0 (versionCode 11), 2026-10-21

Live-service fixes and official-app parity. [docs/RELEASE_NOTES_110.md](docs/RELEASE_NOTES_110.md).

## 1.0.0 (versionCode 10), 2026-10-20

Official-style UI, tools catalog, verified sandbox, release E2E suite. [docs/RELEASE_NOTES_100.md](docs/RELEASE_NOTES_100.md).
