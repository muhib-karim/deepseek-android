# Third-party software

DeepSeek Android is MIT-licensed (see [LICENSE](LICENSE)). The **full** build bundles DeepSeek's own agent harness and a
Node runtime; this file lists what it contains and where the corresponding sources are. Nothing listed here is committed
to the repository as a binary: the engine payload is downloaded and assembled in CI (`scripts/build-dsh-payload.sh`,
pins in `engine/VERSION` and `engine/termux-packages.lock`).

## Code this project is built on

### dsh-android (MIT)
- https://github.com/yhm080315-sudo/dsh-android, commit d799c76e1cad9113bd2efe76688f865610d76c18 (app 1.0.18); fork for
  upstream tracking: https://github.com/muhib-karim/dsh-android
- Copyright (c) 2026 DSH for Android contributors. MIT License (full text below and in every ported file's header).
- Ported into `app/src/full/java/app/dsandroid/engine/` with changes noted at the top of each file: `NodeServer`,
  `TarExtractor`, `RuntimeProbe`, `FileBridge`, the WebView host logic (`EngineWebView`), the runtime environment
  (`RuntimeEnv`) and port choice (`PortPicker`). Its payload patches (`patches/apply-payload-patches.sh`) are ported to
  `engine/patches/` (Node), with P3 and P9 completed and P1 and P5 written here; its packaging approach (Termux bionic
  binaries, LibreOffice WASM dropped) is `scripts/build-dsh-payload.sh`.
- Left out on purpose: the root device bridge (`DeviceServer`, `su`), `SelfUpdater`/`UpdateProvider` (its own releases),
  the launcher console and background image (`LauncherTheme`), the diagnostics HTTP server, and the `/sdcard` mirror of
  state and credentials.

```
MIT License

Copyright (c) 2026 DSH for Android contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Bundled in the full build's engine payload

| Component | Version | Licence | Source |
|---|---|---|---|
| DeepSeek Harness `@deepseek-ai/dsh` and its `@deepseek-ai/*` packages | 0.2.1-rc.2 (`engine/VERSION`) | MIT | https://www.npmjs.com/package/@deepseek-ai/dsh |
| npm dependencies of the harness (installed with `--omit=dev`) | as resolved by npm | MIT, ISC, BSD, Apache-2.0 (each package's own LICENSE ships in `dsh/node_modules`) | npm registry |
| dsh-web-mobile (the harness's phone layout plugin) | 3.2.1 | MIT, Copyright (c) mexiaosh | https://github.com/mexiaosqwq/dsh-web-mobile |
| pnpm (lets the harness's own plugin manager run) | 11.28.6 | MIT | https://github.com/pnpm/pnpm |
| Node.js (Termux `nodejs-lts`) with npm | 24.18.0 | MIT (bundled V8, OpenSSL, ICU, libuv and others under their own licences) | https://github.com/termux/termux-packages/tree/master/packages/nodejs-lts |
| ripgrep (Termux `ripgrep`) | 15.2.0 | MIT or Unlicense | https://github.com/termux/termux-packages/tree/master/packages/ripgrep |
| GNU bash, coreutils, grep, sed, findutils, gawk, tar, gzip (Termux) | see lock | GPL-3.0-or-later | https://github.com/termux/termux-packages/tree/master/packages/ (one directory per package) |
| git (Termux) | 2.56.0 | GPL-2.0 | https://github.com/termux/termux-packages/tree/master/packages/git |
| Libraries: OpenSSL, zlib, ICU, SQLite, c-ares, libcurl, nghttp2/3, ngtcp2, libssh2, pcre2, ncurses, readline, libiconv, gmp, mpfr, expat, acl, attr, libandroid-* (Termux) | see lock | Apache-2.0, zlib, Unicode, public domain, MIT, curl, BSD, GPL/LGPL | Termux package directories as above |

GPL components are unmodified Termux builds, distributed as binaries; the complete GPL-3.0 text ships in the payload
(`usr/share/licenses/COPYING`, from `engine/licenses/GPL-3.0.txt`) with each package's copyright file
(`usr/share/licenses/<package>/`). Corresponding source is the Termux package recipe at the link above (exact versions in
`engine/termux-packages.lock`) plus the upstream release it builds; on request we provide the source archive for any
binary in a release we published.

## Never bundled

- **dsh-skin-whale-fantasy** (CC-BY-NC-SA-4.0, the anime wallpaper skin in dsh-android's own APK): non-commercial and
  share-alike, incompatible with this MIT project. The payload build fails if it appears.
- dsh-android's boot-animation and splash plugins and its root screen-control plugin.

## The rest of the app

Dependencies of both builds (AndroidX, Jetpack Compose, OkHttp, Chaquopy/Python, pdfbox-android, AndroidSVG, ML Kit) are
listed with their licences in [NOTICE](NOTICE).
