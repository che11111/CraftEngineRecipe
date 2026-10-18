#!/bin/sh
# Build each Minecraft target from clean sources, then collect all three release JARs in dist/.
# JAVA_HOME selects JDK 25. CER_MAVEN optionally selects a Maven executable outside PATH.
# Additional arguments are passed to Maven, e.g. ./build-all.sh -o -Dmaven.repo.local=/path/to/repository
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

# Publish the collected outputs only after every target has built and passed verification.
mkdir -p "$cer_project/dist"
for cer_jar in "$cer_stage"/*.jar; do
    cp "$cer_jar" "$cer_project/dist/"
    printf 'Built: %s/dist/%s\n' "$cer_project" "$(basename -- "$cer_jar")"
done
