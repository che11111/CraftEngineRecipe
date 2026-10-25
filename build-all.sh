#!/bin/sh
# 从干净源码依次构建每个 Minecraft 目标，再将三个发行版 JAR 收集到 dist/。
# JAVA_HOME 用于选择 JDK 25；CER_MAVEN 可指定 PATH 之外的 Maven 可执行文件。
# 额外参数会原样传给 Maven，例如：./build-all.sh -o -Dmaven.repo.local=/path/to/repository
set -eu

cer_project=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cer_maven=${CER_MAVEN:-mvn}
if ! command -v "$cer_maven" >/dev/null 2>&1; then
    printf 'Maven not found: %s. Set CER_MAVEN to its executable path.\n' "$cer_maven" >&2
    exit 1
fi

cer_stage=$(mktemp -d "${TMPDIR:-/tmp}/cer-build-all.XXXXXX")
trap 'rm -rf "$cer_stage"' 0
trap 'exit 1' HUP INT TERM

# 在构建任何目标前统一刷新一次按版本归档的原版配方资源。
printf 'Extracting vanilla recipes from lib/*.jar...\n'
sh "$cer_project/scripts/extract-vanilla-recipes.sh"
printf 'Extracting vanilla cultivation data from lib/*.jar...\n'
sh "$cer_project/scripts/extract-vanilla-cultivation.sh"
printf 'Preparing versioned vanilla zh_cn translations...\n'
sh "$cer_project/scripts/extract-vanilla-translations.sh"

for cer_label in 26.1 26.2 26.3; do
    printf '\nBuilding Minecraft %s...\n' "$cer_label"
    "$cer_maven" -B -ntp -f "$cer_project/pom.xml" "$@" -P"mc-$cer_label" clean verify

    cer_jar=
    for cer_candidate in "$cer_project"/target/CraftEngineRecipe-*-mc"$cer_label".jar; do
        [ -f "$cer_candidate" ] || continue
        if [ -n "$cer_jar" ]; then
            printf 'More than one release JAR found for Minecraft %s.\n' "$cer_label" >&2
            exit 1
        fi
        cer_jar=$cer_candidate
    done
    if [ -z "$cer_jar" ]; then
        printf 'Release JAR missing for Minecraft %s.\n' "$cer_label" >&2
        exit 1
    fi
    cp "$cer_jar" "$cer_stage/"
done

# 只有所有目标均构建并通过校验后才发布收集的产物。
mkdir -p "$cer_project/dist"
for cer_jar in "$cer_stage"/*.jar; do
    cp "$cer_jar" "$cer_project/dist/"
    printf 'Built: %s/dist/%s\n' "$cer_project" "$(basename -- "$cer_jar")"
done
