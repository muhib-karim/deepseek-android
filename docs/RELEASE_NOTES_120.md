# v1.2.0 "Agent-first" (versionCode 12)

The engine for tools was already in the app (function calling in API mode, a text tool protocol on the account route,
files, PDF and Office documents, charts, a JavaScript and Python sandbox, web search and reading, MCP), but a new person
never met it: Agent mode started off, the API key (the route with real function calling) hid under "More sign-in options"
as "the separate API-key client", and the first screen looked like a text-only chat. v1.2.0 puts the agent first.

**Status: in review, not released.** Nothing was compiled or run locally: GitHub Actions builds and tests the branch. The
live DeepSeek API is not reachable from CI, so every tool loop is verified against mocked backends (MockWebServer in the
emulator tests, `e2e/fixture/server.py` in the release E2E suite). The CI record is at the end.

## What changes for you

- **Agent is on by default** on both routes. On an existing install it stays off only if you switched it off yourself
  (stored as `agent_mode_set_by_user` / `api_agent_mode_set_by_user`); a value saved by an older version without that flag
  reads as on. API mode now remembers your choice too. Under the composer one line says what Agent can do.
- **Use your DeepSeek API key (agent with tools)** is on the welcome screen, below the account sign-in buttons and the
  terms box, without "More sign-in options". The note keeps the billing fact: API use is billed to your DeepSeek platform
  balance. **Settings > Accounts > Add API key** opens the same dialog (checked with `GET /user/balance`, saved encrypted,
  then API mode). The API-key client is called **API mode** throughout.
- **Tool steps are cards**, as ChatGPT and Claude show tool use: an icon for the kind of tool, a human title
  ("Searched the web: …", "Read example.com", "Ran JavaScript", "Created report.pdf"), the status (spinner, done, error)
  and, on tap, the arguments and the result in monospace (long output is cut with a note). The same card is used in both
  modes. **File cards** offer Open, **Save to Downloads** (`Downloads/DeepSeek`, no storage permission on Android 10+;
  the system file picker before that) and Share.
- **Model routing in API mode:** a turn that offers tools (Agent on) uses `deepseek-chat`; `deepseek-reasoner` is used
  only with Agent off (DeepThink). The composer says so when DeepThink is selected with Agent on.
- **Step counter and limit:** "Step 3 of up to 8" while a reply works; **Settings > Agent > Maximum steps per reply**
  (default 8, 1 to 20) on both routes. Stop ends the run at once and keeps finished steps. At the limit the agent stops
  with "Stopped after N assistant steps. Send a new message to continue."
- **"What Agent can do"** on an empty chat (not a modal), with four examples that fill the composer: a one-page PDF of
  today's top AI news, compound interest with a chart, turning a file into CSV, and a Python script delivered as a file.
- **Files you attach** reach the model on both routes: API mode extracts PDF text on the phone (PdfBox-Android, already
  in the app), reads DOCX, XLSX, CSV and text, and recognises text in images; the account route uploads files to DeepSeek.

## Under the hood

- `ModelSelector.forAgentTurn` (core) chooses the model; `AppSettings.turnModel(think, tools)` uses it.
- `AgentLoop` accepts 1 to 20 steps (`MAX_STEPS_LIMIT`), reports the current step with each `StepsUpdated`, and names an
  unknown tool in its error result. `StreamAccumulator` ignores a tool-call id repeated on every delta.
- `ToolStepTitle` (core) gives both modes the same human titles and argument summaries.
- `AgentModePrefs` (app) stores both switches with their "set by user" flags.

## Tests added

| Test | Where | What it proves |
|---|---|---|
| `ModelSelectorTest.agentTurnsWithToolsUseTheChatModelAndReasonerOnlyWithoutTools` | core JVM (ci.yml) | Tool turns go to deepseek-chat (also offline and for custom endpoints); reasoner only without tools |
| `AgentFirstTest` (9 cases) | core JVM (ci.yml) | Agent default and migration; unknown tool and malformed JSON become error results; a throwing tool does not end the run; max steps is configurable with a clean stop message; step numbers; 1 to 20 range; Stop mid-tool keeps the finished step; tool titles |
| `NativeToolCallStreamTest` (+2) | core JVM (ci.yml) | tool_call deltas split mid-id, mid-name and mid-escape across throttled chunks; a repeated id is not doubled |
| `ApiAgentJourneyTest` (2 journeys) | emulator (emulator.yml) | Fresh install, the welcome screen's API key button, Agent on in API mode without a tap, examples, a native create_pdf tool_calls round trip on deepseek-chat with tools offered, the expandable tool card, Share on the file card, the streamed answer; the step counter while a reply is held, and Stop keeping the finished step |
| `AgentModeTest.agentIsOnByDefaultWithHintAndExamplesThatFillTheComposer` | emulator (emulator.yml) | Account route: Agent on by default, hint, examples fill the composer, a person's own off sticks |
| `ApiAgentJourneyTest` (release E2E) | release-e2e.yml | The same front-door journey on the signed release APK against `e2e/fixture/server.py`; the fixture logs the `api_agent` journey only for deepseek-chat with tools offered, and `e2e/coverage.json` requires it |

Updated: tests written for the plain chat route switch Agent off first (as a person would); agent tests rely on the on
default instead of tapping the pill; the release `AgentJourneyTest` checks Agent is on after sign-in, switches it off for
the chat-shape checks and on again for the tool cases.

## CI record

| Run | Workflow | Commit | Result |
|---|---|---|---|
| PENDING | CI (unit tests, lint, debug + release build) | | PENDING |
| PENDING | Emulator smoke and account screenshots | | PENDING |
| PENDING | Release E2E (mocked backend) | | PENDING |

Not covered by CI: the live DeepSeek API and a real phone. A signed-in live check is left to the release lane.
