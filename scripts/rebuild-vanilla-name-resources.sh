#!/bin/sh
# Rebuilds the compact nine-language item/block-name snapshots bundled in the core JAR.
# VANILLA_LANG_DIR must point to the legacy vanilla-lang cache
# (minecraft-<version>/{en_us,zh_cn,...}.json).
set -eu

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
source_root=${VANILLA_LANG_DIR:-}
if [ -z "$source_root" ] || [ ! -d "$source_root" ]; then
    printf 'VANILLA_LANG_DIR must point to the vanilla language cache.\n' >&2
    exit 1
fi

python3 - "$source_root" "$project_root/src/main/resources/vanilla-name-lang" <<'PY'
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
locales = {
    "en_us": "en_US", "zh_cn": "zh_CN", "zh_tw": "zh_TW",
    "ja_jp": "ja_JP", "ko_kr": "ko_KR", "de_de": "de_DE",
    "fr_fr": "fr_FR", "es_es": "es_ES", "ru_ru": "ru_RU",
}
target.mkdir(parents=True, exist_ok=True)
for version in sorted(path for path in source.iterdir() if path.is_dir()):
    bundle = {}
    for filename, locale in locales.items():
        path = version / f"{filename}.json"
        raw = json.loads(path.read_text(encoding="utf-8"))
        bundle[locale] = {
            key: value for key, value in raw.items()
            if key.startswith(("item.minecraft.", "block.minecraft."))
            and isinstance(value, str)
        }
    output = target / f"{version.name}.json"
    output.write_text(
        json.dumps(bundle, ensure_ascii=False, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    print(f"Built {output}")
PY
