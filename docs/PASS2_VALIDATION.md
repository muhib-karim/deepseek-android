# Pass 2 validation

This pass follows the owner's v0.1 additions in PLAN.md, with item 9 taking
precedence for layout and wording. The spec itself supplies the design contract:
white/near-black background, calm blue accent, compact grey timeline, original
chat-and-spark mark, two-row bottom composer, title model dropdown, and history
drawer. No screenshots or assets were fetched. No local build, SDK, network or
git operations are permitted in this session.

## Implemented source and test coverage

- Attachments: MIME/extension classification; UTF-8/BOM/UTF-16/Windows-1252 text;
  200k-character cap; in-memory DOCX ZIP fixtures, paragraph/text-only extraction,
  entity rejection and truncation. Android copies picked documents before their
  temporary grants expire, extracts PDF text with pinned PDFBox, and samples and
  scales image JPEGs. Camera uses a narrow FileProvider cache directory.
- Models: optional discovery metadata, timestamp/version/alias/tie ranking,
  heuristics and overrides, automatic/manual/fallback selection, authenticated
  GET contract and provider errors. Image payloads are stripped from the entire
  outgoing history when a text-only model is selected.
- Markdown: headings, paragraphs, ordered/unordered lists, quotes, rules, fenced
  code including unfinished streams, GFM tables and inline styles/links/math.
  Compose renders annotations, code Copy and bordered scrollable tables.
- Agent: ordered immutable timeline snapshots; reasoning → tool → reasoning →
  tool → answer; running/waiting/denied/completed statuses, bounded result previews
  and durations. Search registers web tools only for the current run.
- Errors: auth, quota/body codes, refusal, retry/busy/network/timeout. Provider
  response bodies are not displayed or stored as raw exception messages.
- Artifacts: internal PDF/SVG/HTML/Markdown/CSV/image viewers; shared CSV parser;
  FileProvider Share and ACTION_CREATE_DOCUMENT Save on every viewer.
- Identity/UI: package app.dsandroid, unofficial client headers and About notice,
  original icon, drawer history/search/date groups, light/dark themes and empty
  prompt examples. One active account; no rotation or account fallback.

## Source checks performed here

XML and TOML syntax, declared permission allowlist, resource references, legacy
name and machine-path scans, and read-only review of Kotlin contracts/API usage.
These checks cannot establish compilation or Android runtime correctness.

## CI gates — not run here

The existing CI workflow runs core JUnit tests, app JVM tests, Android lint and
debug assembly using pinned dependencies. All must pass before distributing an
APK. Check the merged manifest as well as the source manifest: INTERNET is the
only requested permission. PDFBox is pinned to 2.0.27.0 in the version catalog.

## Device checks — not run here

1. At 320dp and larger widths, large font settings, light and dark modes: inspect
   top bar, bottom composer, keyboard insets and empty state. Verify TalkBack
   labels for menu, attach, send/stop and removal actions.
2. Open drawer, filter chats, create/switch conversations, and verify isolated
   histories and titles. Stop an active run before switching. Restart the process
   to confirm v0.1 history is deliberately transient.
3. Import multiple documents, an in-memory-style DOCX, text with BOM, a long text
   file, a normal and scanned PDF, a damaged/encrypted PDF, and images. Check size
   limits, visible truncation/no-text states, removal and temporary-file cleanup.
4. Capture a photo through a camera app, cancel, rotate while the picker is open,
   and test devices without a camera app. Verify successful images are copied
   and the camera cache file removed without requesting CAMERA permission.
5. Select a vision model, attach an image, then change to a text-only model and
   send again. Verify the image-unreadable chip and no image bytes in text-only
   requests. Override capability heuristics in Settings.
6. Verify model refresh on launch, key save and sign-in, cache on offline failure,
   explicit selection across restarts and automatic newest selection. Start
   editing a key while refresh is in flight; confirm the draft is preserved.
7. Toggle Think/Search independently. Inspect actual provider support for the
   reasoning flag and chosen model; ensure web_fetch cannot run when Search is off.
8. Stream long Markdown, unfinished code, nested emphasis, a table and multiple
   tool rounds. Verify live/collapsed reasoning, tool Details/statuses, Stop,
   confirmation denial, copy controls and scrolling while reading earlier text.
9. Preview every artifact type. For PDF scroll through pages. Verify static
   SVG/HTML cannot execute JavaScript, access files/content, fetch remote resources
   or navigate. Share/save each type and reopen the exported file.
10. Exercise 401/402/403/429/5xx, insufficient-quota bodies, network loss and timeouts;
    confirm plain-English error cards and Open Settings without account switching.

## Known v0.1 limits

Account sign-in still needs a supported provider contract and configured endpoints;
no new account adapter is invented. Provider model/vision/reasoning/tool support
needs integration validation. History is in memory. PDF extraction has no OCR;
PDF generation is plain text layout. CSV/table previews show the first 500 rows.
Math uses monospace rather than a LaTeX engine. PDF pinch zoom is not included.
