#!/bin/sh
# 按 Minecraft 版本缓存官方 9 种语言文件，供原版物品名称本地化使用。
set -eu

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
output_root="$project_root/extensions-build/minecraft/src/main/resources/vanilla-lang"
library_dir=${VANILLA_LIB_DIR:-"$project_root/lib"}
manifest='https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'

mkdir -p "$output_root"

fetch_version() {
    version_id=$1
    output_name=$2

    sh "$project_root/scripts/fetch-vanilla-client.sh" \
        "$version_id" "$library_dir/$version_id.jar"
    version_url=$(curl -L --fail --silent --show-error "$manifest" |
        jq -r --arg id "$version_id" '.versions[] | select(.id == $id) | .url')
    if [ -z "$version_url" ] || [ "$version_url" = "null" ]; then
        printf 'Minecraft version not found: %s\n' "$version_id" >&2
        exit 1
    fi
    asset_index=$(curl -L --fail --silent --show-error "$version_url" | jq -r '.assetIndex.url')
    for locale in en_us zh_cn ja_jp ko_kr zh_tw de_de fr_fr es_es ru_ru; do
        target="$output_root/$output_name/$locale.json"
        if [ -s "$target" ]; then
            printf '%s: cached %s -> %s\n' "$version_id" "$locale" "$target"
            continue
        fi
        mkdir -p "$(dirname "$target")"
        staging=$(mktemp "$output_root/.$locale.XXXXXX")
        trap 'rm -f "$staging"' 0
        trap 'exit 1' HUP INT TERM
        if [ "$locale" = en_us ] && [ -f "$library_dir/$version_id.jar" ] \
            && unzip -p "$library_dir/$version_id.jar" assets/minecraft/lang/en_us.json >"$staging" \
            && jq -e 'type == "object" and length > 1000' "$staging" >/dev/null 2>&1; then
            mv "$staging" "$target"
            printf '%s: extracted %s -> %s\n' "$version_id" "$locale" "$target"
            continue
        fi
        object_path="minecraft/lang/$locale.json"
        hash=$(curl -L --fail --silent --show-error "$asset_index" |
            jq -r --arg path "$object_path" '.objects[$path].hash // empty')
        if [ -z "$hash" ]; then
            printf '%s: language asset not found: %s\n' "$version_id" "$object_path" >&2
            exit 1
        fi
        prefix=$(printf '%s' "$hash" | cut -c1-2)
        curl -L --fail --silent --show-error \
            "https://resources.download.minecraft.net/$prefix/$hash" -o "$staging"
        jq -e 'type == "object" and length > 1000' "$staging" >/dev/null
        mv "$staging" "$target"
        printf '%s: downloaded %s -> %s\n' "$version_id" "$locale" "$target"
    done
}

fetch_version '26.1.2' 'minecraft-26.1'
fetch_version '26.2' 'minecraft-26.2'
fetch_version '26.3' 'minecraft-26.3'
fetch_version '1.21.7' 'minecraft-1.21.7'
fetch_version '1.21.8' 'minecraft-1.21.8'
fetch_version '1.21.9' 'minecraft-1.21.9'
fetch_version '1.21.10' 'minecraft-1.21.10'
fetch_version '1.21.11' 'minecraft-1.21.11'
