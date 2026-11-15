#!/bin/sh
set -eu

cer_homebrew_java=/opt/homebrew/opt/openjdk@25
if [ ! -x "$cer_homebrew_java/bin/java" ]; then
    printf 'JDK 25 not found at %s. Install it with: brew install openjdk@25\n' "$cer_homebrew_java" >&2
    exit 1
fi

cer_maven=${CER_MAVEN:-}
if [ -z "$cer_maven" ] && command -v mvn >/dev/null 2>&1; then
    cer_maven=mvn
elif [ -z "$cer_maven" ] && [ -x '/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn' ]; then
    cer_maven='/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn'
fi
if [ -z "$cer_maven" ]; then
    printf 'Maven not found. Set CER_MAVEN to its executable path.\n' >&2
    exit 1
fi

exec env JAVA_HOME="$cer_homebrew_java" CER_MAVEN="$cer_maven" \
    "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/build-all.sh" "$@"
