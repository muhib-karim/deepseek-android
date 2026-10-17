# v0.1 implementation record

Historical first-pass notes below are superseded by `PASS3.md` and the authoritative
Roadmap in `PLAN.md`. Pass 3 adds JSON persistence, Office, charts, PNG and dictation.

The approved design is in PLAN.md. The build-session brief is the current scope.

- Ruling: use two modules, in-memory history, plain JVM tests and no wrapper JAR,
  as required by the build brief. Room, SVG rasterization, charts and voice are v0.2.
- Ruling: write core tests before implementations, but defer all execution to CI.
  The session forbids Git, Gradle, Android SDK, downloads and network access.
- Ruling: account endpoints and client ID remain empty; implement configurable
  standard authorization-code PKCE without assuming undocumented provider endpoints.
- Contracts: the streaming client emits chat chunks; the agent accumulates messages
  and invokes registered tools through the guard; Android owns UI, secrets and PDFs.
- Verification status: source inspection only; no build or test pass is claimed.

Final source review and corrections:

- Independent read-only source review completed; no build, test, SDK, network or
  Git operations were used. The planned external review remains separate.
- Added tests before fixing ambiguous guard path fields, absent optional wire
  fields, excessive SVG nesting and non-string OAuth token responses.
- Added transport cancellation coverage with MockWebServer.
- Preserved drafts rejected by provider validation; kept sign-in alive across
  activity recreation while cancelling on explicit Back/Cancel.
- Closed loopback sockets on setup cancellation; bounded fetch coroutine duration
  in addition to the HTTP timeout.
- Source checks parsed XML, TOML and workflow YAML and checked Kotlin delimiters.
  These checks do not establish compilation, lint or test success.
- Remaining validation: first CI run, merged manifest permission check, device
  checks for PDF pagination, encrypted preferences, Custom Tabs, rotation and
  document actions, and a supported provider account contract.


## Pass A (v0.2.0)

Implemented feature matrix rows 1–8 in the working tree; this is not a tagged
v0.2.0 release. The existing MIT harness attribution in NOTICE/README is retained.

- Extended the real OpenAI-compatible streaming tool loop: JSON-schema tool
  definitions, indexed argument accumulation, tool-call IDs/results, multi-step
  continuation, an eight-step cap, retry/checkpoints, and cancellation. Chat mode
  omits tools/tool_choice and rejects unsolicited calls. Current tool-turn reasoning
  is retained for DeepSeek follow-ups; older reasoning is removed from request
  history. Streams have a 120-second idle limit without a total duration limit.
- Added Chat/Agent composer controls, explicit Allow once/Deny sheets, inline tool
  names, summaries, status and collapsible output. Existing streamed thinking cards
  show duration and collapse independently of the answer.
- Added Stop, Retry, Regenerate and Edit/Save and resend. Retry resumes an unfinished
  run without replaying committed tools; regeneration/edit replaces the selected
  turn's suffix while preserving attached context. Stop closes pending tool results
  and preserves partial answers. Rewrites record superseded runs to prevent stale
  checkpoint recovery; attachment preparation completes before Stop finalizes it.
- Added numbered search/fetch citations, clickable `[n]` references and a Sources
  sheet with title/domain/URL. Source numbers and current-turn search hosts persist
  across resume and reset for new/replaced turns. Fetch retains readable extraction,
  decoded-byte/time caps, DNS checks and redirect refusal.
- Every search query asks first. Fetches with a query string, over 200 characters,
  or outside the turn's exact search-result hosts ask first. Workspace writes remain
  SAFE and unconfirmed; SECURITY.md lists every registered tool's actual policy.
- Removed app-wide cleartext access; network_security_config denies all cleartext.
  The inbound loopback sign-in listener needs no exemption. Android provider settings
  now require HTTPS. INTERNET/runtime microphone declarations and SDK levels remain
  unchanged; ACCESS_NETWORK_STATE remains removed.
- Pinned workflow actions to full release SHAs, restricted token permissions, and
  made tagged publication depend on tests/lint/assembly of the tagged commit and
  the checked artifact. Version pins were not refreshed online because DNS access
  was unavailable; action resolution must be confirmed by CI.

Files touched: core agent/checkpoints, LLM request history, web tools/guard/sources,
conversation persistence and Markdown parsing; app chat/view-model/timeline/Markdown,
confirmation and sources sheets, string resources and network manifest/config;
CI/release workflows; JVM tests; SECURITY.md and project handoff/plan documentation.

Validation: added JVM coverage for multi-step MockWebServer requests, streamed
arguments/reasoning, Chat mode, cancellation, confirmation, source restoration,
query/length/host rules, edit/attachment preservation, interrupted rewrites and
mixed citations/Markdown links. Local verification is static only: XML/YAML/TOML,
resource references, Kotlin delimiter/import/API inspection and diff hygiene.
**Gradle, JVM tests, Android lint and APK assembly were not run locally.** No commit
or push was made. A successful CI run is required before merging or distributing.

Remaining validation: CI must compile and execute all suites/lint/assembly and
resolve action pins. Device-check narrow/large-font layouts, live reasoning/citation
interaction, Stop during network/confirmation/attachment preparation, process death
and resume, edited turns with attachments, and provider-backed tool calling. Debug
release signing is unchanged. Passes B and C and feature-matrix rows beyond 1–8 are
outside this pass; no version bump or release was performed.
