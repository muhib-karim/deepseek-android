# v0.7.1 "Sign-in fix" (2026-10-20)

Fixes the blank **Log in with DeepSeek** page, adds **Continue with Google** for DeepSeek accounts that use Google,
and stops the signed-out composer from looking usable.

Validation: PENDING (CI and emulator results are filled in after the pull request runs).

## Fixed

- **Blank sign-in page.** chat.deepseek.com now answers first visits with an AWS WAF security check
  (HTTP 202 with `x-amzn-waf-action: challenge`: an empty-looking page that runs a script and reloads).
  The sign-in browser had three faults on that path:
  - Google's sign-in scripts were answered with an empty "successful" response, which left DeepSeek's page
    half-initialised. They now fail like a blocked network, the same as where Google is unreachable.
  - The script that hides the Google button had lost its quotes and threw a syntax error on every page.
  - A security-check (CAPTCHA) response was treated as a refused login. It now stays on screen with a
    short notice, so it can be completed.
- **Signed out, the composer looked usable.** With no DeepSeek session it now takes no typing, reads
  "Log in to chat", and a tap on it (or on send) opens the sign-in page.
- If the sign-in page is still blank after 15 seconds, the app says so and points to **Continue with Google**,
  which signs in through the browser and works for any DeepSeek account (and suggests updating Android System
  WebView and Chrome).
- Debug builds record page addresses (without queries), main-frame errors, other hosts contacted and console
  lines for diagnosis. Release builds record nothing.

## New

- **Continue with Google** (on the sign-in page). Google does not allow its sign-in inside an app's embedded
  browser, so this uses the phone's browser once:
  1. **Copy the helper** and save it in Chrome as a bookmark named `dsandroid`.
  2. **Open DeepSeek in your browser** and sign in with Google.
  3. Type `dsandroid` in the address bar, tap the bookmark, then **Open in DeepSeek Android**, and confirm
     in the app.
  The helper runs only on chat.deepseek.com, reads only the page's session and hands it to this app through
  an intent addressed to this package. The app takes a handed-back session only within 30 minutes of
  starting this from the app, and asks before signing in. A link the app did not ask for is ignored.
- **Paste session**: if the browser does not switch back to the app, **Copy session instead** on the page
  and paste it in the app.
- The emulator workflow has an opt-in `live_login` input that loads the real sign-in page and uploads a
  trace and a screenshot. It is not part of pull request checks.

## Tests

- `BrowserSignInTest` (JVM): the helper targets only this package and reads only the session; pending
  window; garbage, expired and short sessions rejected; the usual pasted shapes accepted.
- `BrowserSignInLinkTest` (emulator): the helper's intent parses to the receiver with the session; a
  pending sign-in takes it; an unrequested link, garbage and a missing session are ignored.
- `AccountFeaturesTest.feature12_loginWebView` checks the new notice and both new buttons.
- `OfficialScreenshotsTest`: the signed-out composer is disabled and reads "Log in to chat"; the sign-in page
  shows Continue with Google.

## Results

| Check | Result |
| --- | --- |
| CI (unit tests, lint, debug and release builds) | PENDING |
| Emulator tests | PENDING |
| Live sign-in page on an emulator (`live_login`) | PENDING |
| Google sign-in through the browser on a real phone | NOT RUN (no Google test account on a device) |
| Sign-in page on a real phone | NOT RUN |

versionCode 9, versionName 0.7.1.
