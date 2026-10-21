# TASKS — v1.3.0 "Official DeepSeek engine" (versionCode 13)

Branch feature/v1.3.0 from origin/main a1683b4 (v1.2.0 merged and tagged). The frozen task file lives outside the repo;
this is the progress checklist. On restart, continue from the first item that is not DONE.
Status: TODO / WIP / DONE (commit) / CI (run id green).

| # | Item | Status |
|---|------|--------|
| 1 | Official engine (dsh) as default: bionic Node payload, NodeServer foreground service, WebView host, payload patches, no su | TODO |
| 2 | Flavors `full` (default, targetSdk 28, per-ABI payload) and `play` (targetSdk 35); release APK names | TODO |
| 3 | Payload built in CI only: scripts/build-dsh-payload.sh + workflow step, pinned Termux debs with sha256, cached | TODO |
| 4 | First screen: Continue with Google (existing route) + Use API key (dsh); key into dsh credentials, mode 600 | TODO |
| 5 | Declutter: minimal first screen, one full-screen chat, one overflow menu; dsh UI defects 1-10 fixed (no wallpaper, no glass, opaque header/sheets/previews, insets, stats into details, no host banner, live slash palette) | TODO |
| 6 | Native agent behind overflow > Advanced (off by default); play flavor keeps working | TODO |
| 7 | Tests: tar extractor, env builder, port picker, credentials writer, harness updater (mocked registry), plugin install/remove; emulator payload + full UI check; Google route to picker; screenshots | TODO |
| 8 | Credits/licences: THIRD-PARTY.md, NOTICE, README credit, in-app About/licences | TODO |
| 9 | README rewrite, docs/RELEASE_NOTES_130.md, CHANGELOG | TODO |
| 10 | versionCode 13 / versionName 1.3.0 | TODO |

Also: GitHub fork muhib-karim/dsh-android (TODO); pinned upstream commit d799c76e1cad9113bd2efe76688f865610d76c18 (app 1.0.18).
Harness update check in overflow menu (TODO). Plugin install/manage in Settings > Plugins (TODO).

## CI record
(none yet)
