#!/bin/sh
# 从客户端 JAR 中提取按版本归档的原版栽培元数据。
# 生成文件位于 cultivation/ 下，不会放入 recipes/ 根目录。
set -eu

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
library_dir=${VANILLA_LIB_DIR:-"$project_root/lib"}
output_root="$project_root/src/main/resources/recipes/cultivation"
staging_root=$(mktemp -d "${TMPDIR:-/tmp}/cer-vanilla-cultivation.XXXXXX")
trap 'rm -rf "$staging_root"' 0
trap 'exit 1' HUP INT TERM

mkdir -p "$output_root"

extract_version() {
    jar_name=$1
    output_name=$2
    jar_path="$library_dir/$jar_name"
    target="$output_root/$output_name"

    if [ ! -f "$jar_path" ]; then
        printf 'Missing vanilla client JAR: %s\n' "$jar_path" >&2
        exit 1
    fi

    version_stage="$staging_root/$output_name"
    mkdir -p "$version_stage"
    python3 - "$jar_path" "$output_name" "$version_stage/vanilla.json" <<'PY'
import json
import sys
import zipfile

jar_path, version, output = sys.argv[1:4]

# 此清单描述客户端中硬编码的玩法放置规则；
# 战利品表与方块 ID 仍会对选定的客户端 JAR 进行验证。
crops = [
    ("wheat", "wheat", ["wheat_seeds"], ["wheat", "wheat_seeds"], ["farmland"], "成熟小麦；收获时还会获得种子"),
    ("carrots", "carrots", ["carrot"], ["carrot"], ["farmland"], "成熟胡萝卜"),
    ("potatoes", "potatoes", ["potato"], ["potato", "poisonous_potato"], ["farmland"], "成熟马铃薯，可能额外获得毒马铃薯"),
    ("beetroots", "beetroots", ["beetroot_seeds"], ["beetroot", "beetroot_seeds"], ["farmland"], "成熟甜菜根；收获时还会获得种子"),
    ("torchflower_crop", "torchflower_crop", ["torchflower_seeds"], ["torchflower", "torchflower_seeds"], ["farmland"], "成熟火把花；收获时还会获得种子"),
    ("pitcher_crop", "pitcher_crop", ["pitcher_pod"], ["pitcher_plant", "pitcher_pod"], ["farmland"], "成熟瓶子草；收获时还会获得种荚"),
    ("melon_stem", "melon_stem", ["melon_seeds"], ["melon_slice", "melon"], ["farmland"], "果实生成在茎旁；精准采集可获得西瓜方块"),
    ("pumpkin_stem", "pumpkin_stem", ["pumpkin_seeds"], ["pumpkin", "pumpkin_seeds"], ["farmland"], "果实生成在茎旁"),
    ("sugar_cane", "sugar_cane", ["sugar_cane"], ["sugar_cane"], ["sand_or_dirt_adjacent_water"], "沙子或泥土紧邻水时种植"),
    ("cactus", "cactus", ["cactus"], ["cactus"], ["sand"], "沙子或红沙"),
    ("sweet_berry_bush", "sweet_berry_bush", ["sweet_berries"], ["sweet_berries"], ["dirt_like"], "草方块、泥土、灰化土或粗泥"),
    ("bamboo", "bamboo", ["bamboo"], ["bamboo"], ["dirt_like", "sand"], "可种植在草方块、泥土或沙子上"),
    ("kelp", "kelp", ["kelp"], ["kelp"], ["underwater_floor"], "水下沙、泥土、沙砾等可支撑方块"),
    ("sea_pickle", "sea_pickle", ["sea_pickle"], ["sea_pickle"], ["underwater_floor"], "必须种在水下"),
    ("vine", "vine", ["vine"], ["vine"], ["vertical_side"], "附着在方块侧面"),
    ("cave_vines", "cave_vines", ["glow_berries"], ["glow_berries"], ["ceiling"], "种在方块底部，用发光浆果种植"),
    ("twisting_vines", "twisting_vines", ["twisting_vines"], ["twisting_vines"], ["ceiling"], "种在方块底部"),
    ("weeping_vines", "weeping_vines", ["weeping_vines"], ["weeping_vines"], ["ceiling"], "种在方块底部"),
    ("cocoa", "cocoa", ["cocoa_beans"], ["cocoa_beans"], ["jungle_log_side"], "种在丛林原木侧面"),
    ("nether_wart", "nether_wart", ["nether_wart"], ["nether_wart"], ["soul_sand"], "种在灵魂沙上"),
    ("chorus_flower", "chorus_flower", ["chorus_flower"], ["chorus_fruit", "chorus_flower"], ["end_stone"], "种在末地石上"),
    ("lily_pad", "lily_pad", ["lily_pad"], ["lily_pad"], ["water_surface"], "放置在水面"),
]

identifiers = {
    "farmland": ["minecraft:farmland"],
    "sand_or_dirt_adjacent_water": ["minecraft:sand", "minecraft:red_sand", "minecraft:dirt", "minecraft:grass_block", "minecraft:coarse_dirt"],
    "sand": ["minecraft:sand", "minecraft:red_sand"],
    "dirt_like": ["minecraft:grass_block", "minecraft:dirt", "minecraft:podzol", "minecraft:coarse_dirt"],
    "underwater_floor": ["minecraft:sand", "minecraft:dirt", "minecraft:gravel"],
    "vertical_side": ["minecraft:oak_log", "minecraft:stone", "minecraft:dirt"],
    "ceiling": ["minecraft:stone", "minecraft:netherrack", "minecraft:dirt"],
    "jungle_log_side": ["minecraft:jungle_log"],
    "soul_sand": ["minecraft:soul_sand"],
    "end_stone": ["minecraft:end_stone"],
    "water_surface": ["minecraft:water"],
}

def names(value):
    found = []
    if isinstance(value, dict):
        if value.get("type") == "minecraft:item" and isinstance(value.get("name"), str):
            found.append(value["name"])
        for child in value.values():
            found.extend(names(child))
    elif isinstance(value, list):
        for child in value:
            found.extend(names(child))
    return found

records = []
with zipfile.ZipFile(jar_path) as archive:
    available = set(archive.namelist())
    for crop_id, block, seeds, products, substrate_keys, note in crops:
        loot_path = f"data/minecraft/loot_table/blocks/{block}.json"
        if loot_path not in available:
            raise SystemExit(f"Missing {loot_path} in {jar_path}")
        loot = json.loads(archive.read(loot_path))
        observed = []
        for item in names(loot):
            if item.startswith("minecraft:") and item not in observed:
                observed.append(item)
        records.append({
            "id": f"minecraft:{crop_id}",
            "seeds": [f"minecraft:{item}" for item in seeds],
            "products": [f"minecraft:{item}" for item in products],
            "substrates": [identifier for key in substrate_keys for identifier in identifiers[key]],
            "note": note,
            "loot_table": loot_path,
            "loot_items": observed,
        })

with open(output, "w", encoding="utf-8") as file:
    json.dump({
        "schema": 1,
        "version": version,
        "source": "client-jar",
        "crops": records,
    }, file, ensure_ascii=False, indent=2)
    file.write("\n")
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
    printf '%s: %s cultivation entries -> %s\n' "$jar_name" \
        "$(grep -c '"id": "minecraft:' "$target/vanilla.json")" "$target"
}

extract_version '26.1.2.jar' 'minecraft-26.1'
extract_version '26.2.jar' 'minecraft-26.2'
extract_version '26.3.jar' 'minecraft-26.3'
