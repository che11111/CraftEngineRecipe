#!/usr/bin/env python3
"""Generate the bundled CER locale files while preserving MiniMessage tokens."""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import re
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

import yaml


TARGETS = {
    "zh_TW": ("zh-CN", "zh-TW"),
    "ja_JP": ("en", "ja"),
    "ko_KR": ("en", "ko"),
    "de_DE": ("en", "de"),
    "fr_FR": ("en", "fr"),
    "es_ES": ("en", "es"),
    "ru_RU": ("en", "ru"),
}
TOKEN = re.compile(r"<[^>]*>|\{[a-zA-Z0-9_-]+\}|%[a-zA-Z0-9_.%+-]+|&[0-9a-fk-orx]")
SEPARATOR = re.compile(r"\s*ZXQSEP(\d+)QXZ\s*")
TOKEN_SEARCH = re.compile(r"[A-Z]*TOKEN(\d+)QXZ")
LANG_NAMES = {
    "language-en": "English",
    "language-zh": "简体中文",
    "language-ja": "日本語",
    "language-ko": "한국어",
    "language-zh-tw": "繁體中文",
    "language-de": "Deutsch",
    "language-fr": "Français",
    "language-es": "Español",
    "language-ru": "Русский",
}


def mask(value: str) -> tuple[str, list[str]]:
    tokens: list[str] = []

    def replace(match: re.Match[str]) -> str:
        index = len(tokens)
        tokens.append(match.group(0))
        return f"ZXQTOKEN{index:04d}QXZ"

    return TOKEN.sub(replace, value), tokens


def unmask(value: str, tokens: list[str], original: str) -> str:
    value = TOKEN_SEARCH.sub(
        lambda match: f"ZXQTOKEN{int(match.group(1)):04d}QXZ", value)
    indexes = [int(match) for match in TOKEN_SEARCH.findall(value)]
    if sorted(indexes) != list(range(len(tokens))):
        raise RuntimeError(f"translation damaged protected tokens: {original!r} -> {value!r}")
    for index, token in enumerate(tokens):
        value = value.replace(f"ZXQTOKEN{index:04d}QXZ", token)
    return value


class Translator:
    def __init__(self) -> None:
        self.cache: dict[tuple[str, str, str], str] = {}
        self.last_request = 0.0

    def request(self, text: str, source: str, target: str) -> str:
        key = (source, target, text)
        if key in self.cache:
            return self.cache[key]
        wait = 0.5 - (time.monotonic() - self.last_request)
        if wait > 0:
            time.sleep(wait)
        self.last_request = time.monotonic()
        query = urllib.parse.urlencode({
            "client": "gtx",
            "sl": source,
            "tl": target,
            "dt": "t",
            "q": text,
        })
        url = "https://clients5.google.com/translate_a/t?" + query
        error: Exception | None = None
        for attempt in range(4):
            try:
                request = urllib.request.Request(url, headers={"User-Agent": "CraftEngineRecipe-language-builder/1.0"})
                with urllib.request.urlopen(request, timeout=45) as response:
                    data = json.loads(response.read().decode("utf-8"))
                if data and isinstance(data[0], str):
                    translated = data[0]
                else:
                    translated = "".join(part[0] for part in data[0] if part and part[0])
                self.cache[key] = translated
                return translated
            except Exception as caught:  # noqa: BLE001 - retry network failures.
                error = caught
                time.sleep(2 ** attempt)
        raise RuntimeError(f"translation request failed: {error}")

    def translate(self, value: str, source: str, target: str) -> str:
        if not value or not any(character.isalpha() for character in value):
            return value
        leading = value[: len(value) - len(value.lstrip())]
        trailing = value[len(value.rstrip()) :]
        core = value.strip()
        masked, tokens = mask(core)
        translated = self.request(masked, source, target).strip()
        try:
            restored = unmask(translated, tokens, value)
        except RuntimeError:
            translated = self.request(masked, source, target)
            try:
                restored = unmask(translated, tokens, value)
            except RuntimeError:
                return value
        return leading + restored + trailing

    def translate_many(self, values: list[str], source: str, target: str) -> list[str]:
        output = list(values)
        pending: list[tuple[int, str]] = []
        for index, value in enumerate(values):
            if value and re.search(r"[A-Za-z\u4e00-\u9fff]", value):
                pending.append((index, value))
        position = 0
        while position < len(pending):
            batch: list[tuple[int, str]] = []
            characters = 0
            while position < len(pending) and len(batch) < 12:
                item = pending[position]
                length = len(item[1]) + 24
                if batch and characters + length > 800:
                    break
                batch.append(item)
                characters += length
                position += 1
            masked_values: list[str] = []
            token_sets: list[list[str]] = []
            padding: list[tuple[str, str]] = []
            for _, value in batch:
                leading = value[: len(value) - len(value.lstrip())]
                trailing = value[len(value.rstrip()) :]
                masked, tokens = mask(value.strip())
                masked_values.append(masked)
                token_sets.append(tokens)
                padding.append((leading, trailing))
            joined = "\n".join(
                f"{value}\nZXQSEP{index:04d}QXZ"
                for index, value in enumerate(masked_values)
            )
            translated = self.request(joined, source, target)
            parts = SEPARATOR.split(translated)
            if len(parts) < len(batch) * 2:
                raise RuntimeError(f"batch separator damaged for {source}->{target}")
            translated_values: list[str] = []
            for index in range(len(batch)):
                translated_values.append(parts[index * 2].strip())
            for (original_index, original), translated_value, tokens, whitespace in zip(
                    batch, translated_values, token_sets, padding):
                if translated_value:
                    try:
                        translated_value = unmask(translated_value, tokens, original)
                    except RuntimeError:
                        translated_value = self.translate(original, source, target)
                output[original_index] = (
                    whitespace[0] + (translated_value or original.strip()) + whitespace[1]
                )
        return output


