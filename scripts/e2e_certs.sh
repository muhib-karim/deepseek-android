#!/usr/bin/env bash
# CI only: a throwaway CA and one server certificate for the E2E fixture's host names. Valid for 7 days, created per
# run in a temporary directory, installed only on the disposable emulator and deleted with the runner.
# Usage: e2e_certs.sh OUTPUT_DIR  ->  ca.pem, ca.key, leaf.pem, leaf.key, <subject_hash_old>.0
set -euo pipefail
out="${1:?output directory}"
mkdir -p "$out"
cd "$out"
openssl req -x509 -newkey rsa:2048 -nodes -days 7 -subj "/CN=DeepSeek Android E2E throwaway CA" \
  -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" \
  -keyout ca.key -out ca.pem 2>/dev/null
cat > leaf.ext <<'EXT'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:chat.deepseek.com,DNS:fe-static.deepseek.com,DNS:api.deepseek.com,DNS:html.duckduckgo.com,DNS:example.com,DNS:www.example.com,DNS:cdn.jsdelivr.net,DNS:cdn.deepseek.com
EXT
openssl req -newkey rsa:2048 -nodes -subj "/CN=chat.deepseek.com" -keyout leaf.key -out leaf.csr 2>/dev/null
openssl x509 -req -in leaf.csr -CA ca.pem -CAkey ca.key -CAcreateserial -days 7 -extfile leaf.ext -out leaf.pem 2>/dev/null
# Android's system store names each CA by its old-style subject hash.
hash="$(openssl x509 -inform PEM -subject_hash_old -noout -in ca.pem)"
{ cat ca.pem; openssl x509 -in ca.pem -text -fingerprint -noout; } > "$hash.0"
echo "$hash.0"
