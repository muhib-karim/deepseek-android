# DeepSeek Android 1.3.0 "Official DeepSeek engine" (versionCode 13)

## What's new
- **DeepSeek's own agent harness on the phone.** The new **full** build (`deepseek-android-v1.3.0-full-arm64-v8a.apk`,
  package `app.dsandroid.full`) runs `@deepseek-ai/dsh` 0.2.1-rc.2 on a bundled Node runtime and shows its full web UI:
  the agent loop, tools, sessions, attachments, plans, goals, plugins and skills are DeepSeek's own code. Built on
  dsh-android (MIT), with credit.
- **One clean first screen:** Continue with Google (your DeepSeek web account, as in 1.2) or Use API key (one field,
  Start, "Get a key").
- **Cleaner than dsh-android's app:** no wallpaper or skin, no glass; opaque header, sheets, menus, palettes, previews
  and settings; correct status-bar, navigation and keyboard insets; session stats behind the menu; no "Host desktop"
  banner without root. Before/after: [DSH_ENGINE.md](DSH_ENGINE.md).
- **One ⋮ menu:** API key, Session stats, Harness version / Check for updates, Engine status and log, Advanced, About and
  licences.
- **Plugins and skills:** Settings > Plugins > Installed plugins installs from a GitHub URL, an npm name, a file or a
  folder, and can enable, disable, update or remove each one. Skills show in the `/` palette.
- **Harness updates:** a daily npm check installs a newer `@deepseek-ai/dsh` after verifying it (sha512, Android
  patches, `dsh --version`); on failure the bundled harness stays.
- **The play build** (`deepseek-android-v1.3.0-release.apk`, `app.dsandroid`, targetSdk 35) is the 1.2 app unchanged.
  In the full build, this app's own API agent is behind ⋮ > Advanced > Built-in engine (fallback), off by default.

## Why a separate APK
The runtime is unpacked into app storage and executed there, which Android 10+ allows only for apps targeting API 28 or
lower (W^X). Google Play does not accept such apps, so the full build has its own package name and is distributed here.

## Tested
CI: JVM tests (tar extractor, environment, port choice, credentials writer, plugin store, harness updater against a
mocked npm registry), payload patch tests, lint for both flavors. Emulator (x86_64 payload): the bundled node runs
JavaScript, the harness reaches its full UI with a fake key, the first screen, the Google route to the browser hand-off,
the chat, menu, keyboard and palette screens, and a fixture skill appearing in and leaving the `/` palette. Not tested
in CI: real DeepSeek API turns (not reachable from CI), Google's account picker (opt-in, needs a browser and network),
the arm64 payload on a real phone.
