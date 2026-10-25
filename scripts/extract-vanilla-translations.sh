#!/bin/sh
# 按 Minecraft 版本缓存官方 zh_cn 语言文件，供原版物品搜索名称使用。
set -eu

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
output_root="$project_root/src/main/resources/vanilla-lang"
manifest='https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'

mkdir -p "$output_root"

fetch_version() {
    version_id=$1
    output_name=$2
    target="$output_root/$output_name/zh_cn.json"
    if [ -s "$target" ]; then
        printf '%s: cached zh_cn -> %s\n' "$version_id" "$target"
        return
    fi

    version_url=$(curl -L --fail --silent --show-error "$manifest" |
        jq -r --arg id "$version_id" '.versions[] | select(.id == $id) | .url')
    if [ -z "$version_url" ] || [ "$version_url" = "null" ]; then
        printf 'Minecraft version not found: %s\n' "$version_id" >&2
        exit 1
    fi
    asset_index=$(curl -L --fail --silent --show-error "$version_url" | jq -r '.assetIndex.url')
    hash=$(curl -L --fail --silent --show-error "$asset_index" |
        jq -r '.objects["minecraft/lang/zh_cn.json"].hash')
    mkdir -p "$(dirname "$target")"
    staging=$(mktemp "$output_root/.zh_cn.XXXXXX")
    trap 'rm -f "$staging"' 0
    trap 'exit 1' HUP INT TERM
    curl -L --fail --silent --show-error \
        "https://resources.download.minecraft.net/${hash%${hash#??}}/$hash" -o "$staging"
    jq -e 'type == "object" and length > 1000' "$staging" >/dev/null
    mv "$staging" "$target"
    printf '%s: downloaded zh_cn -> %s\n' "$version_id" "$target"
}

fetch_version '26.1.2' 'minecraft-26.1'
fetch_version '26.2' 'minecraft-26.2'
fetch_version '26.3' 'minecraft-26.3'
