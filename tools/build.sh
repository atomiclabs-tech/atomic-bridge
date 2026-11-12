#!/usr/bin/env bash
set -euo pipefail
# JAVA_HOME: JDK root; defaults to Homebrew OpenJDK 21.
# ANDROID_HOME: SDK root; defaults to $HOME/Library/Android/sdk.
# GRADLE_USER_HOME: warmed cache root; defaults to $HOME/.gradle.
# ATOMIC_SIGNING_DIR: project signing material; defaults to <checkout>/.signing.
# TMPDIR: set to <checkout>/.gradle/tmp for Gradle, compiler and child JVM files.

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME-$HOME/Library/Android/sdk}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME-$HOME/.gradle}"
export ATOMIC_SIGNING_DIR="${ATOMIC_SIGNING_DIR-$project_root/.signing}"

for variable in JAVA_HOME ANDROID_HOME GRADLE_USER_HOME; do
    resolved_path="${!variable}"
    printf '%s=%s\n' "$variable" "$resolved_path"
    if [[ ! -d "$resolved_path" ]]; then
        printf 'Error: %s directory is missing: %s\n' "$variable" "$resolved_path" >&2
        exit 1
    fi
done
if [[ ! -x "$JAVA_HOME/bin/java" || ! -x "$JAVA_HOME/bin/keytool" ]]; then
    printf 'Error: JAVA_HOME must contain executable bin/java and bin/keytool.\n' >&2
    exit 1
fi
if [[ ! -d "$ANDROID_HOME/platforms" ]]; then
    printf 'Error: ANDROID_HOME must contain an installed platforms directory.\n' >&2
    exit 1
fi
if [[ ! -x "$project_root/gradlew" || ! -f "$project_root/gradle/wrapper/gradle-wrapper.jar" ]]; then
    printf 'Error: project Gradle wrapper is missing.\n' >&2
    exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"
export TMPDIR="$project_root/.gradle/tmp"
if ! mkdir -p "$TMPDIR" || [[ ! -w "$TMPDIR" ]]; then
    printf 'Error: TMPDIR must be a writable directory: %s\n' "$TMPDIR" >&2
    exit 1
fi
printf 'TMPDIR=%s\n' "$TMPDIR"
cd "$project_root"
"$project_root/tools/ensure-signing-key.sh"
exec ./gradlew --offline --no-daemon "-Djava.io.tmpdir=$TMPDIR" "$@"