def string_values(node: Any) -> list[str]:
    values: list[str] = []
    if isinstance(node, dict):
        for value in node.values():
            values.extend(string_values(value))
    elif isinstance(node, list):
        for value in node:
            values.extend(string_values(value))
    elif isinstance(node, str):
        values.append(node)
    return values


def replace_strings(node: Any, replacements: list[str], cursor: list[int]) -> Any:
    if isinstance(node, dict):
        return {key: replace_strings(value, replacements, cursor) for key, value in node.items()}
    if isinstance(node, list):
        return [replace_strings(value, replacements, cursor) for value in node]
    if isinstance(node, str):
        value = replacements[cursor[0]]
        cursor[0] += 1
        return value
    return node


def load_yaml(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as stream:
        return yaml.safe_load(stream)


def save_yaml(path: Path, data: Any, locale: str, source: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as stream:
        stream.write(f"# Generated {locale} translation from {source.name}.\n")
        yaml.safe_dump(data, stream, allow_unicode=True, sort_keys=False, width=4096)


def generate_file(translator: Translator, source_path: Path, target_path: Path, locale: str) -> None:
    source_locale, target_locale = TARGETS[locale]
    source = load_yaml(source_path)
    values = string_values(source)
    translated = translator.translate_many(values, source_locale, target_locale)
    result = replace_strings(source, translated, [0])
    if isinstance(result, dict) and isinstance(result.get("ui"), dict):
        personalization = result["ui"].get("personalization")
        if isinstance(personalization, dict):
            for key, value in LANG_NAMES.items():
                if key in personalization:
                    personalization[key] = value
    save_yaml(target_path, result, locale, source_path)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--workers", type=int, default=3)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    project = args.project
    translator = Translator()
    jobs: list[tuple[Path, Path, str]] = []
    core = project / "src/main/resources/lang"
    common = core / "integrations/common"
    for locale in TARGETS:
        source = core / ("zh_CN.yml" if locale == "zh_TW" else "en_US.yml")
        jobs.append((source, core / f"{locale}.yml", locale))
        common_source = common / ("zh_CN.yml" if locale == "zh_TW" else "en_US.yml")
        jobs.append((common_source, common / f"{locale}.yml", locale))
    for menu_lang in (project / "src/main/resources/resources").glob("cer_menu_*/lang"):
        for locale in TARGETS:
            source = menu_lang / ("zh_CN.yml" if locale == "zh_TW" else "en_US.yml")
            jobs.append((source, menu_lang / f"{locale}.yml", locale))
    for module in ("craftengine", "craftengine-cultivation", "minecraft",
                   "minecraft-cultivation", "farmersdelight", "kaleidoscope", "customcrops"):
        lang = project / "extensions-build" / module / "src/main/resources/lang"
        for locale in TARGETS:
            source = lang / ("zh_CN.yml" if locale == "zh_TW" else "en_US.yml")
            jobs.append((source, lang / f"{locale}.yml", locale))
    if not args.force:
        jobs = [job for job in jobs if not job[1].is_file()]
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.workers)) as pool:
        futures = [pool.submit(generate_file, translator, source, target, locale)
                   for source, target, locale in jobs]
        for future in concurrent.futures.as_completed(futures):
            future.result()
    print(f"Generated {len(jobs)} locale files.")


if __name__ == "__main__":
    main()
