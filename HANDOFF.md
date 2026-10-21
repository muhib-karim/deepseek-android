# HANDOFF — v1.3.0 "Official DeepSeek engine"

Branch feature/v1.3.0 (from origin/main a1683b4). Progress: TASKS.md. Never run Gradle, the emulator or the payload build
locally; CI is the compiler. Push only with the gated push script; never force-push; never tag or merge.

## Design (decided 2026-10-21)
- Flavors (dimension `engine`): `full` = default, applicationId app.dsandroid.full, targetSdk 28, dsh engine;
  `play` = the v1.2.0 app unchanged (app.dsandroid, targetSdk 35). Existing androidTests moved to androidTestPlay.
- ABI splits (arm64-v8a, x86_64) with a universal APK; onVariants disables the universal output for `full` and the
  per-ABI outputs for `play`. The runtime payload is a gzip tar shipped per ABI as jniLibs `lib/<abi>/libdshpayload.so`
  (stored uncompressed in the APK, read through ZipFile at extraction), so ABI splits keep it per ABI.
- Payload: scripts/build-dsh-payload.sh (CI only) + engine/termux-packages.lock (pinned debs + sha256, both arches)
  + engine/VERSION (DSH_VERSION, DSH_WEB_MOBILE_VERSION, PNPM_VERSION). npm install --omit=dev --ignore-scripts,
  then engine/patches/apply_payload_patches.py (port of dsh-android's patches, strict: required anchors must apply).
  Upstream P3 calls an undefined publishExclusive(): our port defines it (lock file + rename, stale-lock break).
- Engine Java (app/src/full/java/com/dsh/android): TarExtractor, RuntimeProbe, FileBridge, NodeServer from
  dsh-android d799c76 (MIT, headers kept) with: no DeviceServer/su, no DiagnosticServer HTTP, no /sdcard state or
  credentials mirror, no SelfUpdater, no whale skin/boot animation/splash/screen-operate rows. dsh-web-mobile (MIT,
  npm) stays as the portrait layout. EngineActivity = WebView host (file chooser, console log, downloads/share) inside
  a native Material 3 frame with system-bar and IME insets, our opaque stylesheet + opacifier script injected, one
  overflow menu.
- Credentials: $DSH_HOME/.credentials.yaml, `version: 1` + `refs: {DEEPSEEK_API_KEY: "..."}`, mode 600.
- Plugins: dsh's own plugin manager needs pnpm; the payload ships pnpm (JS) + usr/bin/pnpm wrapper. Our extension in
  Settings > Plugins (injected section) installs packages via `dsh plugin` and skills into $DSH_HOME/skills.
- Harness updates: HarnessUpdater checks registry.npmjs.org/@deepseek-ai/dsh, downloads the tarball, verifies the
  sha512 integrity, installs next to the bundled one, falls back to bundled on failure.
