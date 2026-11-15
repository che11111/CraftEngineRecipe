#!/bin/sh
# 从源码依次构建每个 Minecraft 目标，再构建独立扩展 JAR，最后收集到 dist/。
# JAVA_HOME 用于选择 JDK 25；CER_MAVEN 可指定 PATH 之外的 Maven 可执行文件。
# 额外参数会原样传给 Maven，例如：./build-all.sh -o -Dmaven.repo.local=/path/to/repository
set -eu

cer_project=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cer_maven=${CER_MAVEN:-mvn}
if ! command -v "$cer_maven" >/dev/null 2>&1; then
    printf 'Maven not found: %s. Set CER_MAVEN to its executable path.\n' "$cer_maven" >&2
    exit 1
fi
cer_java=${JAVA_HOME:+$JAVA_HOME/bin/java}
if [ -z "$cer_java" ] || [ ! -x "$cer_java" ]; then
    cer_java=$(command -v java || true)
fi
if [ -z "$cer_java" ] || [ ! -x "$cer_java" ]; then
    printf 'Java not found. Use ./build.sh or set JAVA_HOME to Homebrew JDK 25.\n' >&2
    exit 1
fi
cer_java_version=$("$cer_java" -version 2>&1 |
    awk -F'"' '/version/ {print $2; exit}')
case "$cer_java_version" in
    25.*) ;;
    *)
        printf 'JDK 25 required, found %s. Use ./build.sh or set JAVA_HOME=/opt/homebrew/opt/openjdk@25.\n' \
            "${cer_java_version:-unknown}" >&2
        exit 1
        ;;
esac

cer_stage=$(mktemp -d "${TMPDIR:-/tmp}/cer-build-all.XXXXXX")
trap 'rm -rf "$cer_stage"' 0
trap 'exit 1' HUP INT TERM

# 在构建任何目标前统一刷新一次按版本归档的原版配方资源。
printf 'Extracting vanilla recipes from lib/*.jar...\n'
sh "$cer_project/scripts/extract-vanilla-recipes.sh"
printf 'Extracting vanilla cultivation data from lib/*.jar...\n'
sh "$cer_project/scripts/extract-vanilla-cultivation.sh"
printf 'Preparing versioned vanilla zh_cn/en_us translations...\n'
sh "$cer_project/scripts/extract-vanilla-translations.sh"

for cer_label in 1.21.7-1.21.11 26.1 26.2 26.3; do
    printf '\nBuilding Minecraft %s...\n' "$cer_label"
    JAVA_HOME=${JAVA_HOME:-"$(dirname "$(dirname "$cer_java")")"} \
        "$cer_maven" -B -ntp -f "$cer_project/pom.xml" "$@" -P"mc-$cer_label" clean install

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

# 扩展只依赖稳定的 CER API。核心安装到本地仓库后，独立 reactor 把来源实现
# 打包为固定文件名，避免升级时同时残留多个版本 JAR。
printf '\nBuilding extension JARs...\n'
JAVA_HOME=${JAVA_HOME:-"$(dirname "$(dirname "$cer_java")")"} \
    "$cer_maven" -B -ntp -f "$cer_project/extensions-build/pom.xml" "$@" clean package

mkdir -p "$cer_stage/extensions"
for cer_extension in craftengine craftengine-cultivation minecraft minecraft-cultivation farmersdelight kaleidoscope customcrops private-alchemy; do
    cer_extension_file="cer-ext-$cer_extension.jar"
    cer_extension_jar="$cer_project/extensions-build/$cer_extension/target/$cer_extension_file"
    if [ ! -f "$cer_extension_jar" ]; then
        printf 'Extension JAR missing: %s\n' "$cer_extension_jar" >&2
        exit 1
    fi
    cp "$cer_extension_jar" "$cer_stage/extensions/"
done

# 只有所有目标均构建并通过校验后才发布收集的产物。
mkdir -p "$cer_project/dist" "$cer_project/dist/extensions"
find "$cer_project/dist" -maxdepth 1 -type f -name 'CraftEngineRecipe-*.jar' -delete
find "$cer_project/dist/extensions" -maxdepth 1 -type f -name '*.jar' -delete
for cer_jar in "$cer_stage"/*.jar; do
    cp "$cer_jar" "$cer_project/dist/"
    printf 'Built: %s/dist/%s\n' "$cer_project" "$(basename -- "$cer_jar")"
done
for cer_extension_jar in "$cer_stage"/extensions/*.jar; do
    cp "$cer_extension_jar" "$cer_project/dist/extensions/"
    printf 'Built: %s/dist/extensions/%s\n' "$cer_project" "$(basename -- "$cer_extension_jar")"
done
