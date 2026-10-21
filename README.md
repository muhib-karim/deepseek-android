# DeepSeek Android: DeepSeek's official agent on your phone

[![CI](https://github.com/muhib-karim/deepseek-android/actions/workflows/ci.yml/badge.svg)](https://github.com/muhib-karim/deepseek-android/actions/workflows/ci.yml)
[![Emulator](https://github.com/muhib-karim/deepseek-android/actions/workflows/emulator.yml/badge.svg)](https://github.com/muhib-karim/deepseek-android/actions/workflows/emulator.yml)

An unofficial Android app that runs **DeepSeek's own agent harness**
([`@deepseek-ai/dsh`](https://www.npmjs.com/package/@deepseek-ai/dsh), MIT) on the phone and shows its full web UI as
a clean, full-screen chat. Every tool, the agent loop, sessions, attachments, skins, plugins and skills are DeepSeek's
own code, the same as on the desktop. The app adds a first screen, one overflow menu, harness updates from npm and a way
to install plugins and skills. It is built on [dsh-android](https://github.com/yhm080315-sudo/dsh-android) (MIT).

Not affiliated with DeepSeek.

| First screen | DeepSeek's harness | One menu | Keyboard open |
|---|---|---|---|
| ![First screen](docs/screenshots/v130/01-first-screen.png) | ![Chat](docs/screenshots/v130/04-engine-chat.png) | ![Menu](docs/screenshots/v130/05-overflow-menu.png) | ![Keyboard](docs/screenshots/v130/06-keyboard-open.png) |

## Quick start

1. Download `deepseek-android-v1.3.0-full-arm64-v8a.apk` from [Releases](https://github.com/muhib-karim/deepseek-android/releases)
   and install it (allow installs from your browser or file manager).
2. Open it, tap **Use API key**, paste a key from [platform.deepseek.com](https://platform.deepseek.com/api_keys) and
   tap **Start**. The first start unpacks the engine once ("Preparing the engine… n%", about a minute).
3. Chat. Type `/` for commands and skills, `+` for files, goals and plans. Everything else is in the ⋮ menu.

Prefer your DeepSeek web account? **Continue with Google** on the first screen signs in through your phone's browser
and uses chat.deepseek.com, as in v1.1/v1.2.

## Two builds

| | **full** (recommended) | **play** |
|---|---|---|
| Engine | DeepSeek's harness (`dsh web`) on a bundled Node runtime | This app's own clients (web account, API mode) |
| Package | `app.dsandroid.full` | `app.dsandroid` |
| Target SDK | 28 | 35 |
| Release file | `deepseek-android-v1.3.0-full-arm64-v8a.apk` | `deepseek-android-v1.3.0-release.apk` |
| Size | about 150-200 MB (runtime + harness) | about 60 MB |

**Why targetSdk 28.** The harness is a Node.js program. The full build unpacks Node, bash, coreutils, ripgrep and git
(Termux builds for Android's libc) into the app's private storage and runs them there, as Termux does. Since
Android 10, apps that target API 29 or newer may not execute files they wrote themselves (W^X). Targeting API 28 keeps
that working on current Android. Google Play no longer accepts such apps, so the full build is a separate APK with its
own package name, installed next to the Play build if you like.

## Privacy

- Your API key is written only to the harness's own credentials file in the app's private storage
  (`$DSH_HOME/.credentials.yaml`, mode 600). It is never logged, synced or copied to shared storage.
- With an API key, the harness on your phone talks to `api.deepseek.com`, and for tools to the sites the agent
  fetches. The app checks `registry.npmjs.org` once a day for harness updates (you can turn this off in ⋮ > Advanced).
- The harness listens only on `127.0.0.1` and requires its own session token. The app has no root and no device
  control. Files you pick are copied in, with JPEG location data removed first; photo access is asked only if you open
  the recent-photos strip on the Google route.
- With Continue with Google, chats go to chat.deepseek.com under your DeepSeek account, as in v1.2.

## The ⋮ menu

API key · Session stats · Harness version / Check for updates · Engine status and log · Advanced (automatic harness
updates, the built-in engine from v1.2 as a fallback, switch to Google sign-in) · About and licences.

## Plugins and skills

In DeepSeek's harness everything is a plugin. **Settings > Plugins** keeps its built-in list and gains an
**Installed plugins** section. Paste a GitHub URL (`https://github.com/owner/repo`, optionally `/tree/<ref>/<folder>`)
or an npm package name, or pick a `.tgz`/`.zip` file or a folder. Each installed plugin can be enabled, disabled, updated
or removed. A skill (a folder with `SKILL.md`) installs into the harness's skill folder and appears in the `/` palette
after a moment. A plugin package gets its loader row and the engine restarts to load it.

## What changed from dsh-android's app

The harness and its UI are the same. The app around it is cleaner: no animated wallpaper or skin, no glass or
translucent surfaces (header, sheets, menus, palettes, previews and settings are opaque), proper status-bar, navigation
and keyboard insets, session stats behind the menu, and no "Host desktop" error banner on a phone without root.
Before/after shots of each defect: [docs/DSH_ENGINE.md](docs/DSH_ENGINE.md).

## Building

GitHub Actions is the build: `ci.yml` (tests, lint, both flavors), `payload.yml` (engine payload per ABI from pinned
Termux packages and npm, cached), `emulator.yml` (engine journey on an x86_64 emulator, screenshots) and `release.yml`
(signed APKs on a `v*` tag). Nothing is built on a developer machine; see [AGENTS.md](AGENTS.md).

## Credits

- **DeepSeek Harness** (`@deepseek-ai/dsh`), MIT, by DeepSeek: the engine.
- **dsh-android** by yhm080315-sudo, MIT: runtime packaging, Android patches, NodeServer, TarExtractor, RuntimeProbe,
  FileBridge and the WebView host, ported here with its licence (commit d799c76; fork at muhib-karim/dsh-android).
- **dsh-web-mobile** by mexiaosh, MIT: the harness's phone layout.
- **Termux** packages: Node.js (MIT), bash, coreutils and other GNU tools (GPL-3.0), ripgrep (MIT/Unlicense), git (GPL-2.0).
- **deepseek-harness** (MIT): protocol and safety design of the play build.

Full list and licence texts: [THIRD-PARTY.md](THIRD-PARTY.md), [NOTICE](NOTICE), and ⋮ > About and licences in the app.
Earlier README (v1.2.0 features of the play build): [docs/README_v120.md](docs/README_v120.md).

Licence: MIT.
