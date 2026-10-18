# v0.5.1 (2026-10-18)

Live verification of account mode against the DeepSeek web service with a test account.

- Live chat completion with the app's exact request contract: accepted. Streamed replies
  recorded for a basic chat ("pong 7"), a follow-up with DeepThink, and a Search turn.
  Rename, server history and delete returned success.
- New unit test `LiveRecordedStreamTest` replays the three recorded streams through the
  app's stream reader (no reader change was needed).
- Pass/fail: 12 pass, 2 not verified (Google sign-in in the embedded page; proof-of-work in
  the app's WebView on a real phone), 1 not applicable (file upload, by design).
  Details: [LIVE_VERIFY_051.md](LIVE_VERIFY_051.md).
- versionCode 6, versionName 0.5.1.
