# v1.1.0 (versionCode 11)

v1.0.0 was checked only against a mocked backend. A signed-in run of the v1.0.0 release APK on a real phone (Pixel 7,
Android 14) against the live service, with a dedicated test account, found what the mock hid: uploads failed, every
Search turn stopped with "DeepSeek's web service changed", the first message after signing in could fail, chats from other
devices were missing and every failure read like an incompatible service. v1.1.0 fixes those against the live contracts
(verified with the same account on 2026-10-20), makes the mocked backend speak the same shapes so CI would have caught
them, and closes the remaining differences from the official app.

**Status: not released.** Nothing was compiled or run locally: GitHub Actions builds, tests and records the results after
the branch is pushed. Rows below say PENDING until a run fills them in.

## Fixed (found with the live service)

- **Uploads work again.** DeepSeek now requires a proof of work for `/api/v0/file/upload_file` too (without it:
  `40300 MISSING_HEADER`). Each file is uploaded with its own proof of work, then the app waits until DeepSeek reports the
  file processed (`fetch_files`, PENDING to SUCCESS, a few seconds) before the message refers to it. A file DeepSeek
  cannot process is named in the error.
- **Search turns finish.** Live Search streams patch earlier fragments with negative indices (`-2`, `-3`), nest batches
  with relative paths, send a batch without an operation, and add `TOOL_SEARCH` / `TOOL_OPEN` fragments and
  `update_file` events; v1.0.0 stopped at the first of these. The reader now resolves any negative index, keeps separate
  inheritance for batch children and ignores events, fields and fragment types it does not know.
- **Citations are chips, never raw text.** `[reference:N]` (DeepThink + Search) resolves through the reply's references
  to the page read, `[citation:N]` (Search) through the result's citation index; both show as small numbered chips that
  open the source. Copied and shared text reads `[N]`.
- **First message after signing in.** The proof of work is solved natively (DeepSeekHashV1, in Kotlin, off the main thread,
  cancellable) instead of loading DeepSeek's hashed worker script into a WebView, which cold-started on the first send.
- **Chats from other devices.** The drawer lists DeepSeek's history (all devices and the website), merged with chats on
  this phone by session id; opening one loads its messages and continuing it replies to its current message. A chat
  continued on another device reloads when opened. Pins are set on DeepSeek, so every device agrees.
- **Honest errors.** Connection problems, server errors, an interrupted reply, a rejected proof of work, a file DeepSeek
  could not process and an expired session each have their own message; "web service changed" is left for answers the
  app cannot read. A transient failure before any reply text is retried once automatically; if it still fails, the draft
  and files come back and the banner offers **Retry**.

## New

- **Thinking timeline** as in the official app: "Thinking" while it streams, then "Thought for N seconds"; expanded, a grey
  timeline with a dot per thinking stage and the search steps "Found N web pages" and "Read N pages" with small site icons
  (DeepSeek's icon service or the site's favicon, cached, a letter when none loads). Each step opens its list of pages.
- **Like / Dislike** under replies, sent to DeepSeek (`message_feedback`) and kept with the message; tapping again clears it.
- **Files as chips** above the message (type colour, name, type and size such as "TXT 1.2MB"), also for files in chats
  loaded from DeepSeek.
- **Inline attach panel** under the composer: a strip of recent photos (pick several, then add them) above Camera, Photo and
  Document. Gallery access is asked only when you tap **Recent photos**; without it the Photo tile's system picker works as
  before.
- **Create image:** Share on a reply offers text, an image card (this app's own mark, the question and the reply, and
  "AI-generated, for reference only") and PDF.
- **Saved to Downloads** with **Open** after Download.
- DeepSeek's generated chat titles replace the first words of the prompt, as in the official app.

## Changed

- Official-parity details: no title on an empty new chat; the composer's attach and voice buttons are outlined circles; the
  Think pill uses an atom-like mark of our own; the drawer search shows a magnifier; reply actions are Copy, Regenerate,
  Like, Dislike and Share (Export as PDF moved into Share); dialogs and sheets use neutral white or dark-grey surfaces
  with 20 dp corners instead of Material's tinted ones.
- **Permissions:** INTERNET stays the only install-time permission. Next to the microphone, photo access
  (`READ_MEDIA_IMAGES`; `READ_EXTERNAL_STORAGE` up to Android 12L) is requested at the moment you tap Recent photos.
- History keeps up to 300 chats; over 8 MiB the oldest chats that DeepSeek also keeps drop their local copy and load again
  when opened. History that exists only on this phone is never dropped.
- The Release E2E fixture now behaves like the live service: real proof-of-work challenges at the live difficulty with
  checked answers for chats and uploads, uploads that stay PENDING until polled, the live Search and DeepThink + Search
  stream shapes, per-account history with a chat "from another device", pins and feedback. New journeys: DeepThink +
  Search, a file shared into the app and uploaded, and the server chat opened from the drawer and continued.

## Results

| Check | Where | Result |
|---|---|---|
| Unit tests (`:core:test` incl. DeepSeekHashV1Test, LiveSearchStreamTest, WebSyncTest; `:app:testDebugUnitTest`) | CI | PENDING |
| Android lint | CI | PENDING |
| Debug and release builds; merged release permissions | CI | PENDING |
| Emulator androidTests, light and dark (incl. OfficialScreenshotsTest timeline, chips, attach panel, reply actions) | Emulator smoke | PENDING |
| Release E2E, mocked backend with live shapes, API 35 light (all journeys, tool coverage, think_search, upload, server_history) | Release E2E | PENDING |
| Release E2E, mocked backend, API 35 dark and API 26 light (baseline, native proof of work) | Release E2E | PENDING |
| Release E2E, live login-page check | Release E2E | PENDING |
| Live service, signed-in test account on a real phone: upload, Search, DeepThink + Search, history, Like | Supervisor (cloud phone) | PENDING |
| Signed release APK published with SHA256SUMS.txt | Tag workflow | PENDING |

The mocked suite covers the protocol and the interface, not DeepSeek's real model replies.
