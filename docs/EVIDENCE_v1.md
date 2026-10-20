# v1.0.0 evidence: how the release build is tested

Three kinds of evidence, always labelled, never mixed:

| Label | Workflow | What it proves | What it does not prove |
|---|---|---|---|
| **official reference** | `reference.yml` (manual) | What the official DeepSeek app looks like at our viewport, in both themes | Anything about this app |
| **release with mocked backend** | `release-e2e.yml` jobs `mocked` | The release APK's UI, sign-in gate, storage, tools, files and protocol handling against a deterministic fixture | Live model behaviour, DeepSeek's real responses, Google sign-in |
| **live login-page check** | `release-e2e.yml` job `live_login` | DeepSeek's real sign-in page renders inside the release APK and the browser route opens a real browser on chat.deepseek.com | That anyone signed in (no test account; Google is never automated) |

Debug-build instrumentation tests (`emulator.yml`) remain as before; they inject ViewModels and fake servers into the
debug build and are not release evidence.

## Release with mocked backend

1. **Build** (`build` job). Pull requests and manual runs build `:app:assembleRelease` (R8, resource shrinking, not
   debuggable) signed with a throwaway key made for that run; the tag pipeline passes the exact signed APK it will
   publish. `scripts/check_release_apk.py` checks package, SDK levels (26/35), permissions (INTERNET and RECORD_AUDIO
   only), ABIs, the debuggable flag and the signature. The `:e2e` module (a test-only host app plus UiAutomator tests in
   `e2e/src/androidTest`) is built separately; it shares no code with the app.
2. **Fixture** (`e2e/fixture/server.py`). A standard-library HTTPS server on the runner stands in for chat.deepseek.com
   (sign-in page, `users/current`, sessions, proof-of-work challenge and worker, streamed replies with thinking and
   search, uploads), api.deepseek.com (models, balance, native tool calls) and the pages the web tools read. Replies are
   chosen by markers in the prompt (`E2E-TOOL:<name>`, `E2E-EXPIRE`, `E2E-SLOW`). Its request log keeps hosts, paths,
   flags and tool names only.
3. **Emulator** (`scripts/e2e_device_setup.sh`, disposable emulator only). A throwaway CA (`scripts/e2e_certs.sh`,
   valid 7 days) joins a tmpfs copy of the system trust store (and the Conscrypt APEX store on Android 14+), and
   iptables sends TCP 443 to the fixture. The app keeps its real addresses, public DNS answers and certificate checks;
   there is no endpoint override, authentication bypass or test receiver in the app.
4. **Run** (`scripts/run_release_e2e.sh`). Installs the APK exactly as supplied and compares the installed bytes with
   it, installs the driver, records the screen in bounded `screenrecord` segments and runs the journeys with
   `am instrument`. Each step leaves a numbered screenshot and window hierarchy.
5. **Check** (`scripts/check_e2e_evidence.py`). Requires passing tests, matching APK hashes, 1080 x 2200 step
   screenshots, a decodable video, no credential markers, and the protocol order (session validated before chat, a
   proof-of-work header on every completion, no unexpected request to a fixture host).

Matrix: API 35 light (all journeys), API 35 dark and API 26 light (baseline journey).

## Live login-page check

`LiveLoginTest` on an API 35 emulator with its normal network: the in-app sign-in page must show text (DeepSeek's form
or its security check, never a blank page or the app's blank-page notice), the browser route must open a package that
handles https links with chat.deepseek.com in view, and the app must still be signed out afterwards.

## Status

Nothing here has run yet: the workflows run on GitHub Actions after the branch is pushed. Results, run links and commit
SHAs are recorded in [RELEASE_NOTES_100.md](RELEASE_NOTES_100.md) as they arrive.
