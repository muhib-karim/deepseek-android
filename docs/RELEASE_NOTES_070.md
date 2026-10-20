# v0.7.0 "Workbench" (2026-10-20)

The artifacts panel, an in-app code editor and an account manager with manual switching only.
Tracked in issues #20, #21 and #22.

Validation: CI green on PR #23 (core JVM tests, app unit tests, lint, debug and release builds, merged-manifest
permission check) and 68/68 instrumented tests on an API 34 x86_64 emulator, including `WorkbenchTest` and
`AccountManagerTest`. The signed release APK is built by the release workflow from tag v0.7.0. No live run
against chat.deepseek.com or a real API key has been recorded yet (row 15).

## New

- **Artifacts panel** (#20). Any HTML, SVG or Markdown artifact, from a code block in an answer
  (**Preview**) or a file the agent made (**Open** on its card), opens in a panel: full screen on phones,
  next to the chat on windows 840 dp and wider. Tabs: **Preview** and **Source**.
  - HTML runs in a disposable sandboxed WebView: JavaScript for the artifact only, no file or content
    access, an opaque blank origin (`about:blank`), no native bridge, and no network until
    **Allow network for this preview** is confirmed (reset when the preview closes or another version is
    picked). SVG is drawn with AndroidSVG; Markdown uses the app's renderer.
  - Edit the source, then **Run**: the edit becomes the next version and the preview re-renders.
  - **Ask DeepSeek to change this** sends the current source (with your edits) and your instruction as
    the next message in the same chat; the code block in DeepSeek's reply becomes v2, v3, ... of the same
    artifact. The version picker lists every kept version with where it came from and a diff count
    line ("v3 · DeepSeek revision · +12 -3 lines").
  - Versions are kept per account (up to 30, the original always kept) outside the agent's chat folder.
- **Code editor** (#21). Opens text artifacts and workspace files (kt, py, js, ts, html, css, json, md,
  csv, sh, sql, xml, yaml, txt) from **Edit** on code blocks and file cards, or from **Files in this chat**
  in the drawer. Original syntax highlighting (keywords, strings, comments, numbers; tags and attributes,
  CSS selectors and properties, JSON and YAML keys, Markdown structure), line numbers, undo and redo,
  find, monospace at your text size. **Save** writes the chat's folder, **Save as** uses the system file
  picker (ACTION_CREATE_DOCUMENT, no storage permission), **Share** sends the text, and **Run** executes
  Python files with the existing on-device `run_python` worker.
- **Account manager** (#22). Settings → Accounts lists DeepSeek accounts (web sign-in) and API-key
  profiles with label, sign-in type, balance and when it was last read. API keys read
  `GET https://api.deepseek.com/user/balance` when you tap Refresh; web sign-ins show "not available"
  (no documented endpoint). One tap switches. Each account keeps its own encrypted credentials and its own
  chats. **Never automatic switching**: a quota or rate error shows the error and a **Switch account**
  button that only opens the list. See [ACCOUNTS.md](ACCOUNTS.md).

## Changed

- versionCode 8, versionName 0.7.0. The About line in Settings reads the version from the build.
- The API-key client keeps chats per API-key profile (`conversations-<id>`); chats saved before v0.7.0
  stay with the key saved under Settings → API-key mode.
- The key saved under Settings → API-key mode is also listed as the API-key profile "API key".
- No new dependencies and no new permissions: the merged manifest keeps INTERNET and RECORD_AUDIO.

## Tests added

JVM (`:core`): `LineDiffTest`, `WorkbenchArtifactTest`, `ArtifactRevisionTest`, `SyntaxTokenizerTest`
(every language family plus random input), `EditorStateTest`, `AccountRegistryTest` (including the rule
that a quota error never changes the active account), `BalanceTest` (MockWebServer), `PythonOutputTextTest`.
App unit test: `SyntaxPaletteTest`. Emulator: `WorkbenchTest`, `AccountManagerTest`.

## Pass/fail

| # | Item | Evidence | Status |
| --- | --- | --- | --- |
| 1 | Line diff, version history and "+N -M lines" | `LineDiffTest`, `WorkbenchArtifactTest` | PASS (CI unit tests) |
| 2 | "Ask DeepSeek to change this" prompt and reply extraction | `ArtifactRevisionTest` | PASS (CI unit tests) |
| 3 | Tokenizer for each language family; undo/redo; find | `SyntaxTokenizerTest`, `EditorStateTest` | PASS (CI unit tests) |
| 4 | A quota error never changes the active account; switching only by explicit choice | `AccountRegistryTest` | PASS (CI unit tests) |
| 5 | Balance endpoint request, parsing and "last read" text | `BalanceTest` | PASS (CI unit tests) |
| 6 | Editor token colors meet 4.5:1 contrast in light and dark | `SyntaxPaletteTest` | PASS (CI unit tests) |
| 7 | Open an HTML artifact from an answer, edit it, Run, see v2 | `WorkbenchTest.htmlArtifactOpensEditsRunsAndShowsV2` | PASS (emulator, 68/68) |
| 8 | Ask DeepSeek: request is the next message in the chat; reply becomes v2 | `WorkbenchTest.askDeepSeekSendsTheEditedSourceAndItsReplyBecomesTheNextVersion` | PASS (emulator, 68/68) |
| 9 | Save as launches ACTION_CREATE_DOCUMENT; Save writes the chat folder | `WorkbenchTest.editorSaveAsLaunchesCreateDocumentAndSaveWritesTheChatFolder` | PASS (emulator, 68/68) |
| 10 | Add a second API-key profile, balances, switch manually and back | `AccountManagerTest.aSecondApiKeyProfileIsListedAndUsedOnlyAfterATap` | PASS (emulator, 68/68) |
| 11 | Mocked rate error shows "Switch account"; active account unchanged | `AccountManagerTest.aQuotaOrRateErrorShowsSwitchAccountAndKeepsTheActiveAccount` | PASS (emulator, 68/68) |
| 12 | Mocked 402 quota error (API-key client) shows "Switch account"; profile unchanged | `AccountManagerTest.anApiQuotaErrorShowsSwitchAccountAndKeepsTheProfile` | PASS (emulator, 68/68) |
| 13 | All earlier JVM, app unit and emulator tests still pass | CI and emulator workflows | PASS (CI and emulator, PR #23) |
| 14 | Lint, debug and signed release builds; merged manifest keeps INTERNET + RECORD_AUDIO only | CI and release workflows | PASS (CI; signed APK from the release workflow) |
| 15 | Live: Ask DeepSeek on chat.deepseek.com with a real account; live balance with a real key | manual | NOT RUN |

## Not included / known limits

- The API-key client keeps its existing v0.2 artifact canvas; the new panel and "Ask DeepSeek to change
  this" are in account mode.
- Running a Python file in the editor shows text output; files it creates appear under Files in this chat.
- Share sends up to 100,000 characters as text; larger files should use Save as.
- Editor highlighting is skipped for files over 200,000 characters; the editor opens files up to 1 MB.
- Web sign-ins have no balance figure (no documented endpoint).
