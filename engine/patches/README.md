# Android payload patches

`apply-payload-patches.mjs` adapts the bundled DeepSeek Harness (`@deepseek-ai/dsh`, MIT) to the Android app sandbox.
It is a Node port of [dsh-android](https://github.com/yhm080315-sudo/dsh-android)'s `patches/apply-payload-patches.sh`
(MIT, Copyright (c) 2026 DSH for Android contributors, commit d799c76), so the same patches run in CI when the payload is
built and on the phone when the app installs a newer harness. Texts are in `payload-patches.json`; each entry records
where it came from (`origin`).

```
node engine/patches/apply-payload-patches.mjs <runtime>/dsh/node_modules --arch arm64|x64 --rg <runtime>/usr/bin/rg
node engine/patches/apply-payload-patches.mjs <dir> --check     # report only
```

Every patch matches an exact, unique anchor and leaves a marker; a file that already has the marker is skipped. A
required patch that neither applies nor is present fails the run (exit 1), so a harness upgrade that moved an anchor
stops the build instead of shipping a runtime that breaks on the phone.

| Id | What | Why (Android) | Origin |
|---|---|---|---|
| P1 | dsh-app-boot reads Node internals through `--expose-internals` when `node-addon-require-builtin` cannot load | the addon has no bionic build; the app starts node with `--expose-internals` | new (dsh-android lists it as manual) |
| P2 | `flock` is a no-op | no native binding for bionic; one harness process per app | dsh-android |
| P3, P3a, P3b | session logs publish with a lock file + `rename` when `link()` is refused | SELinux denies hard links to apps; never a `copyFile` fallback (it corrupts sessions) | rewritten: dsh-android's P3 calls a `publishExclusive` it never defines |
| P4 | directory fsync tolerates EACCES/EPERM | the durability walk reaches directories above the app's data dir | dsh-android |
| P5 | header-only PNG/JPEG/GIF/WebP probe when `sharp` cannot load | sharp ships glibc/musl libraries only; images then pass through unchanged | new (dsh-android lists it as manual) |
| P9h, P9 | bare plugin names resolve from the profile's `baseUrl` | without Node's internal loader, `import(name)` resolves against the harness install | P9 from dsh-android; P9h defines the helper P9 calls (missing upstream) |
| P11 | the Write tool creates new files with a lock + `rename` | same `link()` refusal as P3 | dsh-android |
| P12 | `@vscode/ripgrep-android-<arch>` points at the bundled Termux `rg` | no android package on npm; grep/glob tools fail silently without it | dsh-android, generalised to x64 |
| P13 | ptc workers keep `LD_LIBRARY_PATH`, OpenSSL paths and `TMPDIR` | the bionic node cannot start without them | dsh-android |
| P17, P17b | SIGINT from a shell-tool interrupt no longer stops the harness; SIGTERM is logged | the terminal's SIGINT reached the harness itself | dsh-android |

Left out on purpose: dsh-android's P14-P16a (open files through its root device bridge), P16 salvage/rebuild patches
(their anchors moved in 0.2.1-rc.2 and they are not needed for a clean start), P19 (its boot-animation skin, which this
app does not bundle). Re-check every patch on each harness upgrade: `engine/VERSION` pins the version they were checked
against, and CI fails when one stops applying.
