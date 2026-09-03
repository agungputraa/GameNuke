#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "Usage: $0 <gamenuke-remote-config.v1.json> <ed25519-private.pem>" >&2
  exit 2
fi

config_file="$1"
private_key="$2"
signature_file="${config_file}.sig"

openssl pkeyutl -sign -rawin -inkey "$private_key" -in "$config_file" |
  openssl base64 -A > "$signature_file"

echo "Created $signature_file"
echo "Upload it beside the JSON at the same Raw URL plus .sig"
