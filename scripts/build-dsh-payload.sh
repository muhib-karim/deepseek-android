#!/usr/bin/env bash
# Build the official DeepSeek Harness engine payload for one ABI (CI only; GitHub Actions is the build machine).
#
#   scripts/build-dsh-payload.sh <arm64-v8a|x86_64> [out dir, default engine/payload/jniLibs]
#
# Output: <out>/<abi>/libdshpayload.so, a gzip tar of the runtime tree the app extracts to filesDir/runtime:
#   usr/{bin,lib,libexec,etc/tls,share}   Termux bionic builds of node (LTS), bash, ripgrep, coreutils, git + their libs
#   dsh/node_modules                      @deepseek-ai/dsh (npm, --omit=dev --ignore-scripts), dsh-web-mobile, pnpm
#   PAYLOAD                               what was built (versions, ABI, package list)
# The ".so" name only makes AGP's ABI splits ship it per ABI (stored uncompressed under lib/<abi>/); it is not ELF.
#
# Approach and patches from dsh-android (MIT, https://github.com/yhm080315-sudo/dsh-android): Termux bionic binaries
# (glibc/musl ones are killed by the app seccomp filter), LibreOffice WASM dropped, Android patches applied.
# Every .deb is pinned in engine/termux-packages.lock with its sha256; nothing is resolved here. Never commit the output.
set -euo pipefail

if [[ "${GITHUB_ACTIONS:-}" != "true" ]]; then
  echo "build-dsh-payload.sh runs only in GitHub Actions (the payload is never built on a developer machine)." >&2
  exit 2
fi

ABI="${1:?usage: build-dsh-payload.sh <arm64-v8a|x86_64> [out dir]}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${2:-$HERE/engine/payload/jniLibs}"
case "$ABI" in
  arm64-v8a) DEB_ARCH=aarch64; NODE_ARCH=arm64 ;;
  x86_64) DEB_ARCH=x86_64; NODE_ARCH=x64 ;;
  *) echo "unsupported ABI: $ABI" >&2; exit 2 ;;
esac
# shellcheck disable=SC1091
. "$HERE/engine/VERSION"
: "${DSH_VERSION:?}" "${DSH_WEB_MOBILE:?}" "${PNPM_VERSION:?}"
REPO="https://packages.termux.dev/apt/termux-main"
TERMUX_USR="data/data/com.termux/files/usr"

WORK="$(mktemp -d "${RUNNER_TEMP:-/tmp}/dsh-payload.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
DEBS="$WORK/debs"; ROOT="$WORK/root"; RT="$WORK/runtime"
mkdir -p "$DEBS" "$ROOT" "$RT/usr" "$RT/dsh" "$RT/home" "$RT/tmp"

