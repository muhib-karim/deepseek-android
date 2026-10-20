# v1.0.0 Implementation Plan

> Copied into the repository on 2026-10-20 as the v1.0.0 plan. Only file paths were corrected: bare file names now carry
> their folder, S1 lists `scripts/fetch_verify_reference.sh` (called in Section C) and puts the reference driver in
> `e2e/reference/`, S9 documents tool routes in `docs/TOOLS.md` (the owner's definition of done) instead of
> `docs/TOOL_ROUTES.md`, and S10 names `docs/RELEASE_NOTES_100.md`. Progress against each slice is tracked in
> `docs/PROJECT_BRAIN.md` and `docs/RELEASE_NOTES_100.md`.

**Goal:** Match the official DeepSeek Android interface while integrating the existing agent, tools, and real file outputs.

**Execution:** One Claude Code Opus 5.5 session, working sequentially through S1–S10. Preserve Kotlin/Compose, minSdk 26, targetSdk 35, and only `INTERNET` plus runtime `RECORD_AUDIO`. All Gradle execution happens in GitHub Actions.

**Baseline:** Source audit at `f9b1e61`, including PR #28’s sign-in changes. No files changed or builds run during planning. Historical CI results in documentation are not proof of this revision.

## A. Audit against the definition of done

There is no root `PLAN.md`; the existing plan is `docs/PLAN.md`. It and parts of `README.md`, `docs/FEATURE_MATRIX.md`, and `docs/PROJECT_BRAIN.md` contain outdated roadmap/status statements. This plan supersedes the queued v0.8.0 release and reserves versionCode **10** for v1.0.0.

| Requirement | Existing implementation | Remaining work |
|---|---|---|
| Official reference | `docs/ui-spec.md` describes promotional screenshots and resource inspection of APKPure’s v2.6.2 APK. | No committed device captures, certificate provenance, or `docs/reference/SCREEN_SPEC.md`. `app/src/androidTest/kotlin/app/dsandroid/deepseek/OfficialScreenshotsTest.kt` captures **our client**, not the official app. |
| Screen parity | `app/src/main/kotlin/app/dsandroid/deepseek/DeepSeekShell.kt` implements account chat, drawer, settings, attachment tray, and themes. | Dark theme is explicitly an interpretation. Drawer, settings, welcome, profile, and sharing need measured reconstruction. API mode uses a separate UI. |
| Authentication | `app/src/main/kotlin/app/dsandroid/deepseek/{DeepSeekAccountApp,BrowserSessionLink,DeepSeekWebView}.kt`; PR #28 adds visible sign-in routes and locks composer controls. `app/src/androidTest/kotlin/app/dsandroid/LaunchTest.kt` covers that lock. | Signed-out users still enter the chat shell/navigation. Shared intents and shortcuts need gating. `AccountViewModel.acceptSession()` primarily checks expiry/storage; `needsBirthday()` swallows failures. Require successful server validation before unlocking. |
| Agent transport | `core/.../agent/{AgentLoop,WebToolProtocolClient,ToolCallProtocol}.kt`; `core/.../llm/OpenAiCompatibleClient.kt`. Native API function calling and fenced web-session calls already exist. | Preserve them; test capability filtering and equivalent outcomes across routes. Web-session agent execution against the live service remains unproven in current documentation. |
| Files/workbench | `core/.../tools/{AgentFileTools,OfficeTools}.kt`, `core/.../document/`, `core/.../pdf/`; `app/.../tools/`, `app/.../deepseek/{AgentViews,ArtifactActions}.kt`, `app/.../workbench/`, `app/.../ui/ArtifactViewer.kt`. | Integrate consistent file discovery, preview, Download/Save as/Share, and file persistence. Prove exported bytes, not merely visible cards. |
| Plugins/tools | `app/.../python/AgentToolkit.kt`, `core/.../skills/`, `core/.../mcp/`; API `app/.../ui/ChatViewModel.kt`, `app/.../ui/PersonalizationSettings.kt`, and `app/.../data/SettingsStore.kt`. | Account mode exposes a smaller toolkit and constructs its registry without `disabledTools`. Bring required existing tools and switches into the default account UI. |
| Code execution | `app/.../sandbox/WebViewCodeExecutor.kt` supports JS and downloadable Pyodide; `app/.../python/PythonService.kt` supports native Python. | Expose JS/WASM in account mode and verify release behavior. Native Python shares the app UID and is **not** an isolation boundary. |
| Self-test | `.github/workflows/emulator.yml`, `app/src/androidTest/kotlin/app/dsandroid/deepseek/{AccountFeaturesTest,AgentModeTest,WorkbenchTest}.kt`, `app/src/androidTest/kotlin/app/dsandroid/python/PythonRuntimeTest.kt`. | Tests use debug instrumentation, injected dependencies, and fake servers. No complete signed-release journey, screen video, or registry-wide tool evidence. |
| Release | `.github/workflows/ci.yml`, `release.yml`, `scripts/check_release_manifest.py`; current version **0.7.1/9**. | Signing is optional; publishing depends on build validation but not emulator evidence. Require signing, badging, complete evidence, and testing of the exact APK published. |

Existing image creation renders SVG/charts to real PNG files; it is not a generative-image service. Existing skills and HTTPS tool servers are extensibility mechanisms, not automatic compatibility with Claude Code’s plugin ecosystem.

## B. Ordered implementation slices

Paths below use:

- `A` = `app/src/main/kotlin/app/dsandroid`
- `C` = `core/src/main/kotlin/app/dsandroid/core`
- `AT` = `app/src/androidTest/kotlin/app/dsandroid`
- `CT` = `core/src/test/kotlin/app/dsandroid/core`

**Every slice:** write regression tests first, implement, run Gitleaks before pushing, and finish with green CI plus relevant emulator checks on that commit. Update `docs/PROJECT_BRAIN.md` Status/Next step and append a session-log entry with each completed slice. Record CI run URLs and commit SHAs. Never force-push or advance past a failed required check.

Sizes describe implementation scope, excluding CI queue time: **S** localized; **M** one feature flow; **L** several connected flows, implemented through small commits.

### S1 — Establish the official reference — L

**Files:** New `.github/workflows/reference.yml`, `scripts/fetch_verify_reference.sh`, `scripts/capture_reference.sh`, reference driver under `e2e/reference/`, `scripts/check_reference.py`, and `docs/reference/{SOURCE.json,SCREEN_SPEC.md,captures/}`.

**Work:** Obtain and verify a pinned official APK; capture the complete screen inventory in both themes using the workflow below. Record layout measurements and transitions. Keep reference images outside application assets.

**Tests/evidence:** Reject wrong package, signer, version, ABI, missing screens, blank captures, and unapproved metadata. CI produces installation/badging/certificate reports, capture manifest, screenshots, and video. Commit sanitized screenshots and the measured spec. Authenticated reference screens are a completion gate.

### S2 — Prove the signed-release test harness — L

**Files:** `.github/workflows/{ci,emulator,release}.yml`, new reusable `release-e2e.yml`, `settings.gradle.kts`, `e2e/`, `scripts/run_release_e2e.sh`. Extract reusable responses from `AT/deepseek/{AccountFeaturesTest,AgentModeTest}.kt` into test-only fixtures.

**Work:** Build a separate UIAutomator driver and host fake backend. Install an unmodified, signed, minified release APK. Establish emulator-only HTTPS routing to the fixtures; add no production endpoint override, authentication bypass, or test receiver.

**Tests/evidence:** Baseline launcher journey, synthetic session import through the existing public flow, and one streamed message. Assert installed APK hash equals the supplied artifact. Upload video, screenshots, assertions, and backend request summaries. Prove this infrastructure before UI changes.

### S3 — Welcome screen and complete authentication gate — M

**Files:** `A/deepseek/{DeepSeekAccountApp,DeepSeekShell,AccountViewModel,BrowserSessionLink}.kt`, new `WelcomeScreen.kt`, `C/deepseek/DeepSeekWebClient.kt`, string resources.

**Work:** Add explicit loading, signed-out, validating, and authenticated states. Welcome offers **Continue with Google**, **phone/email**, and **browser sign-in**; browser is the primary route. Reuse the existing browser handoff and explicit session confirmation. Validate credentials through the existing authenticated `/api/v0/users/current` contract before unlocking; validation failure stays locked.

Gate drawer/history, files, tools, editor execution, attachments, voice, shared inputs, and shortcuts at both navigation and action layers. Hold incoming shared content until authentication. Explicit API mode requires its own validated credential; never switch routes automatically.

**Tests/evidence:** Extend `AT/LaunchTest.kt`, `AT/deepseek/BrowserSignInLinkTest.kt`, session/transport tests, and release E2E. Fresh installation must show welcome with no usable chat controls. Cover invalid/expired/revoked tokens, failed validation, logout, recreation, back navigation, unsolicited callbacks, and every external entry point.

### S4 — Match chat, composer, drawer, and themes — L

**Files:** `A/deepseek/DeepSeekShell.kt`, extracted theme/chat/drawer components, `A/ui/{ChatTheme,ChatScreen,AppIcon}.kt`, strings and original vectors; `AT/deepseek/OfficialScreenshotsTest.kt`.

**Work:** Implement S1’s measured colors, type, spacing, icons, copy, message rendering, DeepThink/Search states, attachment tray, keyboard insets, and history interactions. Apply the same visual language to API mode without replacing its transport.

Default chat matches the reference. Put agent entry and file access in documented extension locations; keep agent-specific UI visible only when relevant.

**Tests/evidence:** Both-theme captures for empty/populated chat, thinking expanded/collapsed, Search on/off, drawer, attachment tray, and keyboard. Produce reference overlays and regional diffs. Check small screens, 200% text, and API 35 edge-to-edge behavior.

### S5 — Match settings, profile, and share flows — M

**Files:** Extract screens from `A/deepseek/DeepSeekShell.kt`; update `A/deepseek/AccountsPage.kt`, `A/ui/SettingsScreen.kt`, `A/ui/MessageActions.kt`, and `A/ui/ConversationExportDialog.kt`.

**Work:** Reproduce reference settings groups, dialogs, profile layout, and share entry points. Move technical API configuration into Advanced. Reuse account management and conversation export. Unsupported server-side actions must link explicitly to the official service instead of pretending to succeed.

**Tests/evidence:** Both-theme screenshots for every captured secondary screen and dialog; settings survive restart; logout locks the app; share produces readable content. Manual account selection remains the only switching mechanism.

### S6 — One capability catalog and Plugins/Tools screen — L

**Files:** New `A/tools/ToolCatalog.kt`, `A/deepseek/PluginsToolsScreen.kt`; update `A/python/AgentToolkit.kt`, both ViewModels, `A/ui/PersonalizationSettings.kt`, `A/data/SettingsStore.kt`, and registry integration.

**Work:** Reuse existing generators, workspace tools, `web_search`, `web_fetch`, interpreter, skills, and HTTPS remote-tool support. Define route availability, labels, approval requirements, and enabled state in one catalog consumed by settings and execution.

Persist individual switches. Disabled tools must disappear from model schemas and be rejected if requested anyway. Recheck policy before execution, including pending approvals. Preserve API-only extras with explicit availability labels rather than silently advertising them in account mode.

**Tests/evidence:** Add catalog/registry tests and `AT/deepseek/PluginsToolsTest.kt`: toggle/restart, unknown/disabled calls, aliases, skill functions, remote tools, approval denial, and route differences. Assert backend requests contain only enabled, supported tools.

### S7 — Verify sandboxed execution — M

**Files:** `A/sandbox/{WebViewCodeExecutor,PythonRuntimeLoader,SandboxWebViews}.kt`, `C/sandbox/{PythonRuntime,CodeSandboxDocument}.kt`, catalog/guard integration, runtime tests, `SECURITY.md`.

**Work:** Expose the existing JS/Python-WASM interpreter on both routes. Pin and verify downloaded runtime hashes, retain download confirmation, and support offline reuse after installation. Enforce cancellation, time/output limits, and blocked network/native/file bridges.

Keep native `run_python` and executable skill functions separately labeled as trusted execution, disabled until explicitly enabled, with per-execution approval. Do not describe their shared-UID worker as a security sandbox.

**Tests/evidence:** Real release execution returns `4`; test JS and WASM Python, denied/interrupted/corrupt runtime downloads, offline restart, infinite loops, Stop, and blocked network/app-file access. Retain native Python/chart tests under the explicit trusted-execution setting.

### S8 — Complete the real-file journey — M

**Files:** `A/deepseek/{AgentViews,ArtifactActions,AccountViewModel}.kt`, `A/workbench/{ArtifactPanel,EditorScreen}.kt`, `A/ui/ArtifactViewer.kt`; existing core writers only where tests expose defects.

**Work:** Make generated code, Markdown, CSV, DOCX, PDF, SVG/PNG, and existing Office outputs consistently discoverable from chat and the file panel. Reuse preview/edit/versioning. Offer Download, Save as, and Share with correct names/MIME types and explicit external actions.

**Tests/evidence:** Extend `WorkbenchTest`, `AgentModeTest`, and release E2E. Export each required format, read the exported bytes, compare hashes, parse DOCX/CSV, render PDF, and decode PNG. Verify FileProvider read grants, SAF on API 26, Downloads on newer Android, cancellation, missing files, restart, and account isolation.

### S9 — Complete release E2E and visual acceptance — L

**Files:** `e2e/`, `.github/workflows/release-e2e.yml`, screenshot/evidence validators, new `docs/EVIDENCE_v1.md` and `docs/TOOLS.md`.

**Work:** Implement the complete workflow below. Maintain a machine-readable coverage table for every shipped tool/alias, built-in skill function, and fixture remote plugin, on every supported route. A missing case fails completeness.

**Tests/evidence:** Complete journeys on API 35 in both themes; API 26 compatibility coverage including SAF and sandbox execution. Verify tool success, denial, disabled state, cancellation, session expiry, and restart. Keep live login-page evidence separate from mocked inference. No skipped required case counts as passing.

### S10 — Version, gate, and publish — S

**Files:** `app/build.gradle.kts`, `.github/workflows/release.yml`, `scripts/check_release_manifest.py`, new badging/evidence checks, `README.md`, `docs/RELEASE_NOTES_100.md`, `docs/FEATURE_MATRIX.md`, `docs/PROJECT_BRAIN.md`.

**Work:** Set **versionName `1.0.0` / versionCode `10`**. Require existing release-signing secrets; missing signing fails the release. Remove optional/debug-only publication behavior for v1.0.0.

**Tests/evidence:** Validate package `app.dsandroid`, version, SDK levels, allowed permissions, expected signing certificate, supported ABIs, and non-debuggable status. Require green unit/lint/secret-scan/emulator/evidence checks. Tag only a green commit. Test the tag’s signed APK, then publish those exact bytes without rebuilding, together with checksums and evidence.

## C. Official reference-capture workflow

1. Select an obtainable stable `com.deepseek.chat` release from **APKMirror or APKPure** and pin it. Record listing/download URLs, retrieval date, version, APK SHA-256, signer SHA-256, and ABI set in `SOURCE.json`.
2. Establish the expected signer independently: compare against a Play-delivered copy or independently published signing-certificate evidence. `apksigner verify` alone proves signature integrity, not publisher identity. Do not invent a fingerprint or trust a checksum supplied alongside an unknown binary.
3. Verify package/version/signature before installation. For split APKs, verify every split and install the matching set unchanged. Keep APKs and extracted proprietary assets out of Git.
4. Use an Actions emulator compatible with the APK ABI. Fix viewport, density, locale, font scale, OS version, and navigation mode. Capture native UI hierarchy/bounds where available.
5. Capture welcome; all sign-in routes; empty/populated chat; DeepThink/Search states; drawer/history menus; attachment sheet; settings and dialogs; profile; share screens—**in light and dark**. Traverse secondary screens and record all discovered states in a coverage manifest.
6. Use an existing dedicated test account through supported authentication for protected screens. Suspend recording while entering credentials. Do not automate around Google challenges or CAPTCHA.
7. Commit sanitized captures and `SCREEN_SPEC.md`: screen/state IDs, navigation, bounds in dp, colors, typography/line height, spacing/radii, icon geometry, exact copy, and intentional differences for original art, unofficial identity, and agent extensions.

Abbreviated YAML; reuse reviewed action pins and setup from `emulator.yml`:

```yaml
name: Official DeepSeek reference
on: workflow_dispatch
permissions:
  contents: read

jobs:
  capture:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@<pinned-commit>
      # Install SDK tools and build the independent UIAutomator driver.
      - run: bash scripts/fetch_verify_reference.sh
      - uses: reactivecircus/android-emulator-runner@<pinned-commit>
        with:
          api-level: 35
          arch: x86_64 # Must match the verified APK; select another runner if needed.
          disable-animations: true
          script: bash scripts/capture_reference.sh
      - if: always()
        run: python3 scripts/check_reference.py reference-output/
      # Upload only sanitized output after the safety check succeeds.
      - uses: actions/upload-artifact@<pinned-commit>
        with:
          name: deepseek-official-reference
          path: reference-output/sanitized/
          if-no-files-found: error
```

Capture script outline:

```bash
apksigner verify --verbose --print-certs official.apk
aapt2 dump badging official.apk
# Assert metadata and independently pinned signer before installing.
adb install official.apk
adb shell wm size 1080x2200
adb shell wm density 440
adb shell settings put system font_scale 1.0
adb shell am start -n "$REFERENCE_LAUNCHER"

# Driver navigates by resource/text selectors and waits for stable screen assertions.
adb shell cmd uimode night no
adb exec-out screencap -p > reference-output/light-welcome.png

# Record bounded segments; stop with SIGINT and pull after each scenario.
adb shell screenrecord --time-limit 180 /sdcard/reference-01.mp4
adb pull /sdcard/reference-01.mp4 reference-output/

adb shell cmd uimode night yes
# Repeat navigation/captures; use the app's theme setting if it overrides the system.
```

The wrapper runs recording concurrently with navigation and collects completed segments on failure. Compare our captures at identical device settings; mask only documented dynamic/private regions and original-art differences. Establish tolerances from repeat captures and measured geometry, not by loosening thresholds until implementation passes.

## D. Release E2E evidence workflow

**Test the production APK externally.** A separate UIAutomator test app drives launcher, browser, system picker, sharesheet, and our app. It must not replace `MainActivity`, inject ViewModels, or re-sign the APK.

For deterministic account tests, reuse the existing MockWebServer response contracts behind an emulator-only TLS fixture service. Install its temporary CA in the disposable emulator’s **system** trust store and route fixture traffic at the network layer. Include login/current-user, chat, PoW worker, API tool-call, search/fetch, and plugin responses.

An ordinary HTTP proxy is insufficient: several existing clients explicitly use `Proxy.NO_PROXY`. Preserve public DNS validation and use emulator-level routing. Fail unexpected network requests. Never weaken production TLS/private-address checks, and never route real credentials through this environment.

```yaml
# Outline of the tag pipeline; implementation uses reusable workflows.
jobs:
  validate:
    # Unit tests, app tests, lint, Gitleaks, manifest checks.

  signed_apk:
    needs: validate
    # Require signing secrets; build once.
    # Verify signature/badging; upload APK + SHA256SUMS + provenance.

  mocked_e2e:
    needs: signed_apk
    strategy:
      matrix:
        api: [26, 35]
    # Download that exact APK; install separate driver.
    # Configure disposable TLS fixtures; run release journeys.
    # Validate and upload sanitized evidence even after test failure.

  live_login:
    needs: signed_apk
    # Separate clean emulator, ordinary trust/network, no fixture routing.
    # Verify actual sign-in page and external-browser handoff.
    # Do not attempt headless Google account authentication.

  publish:
    needs: [validate, signed_apk, mocked_e2e, live_login]
    # Require complete reference and E2E manifests.
    # Recheck hashes and publish the tested APK without rebuilding.
```

Required recorded journey:

1. Fresh install/clear data → welcome; prove chat/files/tools/shortcuts are inaccessible.
2. Google/browser action → real browser package and expected DeepSeek URL; phone/email page loads. Back/cancel remains signed out.
3. In the separate fixture run, import a synthetic session through the normal confirmation flow; server validates it.
4. Send a streamed message; exercise DeepThink, Search, attachment, Stop, and history.
5. Enable Agent; request a file; observe tool execution, preview it, download it, and verify exported bytes.
6. Exercise every supported shipped tool, plugin switch, built-in skill, and both tool transports. Test approval refusal and disabled-tool rejection. External-action tools use test receiver apps, not real messages or purchases.
7. Restart, verify files/settings, expire the session, and log out; confirm access is locked again.

Evidence contains numbered screenshots, segmented MP4s, JUnit results, sanitized request assertions, exported sample files, file hashes, visual diffs, tool coverage, APK hash/certificate/badging, commit SHA, and workflow URLs. Validate PNG dimensions and MP4 decodability. Keep auth-entry footage, raw credentials, and private app data out of artifacts.

Label results explicitly as **official reference**, **release with mocked backend**, or **live login-page check**. Mocked success does not establish live model behavior or completed Google authentication.

## E. Risks and fallbacks

| Risk | Response |
|---|---|
| Official APK provenance, compatible ABI, or authenticated screens unavailable | Try the other reputable mirror or a compatible Actions ARM64 emulator. Capture accessible screens and report the missing states. Do not substitute promotional/client screenshots or mark reference capture complete. |
| No usable reference account, CAPTCHA, or Google challenge | Use an already provisioned supported test login if available. No bypass. Missing authenticated references remain a release blocker without asking the owner to test the product. |
| Browser session return is cumbersome | Retain the existing helper and explicit Paste session fallback. Do not promise seamless OAuth: browser cookies cannot simply be read by this app. |
| Web protocol, WAF, or PoW changes | Fail visibly, preserve user work, and offer explicit API-mode selection. Never switch accounts/providers automatically. |
| WASM runtime unavailable or unsupported | JS and file tools remain available; show Python’s precise unavailable state. Do not silently substitute native Python and call it sandboxed. |
| Reference fidelity conflicts with original art or added agent UI | Document narrowly scoped exceptions in `SCREEN_SPEC.md`; match remaining measured screens and interactions. |
| CI time or infrastructure prevents completion in one session | Preserve small commits and project-brain checkpoints. Complete required evidence before distributing v1.0.0; session limits do not convert untested work into a finished release. |