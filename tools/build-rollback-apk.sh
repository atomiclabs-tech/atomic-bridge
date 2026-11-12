#!/usr/bin/env bash
set -euo pipefail
# ATOMIC_SIGNING_DIR: existing project key directory; defaults to <checkout>/.signing.
# JAVA_HOME, ANDROID_HOME and GRADLE_USER_HOME: forwarded to tools/build.sh.
# See tools/build.sh for their defaults; all builds remain offline.

fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }
[[ $# -ge 1 && $# -le 2 ]] || fail 'Usage: build-rollback-apk.sh <commit> [versionCode]'
version_code="${2-9}"
[[ "$version_code" =~ ^[0-9]+$ ]] || fail 'versionCode must be an integer.'
command -v git >/dev/null || fail 'git is required.'
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
commit="$(git -C "$project_root" rev-parse --verify --end-of-options "$1^{commit}")" || fail 'Commit does not resolve.'
short_commit="${commit:0:7}"
rollback_root="$project_root/build/rollback"
worktree="$rollback_root/worktree-$short_commit"
output="$rollback_root/Atomic-rollback-$short_commit-$version_code.apk"
[[ ! -e "$worktree" ]] || fail 'Rollback worktree path already exists; refusing to reuse it.'
[[ ! -e "$output" ]] || fail 'Rollback APK path already exists; refusing to replace it.'
signing_dir="${ATOMIC_SIGNING_DIR-$project_root/.signing}"
[[ -f "$signing_dir/atomic-link.jks" && -f "$signing_dir/signing.properties" ]] || fail 'Existing project signing material is required.'
signing_dir="$(cd "$signing_dir" && pwd)"
mkdir -p "$rollback_root"
created=false
cleanup() {
    local result=$?
    trap - EXIT
    if "$created" && ! git -C "$project_root" worktree remove --force "$worktree"; then
        printf 'Error: rollback worktree cleanup failed.\n' >&2
        result=1
    fi
    exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
git -C "$project_root" worktree add --detach "$worktree" "$commit"
created=true
build_file="$worktree/app/build.gradle.kts"
[[ -f "$build_file" && -x "$worktree/tools/build.sh" ]] || fail 'Commit is missing the Android build files.'
if grep -q 'atomic\.versionCode' "$build_file"; then
    build_type=release
    build_arguments=("-Patomic.versionCode=$version_code" :app:assembleRelease)
else
    build_type=debug
    if ! awk -v code="$version_code" '
        /^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*[0-9]+[[:space:]]*$/ {
            sub(/[0-9]+[[:space:]]*$/, code); count++
        }
        { print }
        END { if (count != 1) exit 1 }
    ' "$build_file" > "$build_file.rollback"; then
        fail 'Expected exactly one integer versionCode line in the rollback source.'
    fi
    mv "$build_file.rollback" "$build_file"
    build_arguments=(:app:assembleDebug)
fi
ATOMIC_SIGNING_DIR="$signing_dir" "$worktree/tools/build.sh" "${build_arguments[@]}"
apk="$worktree/app/build/outputs/apk/$build_type/app-$build_type.apk"
[[ -f "$apk" ]] || fail 'Rollback build did not produce its APK.'
cp "$apk" "$output"
printf 'Rollback APK: %s\n' "$output"
