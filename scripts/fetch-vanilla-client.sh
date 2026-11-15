#!/bin/sh
# Downloads and verifies the official Minecraft client JAR used for build-time data
# extraction. Existing local files are never replaced.
set -eu

version=${1:?Minecraft version is required}
target=${2:?Target JAR path is required}
if [ -s "$target" ]; then
    exit 0
fi

manifest=${MOJANG_VERSION_MANIFEST:-'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'}
version_url=$(curl -L --fail --silent --show-error "$manifest" |
    jq -r --arg id "$version" '.versions[] | select(.id == $id) | .url')
if [ -z "$version_url" ] || [ "$version_url" = null ]; then
    printf 'Minecraft version not found: %s\n' "$version" >&2
    exit 1
fi

metadata=$(curl -L --fail --silent --show-error "$version_url")
client_url=$(printf '%s' "$metadata" | jq -r '.downloads.client.url')
expected_sha1=$(printf '%s' "$metadata" | jq -r '.downloads.client.sha1')
if [ -z "$client_url" ] || [ "$client_url" = null ] \
    || [ -z "$expected_sha1" ] || [ "$expected_sha1" = null ]; then
    printf 'Client download metadata is incomplete for %s\n' "$version" >&2
    exit 1
fi

mkdir -p "$(dirname "$target")"
staging=$(mktemp "${target}.download.XXXXXX")
trap 'rm -f "$staging"' 0
trap 'exit 1' HUP INT TERM
curl -L --fail --silent --show-error "$client_url" -o "$staging"
actual_sha1=$(shasum -a 1 "$staging" | awk '{print $1}')
if [ "$actual_sha1" != "$expected_sha1" ]; then
    printf 'Client SHA-1 mismatch for %s: expected %s, got %s\n' \
        "$version" "$expected_sha1" "$actual_sha1" >&2
    exit 1
fi
mv "$staging" "$target"
printf '%s: downloaded official client -> %s\n' "$version" "$target"
