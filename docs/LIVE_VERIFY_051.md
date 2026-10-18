# Live account-mode verification (2026-10-18, 14:44-14:47 UTC)

A test DeepSeek account signed in on chat.deepseek.com in a real Chrome session
(email + password, then the email verification code; the code request is guarded by an
hCaptcha that was solved interactively). The account token from `localStorage.userToken`
was then used for requests built exactly like `DeepSeekWebClient`: Bearer token, no cookies,
`Origin`/`Referer` chat.deepseek.com, `x-client-platform: web`, `x-client-version: 2.5.0`,
`x-client-locale: en_US`, `x-client-bundle-id: com.deepseek.chat`, and a Base64
`X-DS-PoW-Response` from the official DeepSeekHashV1 worker (fe-static
`static/76608.8f2a9fa413.js` + chunk `8138`), run unmodified.

Result: the service accepted the app's request contract. No additional signed header was
required. The recorded streams are in `docs/live-2026-10-18/` (they carry no token or cookie).

| # | Feature | Live result | Evidence |
|---|---------|-------------|----------|
| 1 | Sign-in (email + password + email code) | PASS (in browser) | token captured from localStorage after "Verify and log in" |
| 2 | Account check `users/current` | PASS | code 0 |
| 3 | New chat `chat_session/create` | PASS | session id returned |
| 4 | PoW challenge + official worker answer | PASS | difficulty 144000, solved in ~0.7-1.7 s on a CPU |
| 5 | Send + streamed reply `chat/completion` | PASS | `chat_basic.sse`: "pong 7", status FINISHED |
| 6 | Follow-up turn with `parent_message_id` | PASS | `chat_followup_deepthink.sse`: answers "7" |
| 7 | DeepThink (`thinking_enabled`) | PASS | THINK then RESPONSE fragments |
| 8 | Search (`search_enabled`) | PASS | `chat_search.sse`: SEARCH fragment with result URLs, FINISHED |
| 9 | Rename `chat_session/update_title` | PASS | code 0, title visible in server history |
| 10 | History (server `chat_session/fetch_page`) | PASS (server) | 5 sessions listed; the app itself keeps local history only |
| 11 | Delete `chat_session/delete` | PASS | code 0 |
| 12 | Google sign-in in the embedded page | NOT VERIFIED | no Google test account; Google may refuse embedded browsers |
| 13 | In-app PoW through the WebView worker on a phone | NOT VERIFIED | same worker code proven here outside a WebView |
| 14 | File upload | N/A by design | app sends extracted text/OCR; originals are not uploaded |
| 15 | Copy/share, settings/theme, logout (local clear) | PASS in emulator CI | 54 emulator tests, v0.5.0 runs |

Not claimed: pixel equivalence with the official app, or behaviour on a physical phone.
