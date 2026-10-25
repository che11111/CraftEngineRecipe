#!/bin/sh
# 将原版配方 JSON 转换为 CER 的 TrMenu 风格 YAML 格式。
# 输出位置：src/main/resources/recipes/minecraft-<profile>/<workstation>.yml
set -eu

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
library_dir=${VANILLA_LIB_DIR:-"$project_root/lib"}
output_root="$project_root/src/main/resources/recipes"
staging_root=$(mktemp -d "${TMPDIR:-/tmp}/cer-vanilla-recipes.XXXXXX")
trap 'rm -rf "$staging_root"' 0
trap 'exit 1' HUP INT TERM

mkdir -p "$output_root"

extract_version() {
    jar_name=$1
    output_name=$2
    jar_path="$library_dir/$jar_name"
    target="$output_root/$output_name"
    version_stage="$staging_root/$output_name"
    mkdir -p "$version_stage"

    if [ ! -f "$jar_path" ]; then
        printf 'Missing vanilla client JAR: %s\n' "$jar_path" >&2
        exit 1
    fi

    python3 - "$jar_path" "$version_stage" <<'PY'
import itertools
import json
import re
import sys
import zipfile
from pathlib import Path
import yaml

jar_path, output_dir = sys.argv[1:3]
output_dir = Path(output_dir)

STATIONS = {
    "crafting": ("minecraft:crafting", "minecraft:crafting_table", "原版·工作台", {"minecraft:crafting_shaped", "minecraft:crafting_shapeless", "minecraft:crafting_transmute", "minecraft:crafting_dye", "minecraft:crafting_special_bannerduplicate", "minecraft:crafting_special_shielddecoration", "minecraft:crafting_imbue", "minecraft:crafting_decorated_pot"}),
    "smelting": ("minecraft:smelting", "minecraft:furnace", "原版·熔炉", {"minecraft:smelting"}),
    "blasting": ("minecraft:blasting", "minecraft:blast_furnace", "原版·高炉", {"minecraft:blasting"}),
    "smoking": ("minecraft:smoking", "minecraft:smoker", "原版·烟熏炉", {"minecraft:smoking"}),
    "campfire_cooking": ("minecraft:campfire_cooking", "minecraft:campfire", "原版·营火", {"minecraft:campfire_cooking"}),
    "stonecutting": ("minecraft:stonecutting", "minecraft:stonecutter", "原版·切石机", {"minecraft:stonecutting"}),
    "smithing": ("minecraft:smithing", "minecraft:smithing_table", "原版·锻造台", {"minecraft:smithing_transform"}),
}

CRAFT_SLOTS = [10, 11, 12, 19, 20, 21, 28, 29, 30]
INPUT_SLOTS = [19, 20, 21, 22, 28, 29, 30]
SYMBOLS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

def load_tags(archive):
    tags = {}
    for name in archive.namelist():
        prefix = "data/minecraft/tags/item/"
        if not name.startswith(prefix) or not name.endswith(".json"):
            continue
        data = json.loads(archive.read(name))
        values = []
        for value in data.get("values", []):
            if isinstance(value, str):
                values.append(value)
            elif isinstance(value, dict) and isinstance(value.get("id"), str):
                values.append(value["id"])
        tags["minecraft:" + name[len(prefix):-5]] = values
    return tags

def expand_tag(value, tags, seen=None):
    seen = set() if seen is None else seen
    if not value.startswith("#"):
        return [value]
    key = value[1:]
    if key in seen:
        return []
    seen.add(key)
    result = []
    for child in tags.get(key, []):
        result.extend(expand_tag(child, tags, set(seen)))
    return list(dict.fromkeys(result))

def choice_values(value, tags):
    if isinstance(value, str):
        return expand_tag(value, tags)
    if isinstance(value, list):
        result = []
        for child in value:
            result.extend(choice_values(child, tags))
        return list(dict.fromkeys(result))
    if isinstance(value, dict):
        item = value.get("item") or value.get("id")
        if item:
            return expand_tag(item, tags)
        tag = value.get("tag")
        if tag:
            return expand_tag("#" + tag, tags)
    return []

def amount(value, fallback=1):
    if isinstance(value, dict) and isinstance(value.get("count"), int):
        return max(1, value["count"])
    return fallback

def parse_result(root):
    result = root.get("result")
    if isinstance(result, str):
        return result, 1
    if not isinstance(result, dict):
        return None
    item = result.get("id") or result.get("item")
    if not item:
        return None
    return item, amount(result)

def parse_ingredients(root, recipe_type, tags):
    if recipe_type == "minecraft:crafting_shaped":
        pattern = root.get("pattern") or []
        keys = root.get("key") or {}
        entries = []
        for row, line in enumerate(pattern[:3]):
            offset_y = (3 - min(3, len(pattern))) // 2
            offset_x = (3 - len(line)) // 2
            for col, symbol in enumerate(line[:3]):
                if symbol == " " or symbol not in keys:
                    continue
                choices = choice_values(keys[symbol], tags)
                if not choices:
                    return None
                slot = (row + offset_y) * 3 + (col + offset_x)
                entries.append({"group": symbol, "choices": choices, "amount": 1, "slot": CRAFT_SLOTS[slot]})
        return entries
    if recipe_type == "minecraft:crafting_shapeless":
        entries = []
        for index, raw in enumerate((root.get("ingredients") or [])[:9]):
            choices = choice_values(raw, tags)
            if not choices:
                return None
            entries.append({"group": str(index), "choices": choices, "amount": 1, "slot": CRAFT_SLOTS[index]})
        return entries
    keys_by_type = {
        "minecraft:crafting_transmute": ["input", "material"],
        "minecraft:crafting_dye": ["dye", "target"],
        "minecraft:crafting_special_bannerduplicate": ["banner"],
        "minecraft:crafting_special_shielddecoration": ["target", "banner"],
        "minecraft:crafting_imbue": ["source", "material"],
        "minecraft:crafting_decorated_pot": ["back", "front", "left", "right"],
        "minecraft:smithing_transform": ["template", "base", "addition"],
    }
    keys = keys_by_type.get(recipe_type)
    if keys:
        entries = []
        for index, key in enumerate(keys):
            choices = choice_values(root.get(key), tags)
            if not choices:
                return None
            entries.append({"group": key, "choices": choices, "amount": 1, "slot": CRAFT_SLOTS[min(index, 8)]})
        return entries
    raw = root.get("ingredient")
    choices = choice_values(raw, tags)
    if not choices:
        return None
    return [{"group": "input", "choices": choices, "amount": amount(raw), "slot": INPUT_SLOTS[0]}]

def recipe_document(recipe_id, entries, result_id, result_count, layout):
    groups = {}
    for entry in entries:
        groups.setdefault(entry["group"], entry["choices"])
    variants = 1
    for choices in groups.values():
        variants = min(64, variants * max(1, len(choices)))
    documents = []
    groups_list = list(groups.items())
    for variant in range(variants):
        selected = {}
        counter = variant
        for group, choices in groups_list:
            selected[group] = choices[counter % len(choices)]
            counter //= len(choices)
        rows = ["         " for _ in range(6)]
        icons = {}
        for index, entry in enumerate(entries):
            symbol = SYMBOLS[index]
            slot = entry["slot"]
            row = slot // 9
            col = slot % 9
            chars = list(rows[row])
            chars[col] = symbol
            rows[row] = "".join(chars)
            icons[symbol] = {
                "Display": {"Material": selected[entry["group"]]},
                "Usage": "ingredient",
                "Amount": entry["amount"],
            }
        result_symbol = SYMBOLS[len(entries)]
        slot = 24
        chars = list(rows[slot // 9])
        chars[slot % 9] = result_symbol
        rows[slot // 9] = "".join(chars)
        icons[result_symbol] = {
            "Display": {"Material": result_id},
            "Usage": "result",
            "Amount": result_count,
        }
        suffix = "" if variant == 0 else f"-variant-{variant + 1:02d}"
        documents.append((recipe_id + suffix, {"Layout": rows, "Icons": icons}))
    return documents

with zipfile.ZipFile(jar_path) as archive:
    tags = load_tags(archive)
    names = sorted(name for name in archive.namelist()
                   if name.startswith("data/minecraft/recipe/") and name.endswith(".json"))
    grouped = {key: {} for key in STATIONS}
    for name in names:
        root = json.loads(archive.read(name))
        recipe_type = root.get("type", "")
        station_key = next((key for key, value in STATIONS.items() if recipe_type in value[3]), None)
        if station_key is None:
            continue
        parsed_result = parse_result(root)
        entries = parse_ingredients(root, recipe_type, tags)
        if parsed_result is None or not entries:
            continue
        base_id = "minecraft_data:" + re.sub(r"[^a-z0-9/._-]+", "_", name.rsplit("/", 1)[-1][:-5].lower())
        variants = recipe_document(base_id, entries, parsed_result[0], parsed_result[1], None)
        for recipe_id, recipe in variants:
            grouped[station_key][recipe_id] = recipe

    for station_key, recipes in grouped.items():
        if not recipes:
            continue
        station_id, icon, title, _types = STATIONS[station_key]
        document = {
            "Title": title,
            "Layout": ["         " for _ in range(6)],
            "Options": {"Page": "campfire" if station_key == "campfire_cooking" else station_key},
            "Workstation": {
                "id": station_id,
                "name": title,
                "icon": {"Material": icon},
            },
            "Recipes": recipes,
        }
        with (output_dir / f"{station_key}.yml").open("w", encoding="utf-8") as file:
            yaml.safe_dump(document, file, allow_unicode=True, sort_keys=False, width=4096)
PY

    case "$target" in
        "$output_root"/minecraft-*) ;;
        *)
            printf 'Refusing to replace unexpected path: %s\n' "$target" >&2
            exit 1
            ;;
    esac
    rm -rf "$target"
    mv "$version_stage" "$target"
    count=$(rg -c '^  minecraft_data:' "$target"/*.yml | awk -F: '{sum += $2} END {print sum}')
    printf '%s: %s CER recipes -> %s\n' "$jar_name" "$count" "$target"
}

extract_version '26.1.2.jar' 'minecraft-26.1'
extract_version '26.2.jar' 'minecraft-26.2'
extract_version '26.3.jar' 'minecraft-26.3'
