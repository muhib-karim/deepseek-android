# v0.1 implementation record

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