echo "== 1/6 Termux packages ($DEB_ARCH) =="
packages=()
while read -r arch name version filename sha256 size; do
  [[ -z "${arch:-}" || "$arch" == \#* || "$arch" != "$DEB_ARCH" ]] && continue
  deb="$DEBS/$(basename "$filename")"
  curl --fail --silent --show-error --location --retry 4 --retry-delay 5 --max-time 300 -o "$deb" "$REPO/$filename" || {
    echo "::error::cannot download $name $version ($filename); Termux may have replaced it: refresh engine/termux-packages.lock with scripts/termux_lock.py" >&2
    exit 1
  }
  echo "$sha256  $deb" | sha256sum --check --quiet || { echo "::error::sha256 mismatch for $name $version" >&2; exit 1; }
  [[ "$(stat -c %s "$deb")" == "$size" ]] || { echo "::error::size mismatch for $name" >&2; exit 1; }
  dpkg-deb -x "$deb" "$ROOT"
  packages+=("$name=$version")
done < "$HERE/engine/termux-packages.lock"
(( ${#packages[@]} > 10 )) || { echo "::error::no packages for $DEB_ARCH in the lock" >&2; exit 1; }
echo "  ${#packages[@]} packages verified and unpacked"

echo "== 2/6 runtime prefix =="
SRC="$ROOT/$TERMUX_USR"
cp -a "$SRC/bin" "$SRC/lib" "$RT/usr/"
[[ -d "$SRC/libexec" ]] && cp -a "$SRC/libexec" "$RT/usr/"
mkdir -p "$RT/usr/etc/tls" "$RT/usr/share"
cp -L "$SRC/etc/tls/cert.pem" "$SRC/etc/tls/openssl.cnf" "$RT/usr/etc/tls/"
for keep in terminfo git-core; do [[ -d "$SRC/share/$keep" ]] && cp -a "$SRC/share/$keep" "$RT/usr/share/"; done
# Licences travel with the binaries (GPL-3.0 for bash/coreutils/git parts; see THIRD-PARTY.md for the sources).
mkdir -p "$RT/usr/share/licenses"
for doc in "$SRC"/share/doc/*; do
  [[ -d "$doc" ]] || continue
  for f in "$doc"/copyright "$doc"/LICENSE* "$doc"/COPYING*; do
    [[ -f "$f" ]] && { mkdir -p "$RT/usr/share/licenses/$(basename "$doc")"; cp -L "$f" "$RT/usr/share/licenses/$(basename "$doc")/"; }
  done
done
cp "$HERE/engine/licenses/GPL-3.0.txt" "$RT/usr/share/licenses/COPYING"
# Static archives and headers are not needed on the phone.
rm -rf "$RT/usr/include" "$RT/usr/lib/pkgconfig" "$RT/usr/lib/cmake"
find "$RT/usr/lib" -name '*.a' -delete
# Termux scripts start with its own prefix; the app has no /data/data/com.termux. Point them at the system shell or
# at bash through PATH (the app sets PATH and PREFIX for every child).
{ grep -rlI '^#!/data/data/com.termux/files/usr/bin/' "$RT/usr/bin" "$RT/usr/libexec" 2>/dev/null || true; } | while read -r script; do
  sed -i '1s|^#!/data/data/com.termux/files/usr/bin/env |#!/system/bin/env |; 1s|^#!/data/data/com.termux/files/usr/bin/sh|#!/system/bin/sh|; 1s|^#!/data/data/com.termux/files/usr/bin/bash|#!/system/bin/sh|' "$script"
done
ln -sfn bash "$RT/usr/bin/sh"
# node scripts cannot rely on a shebang (no /usr/bin/env on Android): small sh launchers instead.
launcher() { # launcher <name> <path under $PREFIX/..>
  printf '#!/system/bin/sh\nexec "$PREFIX/bin/node" "$PREFIX/../%s" "$@"\n' "$2" > "$RT/usr/bin/$1"
  chmod 755 "$RT/usr/bin/$1"
}
rm -f "$RT/usr/bin/npm" "$RT/usr/bin/npx" "$RT/usr/bin/corepack"
[[ -f "$RT/usr/lib/node_modules/npm/bin/npm-cli.js" ]] && launcher npm usr/lib/node_modules/npm/bin/npm-cli.js
[[ -f "$RT/usr/lib/node_modules/npm/bin/npx-cli.js" ]] && launcher npx usr/lib/node_modules/npm/bin/npx-cli.js
launcher pnpm dsh/node_modules/pnpm/bin/pnpm.cjs
launcher dsh dsh/node_modules/@deepseek-ai/dsh/lib/bin.js

echo "== 3/6 harness (npm @deepseek-ai/dsh@$DSH_VERSION) =="
cat > "$RT/dsh/package.json" <<EOF
{
  "name": "deepseek-android-engine",
  "private": true,
  "version": "1.0.0",
  "dependencies": {
    "@deepseek-ai/dsh": "$DSH_VERSION",
    "dsh-web-mobile": "$DSH_WEB_MOBILE",
    "pnpm": "$PNPM_VERSION"
  }
}
EOF
# No install scripts (they would fetch host binaries); android/arch so npm skips linux-only optional packages.
(cd "$RT/dsh" && npm install --omit=dev --ignore-scripts --no-audit --no-fund --os=android --cpu="$NODE_ARCH" --loglevel=warn)
rm -f "$RT/dsh/package-lock.json"
# LibreOffice WASM (145 MB) only serves Office-to-PDF; the harness boots and serves its UI without it.
rm -f "$RT/dsh/node_modules/@deepseek-ai/libreoffice-kit-wasm/assets/soffice.wasm" \
      "$RT/dsh/node_modules/@deepseek-ai/libreoffice-kit-wasm/assets/soffice.data"
# Licence exclusion: the CC-BY-NC-SA whale skin must never ship (THIRD-PARTY.md).
if find "$RT/dsh/node_modules" -maxdepth 2 -name 'dsh-skin-whale-fantasy' | grep -q .; then
  echo "::error::dsh-skin-whale-fantasy (CC-BY-NC-SA) found in the payload" >&2; exit 1
fi

echo "== 4/6 Android patches =="
node "$HERE/engine/patches/apply-payload-patches.mjs" "$RT/dsh/node_modules" --arch "$NODE_ARCH" --rg "$RT/usr/bin/rg"

echo "== 5/6 checks =="
for sentinel in usr/bin/node usr/bin/bash usr/bin/rg usr/bin/git usr/bin/ls usr/etc/tls/cert.pem usr/etc/tls/openssl.cnf \
    dsh/node_modules/@deepseek-ai/dsh/lib/bin.js dsh/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js \
    dsh/node_modules/dsh-web-mobile/package.json dsh/node_modules/pnpm/bin/pnpm.cjs; do
  [[ -e "$RT/$sentinel" ]] || { echo "::error::payload incomplete: $sentinel is missing" >&2; exit 1; }
done
want_machine=$([[ "$NODE_ARCH" == arm64 ]] && echo AArch64 || echo X86-64)
readelf -h "$RT/usr/bin/node" | grep -q "Machine:.*$want_machine" || { echo "::error::node is not $want_machine" >&2; exit 1; }
readelf -l "$RT/usr/bin/node" | grep -q '/system/bin/linker64' || { echo "::error::node is not a bionic build" >&2; exit 1; }
{
  echo "abi=$ABI"
  echo "dsh=$DSH_VERSION"
  echo "dsh-web-mobile=$DSH_WEB_MOBILE"
  echo "pnpm=$PNPM_VERSION"
  echo "built=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "files=$(find "$RT" -type f | wc -l)"
  printf 'termux=%s\n' "${packages[@]}"
} > "$RT/PAYLOAD"
du -sh "$RT/usr" "$RT/dsh"

echo "== 6/6 pack =="
mkdir -p "$OUT/$ABI"
# Contents of runtime/, not the directory: the app extracts straight into filesDir/runtime. No hard links (link(2) is
# refused for apps), owner-only metadata, gzip so the app can stream it with java.util.zip.
tar -C "$RT" --hard-dereference --owner=0 --group=0 --numeric-owner -cf - . | gzip -9 > "$OUT/$ABI/libdshpayload.so"
ls -la "$OUT/$ABI/libdshpayload.so"
sha256sum "$OUT/$ABI/libdshpayload.so"
