#!/usr/bin/env bash
# CI: move downloaded dsh-payload-<abi> artifacts to engine/payload/jniLibs/<abi>/libdshpayload.so (full flavor jniLibs).
set -euo pipefail
src="${1:?usage: place_payloads.sh <download dir>}"
found=0
for dir in "$src"/dsh-payload-*; do
  [[ -f "$dir/libdshpayload.so" ]] || continue
  abi="${dir##*/dsh-payload-}"
  mkdir -p "engine/payload/jniLibs/$abi"
  mv "$dir/libdshpayload.so" "engine/payload/jniLibs/$abi/libdshpayload.so"
  echo "payload $abi: $(stat -c %s "engine/payload/jniLibs/$abi/libdshpayload.so") bytes"
  found=$((found + 1))
done
(( found > 0 )) || { echo "no payload artifacts under $src" >&2; exit 1; }
