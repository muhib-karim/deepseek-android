# Pass B implementation plan

Scope: feature matrix rows 11–15 and 20, using the existing registry, guard,
timeline and viewer. The task brief is the approved specification.

## Constraints and decisions
- Keep the two modules, SDK levels, permissions and existing tool APIs.
- No local Gradle, commits or pushes. Write JVM tests before new core logic;
  compilation, JVM execution and Android lint remain CI gates.
- JavaScript runs in a disposable offscreen WebView worker with a bounded result
  channel and watchdog; no native capabilities are exposed to generated code.
- Python is optional, with an explicit runtime-download confirmation, pinned CDN
  resources and no unrestricted network for model code.
- Artifact edits create immutable versions. Save/share target the selected version.
- HTML previews may run JavaScript; other preview formats never execute supplied
  scripts. Network starts disabled and grants are scoped to the current preview.
- OCR uses a bundled on-device model. Image bytes are sent only when the selected
  model supports vision; extracted text remains useful with text-only models.

## Tasks
- [x] Sandbox: core policy/results and tests, Android execution adapter, optional
  Python download approval, cancellation and caps.
- [x] Artifacts: core creation/history/fence parsing and tests, viewer preview/code,
  edits/rerun/version selection; preserve SAF/FileProvider exports.
- [x] Images: bundled OCR, attachment context and capability tests, composer state.
- [x] Integration: register new tools, inline charts and code/output cards, fenced
  artifact cards, localized UI strings.
- [x] Review: security and API inspection, static resource/config/diff checks,
  BUILD_NOTES and PROJECT_BRAIN handoff with exact remaining validation.

## Interface and ownership review
| Tasks | Shared boundary | Decision |
|---|---|---|
| Sandbox / viewer | WebView policy | Sandbox owns reusable policy; preview uses no native bridge. |
| Artifacts / chat | Artifact value | Retain existing serialized Artifact; immutable version files use the same type. |
| Images / chat | AttachmentImporter and context | Preserve existing image parts and photo picker. |
| Tools / guard | Registry and ToolKind | Add narrow names and policy; no agent-loop rewrite. |
| All | Strings and docs | Integrate resources in values/strings.xml; keep source edits scoped. |

Each task requires tests for its core behavior and a source review. Tests cannot
be reported as passing until CI executes them.

Implementation checkboxes do not imply executed tests. See the Pass B section in
BUILD_NOTES for CI/device gates and the Mermaid, OCR and Python limitations.
