#!/usr/bin/env bash
set -euo pipefail
# ANDROID_HOME: SDK root; defaults to $HOME/Library/Android/sdk.
# ATOMIC_BUILD_TOOLS: build-tools directory; defaults to the highest SDK version.

fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }
[[ $# -ge 1 ]] || fail 'Usage: check-release-apk.sh <apk> [--version-code N] [--debuggable]'
apk="$1"
shift
version_code=10
allow_debuggable=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --version-code)
            [[ $# -ge 2 ]] || fail '--version-code requires an integer.'
            version_code="$2"
            shift 2 ;;
        --debuggable) allow_debuggable=true; shift ;;
        *) fail "Unknown option: $1" ;;
    esac
done
[[ "$version_code" =~ ^[0-9]+$ ]] || fail '--version-code requires an integer.'
[[ -f "$apk" ]] || fail 'APK file is missing.'
sdk="${ANDROID_HOME-$HOME/Library/Android/sdk}"
build_tools="${ATOMIC_BUILD_TOOLS-}"
if [[ -z "$build_tools" ]]; then
    [[ -d "$sdk/build-tools" ]] || fail 'ANDROID_HOME/build-tools is missing.'
    highest_version="$(
        for directory in "$sdk"/build-tools/*; do
            if [[ -d "$directory" ]]; then printf '%s\n' "${directory##*/}"; fi
        done | sort -t. -k1,1n -k2,2n -k3,3n | tail -n 1
    )"
    [[ -n "$highest_version" ]] || fail 'No Android build-tools version is installed.'
    build_tools="$sdk/build-tools/$highest_version"
fi
for tool in aapt2 dexdump; do
    [[ -x "$build_tools/$tool" ]] || fail "Build-tools executable is missing: $tool"
done
badging="$("$build_tools/aapt2" dump badging "$apk")" || fail 'aapt2 dump badging failed.'
package_line="$(grep -E '^package:[[:space:]]' <<< "$badging")" || fail 'APK package badging is missing.'
badging_value() {
    local pattern="(^|[[:space:]])$1[[:space:]]*=[[:space:]]*['\"]?([^'\"[:space:]]+)"
    [[ "$package_line" =~ $pattern ]] || fail "APK package field is missing: $1"
    printf '%s' "${BASH_REMATCH[2]}"
}
[[ "$(badging_value name)" == tech.atomiclabs.link ]] || fail 'APK package must be tech.atomiclabs.link.'
[[ "$(badging_value versionCode)" == "$version_code" ]] || fail "APK versionCode must be $version_code."
if ! "$allow_debuggable" && grep -Eq '^[[:space:]]*application-debuggable([[:space:]]|$)' <<< "$badging"; then
    fail 'APK is debuggable; use --debuggable only for an intentional debug APK.'
fi
dex="$("$build_tools/dexdump" -l plain "$apk")" || fail 'dexdump failed.'
components=(
    service/LinkApp ui/StatusActivity device/access/LinkAccessibilityService
    device/power/TrampolineActivity device/notify/LinkNotificationListener
    service/ConnectionService service/BootReceiver
)
for component in "${components[@]}"; do
    pattern="^[[:space:]]*Class[[:space:]]+descriptor[[:space:]]*:[[:space:]]*['\"]?Ltech/atomiclabs/link/$component;['\"]?[[:space:]]*$"
    grep -Eq "$pattern" <<< "$dex" || fail "APK component is missing from dex: $component"
done
printf 'APK verified: package tech.atomiclabs.link, versionCode %s; all seven components resolve.\n' "$version_code"
